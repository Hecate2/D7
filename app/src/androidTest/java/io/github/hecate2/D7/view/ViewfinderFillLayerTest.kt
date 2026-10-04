package io.github.hecate2.D7.view

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.sensor.Pose
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 取景器楼体遮挡区（天际线以下的淡白填充）的回归护栏。
 *
 * 与 [ViewfinderGroundLayerTest] 同法用像素判定：只开填充层，把叠加层按已知姿态渲染到
 * 透明位图上，认 alpha 等于 [FILL_ALPHA] 的像素。十字线是不透明的白线、且恰好画在画面
 * 正中，它的**抗锯齿边缘**会留下一批部分透明的像素（其中就有恰好 alpha=64 的），
 * 所以另渲染一张 pose=null 的位图（此时 onDraw 只画十字线）当遮罩，凡被十字线碰过的
 * 像素一律不算填充。
 *
 * 覆盖几条曾经真实存在的缺陷：
 * 1. 整段被丢弃——可见半视角（约 65 度）略大于已拍跨度时，扫描循环结束时 started
 *    已被未覆盖采样复位，`if (!started) return` 把已经画出的那一段一起扔掉，填充全没了。
 * 2. 半圆限制——只扫 90..270（北半球），拍到主半圆之外的点不画，与求值用的全周
 *    [io.github.hecate2.D7.core.Skyline] 口径不一致。
 * 3. 闭合方向——下推点写死屏幕正下方，手机横过来时填充方向与地面层不一致；
 *    正对天顶时更会拉出一条横穿天空的弦。
 */
@RunWith(AndroidJUnit4::class)
class ViewfinderFillLayerTest {

    companion object {
        /** 填充层是 alpha=64 的淡白半透明填充。 */
        const val FILL_ALPHA = 64

        const val W = 400
        const val H = 800

        /** 只留填充层，排除地面层与参考弧对像素统计的干扰。 */
        val ONLY_FILL = LineVisibility(
            summer = false, equinox = false, winter = false, today = false,
            segments = false, horizon = false, vertical = false, fill = true, ground = false,
        )
    }

