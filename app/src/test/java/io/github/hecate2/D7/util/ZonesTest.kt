package io.github.hecate2.D7.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 时区三选一的**纯 JVM** 单测（不需要设备，`:app:testDebugUnitTest` 里跑）。
 *
 * 守两件事：
 *  1. 经度换算出来的偏移量对得上（日照计算里时区只影响钟面时间，错一个小时看不出来，
 *     却会让用户以为日出时间就是那样）；
 *  2. [Zones.parse] 认得下 [Zones.fromLongitude] 吐出来的 id——界面上「按经度」是先把值
 *     填进输入框、再由 [Zones.parse] 读回来落库的，这两步接不上就等于那个键没用。
 */
class ZonesTest {

    @Test
    fun fromLongitude_takesTheNearestWholeHour() {
        // 上海 121.47°E / 15 ≈ 8.1 → UTC+8；柏林 13.4 / 15 ≈ 0.9 → UTC+1
        assertEquals("UTC+08:00", Zones.fromLongitude(121.47))
        assertEquals("UTC+01:00", Zones.fromLongitude(13.4))
        // 纽约 74°W / 15 ≈ -4.93 → UTC-5
        assertEquals("UTC-05:00", Zones.fromLongitude(-74.0))
        // 半区不取整点：51.5°E / 15 ≈ 3.43 → UTC+3
        assertEquals("UTC+03:00", Zones.fromLongitude(51.5))
    }

    @Test
    fun fromLongitude_neverLeavesTheValidLongitudeRange() {
        // 零偏移不能吐成裸偏移 `Z`：界面上会显示成一个孤零零的字母
        assertEquals("UTC", Zones.fromLongitude(0.0))
        assertEquals("UTC", Zones.fromLongitude(-7.4))
        assertEquals("UTC+12:00", Zones.fromLongitude(179.9))
        // ±180 是同一条经线，规范到 -180 那一侧
        assertEquals("UTC-12:00", Zones.fromLongitude(-180.0))
        assertEquals("UTC-12:00", Zones.fromLongitude(180.0))
    }

    @Test
    fun fromLongitude_isAlwaysReadableByParse() {
        // 「按经度」那条路径：写进输入框的值必须能被 parse 原样读回
        for (lon in -180..180 step 7) {
            val zoneId = Zones.fromLongitude(lon.toDouble())
            assertEquals(zoneId, Zones.parse(zoneId))
            assertEquals(zoneId, ZoneId.of(zoneId).id)
        }
    }

    @Test
    fun parse_readsIanaNames() {
        assertEquals("Asia/Tokyo", Zones.parse("Asia/Tokyo"))
        assertEquals("Asia/Shanghai", Zones.parse("  Asia/Shanghai  "))
        assertEquals("Europe/Berlin", Zones.parse("Europe/Berlin"))
    }

    @Test
    fun parse_readsSignedOffsetsInEitherSpelling() {
        // 同一个意思的两种写法必须落成同一个 id，否则界面上会两种样子
        assertEquals("UTC+08:00", Zones.parse("+8"))
        assertEquals("UTC+08:00", Zones.parse("+08:00"))
        assertEquals("UTC+08:00", Zones.parse("+0800"))
        assertEquals("UTC-05:00", Zones.parse("-5"))
        assertEquals("UTC-05:30", Zones.parse("-05:30"))
    }

    /** 半时区的小时常常只写一位（+5:30），而 `ZoneOffset.of` 只认两位——这里替它补上。 */
    @Test
    fun parse_padsASingleDigitHourBeforeMinutes() {
        assertEquals("UTC+05:30", Zones.parse("+5:30"))
        assertEquals("UTC+05:30", Zones.parse("UTC+5:30"))
        assertEquals("UTC-03:30", Zones.parse("-3:30"))
        assertEquals("UTC+05:30", Zones.parse("  +5:30  "))
        // 补零后的写法与补零前必须落到同一个 id
        assertEquals(Zones.parse("+05:30"), Zones.parse("+5:30"))
        // 带秒的写法也一起补
        assertEquals("UTC+05:30:10", Zones.parse("+5:30:10"))
        // 用户自己写的前缀原样保留：GMT+05:30 本来就是另一个 id，不硬掰成 UTC
        assertEquals("GMT+05:30", Zones.parse("GMT+5:30"))
        // 一位小时、没有分钟的写法本来就被认（+8），不受这条规则影响
        assertEquals("UTC+08:00", Zones.parse("+8"))
    }

    @Test
    fun parse_rejectsWhatItCannotRead() {
        assertNull(Zones.parse(""))
        assertNull(Zones.parse("   "))
        assertNull(Zones.parse("北京时间"))
        assertNull(Zones.parse("Asia/Nowhere"))
        // 裸数字有歧义（是 +3 还是 -3？），宁可拒绝也不猜
        assertNull(Zones.parse("8"))
        assertNull(Zones.parse("++8"))
        // 偏移量必须带符号
        assertNull(Zones.parse("05:30"))
        // 分钟必须两位
        assertNull(Zones.parse("+05:3"))
    }

    @Test
    fun parsedIdCarriesTheOffsetFromLongitude() {
        val zone = ZoneId.of(Zones.fromLongitude(121.47))
        assertTrue(zone.rules.isFixedOffset)
        assertEquals(ZoneOffset.ofHours(8), zone.rules.getOffset(Instant.now()))
    }
}
