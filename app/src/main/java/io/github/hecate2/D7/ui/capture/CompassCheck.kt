package io.github.hecate2.D7.ui.capture

import android.hardware.SensorManager
import io.github.hecate2.D7.ui.Grade

/**
 * 罗盘可信度定档：平台精度与静置漂移取较差的一档。
 *
 * 单靠平台精度不够用——实测本机（vivo，联发科方案）的 HAL 对旋转矢量恒定上报
 * `SENSOR_STATUS_ACCURACY_HIGH`，从开机到关机都不变，于是「罗盘校准」药丸永远显示
 * 「高」，等于没有信息。所以再加一路自己测出来的信号：
 *
 * - **平台精度**：[SensorManager] 的精度回调。真的报低/不可靠时立刻采信。
 * - **静置漂移**：设备基本不动时，方位角在几秒内自己跑了多少度。磁场干扰（钢筋、
 *   电梯、磁铁）与磁力计未标定都会让这个数变大，而它不依赖 HAL 说不说。
 *
 * 两者都没有依据时返回 [Grade.UNKNOWN]，而不是假装「高」。
 */
object CompassCheck {

    /** 静置漂移小于此值（度）算好。 */
    const val DRIFT_GOOD_DEG = 3.0

    /** 静置漂移小于此值（度）算中，否则算差。 */
    const val DRIFT_OK_DEG = 10.0

    /** 漂移定档：[DRIFT_GOOD_DEG] / [DRIFT_OK_DEG] 两道门槛。 */
    fun driftGrade(driftDeg: Double): Grade = when {
        driftDeg < DRIFT_GOOD_DEG -> Grade.GOOD
        driftDeg < DRIFT_OK_DEG -> Grade.MID
        else -> Grade.LOW
    }

    /**
     * 综合定档：两路取较差的一档。
     *
     * @param platformAccuracy 平台精度常量；还没收到过回调时用 [io.github.hecate2.D7.sensor.OrientationSensor.ACCURACY_UNKNOWN]。
     * @param driftDeg 静置窗口内方位角的最大漂移（度）；null 表示静置样本还不够，本档不作约束。
     * @return 两个来源里较差的一档；都没有依据时 [Grade.UNKNOWN]。
     */
    fun grade(platformAccuracy: Int, driftDeg: Double?): Grade {
        val ranks = listOfNotNull(
            platformGrade(platformAccuracy)?.let { rank(it) },
            driftDeg?.let { rank(driftGrade(it)) },
        )
        return when (ranks.minOrNull()) {
            3 -> Grade.GOOD
            2 -> Grade.MID
            1 -> Grade.LOW
            else -> Grade.UNKNOWN
        }
    }

    /**
     * 可信度序：好 3 / 中 2 / 差 1。**不要用 [Grade] 的声明顺序**——那个顺序是按「好、中、
     * 差、未知」写的，取最小值会取到最好的一档，方向正好反了。
     */
    private fun rank(grade: Grade): Int = when (grade) {
        Grade.GOOD -> 3
        Grade.MID -> 2
        Grade.LOW -> 1
        Grade.UNKNOWN -> 0
    }

    /** 平台精度定档；未知的回调状态（还没收到过）返回 null，表示不作约束。 */
    fun platformGrade(accuracy: Int): Grade? = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Grade.GOOD
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Grade.MID
        SensorManager.SENSOR_STATUS_ACCURACY_LOW,
        SensorManager.SENSOR_STATUS_UNRELIABLE,
        -> Grade.LOW
        else -> null
    }
}