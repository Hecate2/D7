package io.github.hecate2.D7.ui

import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/**
 * 把数值输入框与它右侧的半球后缀绑起来：`°E`/`°W`、`°N`/`°S` 跟着输入的正负走，逐字符更新。
 *
 * 后缀不写死：`-74.000000 °E` 这种自相矛盾的组合会让人以为自己录错了方向。转到西半球、
 * 南半球时它自己就变成 `°W`、`°S`。
 *
 * 空着或者读不成数字时按正数显示（`°E`、`°N`），也就是最常见的那一侧。
 */
fun EditText.bindHemisphereSuffix(suffix: TextView, of: (Double) -> String) {
    fun sync() {
        suffix.text = of(text.toString().toDoubleOrNull() ?: 0.0)
    }
    doAfterTextChanged { sync() }
    sync()
}
