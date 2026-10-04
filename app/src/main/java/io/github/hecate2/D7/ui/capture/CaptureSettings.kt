package io.github.hecate2.D7.ui.capture

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import io.github.hecate2.D7.R
import io.github.hecate2.D7.view.LineVisibility

/**
 * 「显示线」的九个条目：对话框文案、线色、持久化键与默认显隐。
 *
 * 对话框条目顺序就是 [entries] 的顺序，设置读写也按条目本身走，所以加一项只需在这里加一行；
 * 下标的含义不再散在三个文件里靠注释对齐。线色与
 * [io.github.hecate2.D7.view.ViewfinderOverlayView] 画线时取的是同一批 @color，
 * 条目文字即用它上色，所见即所画。
 */
enum class CaptureLine(
    /** 对话框里的条目文案。 */
    @StringRes val labelRes: Int,
    /** 该线的颜色。 */
    @ColorRes val colorRes: Int,
    /** SharedPreferences 键；沿用历史键名，老用户的设置不会丢。 */
    val prefKey: String,
    /** 首次安装的默认显隐，同时也是 [LineVisibility] 各字段的默认值。 */
    val defaultOn: Boolean,
) {
    SUMMER(R.string.line_summer, R.color.summer, "line_summer", true),
    EQUINOX(R.string.line_equinox, R.color.equinox, "line_equinox", true),
    WINTER(R.string.line_winter, R.color.winter, "line_winter", true),
    TODAY(R.string.line_today, R.color.paper, "line_today", true),
    SEGMENTS(R.string.line_segments, R.color.paper, "line_segments", true),
    HORIZON(R.string.line_horizon, R.color.smoke, "line_horizon", false),
    VERTICAL(R.string.line_vertical, R.color.smoke, "line_vertical", false),
    FILL(R.string.line_fill, R.color.moon, "line_fill", true),
    GROUND(R.string.line_ground, R.color.ground, "line_ground", true),
}

/**
 * 采集页的持久化设置：显示线的显隐、+180° 药丸与「不拍照」开关状态。
 * 默认显示四条太阳参考弧、拍摄点连线、地面层与楼体遮挡区，隐藏地平线与铅垂线；
 * +180° 默认叠加，「不拍照」默认关。
 */
class CaptureSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    var plus180: Boolean
        get() = prefs.getBoolean("plus180", true)
        set(value) = prefs.edit { putBoolean("plus180", value) }

    /**
     * 「不拍照」：快门只落角度点，不拍照也不写相册。默认关。
     *
     * 取景预览、对焦与手电筒照常——预览就是瞄准工具；省掉的是 JPEG 编码、EXIF 回写
     * 与 MediaStore 发布这一整条 IO。只想要轮廓、不想留照片（或不想在相册里留一堆
     * 楼体照）时用它。
     */
    var noPhoto: Boolean
        get() = prefs.getBoolean("no_photo", false)
        set(value) = prefs.edit { putBoolean("no_photo", value) }

    /** 一条显示线当前开不开。 */
    fun isOn(line: CaptureLine): Boolean = prefs.getBoolean(line.prefKey, line.defaultOn)

    /** 开关一条显示线。 */
    fun setOn(line: CaptureLine, on: Boolean) = prefs.edit { putBoolean(line.prefKey, on) }

    /** 当前设置对应的显示线组合。 */
    fun lines(): LineVisibility = LineVisibility(
        summer = isOn(CaptureLine.SUMMER),
        equinox = isOn(CaptureLine.EQUINOX),
        winter = isOn(CaptureLine.WINTER),
        today = isOn(CaptureLine.TODAY),
        segments = isOn(CaptureLine.SEGMENTS),
        horizon = isOn(CaptureLine.HORIZON),
        vertical = isOn(CaptureLine.VERTICAL),
        fill = isOn(CaptureLine.FILL),
        ground = isOn(CaptureLine.GROUND),
    )
}
