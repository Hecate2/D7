package io.github.hecate2.D7.util

import android.content.res.Resources
import io.github.hecate2.D7.R
import io.github.hecate2.D7.core.CalcMode
import io.github.hecate2.D7.data.Region
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** 界面文本格式化；时长等语言相关文案取自 strings.xml，便于本地化。 */
object Format {

    /** 时长的紧凑写法：不足一小时写「45分」，否则写「1h00」。 */
    fun durationShort(resources: Resources, minutes: Int): String {
        if (minutes < 60) return resources.getString(R.string.duration_minutes_short, minutes)
        val h = minutes / 60
        val m = minutes % 60
        return resources.getString(R.string.duration_hours_short, h, m)
    }

    /** 时长的完整写法：2 小时 05 分。 */
    fun durationLong(resources: Resources, minutes: Int): String {
        if (minutes < 60) return resources.getString(R.string.duration_minutes_long, minutes)
        val h = minutes / 60
        val m = minutes % 60
        return resources.getString(R.string.duration_hours_minutes_long, h, m)
    }

    /** 时钟分钟（0..1440）转「07:32」。 */
    fun clockMinute(minute: Int): String {
        val m = minute.coerceIn(0, 1440)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /**
     * 经纬度写法：**经度在前**，121.47°E  31.23°N。
     *
     * 顺序跟着输入走：建组与编辑对话框里都是经度在上、纬度在下（沿用的是「先念经度」的习惯），
     * 显示反过来会让用户对着两行数字确认自己有没有录错。
     */
    fun coordinate(lat: Double, lon: Double): String = String.format(
        Locale.US, "%.2f%s %.2f%s",
        abs(lon), longitudeSuffix(lon), abs(lat), latitudeSuffix(lat),
    )

    /** 纬度的半球后缀：`°N` / `°S`。输入框右侧那一小格用的就是它。 */
    fun latitudeSuffix(lat: Double): String = if (lat >= 0) "°N" else "°S"

    /** 经度的半球后缀：`°E` / `°W`。 */
    fun longitudeSuffix(lon: Double): String = if (lon >= 0) "°E" else "°W"

    /** 日期短写：10-02。 */
    fun dateShort(millis: Long, zoneId: String): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of(zoneId)).toLocalDate()
        return monthDay(date)
    }

    /** 日期短写（直接由日期对象）：10-02。 */
    fun monthDay(date: java.time.LocalDate): String =
        String.format(Locale.US, "%02d-%02d", date.monthValue, date.dayOfMonth)

    /** 完整日期：2026-10-02 14:30。 */
    fun dateTime(millis: Long, zoneId: String): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.of(zoneId))
        return String.format(
            Locale.US, "%04d-%02d-%02d %02d:%02d",
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute,
        )
    }

    /** 角度写法（一位小数）：236.2°。方位、仰角、滚转角都走这里。 */
    fun degree(deg: Double): String = String.format(Locale.US, "%.1f°", deg)

    /** 整度写法：236°。 */
    fun degreeInt(deg: Double): String = String.format(Locale.US, "%.0f°", deg)

    /** 计算档位名：仅外部 / 外部+天花板 / 仅天花板。 */
    fun calcMode(resources: Resources, mode: CalcMode): String = resources.getString(
        when (mode) {
            CalcMode.EXTERNAL_ONLY -> R.string.result_mode_external
            CalcMode.EXTERNAL_AND_CEILING -> R.string.result_mode_both
            CalcMode.CEILING_ONLY -> R.string.result_mode_ceiling
        },
    )

    /** 分区名：外 / 天。 */
    fun region(resources: Resources, region: Region): String = resources.getString(
        if (region == Region.EXTERNAL) R.string.result_region_external else R.string.result_region_ceiling,
    )
}
