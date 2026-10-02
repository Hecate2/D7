package io.github.hecate2.D7.sensor

import android.view.Surface

/**
 * 显示旋转（`Display.getRotation()`）到设备轴、相机传感器轴的映射。
 *
 * 采集页锁竖屏，常规使用只有 ROTATION_0 一档；分屏与折叠屏下系统会忽略方向请求，
 * 窗口可能是横的，这时 ROTATION_90 / 270 两档才会真的执行——而这两档过去从未被实测过，
 * 一旦符号写反，叠加层会整体镜像而现场无从察觉。故把映射抽成纯函数并逐档单测。
 *
 * 约定：`rotation` 取 `Display.getRotation()`，是屏幕相对自然方向的旋转角，
 * 与设备自身 x（屏幕右）、y（机顶）、z（屏幕外）基向量配合使用。
 * [axesFor] 与 [clockwiseDegrees] 出自同一张表，保证叠加层投影与相机焦距换算不会各说各话。
 */
object ScreenRotation {

    /**
     * 显示旋转对应的顺时针物理转角（度），用于把传感器安装角换算到当前屏幕朝向。
     * ROTATION_0 → 0，ROTATION_90 → −90，ROTATION_180 → 180，ROTATION_270 → 90。
     */
    fun clockwiseDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> -90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 90
        else -> 0
    }

    /**
     * 屏幕右向与上向（世界向量）。二者是由 [clockwiseDegrees] 同一张表导出的绕设备 z 轴旋转：
     * 屏幕基向量相对设备基向量顺时针转 [clockwiseDegrees] 度，故 ROTATION_90 时右向变为 −y。
     *
     * 四个旋转档下 (右 × 上) 恒等于设备 +z，即始终保持右手系——单测以此为不变量兜底。
     */
    fun axesFor(
        rotation: Int,
        deviceX: FloatArray,
        deviceY: FloatArray,
    ): Pair<FloatArray, FloatArray> = when (clockwiseDegrees(rotation)) {
        -90 -> negate(deviceY) to deviceX
        180 -> negate(deviceX) to negate(deviceY)
        90 -> deviceY to negate(deviceX)
        else -> deviceX to deviceY
    }

    /**
     * 相机传感器长边是否与屏幕长边垂直（即要不要按显示旋转交换传感器宽高）。
     * 取后摄的安装角与当前显示旋转合成后的角度，只有落在 90/270 才是垂直。
     */
    fun sensorAxesSwapped(sensorOrientation: Int, rotation: Int): Boolean {
        val needed = ((sensorOrientation + clockwiseDegrees(rotation)) % 360 + 360) % 360
        return needed == 90 || needed == 270
    }

    private fun negate(v: FloatArray) = floatArrayOf(-v[0], -v[1], -v[2])
}