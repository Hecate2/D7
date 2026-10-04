package io.github.hecate2.D7

import android.Manifest
import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.groups.GroupsActivity
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 采集页与结果页左上角返回键：点一下要回到照片组列表。
 *
 * 用户提的：有些手机没有底部返回导航键（手势导航或把导航条藏起来），这两页就成了死胡同——
 * 采集页还能靠「完成」跳结果页，结果页则连退路都没有，只能杀掉应用。
 *
 * 两条都走真实路径：从照片组列表点卡片进页面，再点返回，最后断言列表页回来了。
 * 直接 `ActivityScenario.launch(CaptureActivity)` 测不出这个问题——那条路上没有上一页，
 * 返回键 finish 掉之后谁也没回来，断言会落在别的东西上。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BackButtonTest {

    // 相机权限不先授予的话，采集页会弹系统权限框把 Activity 切到 PAUSED，Espresso 直接报错
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<GroupsActivity>? = null
    private var groupId: String = ""

    private companion object {
        const val GROUP_NAME = "返回键测试组"
    }

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        groupId = repository.createGroup(
            GROUP_NAME, 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
    }

    /** 空组点卡片直接进采集页（见 `GroupsActivity.openGroup`）。 */
    @Test
    fun captureBackButtonReturnsToGroups() {
        launchGroups()
        openCard()

        waitFor(withId(R.id.shutter))
        onView(withId(R.id.backButton)).perform(click())

        assertBackAtGroups()
    }

    /** 有点的组点卡片进结果页。 */
    @Test
    fun resultBackButtonReturnsToGroups() {
        repository.appendPoint(
            groupId,
            Region.EXTERNAL,
            PointRecord(az = 180.0, el = 30.0, takenAt = 1),
            viaHorizon = false,
        )
        launchGroups()
        openCard()

        waitFor(withId(R.id.titleResult))
        onView(withId(R.id.backButton)).perform(click())

        assertBackAtGroups()
    }

    private fun launchGroups() {
        scene = ActivityScenario.launch(GroupsActivity::class.java)
    }

    /** 点卡片进下一屏：组名那一格不可点，事件落到卡片的监听上。 */
    /** 卡片是异步铺上去的，点之前得先等它出现。 */
    private fun openCard() {
        waitFor(withText(GROUP_NAME))
        onView(withText(GROUP_NAME)).perform(click())
    }

    private fun assertBackAtGroups() {
        waitFor(withId(R.id.newButton))
        onView(withId(R.id.groupList)).check(matches(isDisplayed()))
    }

    /** 轮询等待某个视图出现并可见；超时抛 AssertionError。 */
    private fun waitFor(matcher: Matcher<View>, timeoutMs: Long = 8000) {
        TestSupport.waitFor<Boolean>(timeoutMs) {
            runCatching { onView(matcher).check(matches(isDisplayed())) }.isSuccess.takeIf { it }
        }
    }
}
