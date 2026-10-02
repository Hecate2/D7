package io.github.hecate2.D7.core

import java.time.LocalDate
import java.time.Year
import java.time.ZoneId

/** 计算档：只算外部建筑（默认）、外部 + 天花板、只算天花板。 */
enum class CalcMode { EXTERNAL_ONLY, EXTERNAL_AND_CEILING, CEILING_ONLY }

/**
 * 某日的日照评估结果。分钟序号自当地 0 时起，日出日落为当地钟表时间分钟。
 *
 * @param sunriseMinute 日出（全天有光时为 null，界面显示「极昼」）
 * @param sunsetMinute 日落（全天有光时为 null）
 * @param directMinutes 当日直射日照分钟数
 * @param visibleIntervals 可见直射的分钟区间（闭区间）
 * @param allFromGap 全部可见分钟都来自经地平线推断段（楼间空隙）
 * @param daylightMinutes 当日白天分钟数（几何高度角高于 -0.833 度）
 */
data class DailySunlight(
    val date: LocalDate,
    val sunriseMinute: Int?,
    val sunsetMinute: Int?,
    val directMinutes: Int,
    val visibleIntervals: List<IntRange>,
    val allFromGap: Boolean,
    val daylightMinutes: Int,
)

/**
 * 日照评估：对一组天际线数据逐分钟采样太阳位置，判定可见直射。
 *
 * 可见判定使用几何高度角（不含折射）：太阳在地平线阈值之上、且满足计算档的全部约束。
 * 未拍到的方位由 [Skyline] 的未覆盖语义决定：外部列未拍到视为开阔，天花板列未拍到视为全遮挡；
 * 点列为空即「全周未拍到」，按同一规则退化（界面在未拍天花板时禁用涉及天花板的档位）。
 */
object SunlightEvaluator {

    const val MINUTES_PER_DAY = 1440

    /**
     * 评估某日直射日照。
     *
     * @param stepMinutes 采样步长（分钟），默认逐分钟
     */
    fun evaluate(
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
        mode: CalcMode,
        date: LocalDate,
        stepMinutes: Int = 1,
    ): DailySunlight {
        val dayStart = date.atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
        val externalSky = Skyline(external)
        val ceilingSky = Skyline(ceiling)
        val track = Solar.DayTrack.ofDayStart(dayStart, latDeg, lonDeg)

        var daylight = 0
        var direct = 0
        var firstUp: Int? = null
        var lastUp: Int? = null
        val visibleMinutes = ArrayList<Int>()
        val visibleAzimuths = ArrayList<Double>()

        var m = 0
        while (m < MINUTES_PER_DAY) {
            val instant = dayStart + m * 60_000L
            val pos = track.position(instant, refraction = false)
            if (pos.elevationDeg > Solar.SUNRISE_THRESHOLD_DEG) {
                daylight += stepMinutes
                if (firstUp == null) firstUp = m
                lastUp = m
                if (isVisible(pos, mode, externalSky, ceilingSky)) {
                    direct += stepMinutes
                    visibleMinutes.add(m)
                    visibleAzimuths.add(pos.azimuthDeg)
                }
            }
            m += stepMinutes
        }

        val polarDay = daylight >= MINUTES_PER_DAY
        return DailySunlight(
            date = date,
            sunriseMinute = if (polarDay) null else firstUp,
            sunsetMinute = if (polarDay) null else lastUp?.plus(stepMinutes),
            directMinutes = direct,
            visibleIntervals = buildIntervals(visibleMinutes, stepMinutes),
            allFromGap = isAllFromGap(visibleMinutes, visibleAzimuths, externalSky),
            daylightMinutes = daylight,
        )
    }

