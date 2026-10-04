package io.github.hecate2.D7

import android.Manifest
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.DatePicker
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.groups.GroupsActivity
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.util.Locales
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 界面语言的仪器测试：选择生效、默认跟随系统、语言键位置，以及长文案不把布局撑坏。
 *
 * 布局那条是用户明确提过的担心（德语复合词多、俄语字宽大）。LinearLayout 的横向行在
 * 放不下时不报错，只会把带 weight 的那一格挤扁、或让药丸折成两行——都不报错，界面已经
 * 坏了。所以直接量：读数行必须还剩下宽度，两个药丸必须仍是单行。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@NeedsI18n
class LanguageSwitchTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var groupId: String = ""

    /** 译文最长的几种：德语复合词多、俄语字宽大、法语辅音串长。 */
    private val longTags = listOf("de", "ru", "fr")

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        groupId = repository.createGroup(
            "语言测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
        Locales.setTag(context, Locales.SYSTEM)
    }

    @After
    fun tearDown() {
        Locales.setTag(context, Locales.SYSTEM)
        TestSupport.deleteAllGroups(repository)
    }

    /** 八种语言各自的标题都要真的换过去，不能悄悄回落到中文。 */
    @Test
    fun titleFollowsChosenLanguage() {
        val cases = mapOf(
            "en" to "Photo sets",
            "ja" to "撮影セット",
            "ko" to "촬영 세트",
            "de" to "Fotostrecken",
            "fr" to "Séries",
            "es" to "Series",
            "ru" to "Серии",
            "zh-Hans" to "照片组",
        )
        for ((tag, expected) in cases) {
            Locales.setTag(context, tag)
            ActivityScenario.launch(GroupsActivity::class.java).use { scenario ->
                var actual: String? = null
                scenario.onActivity { actual = it.getString(R.string.groups_title) }
                assertEquals("语言 $tag 的标题不对", expected, actual)
            }
        }
    }

    /**
     * 语言键在「+ 新建」左边，点开列出「跟随系统 + 8 种语言」，选中后立刻重建生效。
     *
     * 走的是真实交互（Espresso 点菜单项），所以对话框漏项、选中不生效、页面不重建
     * 这三种问题都会在这里挂掉。
     */
    @Test
    fun languageDialogListsEveryChoiceAndAppliesIt() {
        ActivityScenario.launch(GroupsActivity::class.java).use { scenario ->
            var langLeftOfNew = false
            scenario.onActivity { activity ->
                val lang = activity.findViewById<View>(R.id.langButton)
                val create = activity.findViewById<View>(R.id.newButton)
                val a = IntArray(2)
                val b = IntArray(2)
                lang.getLocationInWindow(a)
                create.getLocationInWindow(b)
                langLeftOfNew = a[0] < b[0]
                lang.performClick()
            }
            assertTrue("语言键应在「+ 新建」左边", langLeftOfNew)

            val labels = listOf(
                R.string.language_system,
                R.string.lang_zh,
                R.string.lang_en,
                R.string.lang_ja,
                R.string.lang_ko,
                R.string.lang_de,
                R.string.lang_fr,
                R.string.lang_es,
                R.string.lang_ru,
            ).map { context.getString(it) }
            // 对话框是个独立窗口：不钉住 isDialog()，Espresso 会去等主窗口拿焦点，
            // 而对话框弹出时主窗口本来就永远没有焦点，于是必然 10 秒超时
            labels.forEach { onView(withText(it)).inRoot(isDialog()).check(matches(isDisplayed())) }

            onView(withText(context.getString(R.string.lang_ja))).inRoot(isDialog()).perform(click())

            var title = ""
            TestSupport.waitUntil(8000) {
                scenario.onActivity { title = it.findViewById<TextView>(R.id.title).text.toString() }
                title.isNotEmpty()
            }
            assertEquals("选择后应立即重建为日语", "撮影セット", title)
            assertEquals("选择应被持久化", "ja", Locales.currentTag(context))
        }
    }

    /** 默认跟随系统：清掉偏好后，界面语言等于系统当前语言。 */
    @Test
    fun defaultFollowsSystemLanguage() {
        Locales.setTag(context, Locales.SYSTEM)
        ActivityScenario.launch(GroupsActivity::class.java).use { scenario ->
            var ok = false
            scenario.onActivity { activity ->
                val system = activity.resources.configuration.locales[0]
                ok = Locales.wrap(activity).resources.configuration.locales[0] == system
            }
            assertTrue("默认应跟随系统语言", ok)
        }
    }

    /** 长语言下读数行与底部按钮仍有余量，药丸没有被撑成两行。 */
    @Test
    fun longTranslationsDoNotBreakCaptureLayout() {
        for (tag in longTags) {
            Locales.setTag(context, tag)
            val intent = Intent(context, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, groupId)
            ActivityScenario.launch<CaptureActivity>(intent).use { scenario ->
                TestSupport.waitUntil(10000) {
                    var ready = false
                    scenario.onActivity { ready = it.findViewById<TextView>(R.id.readingText).text.isNotEmpty() }
                    ready
                }
                scenario.onActivity { activity ->
                    val screen = activity.resources.displayMetrics.widthPixels
                    val reading = activity.findViewById<TextView>(R.id.readingText)
                    assertTrue(
                        "语言 $tag：读数行被挤没了（${reading.width}px / ${screen}px）",
                        reading.width > screen / 4,
                    )
                    listOf(R.id.noPhoto, R.id.plus180).forEach { id ->
                        val pill = activity.findViewById<TextView>(id)
                        val lines = pill.layout?.lineCount ?: 0
                        assertTrue(
                            "语言 $tag：${activity.resources.getResourceEntryName(id)} 折成了 $lines 行",
                            pill.text.isEmpty() || lines <= 1,
                        )
                    }
                    listOf(R.id.doneButton, R.id.deleteButton).forEach { id ->
                        val button = activity.findViewById<TextView>(id)
                        assertTrue(
                            "语言 $tag：${activity.resources.getResourceEntryName(id)} 被压没了",
                            button.width > 0 && button.text.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }

    /**
     * 结果页两行药丸在长语言下必须仍然点得到。
     *
     * 横向 LinearLayout 放不下时既不报错也不裁剪，只是把子视图推到屏幕外——选项还在，
     * 就是点不着。实测俄语下「夏至 / 自定义」两档整个掉出屏幕，「仅天花板」档被截掉一半。
     * 改成横向滚动之前，这条用例报的是
     * “Scrolling to view was attempted, but the view is not displayed”，正对着「自定义日期」。
     *
     * 所以这里不量像素，走真实交互：`scrollTo()` 会让 Espresso 找到最近的滚动祖先把它滚进
     * 可视区，滚不进来就当场失败；点下去再确认状态真的变了。
     */
    @Test
    fun longTranslationsKeepResultPillsReachable() {
        Locales.setTag(context, "ru")
        val id = repository.createGroup(
            "可达性组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
        // 「仅天花板」档在没有天花板点时是锁着的（会弹提示不切换），那验不到可点性
        (100..260 step 20).forEach { az ->
            repository.appendPoint(id, Region.EXTERNAL, PointRecord(az.toDouble(), 30.0), false)
        }
        repository.appendPoint(id, Region.CEILING, PointRecord(165.0, 30.0), false)

        val intent = Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, id)
        ActivityScenario.launch<ResultActivity>(intent).use { scenario ->
            TestSupport.waitUntil(10000) {
                var ready = false
                scenario.onActivity {
                    ready = it.findViewById<TextView>(R.id.chipCeilingOnly).text.isNotEmpty()
                }
                ready
            }

            // 「仅天花板」：这一档原先在俄语下被截掉一半，够不着
            onView(withId(R.id.chipCeilingOnly)).perform(scrollTo(), click())
            var switched = false
            TestSupport.waitUntil(8000) {
                scenario.onActivity { activity ->
                    switched = activity.findViewById<TextView>(R.id.mainLabel).text.toString()
                        .contains(activity.getString(R.string.result_mode_ceiling))
                }
                switched
            }
            assertTrue("「仅天花板」档点不动或没生效", switched)

            // 「自定义日期」：最后一个药丸，原先整个掉出屏幕外
            onView(withId(R.id.pillCustom)).perform(scrollTo(), click())
            // 断言用 DatePicker 本身而不是标题文字：对话框标题与按钮都是框架文案，
            // 跟着系统语言走，这里不该去猜它长什么样
            onView(isAssignableFrom(DatePicker::class.java)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            pressBack()

            repository.deleteGroup(id)
        }
    }
}
