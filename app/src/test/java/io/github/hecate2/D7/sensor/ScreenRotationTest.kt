package io.github.hecate2.D7.sensor

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示旋转映射的逐档单测。
 *
 * 采集页锁竖屏使 ROTATION_90/270 平时走不到，只在分屏、折叠屏这类系统忽略方向请求的窗口
 * 形态下执行；一旦符号写反，叠加层会整体镜像而现场无从察觉，故四档全部锁死。
 */
class ScreenRotationTest {

    // 斜置基向量：若实现误用了坐标轴而非向量组合，用轴对齐向量会漏检
    private val deviceX = floatArrayOf(0.6f, 0.8f, 0f)
    private val deviceY = floatArrayOf(-0.8f, 0.6f, 0f)

    private fun axes(rotation: Int) = ScreenRotation.axesFor(rotation, deviceX, deviceY)

    private fun assertVec(expected: FloatArray, actual: FloatArray, msg: String) {
        for (i in 0..2) {
            assertEquals("$msg 分量 $i", expected[i], actual[i], 1e-5f)
        }
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    @Test
    fun `零档时屏幕轴就是设备轴`() {
        val (right, up) = axes(Surface.ROTATION_0)
        assertVec(deviceX, right, "ROTATION_0 右向")
        assertVec(deviceY, up, "ROTATION_0 上向")
    }

    @Test
    fun `九十分档右向取负 y 上向取 x`() {
        val (right, up) = axes(Surface.ROTATION_90)
        assertVec(negate(deviceY), right, "ROTATION_90 右向")
        assertVec(deviceX, up, "ROTATION_90 上向")
    }

    @Test
    fun `一百八十分档两轴同时取反`() {
        val (right, up) = axes(Surface.ROTATION_180)
        assertVec(negate(deviceX), right, "ROTATION_180 右向")
        assertVec(negate(deviceY), up, "ROTATION_180 上向")
    }

    @Test
    fun `二百七十分档右向取 y 上向取负 x`() {
        val (right, up) = axes(Surface.ROTATION_270)
        assertVec(deviceY, right, "ROTATION_270 右向")
        assertVec(negate(deviceX), up, "ROTATION_270 上向")
    }

    /** 不变量：任意档位下 (右 × 上) 恒等于设备 +z，屏幕基向量不得翻转手性。 */
    @Test
    fun `四档都保持右手系`() {
        val deviceZ = cross(deviceX, deviceY)
        for (rotation in ROTATIONS) {
            val (right, up) = axes(rotation)
            assertVec(deviceZ, cross(right, up), "rotation=$rotation 手性")
        }
    }

    /** 不变量：每档的基向量长度仍为 1，且与设备轴正交。 */
    @Test
    fun `四档都保持正交归一`() {
        for (rotation in ROTATIONS) {
            val (right, up) = axes(rotation)
            assertEquals("rotation=$rotation 右向模长", 1f, norm(right), 1e-5f)
            assertEquals("rotation=$rotation 上向模长", 1f, norm(up), 1e-5f)
            assertEquals("rotation=$rotation 正交性", 0f, dot(right, up), 1e-5f)
        }
    }

    @Test
    fun `顺时针物理转角四档`() {
        assertEquals(0, ScreenRotation.clockwiseDegrees(Surface.ROTATION_0))
        assertEquals(-90, ScreenRotation.clockwiseDegrees(Surface.ROTATION_90))
        assertEquals(180, ScreenRotation.clockwiseDegrees(Surface.ROTATION_180))
        assertEquals(90, ScreenRotation.clockwiseDegrees(Surface.ROTATION_270))
    }

    /** 常见后摄安装角 90：竖屏需交换传感器宽高，转到横屏后长边与屏幕长边平行，无需交换。 */
    @Test
    fun `安装角九十分时仅竖屏两档交换传感器宽高`() {
        assertTrue(ScreenRotation.sensorAxesSwapped(90, Surface.ROTATION_0))
        assertFalse(ScreenRotation.sensorAxesSwapped(90, Surface.ROTATION_90))
        assertTrue(ScreenRotation.sensorAxesSwapped(90, Surface.ROTATION_180))
        assertFalse(ScreenRotation.sensorAxesSwapped(90, Surface.ROTATION_270))
    }

    /** 安装角 0（部分平板/前摄）：竖屏不交换，横屏交换。 */
    @Test
    fun `安装角零分时仅横屏两档交换传感器宽高`() {
        assertFalse(ScreenRotation.sensorAxesSwapped(0, Surface.ROTATION_0))
        assertTrue(ScreenRotation.sensorAxesSwapped(0, Surface.ROTATION_90))
        assertFalse(ScreenRotation.sensorAxesSwapped(0, Surface.ROTATION_180))
        assertTrue(ScreenRotation.sensorAxesSwapped(0, Surface.ROTATION_270))
    }

    @Test
    fun `交换判定与角度合成公式一致且不受负数影响`() {
        for (sensorOrientation in listOf(0, 90, 180, 270)) {
            for (rotation in ROTATIONS) {
                val expectedDegrees = ((sensorOrientation + ScreenRotation.clockwiseDegrees(rotation)) % 360 + 360) % 360
                val expected = expectedDegrees == 90 || expectedDegrees == 270
                assertEquals(
                    "sensor=$sensorOrientation rotation=$rotation",
                    expected,
                    ScreenRotation.sensorAxesSwapped(sensorOrientation, rotation),
                )
            }
        }
    }

    private companion object {
        val ROTATIONS = listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        )

        fun negate(v: FloatArray) = floatArrayOf(-v[0], -v[1], -v[2])
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun norm(a: FloatArray): Float = kotlin.math.sqrt(dot(a, a))
    }
}