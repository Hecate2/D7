package io.github.hecate2.D7.ui.capture

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hecate2.D7.view.LineVisibility

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

    var showSummer: Boolean
        get() = prefs.getBoolean("line_summer", true)
        set(value) = prefs.edit { putBoolean("line_summer", value) }

    var showEquinox: Boolean
        get() = prefs.getBoolean("line_equinox", true)
        set(value) = prefs.edit { putBoolean("line_equinox", value) }

    var showWinter: Boolean
        get() = prefs.getBoolean("line_winter", true)
        set(value) = prefs.edit { putBoolean("line_winter", value) }

    var showToday: Boolean
        get() = prefs.getBoolean("line_today", true)
        set(value) = prefs.edit { putBoolean("line_today", value) }

    var showSegments: Boolean
        get() = prefs.getBoolean("line_segments", true)
        set(value) = prefs.edit { putBoolean("line_segments", value) }

    var showHorizon: Boolean
        get() = prefs.getBoolean("line_horizon", false)
        set(value) = prefs.edit { putBoolean("line_horizon", value) }

    var showVertical: Boolean
        get() = prefs.getBoolean("line_vertical", false)
        set(value) = prefs.edit { putBoolean("line_vertical", value) }

    /**
     * 遮挡填充：默认开。
     *
     * 曾默认关，理由是会压低参考弧的对比度；但那层淡白（alpha 64）压不暗什么，
     * 关掉的后果却是「拍完看不到挡在哪」——颜色不够明显该靠调 alpha 解决，
     * 不该拿默认关闭来绕。不想要的人仍可在「显示线」里关。
     */
    var showFill: Boolean
        get() = prefs.getBoolean("line_fill", true)
        set(value) = prefs.edit { putBoolean("line_fill", value) }

    /** 地面层：默认开。 */
    var showGround: Boolean
        get() = prefs.getBoolean("line_ground", true)
        set(value) = prefs.edit { putBoolean("line_ground", value) }

    /** 当前设置对应的显示线组合。 */
    fun lines(): LineVisibility = LineVisibility(
        summer = showSummer,
        equinox = showEquinox,
        winter = showWinter,
        today = showToday,
        segments = showSegments,
        horizon = showHorizon,
        vertical = showVertical,
        fill = showFill,
        ground = showGround,
    )

    /** 按「显示线」对话框的条目下标写入（顺序与对话框一致）。 */
    fun setLineChecked(which: Int, checked: Boolean) {
        when (which) {
            0 -> showSummer = checked
            1 -> showEquinox = checked
            2 -> showWinter = checked
            3 -> showToday = checked
            4 -> showSegments = checked
            5 -> showHorizon = checked
            6 -> showVertical = checked
            7 -> showFill = checked
            8 -> showGround = checked
        }
    }

    /** 各条目的当前勾选状态，顺序与对话框一致。 */
    fun lineChecked(): BooleanArray = booleanArrayOf(
        showSummer, showEquinox, showWinter, showToday, showSegments,
        showHorizon, showVertical, showFill, showGround,
    )
}