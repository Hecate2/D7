package io.github.hecate2.D7.ui.capture

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hecate2.D7.view.LineVisibility

/**
 * 采集页的持久化设置：显示线的显隐与 +180° 药丸状态。
 * 默认显示四条太阳参考弧与拍摄点连线，隐藏地平线与铅垂线；+180° 默认叠加。
 */
class CaptureSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    var plus180: Boolean
        get() = prefs.getBoolean("plus180", true)
        set(value) = prefs.edit { putBoolean("plus180", value) }

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

    /** 当前设置对应的显示线组合。 */
    fun lines(): LineVisibility = LineVisibility(
        summer = showSummer,
        equinox = showEquinox,
        winter = showWinter,
        today = showToday,
        segments = showSegments,
        horizon = showHorizon,
        vertical = showVertical,
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
        }
    }

    /** 各条目的当前勾选状态，顺序与对话框一致。 */
    fun lineChecked(): BooleanArray = booleanArrayOf(
        showSummer, showEquinox, showWinter, showToday, showSegments, showHorizon, showVertical,
    )
}