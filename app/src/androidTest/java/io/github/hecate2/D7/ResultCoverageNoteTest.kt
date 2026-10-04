package io.github.hecate2.D7

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.util.Locales
import io.github.hecate2.D7.util.Summaries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 结果页覆盖提示的仪器测试：
 * 拍摄点不足两个、覆盖率低于阈值、覆盖率达标三档分别给出红/黄/不着色的提示，
 * 且提示与主结果同屏（用户点「完成」后就看不到覆盖条了，这里是最后一道告知）。
 * 另外两条盯住「仅天花板」档：未覆盖语义与外部档相反，提示必须换区、换方向。
 *
 * 全类固定中文：`Locales.SYSTEM` 下界面语言等于系统语言（仪器测试的模拟器是英文），
 * 「偏保守 / 偏乐观」这种方向判据是对中文文案本身的断言，跑到别的语言上就没有意义了。
 * 期望值也一律从 Activity 上取字符串而不是从 application context 取——后者没经过
 * `Locales.wrap`，两边的语言可以不同，比出来的相等是假的。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ResultCoverageNoteTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<ResultActivity>? = null
    private var groupId: String = ""

    @Before
    fun setUp() {
        Locales.setTag(context, "zh-Hans")
        TestSupport.deleteAllGroups(repository)
    }

    @After
    fun tearDown() {
        scene?.close()
        Locales.setTag(context, Locales.SYSTEM)
        TestSupport.deleteAllGroups(repository)
    }

    @Test
    fun singlePoint_showsRedTooFewWarning() {
        openResult(31.23, listOf(Triple(180.0, 30.0, false)))
        val note = noteView()
        assertTrue("拍摄点不足时提示应可见", note.isShown)
        assertEquals(
            "单点应显示「太少」文案",
            stringOnActivity(R.string.result_coverage_toofew),
            note.text.toString(),
        )
    }

    @Test
    fun narrowSpan_showsRedLowCoverage() {
        // 只扫 165..175：主半圆 180 个采样里只覆盖约 10 个，远低于 60% 阈值
        openResult(31.23, listOf(Triple(165.0, 30.0, false), Triple(175.0, 30.0, false)))
        val note = noteView()
        assertTrue("覆盖率过低时提示应可见", note.isShown)
        val text = note.text.toString()
        assertTrue("应含百分比，实际：$text", text.contains('%'))
        assertEquals("低覆盖率应着红色", LOW, note.currentTextColor)
    }

    @Test
    fun wideSpan_showsOkNoteInMutedColor() {
        //扫满主半圆 100..260：覆盖率 100%
        val points = (100..260 step 10).map { Triple(it.toDouble(), 30.0, false) }
        openResult(31.23, points)
        val note = noteView()
        assertTrue("全覆盖时提示仍应可见（告知已拍全）", note.isShown)
        assertEquals("全覆盖应为不着色的烟灰", MUTED, note.currentTextColor)
    }

    @Test
    fun southernHemisphere_usesMirroredHalfCircle() {
        // 南纬34：主半圆是 270..90（经 0）。只拍 280..300 应算低覆盖，
        // 而北半球算法会认为 90..270 未覆盖、这180 度属于 300..360..0..90 之外
        openResult(-34.0, listOf(Triple(280.0, 30.0, false), Triple(300.0, 30.0, false)))
        val note = noteView()
        assertTrue("南半球也应显示覆盖率提示", note.isShown)
        assertEquals("南半球低覆盖应着红色", LOW, note.currentTextColor)
    }

    /**
     * 「仅天花板」档的提示必须换口径：那一档未拍到 = 全遮挡，拍得越少结论越偏向「没太阳」，
     * 偏差方向与外部档相反；且外部区的覆盖率与该档结论无关，得看天花板区自己的。
     *
     * 这里的种子故意让两区覆盖率差距极大（外部扫满100%、天花板只扫 165..175）：
     * 若代码仍读外部列，文案会变成「半球覆盖 100%」的烟灰提示，与期望不符。
     */
    @Test
    fun ceilingOnlyMode_usesCeilingCoverageAndFlipsTheWording() {
        val group = repository.createGroup(
            "天花板覆盖组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        )
        groupId = group.id
        (100..260 step 10).forEach { az ->
            repository.appendPoint(group.id, Region.EXTERNAL, PointRecord(az.toDouble(), 30.0), false)
        }
        val ceiling = listOf(PointRecord(165.0, 30.0), PointRecord(175.0, 30.0))
        ceiling.forEach { p -> repository.appendPoint(group.id, Region.CEILING, p, false) }

        scene = ActivityScenario.launch(
            Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, group.id),
        )
        onView(withId(R.id.chipCeilingOnly)).perform(scrollTo(), click())

        val expectedPercent = Summaries.coveragePercent(31.23, ceiling)
        val expected = stringOnActivity(R.string.result_coverage_ceiling_low, expectedPercent)
        val note = TestSupport.waitFor { noteView().takeIf { it.text.toString() == expected } }

        onView(withId(R.id.coverageNote)).check(matches(isDisplayed()))
        assertEquals("仅天花板档应改用天花板区自己的覆盖率", expected, note.text.toString())
        assertEquals("天花板区覆盖过低同样着红色", LOW, note.currentTextColor)
        assertTrue(
            "不该再出现外部区的「偏乐观」文案",
            note.text.toString() != stringOnActivity(R.string.result_coverage_toofew),
        )
    }

    /** 外部区扫满、天花板区不足两点：提示要说「偏保守」，而外部档说的是「偏乐观」。 */
    @Test
    fun ceilingOnlyMode_singlePointSaysConservativeNotOptimistic() {
        val group = repository.createGroup(
            "天花板单点组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        )
        groupId = group.id
        (100..260 step 10).forEach { az ->
            repository.appendPoint(group.id, Region.EXTERNAL, PointRecord(az.toDouble(), 30.0), false)
        }
        repository.appendPoint(group.id, Region.CEILING, PointRecord(165.0, 30.0), false)

        scene = ActivityScenario.launch(
            Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, group.id),
        )
        onView(withId(R.id.chipCeilingOnly)).perform(scrollTo(), click())

        val expected = stringOnActivity(R.string.result_coverage_ceiling_toofew)
        val note = TestSupport.waitFor { noteView().takeIf { it.text.toString() == expected } }
        assertEquals(expected, note.text.toString())
        assertTrue(
            "单点文案必须是「偏保守」而不是外部档的「偏乐观」",
            expected.contains("保守") &&
                stringOnActivity(R.string.result_coverage_toofew).contains("乐观"),
        )
    }

    private fun openResult(lat: Double, external: List<Triple<Double, Double, Boolean>>) {
        val group = repository.createGroup(
            "覆盖测试组", lat, 121.47, 0.0, ZoneId.systemDefault().id,
        )
        groupId = group.id
        external.forEach { (az, el, gap) ->
            repository.appendPoint(
                group.id,
                Region.EXTERNAL,
                PointRecord(az = az, el = el),
                viaHorizon = gap,
            )
        }
        scene = ActivityScenario.launch(
            Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, group.id),
        )
        // 覆盖率是纯函数，但主结果要等后台协程算完才刷新
        TestSupport.waitUntil(8000) { noteView()?.isShown == true }
    }

    /** 从 Activity 上取文案：只有 Activity 的 context 被 `Locales.wrap` 过，语言才对得上。 */
    private fun stringOnActivity(@StringRes id: Int, vararg args: Any): String {
        var value = ""
        scene?.onActivity { value = it.getString(id, *args) }
        return value
    }

    private fun noteView(): TextView {
        var view: TextView? = null
        scene?.onActivity { view = it.findViewById(R.id.coverageNote) }
        return view!!
    }

    private val LOW: Int get() = ContextCompat.getColor(context, R.color.precision_low)
    private val MUTED: Int get() = ContextCompat.getColor(context, R.color.precision_unknown)
}
