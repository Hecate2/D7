package io.github.hecate2.D7.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SunlightTest {

    private val bjLat = 39.9
    private val bjLon = 116.4
    private val bjZone = "Asia/Shanghai"
    private val winterSolstice = LocalDate.of(2026, 12, 21)

    private fun evaluateWinter(
        mode: CalcMode,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
    ): DailySunlight = SunlightEvaluator.evaluate(
        bjLat, bjLon, bjZone, external, ceiling, mode, winterSolstice,
    )

    @Test
    fun equatorEquinoxDayLengthIsTwelveHoursSeven() {
        val r = SunlightEvaluator.evaluate(
            0.1, 0.0, "UTC", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 3, 20),
        )
        assertEquals(727.0, r.daylightMinutes.toDouble(), 8.0)
        assertEquals(r.daylightMinutes, r.directMinutes)
    }

    @Test
    fun polarNightGivesZero() {
        val r = SunlightEvaluator.evaluate(
            78.22, 15.65, "UTC", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, winterSolstice,
        )
        assertEquals(0, r.directMinutes)
        assertEquals(0, r.daylightMinutes)
        assertNull(r.sunriseMinute)
        assertNull(r.sunsetMinute)
        assertTrue(r.visibleIntervals.isEmpty())
    }

    @Test
    fun polarDayGivesFullDay() {
        val r = SunlightEvaluator.evaluate(
            78.22, 15.65, "UTC", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 6, 21),
        )
        assertEquals(SunlightEvaluator.MINUTES_PER_DAY, r.daylightMinutes)
        assertEquals(SunlightEvaluator.MINUTES_PER_DAY, r.directMinutes)
        assertNull(r.sunriseMinute)
    }

    @Test
    fun externalWallBlocksWholeDay() {
        // 南侧 45 度高的整面墙：冬至全天被挡
        val wall = listOf(ShotPoint(100.0, 45.0), ShotPoint(260.0, 45.0))
        val blocked = evaluateWinter(CalcMode.EXTERNAL_ONLY, wall, emptyList())
        assertEquals(0, blocked.directMinutes)
        assertTrue("白天分钟=${blocked.daylightMinutes}", blocked.daylightMinutes > 500)
    }

    @Test
    fun emptyExternalIsOpen() {
        val r = evaluateWinter(CalcMode.EXTERNAL_ONLY, emptyList(), emptyList())
        assertEquals(r.daylightMinutes, r.directMinutes)
        assertTrue(r.directMinutes > 500)
    }

    @Test
    fun emptyCeilingBlocksCeilingModes() {
        // 天花板列空 = 全周未拍到 = 全遮挡
        assertEquals(0, evaluateWinter(CalcMode.CEILING_ONLY, emptyList(), emptyList()).directMinutes)
        assertEquals(0, evaluateWinter(CalcMode.EXTERNAL_AND_CEILING, emptyList(), emptyList()).directMinutes)
    }

    @Test
    fun ceilingOnlyRestrictsToCoveredArc() {
        // 天花板 80 度平沿仅覆盖 170..190 度；外侧方位视为全遮挡
        val ceiling = listOf(ShotPoint(170.0, 80.0), ShotPoint(190.0, 80.0))
        val r = evaluateWinter(CalcMode.CEILING_ONLY, emptyList(), ceiling)
        assertTrue("直射分钟=${r.directMinutes}", r.directMinutes in 40..150)
        assertTrue(r.visibleIntervals.all { it.first > 600 && it.last < 900 })
    }

    @Test
    fun gapPunchThroughMarkedAllFromGap() {
        // 两栋 45 度高的楼，中间 170..190 度为长按产生的空隙
        val towers = listOf(
            ShotPoint(100.0, 45.0),
            ShotPoint(170.0, 45.0, viaHorizonAfter = true),
            ShotPoint(190.0, 45.0),
            ShotPoint(260.0, 45.0),
        )
        val r = evaluateWinter(CalcMode.EXTERNAL_ONLY, towers, emptyList())
        assertTrue("直射分钟=${r.directMinutes}", r.directMinutes in 40..150)
        assertTrue(r.allFromGap)
        assertTrue(r.visibleIntervals.isNotEmpty())
    }

    @Test
    fun withoutGapSameTowersBlockFully() {
        val towers = listOf(ShotPoint(100.0, 45.0), ShotPoint(260.0, 45.0))
        val r = evaluateWinter(CalcMode.EXTERNAL_ONLY, towers, emptyList())
        assertEquals(0, r.directMinutes)
        assertTrue(!r.allFromGap)
    }

    @Test
    fun southHemisphereWinterHasSun() {
        // 悉尼冬至是 6 月 21 日（南半球），白天约 9 小时 55 分，无遮挡应全为直射
        val winter = SunlightEvaluator.evaluate(
            -33.87, 151.21, "Australia/Sydney", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 6, 21),
        )
        assertTrue("悉尼冬至白天=${winter.daylightMinutes}", winter.daylightMinutes in 550..650)
        assertEquals(winter.daylightMinutes, winter.directMinutes)

        // 12 月 21 日是南半球夏至，白天约 14 小时 25 分
        val summer = SunlightEvaluator.evaluate(
            -33.87, 151.21, "Australia/Sydney", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, winterSolstice,
        )
        assertTrue("悉尼夏至白天=${summer.daylightMinutes}", summer.daylightMinutes in 800..900)
        assertEquals(summer.daylightMinutes, summer.directMinutes)
    }

    @Test
    fun windowMinutesRespectsTrueSolarTime() {
        // 大寒日真太阳时 8:00-16:00：无遮挡时应接近 480 分钟
        val r = SunlightEvaluator.windowMinutes(
            bjLat, bjLon, bjZone, emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 1, 20), 8 * 60, 16 * 60,
        )
        assertTrue("窗口内有效直射分钟=$r", r in 440..510)
    }

    @Test
    fun ceilingCutsPartOfTheDay() {
        // 天花板 70 度平沿覆盖 150..210 度：正午前后一部分时间被自家窗框挡住
        val ceiling = listOf(ShotPoint(150.0, 70.0), ShotPoint(210.0, 70.0))
        val open = evaluateWinter(CalcMode.EXTERNAL_ONLY, emptyList(), emptyList())
        val cut = evaluateWinter(CalcMode.EXTERNAL_AND_CEILING, emptyList(), ceiling)
        assertTrue(cut.directMinutes < open.directMinutes)
        assertTrue(cut.directMinutes > 0)
    }
}