package io.github.hecate2.D7

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.Settings
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.groups.GroupsActivity
import io.github.hecate2.D7.ui.result.ResultActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 照片组页右上角的「屏幕常亮」开关，以及它对三个页面的作用。
 *
 * 三条线索各盯一类错：
 * 1. 开关本身要落偏好、并**当场**改本窗口的标志——标志是逐窗口的，只写偏好不重设
 *    就感觉不到点了没反应；
 * 2. 关掉后采集页与结果页也不该再常亮——这两个页面各自读偏好，漏一处就白设；
 * 3. 重新进页面要读回上次的选择，而不是回到默认值。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class KeepScreenOnTest {

    // 采集页那条要绑相机，不先授予权限会弹系统框打断 Espresso
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<out Activity>? = null
    private var groupId: String = ""

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        groupId = repository.createGroup(
            "常亮测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
        // 每条用例都从默认态开始：默认常亮
        Settings(context).keepScreenOn = true
    }

    @After
    fun tearDown() {
        scene?.close()
        Settings(context).keepScreenOn = true
        TestSupport.deleteAllGroups(repository)
    }

    @Test
    fun toggleWritesPreferenceAndWindowFlagAtOnce() {
        scene = ActivityScenario.launch(GroupsActivity::class.java)
        assertTrue("默认就该常亮", keepAwakeFlag(scene!!))

        onView(withId(R.id.keepOnButton)).perform(click())

        assertFalse("点了之后偏好必须落成「不常亮」", Settings(context).keepScreenOn)
        assertFalse("本窗口的标志也必须当场清掉，不然点了像没反应", keepAwakeFlag(scene!!))
    }

    @Test
    fun choiceSurvivesReenteringTheScreen() {
        scene = ActivityScenario.launch(GroupsActivity::class.java)
        onView(withId(R.id.keepOnButton)).perform(click())
        assertFalse(keepAwakeFlag(scene!!))

        // 关掉再开一次，模拟用户离开又回来
        scene!!.close()
        scene = ActivityScenario.launch(GroupsActivity::class.java)

        assertFalse("重进页面该读回上次的选择，不是回到默认的常亮", Settings(context).keepScreenOn)
        assertFalse(keepAwakeFlag(scene!!))
    }

    @Test
    fun captureScreenHonorsTheSetting() {
        Settings(context).keepScreenOn = false
        scene = launchCapture()
        assertFalse("关掉常亮后采集页也不该再常亮", keepAwakeFlag(scene!!))

        scene!!.close()
        Settings(context).keepScreenOn = true
        scene = launchCapture()
        assertTrue("常亮开着时采集页要常亮", keepAwakeFlag(scene!!))
    }

    @Test
    fun resultScreenHonorsTheSetting() {
        repository.appendPoint(
            groupId,
            Region.EXTERNAL,
            PointRecord(az = 180.0, el = 30.0, takenAt = 1),
            viaHorizon = false,
        )
        Settings(context).keepScreenOn = false
        scene = launchResult()
        assertFalse("关掉常亮后结果页也不该再常亮", keepAwakeFlag(scene!!))

        scene!!.close()
        Settings(context).keepScreenOn = true
        scene = launchResult()
        assertTrue("常亮开着时结果页要常亮", keepAwakeFlag(scene!!))
    }

    private fun launchCapture(): ActivityScenario<CaptureActivity> =
        ActivityScenario.launch(
            Intent(context, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, groupId),
        )

    private fun launchResult(): ActivityScenario<ResultActivity> =
        ActivityScenario.launch(
            Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, groupId),
        )

    /** 该窗口有没有拿到常亮标志。 */
    private fun keepAwakeFlag(scene: ActivityScenario<out Activity>): Boolean {
        var on = false
        (scene as ActivityScenario<Activity>).onActivity { activity ->
            val flags = activity.window.attributes.flags
            on = flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        return on
    }
}