    /**
     * 全年每日直射分钟数，按日期顺序。
     */
    fun yearlyCurve(
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
        mode: CalcMode,
        year: Int,
        stepMinutes: Int = 1,
    ): List<Double> {
        val first = LocalDate.of(year, 1, 1)
        val result = ArrayList<Double>(Year.of(year).length())
        for (i in 0 until Year.of(year).length()) {
            result.add(
                evaluate(
                    latDeg, lonDeg, zoneId, external, ceiling, mode,
                    first.plusDays(i.toLong()), stepMinutes,
                ).directMinutes.toDouble()
            )
        }
        return result
    }

    /**
     * 指定日期在「真太阳时时间带」内的有效直射分钟数（用于 GB 50180 辅助卡）。
     *
     * @param fromSolarMinute 时间带起点（真太阳时分钟，8:00 为 480）
     * @param toSolarMinute 时间带终点（真太阳时分钟，16:00 为 960）
     */
    fun windowMinutes(
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
        mode: CalcMode,
        date: LocalDate,
        fromSolarMinute: Int,
        toSolarMinute: Int,
        stepMinutes: Int = 1,
    ): Int {
        val dayStart = date.atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
        val externalSky = Skyline(external)
        val ceilingSky = Skyline(ceiling)
        val track = Solar.DayTrack.ofDayStart(dayStart, latDeg, lonDeg)

        var count = 0
        var m = 0
        while (m < MINUTES_PER_DAY) {
            val instant = dayStart + m * 60_000L
            val solarMinute = track.trueSolarMinutes(instant)
            if (solarMinute >= fromSolarMinute && solarMinute < toSolarMinute) {
                val pos = track.position(instant, refraction = false)
                if (pos.elevationDeg > Solar.SUNRISE_THRESHOLD_DEG &&
                    isVisible(pos, mode, externalSky, ceilingSky)
                ) {
                    count += stepMinutes
                }
            }
            m += stepMinutes
        }
        return count
    }

    /**
     * 可见判定。obstructionAt 是「天际线边缘仰角」：外部区太阳须高于边缘；天花板区太阳
     * 须低于边缘。未拍到（含整列为空）的方位返回 -∞，在外部区等价于开阔、在天花板区
     * 等价于全遮挡，与需求的两条未覆盖语义恰好吻合。
     */
    private fun isVisible(
        pos: SolarPosition,
        mode: CalcMode,
        externalSky: Skyline,
        ceilingSky: Skyline,
    ): Boolean = when (mode) {
        CalcMode.EXTERNAL_ONLY ->
            pos.elevationDeg > externalSky.obstructionAt(pos.azimuthDeg)

        CalcMode.CEILING_ONLY ->
            pos.elevationDeg < ceilingSky.obstructionAt(pos.azimuthDeg)

        CalcMode.EXTERNAL_AND_CEILING ->
            pos.elevationDeg > externalSky.obstructionAt(pos.azimuthDeg) &&
                pos.elevationDeg < ceilingSky.obstructionAt(pos.azimuthDeg)
    }

    private fun buildIntervals(minutes: List<Int>, stepMinutes: Int): List<IntRange> {
        if (minutes.isEmpty()) return emptyList()
        val intervals = ArrayList<IntRange>()
        var start = minutes[0]
        var prev = start
        for (i in 1 until minutes.size) {
            val cur = minutes[i]
            if (cur == prev + stepMinutes) {
                prev = cur
            } else {
                intervals.add(start..(prev + stepMinutes - 1))
                start = cur
                prev = cur
            }
        }
        intervals.add(start..(prev + stepMinutes - 1))
        return intervals
    }

    private fun isAllFromGap(
        visibleMinutes: List<Int>,
        visibleAzimuths: List<Double>,
        externalSky: Skyline,
    ): Boolean {
        if (visibleMinutes.isEmpty()) return false
        val gaps = externalSky.gapArcs()
        if (gaps.isEmpty()) return false
        return visibleAzimuths.all { az ->
            gaps.any { (start, end) -> Angles.shortArcContainsOpen(start, end, az) }
        }
    }
}