    private fun pose(forward: FloatArray, right: FloatArray, up: FloatArray) =
        Pose(forward, right, up, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

    private fun render(p: Pose?, points: List<PointRecord>): Bitmap {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ViewfinderOverlayView(ctx)
        view.show = ONLY_FILL
        view.layout(0, 0, W, H)
        view.pose = p
        view.points = points
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 只有十字线的位图：用来扣掉十字线本体与其抗锯齿边缘。pose 为 null 时 onDraw 提前返回。 */
    private val mask: Bitmap by lazy { render(null, emptyList()) }

    private fun alpha(bmp: Bitmap, x: Int, y: Int): Int = bmp.getPixel(x, y) ushr 24

    private fun fillCount(scene: Bitmap, mask: Bitmap, yFrom: Int, yTo: Int): Int =
        (yFrom until yTo).sumOf { y ->
            (0 until W).count { x ->
                alpha(scene, x, y) == FILL_ALPHA && alpha(mask, x, y) == 0
            }
        }

    /** 后摄正对北方/南方且屏幕水平。 */
    private fun level(facingNorth: Boolean) = if (facingNorth) {
        pose(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    } else {
        pose(floatArrayOf(0f, -1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    }

    /** 后摄朝 [facingNorth] 方向并低头 [deg] 度。 */
    private fun pitchedDown(facingNorth: Boolean, deg: Double) = run {
        val a = Math.toRadians(deg)
        val n = if (facingNorth) 1f else -1f
        val e = if (facingNorth) 1f else -1f
        pose(
            floatArrayOf(0f, n * Math.cos(a).toFloat(), -Math.sin(a).toFloat()),
            floatArrayOf(e, 0f, 0f),
            floatArrayOf(0f, n * Math.sin(a).toFloat(), Math.cos(a).toFloat()),
        )
    }

    /** 正对天顶：世界下方投影退化，[Projector.screenDown] 返回零向量。 */
    private fun straightUp() =
        pose(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))

    private fun shot(vararg azEl: Pair<Double, Double>) =
        azEl.mapIndexed { i, (az, el) -> PointRecord(az = az, el = el, takenAt = i.toLong()) }

    @Test
    fun coveredSpanWiderThanViewFillsBelowSkyline() {
        // 已拍 90..270 远宽于可见半视角，画面内应整片都是遮挡区，天际线以上干净
        val scene = render(level(facingNorth = false), shot(90.0 to 30.0, 270.0 to 30.0))
        assertTrue("天际线以上不该有填充", fillCount(scene, mask, 0, H / 5) == 0)
        assertTrue("天际线以下整片都应是遮挡区", fillCount(scene, mask, H * 4 / 5, H) > (W * H / 5) * 0.9f)
    }

    @Test
    fun spanEndingInsideViewStillFills() {
        // 已拍跨度 150..200，正南方可见半视角是 147.5..212.5：可见段末尾落在未拍方位上。
        // 旧实现此时 started 已被复位并 return，整段填充全部消失；正确结果应是左侧约 3/4 宽。
        val scene = render(level(facingNorth = false), shot(150.0 to 30.0, 200.0 to 30.0))
        assertTrue(
            "可见段末尾越过已拍跨度时，挡住的那段仍必须画出来",
            fillCount(scene, mask, H * 4 / 5, H) > (W * H / 5) * 0.6f,
        )
    }

    @Test
    fun fillsOutsideMainHalf() {
        // 300 与 60 两个点都在北半球主半圆（90..270）之外，且连线跨过方位 0：
        // 求值按全周算，填充也必须画，且可见半视角几乎整个落在已拍跨度内
        val scene = render(level(facingNorth = true), shot(300.0 to 25.0, 60.0 to 25.0))
        assertTrue("天际线以上不该有填充", fillCount(scene, mask, 0, H / 5) == 0)
        assertTrue(
            "主半圆之外拍到的遮挡区也必须画",
            fillCount(scene, mask, H * 4 / 5, H) > (W * H / 5) * 0.9f,
        )
    }

    @Test
    fun pitchedDownFillStopsAtRaisedSkyline() {
        // 低头 5 度、天际线仰角 10 度：天际线在画面上半部，填充只到天际线为止
        val scene = render(pitchedDown(facingNorth = false, 5.0), shot(90.0 to 10.0, 270.0 to 10.0))
        assertTrue("天际线以上不该有填充", fillCount(scene, mask, 0, H / 6) == 0)
        assertTrue("天际线以下应整片填满", fillCount(scene, mask, H * 5 / 6, H) > (W * H / 6) * 0.9f)
    }

    @Test
    fun unshotAzimuthsDrawNothing() {
        // 只拍正北 300..60，却正对南方看：可见方位全未拍，不该有任何填充
        val scene = render(level(facingNorth = false), shot(300.0 to 25.0, 60.0 to 25.0))
        assertTrue("未拍方位不该凭空出现填充", fillCount(scene, mask, 0, H) == 0)
    }

    @Test
    fun lookingStraightUpDrawsNoFill() {
        // 正对天顶时「下方」在屏幕里不存在，高仰角天际线若照直闭合会拉出横穿天空的弦。
        // 取仰角 70 度：仍在视锥内（距天顶 20 度），是最容易糊成一大片的那种姿态。
        val scene = render(straightUp(), shot(90.0 to 70.0, 270.0 to 70.0))
        assertTrue("正对天顶时不该有填充", fillCount(scene, mask, 0, H) == 0)
    }

    @Test
    fun singlePointHasNoSkyline() {
        // 只有一个点不构成线段，天际线为空
        val scene = render(level(facingNorth = false), shot(180.0 to 30.0))
        assertTrue("单点不构成天际线，不该有填充", fillCount(scene, mask, 0, H) == 0)
    }
}