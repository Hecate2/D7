package io.github.hecate2.D7.ui

import io.github.hecate2.D7.R

/**
 * 四态分级的公共取值：好 / 中 / 差 / 未知。
 *
 * 采集页的罗盘校准与读数精度、结果页的覆盖率都要「按档位给不同颜色与措辞」，
 * 此前每处都写两遍同样的 `when`（一次配文案、一次配色），容易改一处漏一处。
 * 先由状态定档，再由档位取色，文案则各页面用自己的措辞。
 */
enum class Grade { GOOD, MID, LOW, UNKNOWN }

/** 本级对应的药丸 / 文字颜色资源，沿用 `colors.xml` 里的 precision 四态。 */
fun Grade.colorRes(): Int = when (this) {
    Grade.GOOD -> R.color.precision_high
    Grade.MID -> R.color.precision_mid
    Grade.LOW -> R.color.precision_low
    Grade.UNKNOWN -> R.color.precision_unknown
}
