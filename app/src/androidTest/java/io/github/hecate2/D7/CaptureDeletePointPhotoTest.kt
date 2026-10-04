package io.github.hecate2.D7

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.capture.CaptureActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * 采集页删除键的两组行为：长按删点时连带的相册删除，以及短按时只给一句提示。
 *
 * 用户提的：删掉一个拍错的点，那张照片却还留在相册里。删点不可撤销，留一张再也对不上
 * 任何点的照片（占着相册、占着空间）比删掉它更糟。
 *
 * 另一半是提示的去处：原先「长按删右侧最近点」常驻在删除键下方占一整行，现在撤掉了，
 * 改成短按时弹一次。所以短按必须仍是「什么也不删」，且不能被当成删除。
 *
 * 三条用例把「注意没有照片的情况」拆开盯：
 * 1. 有照片：被删点的那张必须从 MediaStore 消失，而没被删的那张必须原样还在——
 *    只断言「少了一张」的话，把两张一起删掉也会通过；
 * 2. 无照片（「不拍照」模式录的点、批量编辑新增的点）：长按删除照常完成，不抛异常；
 * 3. 照片已经不在相册里（用户手动清过）：删除照常完成，不抛异常——`ContentResolver.delete`
 *    对「本来就没有」返回 0 而不是抛，但不能保证别的实现也这么客气。
 *
 * 删除目标是「十字线右侧、离当前方位最近」的那一个点，而模拟器的合成姿态方位角是固定的
 * 未知值，所以两条用例都放两个相距 180 度的点：无论当前朝向如何，总有且只有一个落在
 * 顺时针 (0°, 180°) 里。到底删了哪个不去猜，删完读一遍仓库反推。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CaptureDeletePointPhotoTest {

    // 相机权限不先授予的话，采集页会弹系统权限框把 Activity 切到 PAUSED，Espresso 直接报错
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<CaptureActivity>? = null
    private var groupId: String = ""
    private val insertedPhotos = mutableListOf<Uri>()

    private companion object {
        /** 锁定的瞄准仰角（度）：正数，与其它采集页用例同一套摆姿态的口子。 */
        const val TEST_ELEVATION_DEG = 20.0

        /** 相册里插一张图，挂在点上的那个 URI。 */
        fun insertPhoto(context: Context, group: String, into: MutableList<Uri>): Uri =
            TestSupport.insertTestImage(context.contentResolver, group).also { into.add(it) }
    }

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
        groupId = repository.createGroup(
            "删点照片组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
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
        TestSupport.deleteAllGroups(repository)
        // 用例没删干净的照片在这里收尾，别把模拟器/真机的相册塞满
        insertedPhotos.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        insertedPhotos.clear()
    }

    @Test
    fun longPressDelete_removesThePointsPhotoToo() {
        val first = insertPhoto(context, "删点照片组", insertedPhotos)
        val second = insertPhoto(context, "删点照片组", insertedPhotos)
        seedPoints(photoA = first.toString(), photoB = second.toString())

        val before = repository.get(groupId)!!.external
        assertEquals(2, before.size)

        longPressDelete()

        val after = TestSupport.waitFor {
            repository.get(groupId)?.external?.takeIf { it.size == 1 }
        }
        val survivorAz = after.single().az
        val deleted = before.first { it.az != survivorAz }
        val survived = before.first { it.az == survivorAz }

        assertFalse(
            "被删的点（方位 ${deleted.az}）的照片还留在相册里",
            TestSupport.mediaExists(context.contentResolver, Uri.parse(deleted.photoUri!!)),
        )
        assertTrue(
            "没被删的点（方位 ${survived.az}）的照片不该被连累",
            TestSupport.mediaExists(context.contentResolver, Uri.parse(survived.photoUri!!)),
        )
    }

    @Test
    fun longPressDelete_withoutPhotoStillDeletesThePoint() {
        seedPoints(photoA = null, photoB = null)

        longPressDelete()

        val after = TestSupport.waitFor {
            repository.get(groupId)?.external?.takeIf { it.size == 1 }
        }
        assertEquals("无照片的点也必须能删掉", 1, after.size)
        assertTrue("剩下的那个点本来就没有照片", after.single().photoUri == null)
    }

    /**
     * 一半有照片、一半没有：删掉的那个是谁由当前朝向决定，不去猜，
     * 但**相册的最终状态必须与「删掉的那一个」一致**。
     *
     * 这条盯的是另一类错：删的是一个无照片的点，却把别处那张照片顺手删了
     * （比如误用「组里的照片」而不是「这个点的照片」）。上一条两个点都没照片，
     * 逮不到这种错。
     */
    @Test
    fun longPressDelete_onlyTouchesTheDeletedPointsPhoto() {
        val photo = insertPhoto(context, "删点照片组", insertedPhotos)
        seedPoints(photoA = null, photoB = photo.toString())

        val before = repository.get(groupId)!!.external
        longPressDelete()

        val after = TestSupport.waitFor {
            repository.get(groupId)?.external?.takeIf { it.size == 1 }
        }
        val survivorAz = after.single().az
        val deleted = before.first { it.az != survivorAz }
        val inAlbum = TestSupport.mediaExists(context.contentResolver, photo)
        if (deleted.photoUri == null) {
            assertTrue("删的是无照片的点（方位 ${deleted.az}），别处那张照片不该被连累", inAlbum)
        } else {
            assertFalse("删的是有照片的点（方位 ${deleted.az}），那张照片必须跟着走", inAlbum)
        }
    }

    @Test
    fun longPressDelete_photoAlreadyGoneFromAlbumStillDeletesThePoint() {
        val first = insertPhoto(context, "删点照片组", insertedPhotos)
        val second = insertPhoto(context, "删点照片组", insertedPhotos)
        seedPoints(photoA = first.toString(), photoB = second.toString())
        // 模拟用户在相册里手动把图清了：URI 还在点上，条目已经没了
        listOf(first, second).forEach { context.contentResolver.delete(it, null, null) }

        longPressDelete()

        val after = TestSupport.waitFor {
            repository.get(groupId)?.external?.takeIf { it.size == 1 }
        }
        assertEquals("照片早已不在相册时，删点照常完成", 1, after.size)
    }

    /**
     * 短按删除键：一个点都不删，只弹一次提示。
     *
     * 常驻提示撤掉后，短按就成了「没有提示的无效操作」，没人知道该长按——所以这次提示
     * 是功能的一部分，不是装饰。
     */
    @Test
    fun shortPressOnlyShowsTheHoldHint() {
        seedPoints(photoA = null, photoB = null)

        shortPressDelete()

        assertEquals("短按不该删掉任何点", 2, repository.get(groupId)!!.external.size)
        assertEquals("短按要弹一次「长按删右侧最近点」", 1, hintCount())
    }

    /** 长按是正常删除，不该再弹短按那句提示。 */
    @Test
    fun longPressDeletesWithoutShowingTheHoldHint() {
        seedPoints(photoA = null, photoB = null)

        longPressDelete()

        TestSupport.waitFor { repository.get(groupId)?.external?.takeIf { it.size == 1 } }
        assertEquals("长按删完不该再弹短按提示", 0, hintCount())
    }

    /**
     * 删除键下方那句常驻提示已经不在界面上，高度还给取景器。
     *
     * 按文案找而不是按 id 找：[R.id.deleteHint] 已随那一行一起删掉，而这条断言要防的
     * 正是「有人把它加回来」。
     */
    @Test
    fun thePermanentHintTextIsNotOnScreen() {
        onView(withText(R.string.capture_delete_hint)).check(doesNotExist())
    }

    /** 两个相距 180 度的点：任何朝向下都有且只有一个能成为删除目标。 */
    private fun seedPoints(photoA: String?, photoB: String?) {
        repository.appendPoint(
            groupId,
            Region.EXTERNAL,
            PointRecord(az = 100.0, el = 20.0, photoUri = photoA, takenAt = 1),
            viaHorizon = false,
        )
        repository.appendPoint(
            groupId,
            Region.EXTERNAL,
            PointRecord(az = 280.0, el = 20.0, photoUri = photoB, takenAt = 2),
            viaHorizon = false,
        )
    }

    /**
     * 长按删除键：按下 → 在测试线程上等过阈值 → 抬手。
     *
     * 等待必须在 `runOnMainSync` 之外：删除是 `postDelayed` 到主线程队列上的，
     * 主线程被 runOnMainSync 占着就永远轮不到它。
     */
    private fun longPressDelete() {
        touch(MotionEvent.ACTION_DOWN)
        Thread.sleep(800)
        touch(MotionEvent.ACTION_UP)
    }

    /** 短按：按下即抬手，不越过长按阈值。 */
    private fun shortPressDelete() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
    }

    private fun hintCount(): Int = withActivity { it.deleteHintCountForTest() }

    private fun touch(action: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val button = withActivity { it.findViewById<View>(R.id.deleteButton) }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, 1f, 1f, 0)
            button.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /**
     * 锁定/解除瞄准仰角（null 恢复真实读数）。
     *
     * 反复重试直到生效：[io.github.hecate2.D7.sensor.OrientationSensor] 在 onStart 才创建，
     * 若在它之前调用，override 会落在 null 上被静默丢弃。
     */
    private fun aimAt(elevationDeg: Double?) {
        TestSupport.waitUntil(8000) {
            var applied = false
            scene?.onActivity { applied = it.setAimElevationForTest(elevationDeg) }
            applied
        }
    }

    private fun reading(): String? =
        withActivity { it.findViewById<TextView>(R.id.readingText)?.text?.toString() }

    private fun <T> withActivity(block: (CaptureActivity) -> T): T {
        var result: T? = null
        scene?.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
