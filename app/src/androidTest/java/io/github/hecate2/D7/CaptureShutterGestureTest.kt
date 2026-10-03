package io.github.hecate2.D7

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.capture.CaptureActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 快门手势的仪器测试：
 * 长按满阈值时就地记下一个经地平线推断的空隙点（不等抬手），之后按住多久都不再记；
 * 按住期间滑出按钮则什么也不记。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CaptureShutterGestureTest {

    // 相机权限不先授予的话，采集页会弹系统权限框把 Activity 切到 PAUSED，Espresso 直接报错
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<CaptureActivity>? = null
    private var groupId: String = ""

    private companion object {
        /** 锁定的瞄准仰角（度）：正数，确保不被 AimGuard 判为瞄地面。 */
        const val TEST_ELEVATION_DEG = 20.0
    }

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        groupId = repository.createGroup(
            "手势测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
        scene = ActivityScenario.launch(
            Intent(context, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, groupId),
        )
        // 没拿到姿态时快门不记录点，先等读数出现
        TestSupport.waitUntil(8000) { !reading().isNullOrBlank() }
        // 模拟器的合成姿态固定在约 -4.7 度（略微下倾），会被 AimGuard 当成瞄地面
        // 而拒绝记录。真机竖持朝楼顶时仰角为正，走不到那个分支。
        // 在传感器层锁定仰角，逐帧生效，长按期间也不会被真实读数覆盖。
        aimAt(TEST_ELEVATION_DEG)
    }

    /** 锁定/解除瞄准仰角（null恢复真实读数）。 */
    private fun aimAt(elevationDeg: Double?) {
        scene?.onActivity { it.setAimElevationForTest(elevationDeg) }
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
    }

    @Test
    fun longPress_recordsHorizonPointBeforeReleaseAndOnlyOnce() {
        // 先短按记一个直接连线点，作为长按的参照
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        TestSupport.waitFor { repository.get(groupId)?.external?.takeIf { it.size >= 1 } }
        waitShutterIdle()

        // 再长按：到阈值时（还没抬手）就该已经记下第二个点
        touch(MotionEvent.ACTION_DOWN)
        TestSupport.waitFor { repository.get(groupId)?.external?.takeIf { it.size >= 2 } }
        val fired = snapshot()
        // 再多按一会儿也不该多记
        Thread.sleep(900)
        val points = repository.get(groupId)!!.external
        assertEquals("按住期间只应记一个点 fired=$fired after=${snapshot()}", 2, points.size)
        // gapAfter 记在「与新点相连的那个旧点」上：新点自身仍是 false
        assertTrue("长按后前一点与新点应改为经地平线推断段", points[0].gapAfter)
        assertTrue("新点自身的 gapAfter 应为 false", !points[1].gapAfter)
        touch(MotionEvent.ACTION_UP)
        assertEquals("抬手不应再补记", 2, repository.get(groupId)!!.external.size)
    }

    @Test
    fun slideOffBeforeThreshold_recordsNothing() {
        touch(MotionEvent.ACTION_DOWN)
        Thread.sleep(100)
        // 向右滑出按钮：触发 MOVE 取消分支（尚未到长按阈值）
        touch(MotionEvent.ACTION_MOVE, outOfShutter = true)
        Thread.sleep(700)
        assertTrue("滑出后不应留下任何点", repository.get(groupId)!!.external.isEmpty())
        touch(MotionEvent.ACTION_UP)
        assertTrue(repository.get(groupId)!!.external.isEmpty())
    }

    /** 当前全部组的点数快照，失败时一起打出来定位。 */
    private fun snapshot(): String = repository.groups.value
        .joinToString { "${it.id.take(6)}:e${it.external.size}/c${it.ceiling.size}" }

    /** 等上一次拍照彻底做完（快门透明度由 onShutter 置灰、结束后复原），避免两次按压互相干扰。 */
    private fun waitShutterIdle() {
        TestSupport.waitUntil(8000) { withActivity { it.findViewById<View>(R.id.shutter).alpha } == 1f }
    }

    /** 直接向快门派发触摸事件，避开 Espresso 的按压动作以便精确控制按住时长。 */
    private fun touch(action: Int, outOfShutter: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val shutter = withActivity { it.findViewById<View>(R.id.shutter) }
            val now = SystemClock.uptimeMillis()
            val x = if (outOfShutter) shutter.width + 200f else 1f
            val event = MotionEvent.obtain(now, now, action, x, 1f, 0)
            shutter.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun reading(): String? = withActivity { it.findViewById<TextView>(R.id.readingText)?.text?.toString() }

    private fun <T> withActivity(block: (CaptureActivity) -> T): T {
        var result: T? = null
        scene?.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}