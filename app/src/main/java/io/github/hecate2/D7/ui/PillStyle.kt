package io.github.hecate2.D7.ui

import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R

/** 药丸选中态：选中填充亮底 + 墨色文字，未选中描边 + 烟灰色文字。 */
fun TextView.setPillSelected(selected: Boolean) {
    setBackgroundResource(if (selected) R.drawable.bg_pill_filled else R.drawable.bg_pill)
    setTextColor(ContextCompat.getColor(context, if (selected) R.color.ink else R.color.smoke))
}