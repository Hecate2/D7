package io.github.hecate2.D7.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import io.github.hecate2.D7.core.Angles
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一次姿态解算的结果。世界坐标取东（x）、北（y）、天（z）右手系；
 * 方位角一律已加磁偏角，为真北方位。
 */
class Pose(
    /** 后摄视轴（指向被摄物）的世界向量。 */
    val forward: FloatArray,
    /** 屏幕右向与上向的世界向量（已按显示旋转映射）。 */
    val right: FloatArray,
    val up: FloatArray,
    /**
     * 屏幕外法线（设备 +z）的瞬时方位/仰角，即「机身朝向读数」。
     * 竖持拍照时它指向人，与后摄视轴天然相差约 180 度（界面以 +180° 药丸叠加）。
     */
    val frontAzDeg: Double,
    val frontElDeg: Double,
    /** 同一读数的低通平滑值，用于数字显示；拍摄记录取瞬时值。 */
    val smoothAzDeg: Double,
    val smoothElDeg: Double,
    /**
     * 屏幕左右倾斜（滚转）角：屏幕「上」方向相对世界竖直的偏转角，不是航向。
     * 竖持取景时为正表示机顶向右倒（从机主视角看顺时针），单位度。
     */
    val rollDeg: Double,
    /** 罗盘精度：SensorManager.SENSOR_STATUS_*；ACCURACY_UNKNOWN 表示尚未收到系统精度回调。 */
    val accuracy: Int,
)

/**
 * 旋转矢量传感器封装：注册/注销、坐标换算、磁偏角叠加与读数平滑。
 *
 * 设备坐标：+x 屏幕右、+y 机顶、+z 屏幕外；旋转矩阵 R 把设备向量映射到世界（东、北、天）。
 * 磁偏角由外部以 lambda 提供（用组内经纬度快照），显示旋转由界面实时提供。
 */
class OrientationSensor(
    context: Context,
    private val declinationDeg: () -> Double,
    private val displayRotation: () -> Int,
    private val onPose: (Pose) -> Unit,
) : SensorEventListener {

    companion object {
        /** 尚未收到系统精度回调时的哨兵值（SensorManager 的精度常量均为非负）。 */
        const val ACCURACY_UNKNOWN = -1
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val smoothFront = FloatArray(3)
    private var smoothInit = false
    private var accuracy = ACCURACY_UNKNOWN

    /** 本机是否有旋转矢量传感器。 */
    val available: Boolean get() = sensor != null

    /** 注册监听；返回 false 表示传感器缺失或注册失败。 */
    fun start(): Boolean {
        val s = sensor ?: return false
        smoothInit = false
        return manager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 磁偏角：方位角整体 +d，等价于绕世界天轴旋转三个基向量
        val d = declinationDeg() * PI / 180.0
        val cd = cos(d).toFloat()
        val sd = sin(d).toFloat()
        val worldX = axis(0, cd, sd)
        val worldY = axis(1, cd, sd)
        val worldZ = axis(2, cd, sd)

        // 后摄视轴 = -z（屏幕外法线的反向）
        val forward = floatArrayOf(-worldZ[0], -worldZ[1], -worldZ[2])
        val (right, up) = screenAxes(displayRotation(), worldX, worldY)

        // 滚转：屏幕「上」相对世界竖直（天）的偏转；机顶向右倒为正
        val rollDeg = atan2(-right[2].toDouble(), up[2].toDouble()) * 180.0 / PI

        val frontAz = Angles.normalize360(
            atan2(worldZ[0].toDouble(), worldZ[1].toDouble()) * 180.0 / PI,
        )
        val frontEl = asin(worldZ[2].coerceIn(-1f, 1f).toDouble()) * 180.0 / PI

        // 读数低通：对屏幕外法线向量做指数平滑后取角
        if (!smoothInit) {
            worldZ.copyInto(smoothFront)
            smoothInit = true
        } else {
            for (i in 0..2) smoothFront[i] = smoothFront[i] * 0.7f + worldZ[i] * 0.3f
            val norm = kotlin.math.sqrt(
                smoothFront[0] * smoothFront[0] + smoothFront[1] * smoothFront[1] + smoothFront[2] * smoothFront[2],
            )
            if (norm > 1e-6f) {
                for (i in 0..2) smoothFront[i] /= norm
            }
        }
        val smoothAz = Angles.normalize360(
            atan2(smoothFront[0].toDouble(), smoothFront[1].toDouble()) * 180.0 / PI,
        )
        val smoothEl = asin(smoothFront[2].coerceIn(-1f, 1f).toDouble()) * 180.0 / PI

        onPose(
            Pose(
                forward = forward,
                right = right,
                up = up,
                frontAzDeg = frontAz,
                frontElDeg = frontEl,
                smoothAzDeg = smoothAz,
                smoothElDeg = smoothEl,
                rollDeg = rollDeg,
                accuracy = accuracy,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) accuracy = newAccuracy
    }

    /** 取设备第 col 个轴的世界向量（东、北、天），并把方位角整体加磁偏角。 */
    private fun axis(col: Int, cd: Float, sd: Float): FloatArray {
        val e = rotationMatrix[col]
        val n = rotationMatrix[3 + col]
        val u = rotationMatrix[6 + col]
        return floatArrayOf(e * cd + n * sd, n * cd - e * sd, u)
    }

    /**
     * 屏幕右/上向量的显示旋转映射。四档映射与不变量说明见 [ScreenRotation]，
     * 该处同时供相机焦距换算复用，并有逐档单测兜底。
     */
    private fun screenAxes(rotation: Int, wx: FloatArray, wy: FloatArray): Pair<FloatArray, FloatArray> =
        ScreenRotation.axesFor(rotation, wx, wy)
}
