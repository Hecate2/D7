package io.github.hecate2.D7.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    // ---------- 填充层采样：形状要跟着拍摄点走 ----------

    /** 典型的楼顶扫拍：方位不是 2 度的整数倍，这是常态，也是顶边对不齐的根源。 */
    private fun facadePoints() = listOf(
        ShotPoint(101.7, 6.3),
        ShotPoint(118.2, 22.9),
        ShotPoint(133.6, 41.5, viaHorizonAfter = true),
        ShotPoint(160.4, 30.1),
        ShotPoint(177.9, 30.4),
        ShotPoint(198.3, 12.8),
        ShotPoint(221.6, 8.1),
    )

    @Test
    fun fillAzimuthsContainEveryShotPoint() {
        val points = facadePoints()
        val az = Skyline(points).fillAzimuths(2.0)
        for (p in points) {
            val i = az.indexOfFirst { abs(it - p.azDeg) < 1e-9 }
            assertTrue("采样方位里缺了拍摄点 ${p.azDeg}", i >= 0)
        }
    }

    /**
     * 缺口端头必须是竖线。
     *
     * 经地平线段在端点处是竖直跳变（端点取该点自身仰角，开区间内是 0）。要让填充把它
     * 画成竖线，采样序列里端点方位与它的右邻必须**紧挨着**——中间隔着一个等间距网格点，
     * 落差就会被摊成一条两度宽的斜线。
     */
    @Test
    fun fillAzimuthsPutTheCliffOnASingleStep() {
        val sky = Skyline(
            listOf(
                ShotPoint(101.7, 30.0, viaHorizonAfter = true),
                ShotPoint(160.4, 10.0),
            ),
        )
        val az = sky.fillAzimuths(2.0)
        val i = az.indexOfFirst { abs(it - 101.7) < 1e-9 }
        assertTrue("采样方位里缺了缺口端头", i >= 0)
        assertEquals("缺口端头前必须紧跟一个采样点，否则崖边会画成斜的", 0.001, az[i + 1] - az[i], 1e-12)
        assertEquals(30.0, sky.obstructionAt(az[i]), 1e-9)
        assertEquals(0.0, sky.obstructionAt(az[i + 1]), 1e-9)
    }

    @Test
    fun fillAzimuthsAreSortedUniqueAndWithinOneTurn() {
        val az = Skyline(facadePoints()).fillAzimuths(2.0)
        assertTrue("整圈 181 点 + 每点 3 个折点，不该超过 300", az.size < 300)
        for (i in az.indices) {
            assertTrue("必须落在 [0, 360)：${az[i]}", az[i] >= 0.0 && az[i] < 360.0)
            if (i > 0) assertTrue("必须升序且无重复：${az[i - 1]} → ${az[i]}", az[i] > az[i - 1])
        }
        assertEquals(0.0, az[0], 1e-9)
    }

    /**
     * 这条是「更准」的量化口径：把采样点连成折线后，折线在任意方位上的取值应当等于
     * [Skyline.obstructionAt]。等间距网格做不到——折点被跳过，陡峭处差出整整一度。
     */
    @Test
    fun fillPolylineReproducesTheSkylineAtAnyAzimuth() {
        val points = facadePoints()
        val sky = Skyline(points)
        val az = sky.fillAzimuths(2.0)
        val el = DoubleArray(az.size) { sky.obstructionAt(az[it]) }

        var worst = 0.0
        var worstAt = 0.0
        var q = 1.0
        while (q < 359.0) {
            val truth = sky.obstructionAt(q)
            val drawn = interpolatePolyline(az, el, q)
            if (truth.isFinite() && drawn != null) {
                val err = abs(truth - drawn!!)
                if (err > worst) {
                    worst = err
                    worstAt = q
                }
            }
            q += 0.1
        }
        assertTrue(
            "折线偏离天际线最大 $worst 度（方位 $worstAt），顶边会与画出来的连线对不上",
            worst < 0.05,
        )
    }

    /**
     * 把同一件事用等间距网格做一遍，证明上面那条不是自说自话：
     * 网格采样在折点处的偏差是度的量级（屏幕中心约 15 像素 / 度），
     * 而不是零。数值本身随点列而异，这里只要求它明显大于折点方案的容差。
     */
    @Test
    fun plainGridCannotReproduceTheSkyline() {
        val points = facadePoints()
        val sky = Skyline(points)
        val az = DoubleArray(181) { it * 2.0 }
        val el = DoubleArray(az.size) { sky.obstructionAt(az[it]) }

        var worst = 0.0
        var q = 1.0
        while (q < 359.0) {
            val truth = sky.obstructionAt(q)
            val drawn = interpolatePolyline(az, el, q)
            if (truth.isFinite() && drawn != null) {
                worst = maxOf(worst, abs(truth - drawn!!))
            }
            q += 0.1
        }
        assertTrue("等间距网格的偏差应当在 0.5 度以上，实测 $worst", worst > 0.5)
    }

    /** 折线在 q 处的线性插值；q 落在折线两端之外返回 null。 */
    private fun interpolatePolyline(az: DoubleArray, el: DoubleArray, q: Double): Double? {
        if (q < az[0] || q > az[az.size - 1]) return null
        for (i in 0 until az.size - 1) {
            if (q >= az[i] && q <= az[i + 1]) {
                if (az[i + 1] - az[i] < 1e-12) return el[i]
                val t = (q - az[i]) / (az[i + 1] - az[i])
                // 未覆盖的一侧是 -∞，折线在这里是断的，取另一侧的值
                if (!el[i].isFinite()) return el[i + 1]
                if (!el[i + 1].isFinite()) return el[i]
                return el[i] + (el[i + 1] - el[i]) * t
            }
        }
        return null
    }
}
