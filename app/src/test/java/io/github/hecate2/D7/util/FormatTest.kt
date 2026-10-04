package io.github.hecate2.D7.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 界面文本格式化的**纯 JVM** 单测（不需要设备，`:app:testDebugUnitTest` 里跑）。
 *
 * 目前只守一条：地点是**经度在前**。这个顺序在五个地方显示（照片组卡片、结果页元信息、
 * 采集页标题、GPS 定位结果、导出图片页脚），翻回纬度在前不会有任何编译错误，
 * 只会让人对着两行数字怀疑自己录错了。
 */
class FormatTest {

    @Test
    fun coordinate_putsLongitudeFirst() {
        assertEquals("121.47°E 31.23°N", Format.coordinate(31.23, 121.47))
    }

    @Test
    fun coordinate_namesTheHemisphereBySign() {
        assertEquals("74.01°W 40.71°N", Format.coordinate(40.71, -74.01))
        assertEquals("58.38°W 34.60°S", Format.coordinate(-34.60, -58.38))
        assertEquals("0.00°E 0.00°N", Format.coordinate(0.0, 0.0))
    }

    /** 输入框右侧那一小格用的后缀，与 [Format.coordinate] 必须同一套记号。 */
    @Test
    fun hemisphereSuffixes_matchTheCoordinateString() {
        assertEquals("°N", Format.latitudeSuffix(31.23))
        assertEquals("°S", Format.latitudeSuffix(-33.87))
        assertEquals("°E", Format.longitudeSuffix(121.47))
        assertEquals("°W", Format.longitudeSuffix(-74.01))
        // 零度算东经北纬：`Format.coordinate` 里也是这么判的
        assertEquals("°N", Format.latitudeSuffix(0.0))
        assertEquals("°E", Format.longitudeSuffix(0.0))
    }
}
