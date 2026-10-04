package io.github.hecate2.D7.util

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import io.github.hecate2.D7.R
import java.util.Locale

/**
 * 应用内语言：默认跟随系统，可在照片组管理页切换。
 *
 * 不引入 AppCompat——本项目刻意把依赖树收到最小（见 `docs/ARCHITECTURE.md` 第 12 节），
 * 而 `AppCompatDelegate.setApplicationLocales` 是最省事的一条路，改用它等于为一件小事
 * 把 appcompat + fragment + material 全拖回来。所以走最朴素可靠的一套：每个 Activity 在
 * `attachBaseContext` 里用当前选择覆写一份配置。minSdk 26 到 targetSdk 35 行为一致，
 * 也不必把用户领去系统设置里找。
 *
 * 语言名一律用各自语言自己的写法（endonym），不翻译——翻译成中文的「德语」对想切德语的
 * 用户毫无帮助，反而多一份要维护的文案。
 */
object Locales {

    /** 跟随系统：偏好里存空串。 */
    const val SYSTEM = ""

    private const val PREFS = "locale"
    private const val KEY_TAG = "tag"

    /**
     * 支持的语言，按用户指定的顺序：联合国安理会常任理事国（中、英、法、俄）
     * 外加日、韩、德、西。tag 用 BCP 47 形式，带书写系统以免 `zh` 在部分地区被解析成繁体。
     */
    private val TAGS = listOf("zh-Hans", "en", "ja", "ko", "de", "fr", "es", "ru")

    /** 语言名（endonym）。与界面语言无关，故全部标 `translatable="false"`，只存一份。 */
    private val ENDONYMS = mapOf(
        "zh-Hans" to R.string.lang_zh,
        "en" to R.string.lang_en,
        "ja" to R.string.lang_ja,
        "ko" to R.string.lang_ko,
        "de" to R.string.lang_de,
        "fr" to R.string.lang_fr,
        "es" to R.string.lang_es,
        "ru" to R.string.lang_ru,
    )

    /** 对话框里的顺序：跟随系统打头，其余按 [TAGS]。 */
    fun choices(): List<String> = listOf(SYSTEM) + TAGS

    /** 该语言的名字用哪种写法写；传入 [SYSTEM] 会得到 [R.string.language_system]。 */
    @StringRes
    fun labelRes(tag: String): Int = ENDONYMS[tag] ?: R.string.language_system

    /** 当前选择，[SYSTEM] 表示跟随系统。 */
    fun currentTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, SYSTEM)
            ?: SYSTEM

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
    }

    /**
     * 把 [base] 的配置按当前选择改写，返回一个可以直接拿去 setContentView 的 Context。
     *
     * `setDefault` 也要跟着改：格式化日期与数字（`String.format`）走的是默认 Locale，
     * 只改资源配置的话，中文界面下仍会按英文习惯拼「12月21日」。
     */
    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        val locale = if (tag == SYSTEM) systemLocale(base) else Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** 系统当前语言；取不到就退回简体中文，与 `values/` 的默认资源一致。 */
    private fun systemLocale(context: Context): Locale {
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) Locale.SIMPLIFIED_CHINESE else locales[0]
    }
}