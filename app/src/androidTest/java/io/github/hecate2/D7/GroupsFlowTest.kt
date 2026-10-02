package io.github.hecate2.D7

import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.groups.GroupsActivity
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.ArrayList
import kotlin.math.abs

/**
 * 照片组管理页的仪器测试（模拟器运行，无需真机）：
 * 新建组对话框的经纬度布局与半球提示、校验拦截、建组落库，以及删组时照片保留/连带删除两条路径。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GroupsFlowTest {

    @get:Rule
    val intentsRule = IntentsRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private val insertedPhotos = ArrayList<Uri>()
    private var scene: ActivityScenario<GroupsActivity>? = null

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
    }

    @After
    fun tearDown() {
        scene?.close()
        insertedPhotos.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        insertedPhotos.clear()
        TestSupport.deleteAllGroups(repository)
    }

    private fun launchGroups() {
        scene = ActivityScenario.launch(GroupsActivity::class.java)
    }

    private fun openNewGroupDialog() {
        launchGroups()
        onView(withId(R.id.newButton)).perform(click())
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
    }

    @Test
    fun newGroupDialog_longitudeLeftOfLatitude() {
        openNewGroupDialog()
        onView(withId(R.id.lonInput)).check(
            matches(allOf(isDisplayed(), withHint(R.string.label_longitude_hint))),
        )
        assertSideBySideLeftOf(R.id.lonInput, R.id.latInput)
        onView(withId(R.id.latInput)).check(
            matches(allOf(isDisplayed(), withHint(R.string.label_latitude_hint))),
        )
    }

    /** 断言两个控件在同一行且左者完全位于右者左边（Espresso 3.6 已移除位置匹配器）。 */
    private fun assertSideBySideLeftOf(leftId: Int, rightId: Int) {
        onView(withId(leftId)).check { view, noViewFoundException ->
            if (view == null) throw noViewFoundException
            val right = view.rootView.findViewById<View>(rightId)
                ?: throw AssertionError("未找到右侧输入框")
            val leftPos = IntArray(2).also { view.getLocationOnScreen(it) }
            val rightPos = IntArray(2).also { right.getLocationOnScreen(it) }
            assertTrue("两输入框应在同一行", abs(leftPos[1] - rightPos[1]) < view.height)
            assertTrue("左框应完全位于右框左边", leftPos[0] + view.width <= rightPos[0])
        }
    }

    @Test
    fun newGroupDialog_hemisphereHintFollowsLatitude() {
        openNewGroupDialog()
        onView(withId(R.id.latInput)).perform(replaceText("31.23"), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_north)))
        onView(withId(R.id.latInput)).perform(replaceText("-31.23"), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_south)))
        onView(withId(R.id.latInput)).perform(replaceText("10"), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_tropic)))
        onView(withId(R.id.latInput)).perform(clearText(), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_unknown)))
    }

    @Test
    fun newGroupDialog_validationKeepsDialogOpenAndCreatesNothing() {
        openNewGroupDialog()
        val before = repository.groups.value.size
        // 空组名：拦截，对话框保持打开
        onView(withText(R.string.start_capture)).perform(click())
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
        // 非法纬度：同样拦截
        onView(withId(R.id.nameInput)).perform(replaceText("在线测试组"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(replaceText("999"), closeSoftKeyboard())
        onView(withId(R.id.lonInput)).perform(replaceText("121.47"), closeSoftKeyboard())
        onView(withText(R.string.start_capture)).perform(click())
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
        assertEquals(before, repository.groups.value.size)
    }

    @Test
    fun newGroupDialog_createsGroupWithTypedCoordinates() {
        openNewGroupDialog()
        // 采集页被桩替换，避免测试依赖相机
        intending(hasComponent(CaptureActivity::class.java.name))
            .respondWith(ActivityResult(android.app.Activity.RESULT_OK, null))
        onView(withId(R.id.nameInput)).perform(replaceText("在线测试组"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(replaceText("31.23"), closeSoftKeyboard())
        onView(withId(R.id.lonInput)).perform(replaceText("121.47"), closeSoftKeyboard())
        onView(withText(R.string.start_capture)).perform(click())
        intended(hasComponent(CaptureActivity::class.java.name))
        val group = TestSupport.waitFor {
            repository.groups.value.firstOrNull { it.name == "在线测试组" }
        }
        assertEquals(31.23, group.lat, 1e-9)
        assertEquals(121.47, group.lon, 1e-9)
    }

    @Test
    fun deleteGroup_defaultKeepsPhotos() {
        val uri = TestSupport.insertTestImage(context.contentResolver, "在线测试组")
        insertedPhotos.add(uri)
        val group = seedGroupWithPhoto(uri)
        launchGroups()
        openDeleteDialog(uri)
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        TestSupport.waitFor { repository.get(group.id) == null }
        assertTrue(TestSupport.mediaExists(context.contentResolver, uri))
    }

    @Test
    fun deleteGroup_withPhotosChecked_deletesPhotos() {
        val uri = TestSupport.insertTestImage(context.contentResolver, "在线测试组")
        insertedPhotos.add(uri)
        val group = seedGroupWithPhoto(uri)
        launchGroups()
        openDeleteDialog(uri)
        onView(withId(R.id.deletePhotos)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        TestSupport.waitFor { repository.get(group.id) == null }
        TestSupport.waitFor { !TestSupport.mediaExists(context.contentResolver, uri) }
    }

    /** 长按卡片 → 菜单「删除」→ 确认勾选框默认为未勾选。 */
    private fun openDeleteDialog(uri: Uri) {
        val name = TestSupport.waitFor {
            repository.groups.value.firstOrNull { it.external.any { p -> p.photoUri == uri.toString() } }
        }
        onView(withText(name.name)).perform(longClick())
        onView(withText(R.string.menu_delete)).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.deletePhotos)).inRoot(isDialog()).check(matches(isNotChecked()))
    }

    private fun seedGroupWithPhoto(uri: Uri) = repository.createGroup(
        "在线测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
    ).also { group ->
        repository.appendPoint(
            group.id,
            Region.EXTERNAL,
            PointRecord(
                az = 180.0,
                el = 10.0,
                photoUri = uri.toString(),
                takenAt = System.currentTimeMillis(),
            ),
            viaHorizon = false,
        )
    }
}