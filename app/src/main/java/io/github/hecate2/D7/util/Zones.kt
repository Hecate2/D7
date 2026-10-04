package io.github.hecate2.D7.util

import android.content.res.Resources
import io.github.hecate2.D7.R
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * 时区三选一的换算与文本解析。纯 java.time，不碰 Context（[label] 例外，它要读资源）。
 *
 * 组记录里只存一个时区 id，界面上的「系统时区 / 按经度 / 自定义」都先解析成 id 再落库，
 * 所以不给 [io.github.hecate2.D7.data.GroupRecord] 再加一个「模式」字段。加字段的好处是
 * 「按经度」能在改经纬度时自动跟随，代价是多一个持久化字段、多一套显示与迁移分支；
 * 这里的取舍是让用户在编辑框里再点一次「按经度」。
 *
 * 为什么需要它：建组时时区取的是**手机**当前时区（`LocationProvider.toFix`）。站在房子里拍，
 * 手机时区就是房子时区，没有问题；拿地图坐标远程替外地的房子测量时，手机时区与房子时区
 * 就会差出几小时，日出日落与时间线的钟面时间全跟着偏。
 */
object Zones {

    /** 中国标准时间的 id；显示时换成中文习惯说法，别处不必知道这个特例。 */
    private const val ASIA_SHANGHAI = "Asia/Shanghai"

    /** 手机当前时区。 */
    fun system(): String = ZoneId.systemDefault().id

    /**
     * 经度所在的整点时区：每 15° 一个时区，四舍五入到最近的整点。
     *
     * 地球上没有「按经纬度查 IANA 时区名」的标准做法（时区边界跟着国界与行政划分走），
     * 所以这里给的是太阳意义上的当地时区，对日照计算恰好是最贴切的那个。
     * 用 `UTC±hh:mm` 而不用裸偏移 `+08:00`：界面上能与 `Asia/Shanghai` 一眼区分，
     * 也不会在零偏移时显示成一个孤零零的 `Z`。
     */
    fun fromLongitude(lonDeg: Double): String {
        val normalized = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val hours = (normalized / 15.0).roundToInt().coerceIn(-12, 12)
        return ZoneId.ofOffset("UTC", ZoneOffset.ofHours(hours)).id
    }

    /**
     * 文本 → 时区 id：先按 IANA 名认（Asia/Kolkata），再按带符号偏移量认（+8、+05:30、-3:30）。都不认返回 null。
     *
     * 裸偏移量统一补上 `UTC` 前缀，与 [fromLongitude] 的写法对齐：否则打 `+8` 存成 `UTC+08:00`、
     * 打 `+08:00` 存成 `+08:00`，同一个意思在界面上两种样子。
     */
    fun parse(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // 先补成 ZoneOffset 认得的写法：它只接受两位小时，而人写半时区常常只写一位（+5:30）
        val padded = trimmed.replace(SINGLE_DIGIT_HOUR) { match ->
            val (prefix, sign, hour, rest) = match.destructured
            "$prefix$sign" + "0" + hour + rest
        }
        val zone = try {
            ZoneId.of(padded)
        } catch (_: Exception) {
            try {
                ZoneId.ofOffset("UTC", ZoneOffset.of(padded))
            } catch (_: Exception) {
                return null
            }
        }
        return if (zone is ZoneOffset) ZoneId.ofOffset("UTC", zone).id else zone.id
    }

    /** `+5:30` / `UTC+5:30` / `-3:30`：前缀可有可无，小时一位，后面跟分钟（秒可选）。 */
    private val SINGLE_DIGIT_HOUR = Regex("^([A-Za-z]{0,3})([+-])(\\d)(:\\d{2}(?::\\d{2})?)$")

    /** 界面上显示的时区名：`Asia/Shanghai` 用中文习惯说法，其余原样显示。 */
    fun label(resources: Resources, zoneId: String): String =
        if (zoneId == ASIA_SHANGHAI) resources.getString(R.string.result_timezone_beijing) else zoneId
}
