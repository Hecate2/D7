package io.github.hecate2.D7.ui.capture

import android.hardware.SensorManager
import io.github.hecate2.D7.ui.Grade
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 罗盘可信度定档的单测。
 *
 * 要守的是「HAL 恒报高时不能跟着一起报高」：平台精度与静置漂移取较差的一档，
 * 漂移没测出来时也绝不能凭平台的高就下结论。
 */
class CompassCheckTest {

    private val high = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private val medium = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private val low = SensorManager.SENSOR_STATUS_ACCURACY_LOW
    private val unreliable = SensorManager.SENSOR_STATUS_UNRELIABLE
    private val unknown = io.github.hecate2.D7.sensor.OrientationSensor.ACCURACY_UNKNOWN

    @Test
    fun driftThresholdsSplitGoodMidLow() {
        assertEquals(Grade.GOOD, CompassCheck.driftGrade(0.0))
        assertEquals(Grade.GOOD, CompassCheck.driftGrade(2.9))
        assertEquals(Grade.MID, CompassCheck.driftGrade(3.0))
        assertEquals(Grade.MID, CompassCheck.driftGrade(9.9))
        assertEquals(Grade.LOW, CompassCheck.driftGrade(10.0))
        assertEquals(Grade.LOW, CompassCheck.driftGrade(45.0))
    }

    /** 本机的真实情形：平台恒为高，漂移一大就得跟着掉档。 */
    @Test
    fun driftOverridesPlausiblyHighPlatform() {
        assertEquals(Grade.GOOD, CompassCheck.grade(high, 0.4))
        assertEquals(Grade.MID, CompassCheck.grade(high, 6.0))
        assertEquals(Grade.LOW, CompassCheck.grade(high, 30.0))
    }

    /** 平台明说不行时，即便漂移漂亮也不能报高。 */
    @Test
    fun lowPlatformCapsTheGrade() {
        assertEquals(Grade.LOW, CompassCheck.grade(low, 0.1))
        assertEquals(Grade.LOW, CompassCheck.grade(unreliable, 0.1))
        assertEquals(Grade.MID, CompassCheck.grade(medium, 0.1))
    }

    /** 还没站稳三秒、没有漂移数据时，只能报平台那一路。 */
    @Test
    fun withoutDriftOnlyPlatformCounts() {
        assertEquals(Grade.GOOD, CompassCheck.grade(high, null))
        assertEquals(Grade.LOW, CompassCheck.grade(low, null))
        assertEquals(Grade.UNKNOWN, CompassCheck.grade(unknown, null))
    }

    /** 两路都没有依据时不编一个「高」出来。 */
    @Test
    fun noEvidenceIsUnknownNotGood() {
        assertEquals(Grade.UNKNOWN, CompassCheck.grade(unknown, null))
    }

    /** 漂移本身可以独立定档：HAL 还没回调过时，只要测到了就能判。 */
    @Test
    fun driftGradesEvenBeforePlatformCallback() {
        assertEquals(Grade.LOW, CompassCheck.grade(unknown, 20.0))
        assertEquals(Grade.GOOD, CompassCheck.grade(unknown, 0.5))
    }
}