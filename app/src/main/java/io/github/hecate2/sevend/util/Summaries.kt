package io.github.hecate2.sevend.util

import io.github.hecate2.sevend.core.CalcMode
import io.github.hecate2.sevend.core.ShotPoint
import io.github.hecate2.sevend.core.Skyline
import io.github.hecate2.sevend.core.SunlightEvaluator
import io.github.hecate2.sevend.data.GroupRecord
import io.github.hecate2.sevend.data.PointRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 数据记录到算法点的转换。 */
fun List<PointRecord>.toShotPoints(): List<ShotPoint> =
    map { ShotPoint(it.az, it.el, it.gapAfter) }

/** 卡片与覆盖条用的派生数据。 */
object Summaries {

    /** 主方向半圆：北半球取 90..270 度，南半球取 270..360 与 0..90 度。 */
    fun isMainHalf(azDeg: Double, latDeg: Double): Boolean =
        if (latDeg >= 0) azDeg >= 90.0 && azDeg < 270.0 else azDeg >= 270.0 || azDeg < 90.0

    /** 外部列在主方向半圆的覆盖百分比（0..100）。 */
    fun coveragePercent(latDeg: Double, external: List<PointRecord>): Int {
        if (external.size < 2) return 0
        val bins = Skyline(external.toShotPoints()).coverage()
        var covered = 0
        var total = 0
        for (k in bins.indices) {
            if (isMainHalf(k.toDouble(), latDeg)) {
                total++
                if (bins[k]) covered++
            }
        }
        return if (total == 0) 0 else (covered * 100.0 / total).roundToInt()
    }

    /**
     * 冬至结论：当地一年中太阳最低一天在默认档位（仅外部）下的直射分钟数。
     * 两区都没有点位时返回 null（界面显示「未测」）。
     */
    fun winterSolsticeMinutes(
        group: GroupRecord,
        mode: CalcMode = CalcMode.EXTERNAL_ONLY,
    ): Int? {
        if (group.external.isEmpty() && group.ceiling.isEmpty()) return null
        val year = LocalDate.now(ZoneId.of(group.zoneId)).year
        val date = if (group.lat >= 0.0) LocalDate.of(year, 12, 21) else LocalDate.of(year, 6, 21)
        return SunlightEvaluator.evaluate(
            group.lat, group.lon, group.zoneId,
            group.external.toShotPoints(), group.ceiling.toShotPoints(),
            mode, date,
        ).directMinutes
    }
}