package io.github.hecate2.D7.ui

import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R

/** 药丸选中态：选中填充亮底 + 墨色文字，未选中描边 + 烟灰色文字。 */
fun TextView.setPillSelected(selected: Boolean) {
    setBackgroundResource(if (selected) R.drawable.bg_pill_filled else R.drawable.bg_pill)
    setTextColor(ContextCompat.getColor(context, if (selected) R.color.ink else R.color.smoke))
}

/**
 * 开关型图标键的着色：开用按住快门的琥珀色（[R.color.press]，黑底上最显眼），
 * 关用快门中心的月灰（[R.color.moon]）。采集页的手电筒键与对焦键、照片组页的
 * 屏幕常亮键共用这一套，纯图标键靠颜色表示状态，不另加文字。
 */
fun ImageView.setToggleTint(on: Boolean) {
    imageTintList = ColorStateList.valueOf(
        ContextCompat.getColor(context, if (on) R.color.press else R.color.moon),
    )
}
