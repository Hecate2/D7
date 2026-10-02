package io.github.hecate2.D7

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onIdle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 结果页点列编辑的仪器测试（模拟器运行，无需真机）：
 * 批量编辑的 h/0/HORIZON 别名（行内语义：horizon 标在某行表示该行与上一行/右边相邻点之间经地平线）、
 * 首行 horizon 拒绝且不部分应用、重排行序按 (az, el) 继承照片、整体偏移（含方位规范化与仰角钳位）、
 * 行数变化变无图点、非法行不改数据、两区分段编辑互不影响。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ResultEditFlowTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private lateinit var groupId: String
    private var scene: ActivityScenario<ResultActivity>? = null

    @Before
    fun seed() {
        TestSupport.deleteAllGroups(repository)
        val group = repository.createGroup(
            "在线测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        )
        groupId = group.id
        seedPoint(180.0, 10.0, "content://test/1", 11L)
        seedPoint(190.0, 20.0, "content://test/2", 22L)
        seedPoint(200.0, 30.0, "content://test/3", 33L)
        repository.appendPoint(
            groupId,
            Region.CEILING,
            PointRecord(az = 90.0, el = 60.0, photoUri = "content://test/4", takenAt = 44L),
            viaHorizon = false,
        )
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
    }

    private fun seedPoint(az: Double, el: Double, photoUri: String, takenAt: Long) {
        repository.appendPoint(
            groupId,
            Region.EXTERNAL,
            PointRecord(az = az, el = el, photoUri = photoUri, takenAt = takenAt),
            viaHorizon = false,
        )
    }

    private fun launchResult() {
        val intent = Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, groupId)
        scene = ActivityScenario.launch(intent)
        onView(withId(R.id.editPoints)).perform(scrollTo(), click())
        onView(withId(R.id.bulkInput)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun bulkText(text: String) {
        onView(withId(R.id.bulkInput)).inRoot(isDialog()).perform(replaceText(text))
    }

    private fun confirmBulk() {
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        onIdle()
    }

    private fun external(): List<PointRecord> = repository.get(groupId)?.external.orEmpty()

    @Test
    fun bulkEdit_horizonAliasesMarkSegmentToPreviousRow() {
        launchResult()
        bulkText("236.2 18.4\n210 20 h\n220 30 0\n230 40 HORIZON")
        confirmBulk()
        val external = TestSupport.waitFor {
            external().takeIf { it.size == 4 && it[0].az == 236.2 }
        }
        assertEquals(18.4, external[0].el, 1e-9)
        assertEquals(210.0, external[1].az, 1e-9)
        assertEquals(220.0, external[2].az, 1e-9)
        assertEquals(230.0, external[3].az, 1e-9)
        // horizon 标在第 i 行 = 第 i-1 点与其右边相邻点之间的段
        assertTrue(external[0].gapAfter)
        assertTrue(external[1].gapAfter)
        assertTrue(external[2].gapAfter)
        assertFalse(external[3].gapAfter)
        // 行数由 3 变 4，对不上旧点，全部无图
        external.forEach {
            assertNull(it.photoUri)
            assertEquals(0L, it.takenAt)
        }
    }

    @Test
    fun bulkEdit_reorderInheritsPhotosByAngle() {
        launchResult()
        bulkText("200 30\n180 10\n190 20")
        confirmBulk()
        val external = TestSupport.waitFor {
            external().takeIf { it.size == 3 && it[0].az == 200.0 }
        }
        // 行序打乱但角度一一对应：照片与拍摄时间跟着角度走
        assertEquals("content://test/3", external[0].photoUri)
        assertEquals(33L, external[0].takenAt)
        assertEquals("content://test/1", external[1].photoUri)
        assertEquals(11L, external[1].takenAt)
        assertEquals("content://test/2", external[2].photoUri)
        assertEquals(22L, external[2].takenAt)
        assertTrue(external.none { it.gapAfter })
    }

    @Test
    fun bulkEdit_firstLineHorizonRejectedWithoutPartialApply() {
        launchResult()
        bulkText("185 15")
        onView(withId(R.id.chipCeiling)).inRoot(isDialog()).perform(click())
        bulkText("95 65 h\n100 45")
        confirmBulk()
        // 弹窗保持打开；首行无上一行，标 horizon 被拒绝，两区都不落库
        onView(withId(R.id.bulkInput)).inRoot(isDialog()).check(matches(isDisplayed()))
        val external = external()
        assertEquals(3, external.size)
        assertEquals(180.0, external[0].az, 1e-9)
        assertTrue(external.all { it.photoUri != null })
        val ceiling = repository.get(groupId)?.ceiling.orEmpty()
        assertEquals(1, ceiling.size)
        assertEquals(90.0, ceiling[0].az, 1e-9)
    }

    @Test
    fun bulkEdit_invalidLineKeepsData() {
        launchResult()
        bulkText("abc 10\n200 30")
        confirmBulk()
        val external = external()
        assertEquals(3, external.size)
        assertEquals(180.0, external[0].az, 1e-9)
        assertTrue(external.all { it.photoUri != null })
    }

    @Test
    fun bulkEdit_offsetNormalizesAzimuthAndClampsElevation() {
        launchResult()
        bulkText("350 85\n10 20\n30 40")
        onView(withId(R.id.azOffsetInput)).inRoot(isDialog()).perform(replaceText("5"))
        onView(withId(R.id.elOffsetInput)).inRoot(isDialog()).perform(replaceText("10"))
        onView(withId(R.id.applyOffset)).inRoot(isDialog()).perform(click())
        confirmBulk()
        val external = TestSupport.waitFor {
            external().takeIf { it.size == 3 && it[0].az == 355.0 }
        }
        assertEquals(90.0, external[0].el, 1e-9)
        assertEquals("content://test/1", external[0].photoUri)
        assertEquals(15.0, external[1].az, 1e-9)
        assertEquals(30.0, external[1].el, 1e-9)
        assertEquals(35.0, external[2].az, 1e-9)
        assertEquals(50.0, external[2].el, 1e-9)
    }

    @Test
    fun bulkEdit_regionSwitchKeepsOtherRegionUntouched() {
        launchResult()
        val externalText = "180.0 10.0\n190.0 20.0\n200.0 30.0"
        onView(withId(R.id.bulkInput)).inRoot(isDialog()).check(matches(withText(externalText)))
        onView(withId(R.id.chipCeiling)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.bulkInput)).inRoot(isDialog())
            .check(matches(withText("90.0 60.0")))
        bulkText("100 45\n110 50")
        onView(withId(R.id.chipExternal)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.bulkInput)).inRoot(isDialog()).check(matches(withText(externalText)))
        confirmBulk()
        val group = TestSupport.waitFor {
            repository.get(groupId)?.takeIf { it.ceiling.size == 2 }
        }
        assertEquals(3, group.external.size)
        assertEquals(100.0, group.ceiling[0].az, 1e-9)
        assertEquals(45.0, group.ceiling[0].el, 1e-9)
        assertNull(group.ceiling[0].photoUri)
        onView(withId(R.id.pointsCount)).check(
            matches(withText(context.getString(R.string.result_points_count, 3, 2))),
        )
    }
}