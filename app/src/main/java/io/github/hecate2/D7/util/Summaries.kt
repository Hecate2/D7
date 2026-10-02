package io.github.hecate2.D7.util

import io.github.hecate2.D7.core.CalcMode
import io.github.hecate2.D7.core.DailySunlight
import io.github.hecate2.D7.core.ShotPoint
import io.github.hecate2.D7.core.Skyline
import io.github.hecate2.D7.core.SunlightEvaluator
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.PointRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 数据记录到算法点的转换。 */
fun List<PointRecord>.toShotPoints(): List<ShotPoint> =
    map { ShotPoint(it.az, it.el, it.gapAfter) }

/** 照片组卡片的数据汇总。 */
data class CardSummary(
    val group: GroupRecord,
    /** 冬至（南半球为 6-21）默认档位直射分钟；null 表示两区都未拍。 */
    val winterMinutes: Int?,
    val externalCount: Int,
    val externalCoveredPercent: Int,
    val ceilingCount: Int,
)

/** 卡片与覆盖条用的派生数据。 */
object Summaries {

    fun cardSummary(group: GroupRecord): CardSummary = CardSummary(
        group = group,
        winterMinutes = winterSolsticeMinutes(group),
        externalCount = group.external.size,
        externalCoveredPercent = coveragePercent(group.lat, group.external),
        ceilingCount = group.ceiling.size,
    )

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
     * 默认档位：外部有点用「仅外部」；外部空白而只有天花板时用「仅天花板」
     * （否则「未拍区域视为无遮挡」会给出全周开阔的失真结论）；两区都空回退「仅外部」。
     */
    fun defaultMode(group: GroupRecord): CalcMode =
        if (group.external.isEmpty() && group.ceiling.isNotEmpty()) {
            CalcMode.CEILING_ONLY
        } else {
            CalcMode.EXTERNAL_ONLY
        }

    /**
     * 冬至结论：当地一年中太阳最低一天在默认档位下的直射分钟数。
     * 两区都没有点位时返回 null（界面显示「未测」）。
     */
    fun winterSolsticeMinutes(
        group: GroupRecord,
        mode: CalcMode = defaultMode(group),
    ): Int? {
        if (group.external.isEmpty() && group.ceiling.isEmpty()) return null
        val year = LocalDate.now(ZoneId.of(group.zoneId)).year
        return evaluate(group, mode, winterDate(year, group.lat)).directMinutes
    }

    /** 当地冬至：北半球 12-21，南半球 6-21（一年中太阳最低的一天）。 */
    fun winterDate(year: Int, latDeg: Double): LocalDate =
        if (latDeg >= 0.0) LocalDate.of(year, 12, 21) else LocalDate.of(year, 6, 21)

    /** 当地大寒：北半球 1-20，南半球镜像为 7-20。 */
    fun dahanDate(year: Int, latDeg: Double): LocalDate =
        if (latDeg >= 0.0) LocalDate.of(year, 1, 20) else LocalDate.of(year, 7, 20)

    /** 某组某日某档位的日照评估。 */
    fun evaluate(group: GroupRecord, mode: CalcMode, date: LocalDate): DailySunlight =
        SunlightEvaluator.evaluate(
            group.lat, group.lon, group.zoneId,
            group.external.toShotPoints(), group.ceiling.toShotPoints(),
            mode, date,
        )

    /** 国标辅助卡：大寒日真太阳时 8:00–16:00 时间带内的有效直射分钟。 */
    fun gbWindowMinutes(group: GroupRecord, mode: CalcMode, year: Int): Int =
        SunlightEvaluator.windowMinutes(
            group.lat, group.lon, group.zoneId,
            group.external.toShotPoints(), group.ceiling.toShotPoints(),
            mode, dahanDate(year, group.lat), 480, 960,
        )
}