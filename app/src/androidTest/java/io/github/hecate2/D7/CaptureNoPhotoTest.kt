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
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 「不拍照」模式的仪器测试：按钮位置在 +180° 左侧、开关持久化、开启后快门只落
 * 无图角度点、关掉后恢复正常拍照。
 *
 * 模拟器后摄能出图（[CaptureShutterGestureTest] 已在真机/模拟器上验证过拍照链路），
 * 但「关掉开关后仍存照片」这一条只断言「拍照链路被走到」，不拿真实 Uri 去反查相册——
 * 相机尚未绑定完时 capture 会返回 null，那不是这条用例要守的东西。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CaptureNoPhotoTest {

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
        // 设置是全局持久的，测试前先归零，避免上一条用例的开关状态渗进来
        Settings(context).noPhoto = false
        groupId = repository.createGroup(
            "不拍照测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        ).id
        scene = ActivityScenario.launch(
            Intent(context, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, groupId),
        )
        TestSupport.waitUntil(8000) { !reading().isNullOrBlank() }
        aimAt(TEST_ELEVATION_DEG)
    }

    @After
    fun tearDown() {
        scene?.close()
        Settings(context).noPhoto = false
        TestSupport.deleteAllGroups(repository)
    }

    /**
     * 位置是用户明确指定的：屏幕右上方、`+180°` 药丸左边。
     * 两个药丸必须同一行且都在页面上半部（读数行），不能被挤到按钮行去。
     */
    @Test
    fun noPhotoPillSitsLeftOfPlus180OnTheSameRow() {
        val noPhoto = withActivity { it.findViewById<View>(R.id.noPhoto) }
        val plus180 = withActivity { it.findViewById<View>(R.id.plus180) }
        val (noPhotoX, noPhotoY, noPhotoH) = loc(noPhoto)
        val (plus180X, plus180Y, _) = loc(plus180)
        assertTrue("「不拍照」应在 +180° 左边", noPhotoX < plus180X)
        assertEquals("两个药丸应同行", plus180Y.toFloat(), noPhotoY.toFloat(), 1f)
        assertTrue(
            "两个药丸应在取景器上方（页面上半部）",
            noPhotoY < loc(withActivity { it.findViewById<View>(R.id.viewfinder) }).second,
        )

        // 不该把读数挤没：读数仍占满剩余宽度
        val reading = withActivity { it.findViewById<View>(R.id.readingText) }
        assertTrue("读数行被压得没了宽度", reading.width > withActivity { it.resources.displayMetrics.widthPixels / 4 })
        assertTrue("药丸高度不应超过读数行", noPhotoH > 0)
    }

    @Test
    fun defaultIsOffAndToggleFlipsState() {
        assertTrue("「不拍照」默认应为关", !withActivity { it.noPhotoForTest() })
        click(R.id.noPhoto)
        assertTrue("点一下应切到开", withActivity { it.noPhotoForTest() })
        click(R.id.noPhoto)
        assertTrue("再点一下应切回关", !withActivity { it.noPhotoForTest() })
    }

    @Test
    fun togglePersistsAcrossRecreate() {
        click(R.id.noPhoto)
        assertTrue("切换后应为开", withActivity { it.noPhotoForTest() })
        scene?.recreate()
        assertTrue("重建后应记住「不拍照」", withActivity { it.noPhotoForTest() })
    }

    /** 开启后按快门：记下角度点，但 photoUri 必须为空（只有轮廓、没有照片）。 */
    @Test
    fun shutterInNoPhotoModeRecordsPointWithoutPhoto() {
        click(R.id.noPhoto)
        tapShutter()
        val points = awaitPoints(1)
        assertEquals("应记下 1 个点", 1, points.size)
        assertNull("「不拍照」时不应带照片", points[0].photoUri)
        assertTrue("仍应记下拍摄时间", points[0].takenAt > 0L)
        assertEquals("「不拍照」时根本不该发起拍照", 0, photoAttempts())
    }

    /** 长按（经地平线推断段）在「不拍照」下同样只落角度，且 gapAfter 语义不变。 */
    @Test
    fun longPressInNoPhotoModeStillRecordsHorizonGap() {
        click(R.id.noPhoto)
        tapShutter()
        TestSupport.waitFor { repository.get(groupId)?.external?.takeIf { it.size >= 1 } }
        touch(MotionEvent.ACTION_DOWN)
        TestSupport.waitFor { repository.get(groupId)?.external?.takeIf { it.size >= 2 } }
        touch(MotionEvent.ACTION_UP)
        val points = repository.get(groupId)!!.external
        assertEquals("长按应记下第 2 个点", 2, points.size)
        assertTrue("长按后前一点与新点应改为经地平线推断段", points[0].gapAfter)
        assertTrue(points.all { it.photoUri == null })
    }

    /**
 * 关掉开关后快门回到「拍照 + 记点」，别让上一次开着的状态粘住。
 *
 * 断言的是「拍照链路被走到」（发起次数 +1）而不是「真的拿到照片 Uri」：相机尚未绑定
 * 或拍摄失败时 Uri 本来就允许为空，那是另一条链路的事，不应该让这条用例变成相机体检。
 */
    @Test
    fun turningItOffRestoresPhotoCapture() {
        TestSupport.waitUntil(15000) { cameraReady() }
        click(R.id.noPhoto)
        tapShutter()
        awaitPoints(1)
        assertEquals("「不拍照」时不该发起拍照", 0, photoAttempts())

        click(R.id.noPhoto)
        assertTrue("应已切回拍照", !withActivity { it.noPhotoForTest() })
        tapShutter()
        val points = awaitPoints(2)
        assertEquals("应记下第 2 个点", 2, points.size)
        assertEquals("切回拍照后应重新发起拍照", 1, photoAttempts())
    }

    /** 天花板分区同样受开关控制，不该只在外部区生效。 */
    @Test
    fun noPhotoAppliesToCeilingRegionToo() {
        click(R.id.noPhoto)
        click(R.id.chipCeiling)
        tapShutter()
        val ceiling = awaitPoints(1, Region.CEILING)
        assertEquals("天花板区应记下 1 个点", 1, ceiling.size)
        assertNull(ceiling[0].photoUri)
    }

    // ---------- 工具 ----------

    private fun loc(view: View): Triple<Int, Int, Int> = withActivity {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        Triple(loc[0], loc[1], view.height)
    }

    private fun click(id: Int) = withActivity { it.findViewById<View>(id).performClick() }

    private fun tapShutter() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
    }

    private fun awaitPoints(n: Int, region: Region = Region.EXTERNAL): List<PointRecord> {
        TestSupport.waitFor { repository.get(groupId)?.regionList(region)?.takeIf { it.size >= n } }
        return repository.get(groupId)!!.regionList(region)
    }

    private fun aimAt(elevationDeg: Double?) {
        TestSupport.waitUntil(8000) {
            var applied = false
            scene?.onActivity { applied = it.setAimElevationForTest(elevationDeg) }
            applied
        }
    }

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

    private fun reading(): String? =
        withActivity { it.findViewById<TextView>(R.id.readingText)?.text?.toString() }

    private fun photoAttempts(): Int = withActivity { it.photoAttemptsForTest() }
    private fun cameraReady(): Boolean = withActivity { it.cameraReadyForTest() }

    private fun <T> withActivity(block: (CaptureActivity) -> T): T {
        var result: T? = null
        scene?.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}