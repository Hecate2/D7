package io.github.hecate2.D7.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折线采样的形状护栏。
 *
 * [SkylineShape] 的几何被三处共用：求值（`Skyline.obstructionAt`）、取景器叠加层画的
 * 拍摄点连线、导出参考图。三者对不上时，看到的就是「屏幕上的覆盖与实际算出来的不一样」，
 * 而这正是历史上真实出过的问题，所以这里把形状本身钉住。
 */
class SkylineShapeTest {

    @Test
    fun directInterpolatesAzimuthAndElevationLinearly() {
        // 方位 200→220 度、仰角 10→30 度，2 度一步共 11 个点
        val t = SkylineShape.direct(200.0, 10.0, 220.0, 30.0)
        assertEquals(11, t.size)
        for (k in 0 until t.size) {
            val f = k / (t.size - 1).toDouble()
            assertEquals(200.0 + 20.0 * f, t.azimuths[k], 1e-9)
            assertEquals(10.0 + 20.0 * f, t.elevations[k], 1e-9)
        }
    }

    @Test
    fun directTakesShortArcAcrossNorth() {
        // 350→10 度走 20 度的短弧（跨过正北），不是反向绕 340 度
        val t = SkylineShape.direct(350.0, 0.0, 10.0, 0.0)
        assertEquals(11, t.size)
        assertEquals(350.0, t.azimuths.first(), 1e-9)
        assertEquals(10.0, t.azimuths.last(), 1e-9)
        assertTrue("应当跨过正北而不是绕回去", t.azimuths[1] > 350.0)
    }

    @Test
    fun directAlwaysHasAtLeastTheTwoEndpoints() {
        // 两端重合时短弧为 0，仍要给出两个点，否则叠加层与导出图画不出那个点
        val t = SkylineShape.direct(180.0, 20.0, 180.0, 20.0)
        assertEquals(2, t.size)
        assertEquals(20.0, t.elevations[0], 1e-9)
        assertEquals(20.0, t.elevations[1], 1e-9)
    }

    /**
     * 经地平线推断段的三段形状：起点方位垂直降到地平线、沿地平线走到终点方位、
     * 终点方位垂直升到终点。除两端点方位外，其余采样点的仰角必须是 0。
     */
    @Test
    fun viaHorizonDropsOverTheHorizonThenRises() {
        val t = SkylineShape.viaHorizon(170.0, 30.0, 200.0, 21.0)
        assertEquals(170.0, t.azimuths.first(), 1e-9)
        assertEquals(30.0, t.elevations.first(), 1e-9)
        assertEquals(200.0, t.azimuths.last(), 1e-9)
        assertEquals(21.0, t.elevations.last(), 1e-9)

        var horizonPoints = 0
        for (k in 0 until t.size) {
            val el = t.elevations[k]
            if (el == 0.0) {
                horizonPoints++
                continue
            }
            assertTrue(
                "第 $k 点 (${t.azimuths[k]}, $el) 既不在地平线上也不在端点方位上",
                t.azimuths[k] == 170.0 || t.azimuths[k] == 200.0,
            )
        }
        assertTrue("缺口中间必须落到地平线，否则两点被直接连成假遮挡", horizonPoints > 0)
    }

    /** 首尾必须原样落在给的两端上，仰角恰好是整数倍步长时也不能多算一点。 */
    @Test
    fun viaHorizonKeepsBothEndpointsForAwkwardElevations() {
        val elevations = listOf(0.0, 0.5, 3.0, 6.0, 10.0, 33.0, 45.5, 90.0)
        for (el0 in elevations) for (el1 in elevations) {
            val t = SkylineShape.viaHorizon(160.0, el0, 210.0, el1)
            assertEquals(t.azimuths.size, t.elevations.size)
            assertEquals(160.0, t.azimuths.first(), 1e-9)
            assertEquals(el0, t.elevations.first(), 1e-9)
            assertEquals(210.0, t.azimuths.last(), 1e-9)
            assertEquals(el1, t.elevations.last(), 1e-9)
        }
    }

    @Test
    fun azElTrackRejectsLengthMismatch() {
        val bad = runCatching { AzElTrack(DoubleArray(3), DoubleArray(2)) }
        assertTrue("两条数组不等长应当立刻报错，而不是在后面某次投影时越界", bad.isFailure)
    }
}
