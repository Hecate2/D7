package io.github.hecate2.D7.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * 角度工具：统一方位角的归一与短弧（小于 180 度的弧）判定。
 * 全部方法都以度为单位，方位角从正北起顺时针。
 */
object Angles {

    /** 归一化到 [0, 360)。 */
    fun normalize360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        return d
    }

    /** 从 fromDeg 到 toDeg 的短弧差值，落在 (-180, 180]。 */
    fun shortArcDelta(fromDeg: Double, toDeg: Double): Double {
        var d = (toDeg - fromDeg) % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    /** 判断 qDeg 是否落在从 startDeg 到 endDeg 的短弧内部（开区间，不含两端）。 */
    fun shortArcContainsOpen(startDeg: Double, endDeg: Double, qDeg: Double): Boolean {
        val d = shortArcDelta(startDeg, endDeg)
        if (d == 0.0) return false
        val t = shortArcDelta(startDeg, qDeg)
        val sameSign = (d > 0.0 && t > 0.0) || (d < 0.0 && t < 0.0)
        return sameSign && abs(t) < abs(d)
    }

    /** 判断 qDeg 是否落在从 startDeg 到 endDeg 的短弧上（闭区间，含两端）。 */
    fun shortArcContainsClosed(startDeg: Double, endDeg: Double, qDeg: Double): Boolean {
        val d = shortArcDelta(startDeg, endDeg)
        if (d == 0.0) return abs(shortArcDelta(startDeg, qDeg)) < 1e-9
        val t = shortArcDelta(startDeg, qDeg)
        val sameSign = (d > 0.0 && t >= 0.0) || (d < 0.0 && t <= 0.0)
        return sameSign && abs(t) <= abs(d)
    }
}

/**
 * 一个拍摄点：方位角 0..360（北 0、东 90、南 180、西 270），仰角地平线 0、天顶 90。
 *
 * @param viaHorizonAfter 该点与拍摄序下一个点之间是否为「经地平线推断段」
 *        （长按快门产生：竖直降到地平线、沿地平线走到下一点方位、再竖直升到该点）。
 */
data class ShotPoint(
    val azDeg: Double,
    val elDeg: Double,
    val viaHorizonAfter: Boolean = false,
)

/**
 * 天际线：按拍摄顺序给出点列，相邻两点构成线段。
 *
 * 任一查询方位角的遮挡仰角取所有覆盖该方位角的线段候选取最大值（遮挡取高者），
 * 因此不假设方位角单调，自交与环绕均自然处理。
 *
 * [obstructionAt] 返回的是「天际线边缘仰角」。未被任何线段覆盖的方位返回 -∞，
 * 由调用方按分区语义解读：外部区判定「太阳高于边缘」，-∞ 等价于开阔；
 * 天花板区判定「太阳低于边缘」，-∞ 等价于全遮挡。空点列即全周未覆盖，按同一规则退化。
 */
class Skyline(points: List<ShotPoint>) {
    private val pts: List<ShotPoint> = points.toList()

    /** 查询某方位角处的天际线边缘仰角；未覆盖时返回 -∞。 */
    fun obstructionAt(azDeg: Double): Double {
        var best = Double.NEGATIVE_INFINITY
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = Angles.shortArcDelta(a.azDeg, b.azDeg)
            if (d == 0.0) continue // 同方位两点：零宽线段，不参与
            val t = Angles.shortArcDelta(a.azDeg, azDeg)
            val sameSign = (d > 0.0 && t >= 0.0) || (d < 0.0 && t <= 0.0)
            if (!sameSign || abs(t) > abs(d) + 1e-9) continue

            val candidate = if (a.viaHorizonAfter) {
                when {
                    abs(t) <= 1e-9 -> a.elDeg                                  // 起点处取端点自身
                    abs(abs(t) - abs(d)) <= 1e-9 -> b.elDeg                    // 终点处取端点自身
                    else -> 0.0                                                // 开区间内为地平线
                }
            } else {
                a.elDeg + (b.elDeg - a.elDeg) * (abs(t) / abs(d))              // 方位角线性插值
            }
            if (candidate > best) best = candidate
        }
        return best
    }

    /** 所有经地平线推断段的方位角区间（起止以拍摄序为准，判定用短弧包含）。 */
    fun gapArcs(): List<Pair<Double, Double>> {
        val arcs = ArrayList<Pair<Double, Double>>()
        for (i in 0 until pts.size - 1) {
            if (pts[i].viaHorizonAfter) {
                arcs.add(pts[i].azDeg to pts[i + 1].azDeg)
            }
        }
        return arcs
    }

    /** 方位角是否落在任一线段上（闭区间，含端点）。 */
    fun isCovered(azDeg: Double): Boolean {
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (Angles.shortArcDelta(a.azDeg, b.azDeg) == 0.0) continue
            if (Angles.shortArcContainsClosed(a.azDeg, b.azDeg, azDeg)) return true
        }
        return false
    }

    /**
     * 按全周采样是否被覆盖，默认 360 个采样点（1 度步长，第 k 个对应方位 k 度）。
     * 供覆盖条与覆盖率统计使用。
     */
    fun coverage(bins: Int = 360): BooleanArray {
        val result = BooleanArray(bins)
        for (k in 0 until bins) {
            result[k] = isCovered(k * 360.0 / bins)
        }
        return result
    }
}

/**
 * 天际线线段的折线采样：把 [Skyline.obstructionAt] 的几何语义展开成绘图折线，
 * 供取景器叠加层与导出参考图共用，保证「求值、叠加层、导出」三处语义一致。
 */
object SkylineShape {

    /** 直接连线：两端点之间按方位角线性插值仰角。 */
    fun direct(
        az0: Double,
        el0: Double,
        az1: Double,
        el1: Double,
        stepDeg: Double = 2.0,
    ): List<Pair<Double, Double>> {
        val delta = Angles.shortArcDelta(az0, az1)
        val steps = max(1, ceil(abs(delta) / stepDeg).toInt())
        return (0..steps).map { k ->
            val t = k.toDouble() / steps
            Angles.normalize360(az0 + delta * t) to el0 + (el1 - el0) * t
        }
    }

    /**
     * 经地平线推断段的三段展开：起点垂直降到地平线、沿地平线走到终点方位、再垂直升到终点。
     * 端点处垂直、开区间内沿地平线，与求值语义一致；避免两点间直接插值画出假遮挡。
     */
    fun viaHorizon(
        az0: Double,
        el0: Double,
        az1: Double,
        el1: Double,
        azStepDeg: Double = 2.0,
        elStepDeg: Double = 3.0,
    ): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        out.add(az0 to el0)
        var drop = el0
        while (drop > elStepDeg) {
            drop -= elStepDeg
            out.add(az0 to drop)
        }
        out.add(az0 to 0.0)
        val delta = Angles.shortArcDelta(az0, az1)
        val steps = max(1, ceil(abs(delta) / azStepDeg).toInt())
        for (k in 1..steps) {
            out.add(Angles.normalize360(az0 + delta * k / steps) to 0.0)
        }
        var rise = elStepDeg
        while (rise < el1) {
            out.add(az1 to rise)
            rise += elStepDeg
        }
        out.add(az1 to el1)
        return out
    }
}