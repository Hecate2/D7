package io.github.hecate2.D7

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.R
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
 * 采集页闪光灯键与对焦键的仪器测试：
 * 短按立刻对焦一次、长按开关自动对焦、按下后滑出不触发；
 * 以及两个键夹在快门两侧、且不引入额外界面行。
 *
 * 闪光灯是否真的点亮依赖硬件（模拟器后摄通常无闪光灯），故这里只断言可见性与
 * 布局位置，不对灯的通电做断言。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CaptureFocusTorchTest {

    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<CaptureActivity>? = null
    private var groupId: String = ""

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        groupId = repository.createGroup(
            "对焦测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
        scene = ActivityScenario.launch(
            Intent(context, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, groupId),
        )
        TestSupport.waitUntil(8000) { !reading().isNullOrBlank() }
        TestSupport.waitUntil(8000) { focusButtonVisible() }
        // 必须等相机真的绑上：闪光灯能力、手电筒显隐都要绑定后才拿得到
        TestSupport.waitUntil(15000) { cameraReady() }
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
    }

    /**
     * 两个键必须与快门同一行，且顺序是「手电筒 · 快门 · 对焦」——
     * 这是用户明确要求的相对位置，任何一方被挪走或换列都会挂。
     */
    @Test
    fun torchAndFocusFlankTheShutterOnTheSameRow() {
        val (torch, shutter, focus) = positions()
        assertTrue("手电筒键应在快门左侧", torch < shutter)
        assertTrue("对焦键应在快门右侧", focus > shutter)
        // 同一行：垂直中心偏差不超过 1px
        assertEquals("手电筒键应与快门同行", shutterCenterY(), torchCenterY(), 1f)
        assertEquals("对焦键应与快门同行", shutterCenterY(), focusCenterY(), 1f)
        // 不新增界面行：两个键都落在 buttonRow 的高度范围内
        val row = withActivity { it.findViewById<View>(R.id.buttonRow) }
        val rowTop = topOnScreen(row)
        val rowBottom = rowTop + row.height
        listOf(R.id.torchButton, R.id.shutter, R.id.focusButton).forEach { id ->
            val v = withActivity { it.findViewById<View>(id) }
            val top = topOnScreen(v)
            assertTrue("id=$id 不应落在 buttonRow 之外（会占掉一行）", top >= rowTop - 1 && top <= rowBottom)
        }
    }

    /** 短按对焦键：立即对焦一次。 */
    @Test
    fun shortPressFocusesOnce() {
        val before = focusActions()
        touch(R.id.focusButton, MotionEvent.ACTION_DOWN)
        touch(R.id.focusButton, MotionEvent.ACTION_UP)
        TestSupport.waitFor(4000) { focusActions().takeIf { it > before } }
        assertEquals("短按应恰好触发一次对焦", before + 1, focusActions())
    }

    /** 长按对焦键：只切换自动对焦，不应顺带触发一次手动对焦。 */
    @Test
    fun longPressTogglesAutoFocusWithoutFocusing() {
        assertTrue("自动对焦默认应为开", autoFocusEnabled())
        val before = focusActions()

        touch(R.id.focusButton, MotionEvent.ACTION_DOWN)
        TestSupport.waitUntil(5000) { !autoFocusEnabled() }
        touch(R.id.focusButton, MotionEvent.ACTION_UP)

        assertEquals("长按不应顺带触发手动对焦", before, focusActions())

        // 再长按一次应能开回来
        touch(R.id.focusButton, MotionEvent.ACTION_DOWN)
        TestSupport.waitUntil(5000) { autoFocusEnabled() }
        touch(R.id.focusButton, MotionEvent.ACTION_UP)
    }

    /** 按住后滑出按钮：两个键都不应留下动作。 */
    @Test
    fun slideOffFocusButtonDoesNothing() {
        val before = focusActions()
        val afBefore = autoFocusEnabled()
        touch(R.id.focusButton, MotionEvent.ACTION_DOWN)
        Thread.sleep(100)
        touch(R.id.focusButton, MotionEvent.ACTION_MOVE, outOfView = true)
        Thread.sleep(700)
        touch(R.id.focusButton, MotionEvent.ACTION_UP)
        assertEquals("滑出后不应触发手动对焦", before, focusActions())
        assertEquals("滑出后不应切换自动对焦", afBefore, autoFocusEnabled())
    }

    /**
     * 开/关两种状态必须颜色不同，否则「当前是开还是关」在户外根本看不出来。
     *
     * 断言顺序要紧：默认态必须在长按之前先抓下来，否则拿到的是已经被关掉的那一档。
     */
    @Test
    fun toggleStatesUseDistinctColors() {
        assertTrue("自动对焦默认应为开", autoFocusEnabled())
        assertEquals("自动对焦默认应为琥珀色", amber(), tintOf(R.id.focusButton))

        touch(R.id.focusButton, MotionEvent.ACTION_DOWN)
        TestSupport.waitUntil(5000) { !autoFocusEnabled() }
        touch(R.id.focusButton, MotionEvent.ACTION_UP)
        assertEquals("关掉自动对焦后应为月灰色", moon(), tintOf(R.id.focusButton))

        touch(R.id.focusButton, MotionEvent.ACTION_DOWN)
        TestSupport.waitUntil(5000) { autoFocusEnabled() }
        touch(R.id.focusButton, MotionEvent.ACTION_UP)
        assertEquals("开回来后应恢复琥珀色", amber(), tintOf(R.id.focusButton))
    }

    /**
     * 手电筒位次的显隐必须跟本机闪光灯能力一致。
     *
     * 模拟器后摄与真机都报有闪光灯，所以靠 setFlashUnitOverrideForTest 把两侧分支都走一遍：
     * 去掉 `applyCameraCapabilities` 里那行显隐赋值，「无闪光灯应隐藏」就会失败。
     */
    @Test
    fun torchButtonVisibilityFollowsFlashUnit() {
        overrideFlashUnit(true)
        assertEquals("强制有闪光灯后应可见", true, withActivity { it.torchVisibleForTest() })
        assertTrue(
            "有闪光灯时手电筒键应可点击",
            withActivity { it.findViewById<View>(R.id.torchButton).isClickable },
        )

        overrideFlashUnit(false)
        assertEquals("强制无闪光灯后应隐藏", false, withActivity { it.torchVisibleForTest() })

        // 恢复真实值后应与本机真实能力一致
        overrideFlashUnit(null)
        assertEquals(
            "恢复真实值后应与本机能力一致",
            withActivity { it.hasFlashUnitForTest() },
            withActivity { it.torchVisibleForTest() },
        )
    }

    private fun overrideFlashUnit(hasFlash: Boolean?) {
        withActivity { it.setFlashUnitOverrideForTest(hasFlash) }
    }

    // ---------- 工具 ----------

    private data class Pos(val left: Int, val top: Int, val w: Int, val h: Int)

    private fun pos(id: Int): Pos = withActivity {
        val v = it.findViewById<View>(id)
        Pos(v.left, v.top, v.width, v.height)
    }

    /**
     * 取控件在 window 里的横坐标。
     *
     * 不能用 `buttonRow.left + child.left`：两个图标键各自包了一层 FrameLayout，
     * 它们的 left 是相对那一层的，逐层累加容易漏。这里直接用
     * `getLocationInWindow`，嵌套层数无关。
     */
    private fun positions(): Triple<Int, Int, Int> = Triple(
        screenX(R.id.torchButton),
        screenX(R.id.shutter),
        screenX(R.id.focusButton),
    )

    private fun screenX(id: Int): Int = withActivity { a ->
        val loc = IntArray(2)
        a.findViewById<View>(id).getLocationInWindow(loc)
        loc[0]
    }

    private fun centerY(id: Int): Float = withActivity { a ->
        val v = a.findViewById<View>(id)
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        loc[1] + v.height / 2f
    }

    private fun shutterCenterY(): Float = centerY(R.id.shutter)
    private fun torchCenterY(): Float = centerY(R.id.torchButton)
    private fun focusCenterY(): Float = centerY(R.id.focusButton)

    private fun topOnScreen(view: View): Int = withActivity {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        loc[1]
    }

    private fun tintOf(id: Int): Int = withActivity {
        it.findViewById<ImageView>(id).imageTintList?.defaultColor ?: -1
    }

    private fun amber(): Int = ContextCompat.getColor(context, R.color.press)
    private fun moon(): Int = ContextCompat.getColor(context, R.color.moon)

    private fun focusActions(): Int = withActivity { it.focusActionsForTest() }
    private fun autoFocusEnabled(): Boolean = withActivity { it.autoFocusEnabledForTest() }
    private fun focusButtonVisible(): Boolean = withActivity { it.findViewById<View>(R.id.focusButton).isShown }
    private fun cameraReady(): Boolean = withActivity { it.cameraReadyForTest() }

    private fun reading(): String? =
        withActivity { it.findViewById<TextView>(R.id.readingText)?.text?.toString() }

    /** 直接向目标控件派发触摸事件，便于精确控制按住时长与滑出。 */
    private fun touch(id: Int, action: Int, outOfView: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = withActivity { it.findViewById<View>(id) }
            val now = SystemClock.uptimeMillis()
            val x = if (outOfView) view.width + 200f else 1f
            val event = MotionEvent.obtain(now, now, action, x, 1f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun <T> withActivity(block: (CaptureActivity) -> T): T {
        var result: T? = null
        scene?.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
