package io.github.hecate2.sevend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkylineTest {

    @Test
    fun directSegmentInterpolatesAzimuthLinearly() {
        val sky = Skyline(listOf(ShotPoint(200.0, 10.0), ShotPoint(220.0, 30.0)))
        assertEquals(20.0, sky.obstructionAt(210.0), 1e-9)
        assertEquals(10.0, sky.obstructionAt(200.0), 1e-9)
        assertEquals(30.0, sky.obstructionAt(220.0), 1e-9)
    }

    @Test
    fun viaHorizonSegmentExpandsToHorizon() {
        val sky = Skyline(
            listOf(ShotPoint(200.0, 10.0, viaHorizonAfter = true), ShotPoint(220.0, 30.0)),
        )
        // 开区间内沿地平线
        assertEquals(0.0, sky.obstructionAt(210.0), 1e-9)
        // 两端点仍取端点自身
        assertEquals(10.0, sky.obstructionAt(200.0), 1e-9)
        assertEquals(30.0, sky.obstructionAt(220.0), 1e-9)
    }

    @Test
    fun overlappingSegmentsTakeMaximumObstruction() {
        // 拍摄往返造成的方位重叠：190 → 230 → 210
        val sky = Skyline(
            listOf(
                ShotPoint(190.0, 20.0),
                ShotPoint(230.0, 10.0),
                ShotPoint(210.0, 40.0),
            ),
        )
        // 段一 190→230 在 215 处插值 13.75；段二 230→210 在 215 处插值 32.5；取最大
        assertEquals(32.5, sky.obstructionAt(215.0), 1e-9)
    }

    @Test
    fun wrappingAcrossNorthIsHandled() {
        val sky = Skyline(listOf(ShotPoint(350.0, 5.0), ShotPoint(10.0, 15.0)))
        assertEquals(10.0, sky.obstructionAt(0.0), 1e-9)
        assertTrue(sky.isCovered(355.0))
        assertTrue(sky.isCovered(5.0))
        assertFalse(sky.isCovered(180.0))
    }

    @Test
    fun uncoveredReturnsNegativeInfinity() {
        // obstructionAt 是「天际线边缘仰角」：未覆盖方位返回 -∞。
        // 外部区判定「太阳高于边缘」，-∞ 即开阔；天花板区判定「太阳低于边缘」，-∞ 即全遮挡。
        val points = listOf(ShotPoint(100.0, 10.0), ShotPoint(110.0, 20.0))
        assertTrue(Skyline(points).obstructionAt(180.0) == Double.NEGATIVE_INFINITY)
        // 空列即全周未拍到，按同一未覆盖规则退化
        assertTrue(Skyline(emptyList()).obstructionAt(90.0) == Double.NEGATIVE_INFINITY)
    }

    @Test
    fun coverageBinsMarkCoveredAzimuths() {
        val sky = Skyline(listOf(ShotPoint(200.0, 10.0), ShotPoint(220.0, 30.0)))
        val bins = sky.coverage()
        assertEquals(360, bins.size)
        assertTrue(bins[210])
        assertTrue(bins[200])
        assertTrue(bins[220])
        assertFalse(bins[199])
        assertFalse(bins[90])
    }

    @Test
    fun gapArcsListViaHorizonSegments() {
        val sky = Skyline(
            listOf(
                ShotPoint(120.0, 30.0, viaHorizonAfter = true),
                ShotPoint(150.0, 40.0),
                ShotPoint(200.0, 35.0, viaHorizonAfter = true),
                ShotPoint(210.0, 25.0),
            ),
        )
        val arcs = sky.gapArcs()
        assertEquals(2, arcs.size)
        assertEquals(120.0, arcs[0].first, 1e-9)
        assertEquals(150.0, arcs[0].second, 1e-9)
        assertEquals(200.0, arcs[1].first, 1e-9)
        assertEquals(210.0, arcs[1].second, 1e-9)
    }

    @Test
    fun shortArcContainsHandlesWrap() {
        assertTrue(Angles.shortArcContainsOpen(350.0, 10.0, 0.0))
        assertTrue(Angles.shortArcContainsClosed(350.0, 10.0, 350.0))
        assertFalse(Angles.shortArcContainsOpen(350.0, 10.0, 350.0))
        assertTrue(Angles.shortArcContainsOpen(200.0, 160.0, 180.0))
        assertTrue(Angles.shortArcContainsOpen(200.0, 160.0, 190.0))
        assertFalse(Angles.shortArcContainsOpen(200.0, 160.0, 150.0))
    }
}