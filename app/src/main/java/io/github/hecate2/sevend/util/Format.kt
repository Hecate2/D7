package io.github.hecate2.sevend.util

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** 界面文本格式化。 */
object Format {

    /** 时长的紧凑写法：不足一小时写「45分」，否则写「1h00」。 */
    fun durationShort(minutes: Int): String {
        if (minutes < 60) return "${minutes}分"
        val h = minutes / 60
        val m = minutes % 60
        return String.format(Locale.US, "%dh%02d", h, m)
    }

    /** 时长的中文写法：2 小时 05 分。 */
    fun durationCn(minutes: Int): String {
        if (minutes < 60) return "${minutes} 分"
        val h = minutes / 60
        val m = minutes % 60
        return String.format(Locale.US, "%d 小时 %02d 分", h, m)
    }

    /** 时钟分钟（0..1440）转「07:32」。 */
    fun clockMinute(minute: Int): String {
        val m = minute.coerceIn(0, 1440)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 经纬度写法：31.23°N 121.47°E。 */
    fun coordinate(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.2f°%s %.2f°%s", abs(lat), ns, abs(lon), ew)
    }

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

    /** 角度写法：236.2°。 */
    fun azimuth(deg: Double): String = String.format(Locale.US, "%.1f°", deg)

    /** 仰角写法：18.4°。 */
    fun elevation(deg: Double): String = String.format(Locale.US, "%.1f°", deg)

    /** 整度写法：236°。 */
    fun degreeInt(deg: Double): String = String.format(Locale.US, "%.0f°", deg)
}