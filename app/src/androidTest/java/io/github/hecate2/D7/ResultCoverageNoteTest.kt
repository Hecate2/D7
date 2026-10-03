package io.github.hecate2.D7

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.result.ResultActivity
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
        TestSupport.deleteAllGroups(repository)
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
    }

    @Test
    fun singlePoint_showsRedTooFewWarning() {
        openResult(31.23, listOf(Triple(180.0, 30.0, false)))
        val note = noteView()
        assertTrue("拍摄点不足时提示应可见", note.isShown)
        assertEquals(
            "单点应显示「太少」文案",
            context.getString(R.string.result_coverage_toofew),
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

    private fun noteView(): TextView {
        var view: TextView? = null
        scene?.onActivity { view = it.findViewById(R.id.coverageNote) }
        return view!!
    }

    private val LOW: Int get() = ContextCompat.getColor(context, R.color.precision_low)
    private val MUTED: Int get() = ContextCompat.getColor(context, R.color.precision_unknown)
}
