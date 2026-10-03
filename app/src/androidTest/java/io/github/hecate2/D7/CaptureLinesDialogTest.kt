package io.github.hecate2.D7

import android.Manifest
import android.content.Context
import android.content.Intent
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.capture.CaptureSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 采集页「显示线」对话框的仪器测试：
 * 每条线名用线本身的颜色着色（夏至橙 / 春秋分红 / 冬至蓝 / 今日白 / 参考线灰），
 * 且着色之后勾选与持久化仍照旧工作。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CaptureLinesDialogTest {

    // 相机权限不先授予的话，采集页会弹出系统权限框把 Activity 切到 PAUSED，Espresso 直接报
    // NoActivityResumedException
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private val settings get() = CaptureSettings(context)
    private var scene: ActivityScenario<CaptureActivity>? = null

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        resetLinePrefs()
        val group = repository.createGroup("配色测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id)
        scene = ActivityScenario.launch(
            Intent(context, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, group.id),
        )
    }

    @After
    fun tearDown() {
        scene?.close()
        resetLinePrefs()
        TestSupport.deleteAllGroups(repository)
    }

    /** 该对话框的期望配色：条目文案 → 线色，锁住标签与线不再错位。 */
    private val expectedColors = listOf(
        R.string.line_summer to R.color.summer,
        R.string.line_equinox to R.color.equinox,
        R.string.line_winter to R.color.winter,
        R.string.line_today to R.color.paper,
        R.string.line_segments to R.color.paper,
        R.string.line_horizon to R.color.smoke,
        R.string.line_vertical to R.color.smoke,
        R.string.line_fill to R.color.moon,
        R.string.line_ground to R.color.ground,
    )

    @Test
    fun linesDialog_eachLabelUsesItsLineColor() {
        openDialog()
        expectedColors.forEach { (labelRes, colorRes) -> assertLabelColor(labelRes, colorRes) }
    }

    @Test
    fun linesDialog_toggleRowStillPersists() {
        openDialog()
        onView(withText(R.string.line_summer)).inRoot(isDialog()).check(matches(isChecked()))
        onView(withText(R.string.line_summer)).inRoot(isDialog()).perform(click())
        TestSupport.waitFor { settings.showSummer.takeIf { !it } }
        onView(withText(R.string.line_summer)).inRoot(isDialog()).check(matches(isNotChecked()))
        onView(withText(R.string.line_summer)).inRoot(isDialog()).perform(click())
        TestSupport.waitFor { settings.showSummer.takeIf { it } }
    }

    private fun openDialog() {
        onView(withId(R.id.showLinesButton)).perform(click())
        onView(withText(R.string.lines_title)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun assertLabelColor(labelRes: Int, colorRes: Int) {
        val expected = ContextCompat.getColor(context, colorRes)
        onView(withText(labelRes)).inRoot(isDialog()).check { view, _ ->
            val text = (view as TextView).text as Spanned
            val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            val actual = spans.singleOrNull()?.foregroundColor
            if (actual != expected) {
                throw AssertionError("$labelRes 的文字颜色应为 ${expected.toHex()}，实为 ${actual?.toHex()}")
            }
        }
    }

    /** 用例前后把持久化设置复位，避免跨用例污染。 */
    private fun resetLinePrefs() {
        settings.showSummer = true
        settings.showEquinox = true
        settings.showWinter = true
        settings.showToday = true
        settings.showSegments = true
        settings.showHorizon = false
        settings.showVertical = false
        settings.showFill = false
        settings.showGround = true
    }

    private fun Int.toHex(): String = "#%06X".format(this and 0xFFFFFF)
}