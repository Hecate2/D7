package io.github.hecate2.D7.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R
import io.github.hecate2.D7.core.Angles
import io.github.hecate2.D7.core.SkylineShape
import io.github.hecate2.D7.core.Solar
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.sensor.Pose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** 取景器显示线的显隐设置（与 SharedPreferences 的序列化一致）。 */
data class LineVisibility(
    val summer: Boolean = true,
    val equinox: Boolean = true,
    val winter: Boolean = true,
    val today: Boolean = true,
    val segments: Boolean = true,
    val horizon: Boolean = false,
    val vertical: Boolean = false,
)

/**
 * 取景器叠加层：太阳参考弧、地平线与铅垂线、拍摄点与连线、十字线。
 *
 * 投影为针孔模型：世界方向（东、北、天）点乘屏幕右/上/后摄视轴得相机坐标，
 * 屏幕坐标 = 中心 + (x/z, -y/z) × 焦距像素；z ≤ 0.01 视为在相机背后，剔除并断线。
 * 参考弧按赤纬采样小时角生成，只由纬度与日期决定，手机姿态决定显示哪一段。
 */
class ViewfinderOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 显示哪些线。 */
    var show: LineVisibility = LineVisibility()
        set(value) {
            field = value
            invalidate()
        }

    /** 所在纬度（南半球参考弧镜像）。 */
    var latDeg: Double = 39.0
        set(value) {
            field = value
            arcCache.clear()
            invalidate()
        }

    /** 当前姿态（瞬时向量）；null 时只画十字线。 */
    var pose: Pose? = null

    /** 当前分区的拍摄点，按拍摄顺序。 */
    var points: List<PointRecord> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 十字线方位角（+180° 药丸后的显示值），供地平线与铅垂线参考。 */
    var aimAzDeg: Double = 180.0

    /** 焦距像素提供者（相机就绪后由 CameraController 计算，失败时用半视场角假设）。 */
    var focalPxProvider: ((Int, Int) -> Float)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val colorPaper = ContextCompat.getColor(context, R.color.paper)
    private val colorMoon = ContextCompat.getColor(context, R.color.moon)
    private val colorSmoke = ContextCompat.getColor(context, R.color.smoke)

    private fun arcPaint(color: Int, dashed: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }

    private val summerArc = arcPaint(ContextCompat.getColor(context, R.color.summer), true)
    private val equinoxArc = arcPaint(ContextCompat.getColor(context, R.color.equinox), true)
    private val winterArc = arcPaint(ContextCompat.getColor(context, R.color.winter), true)
    private val todayArc = arcPaint(colorPaper, false)

    private val solidLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorPaper
    }
    private val moonLine = Paint(solidLine).apply {
        color = colorMoon
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
    }
    private val smokeLine = Paint(solidLine).apply {
        strokeWidth = dp(1.2f)
        color = colorSmoke
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val crossPaint = Paint(solidLine)
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPaper
    }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.BLACK
    }
    private val moonDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorMoon
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(8f)
        color = colorSmoke
        textAlign = Paint.Align.CENTER
    }

    private val path = Path()
    private val pt = FloatArray(2)

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        drawCrosshair(canvas)
        val p = pose ?: return

        val focal = focalPxProvider?.invoke(width, height)
            ?: ((width / 2f) / tan(32.5 * PI / 180.0).toFloat())
        val projector = Projector(p, width / 2f, height / 2f, focal)

        val summerDecl = if (latDeg >= 0) 23.44 else -23.44
        if (show.summer) drawSunArc(canvas, projector, summerDecl, summerArc)
        if (show.equinox) drawSunArc(canvas, projector, 0.0, equinoxArc)
        if (show.winter) drawSunArc(canvas, projector, -summerDecl, winterArc)
        if (show.today) {
            drawSunArc(canvas, projector, Solar.declinationDeg(System.currentTimeMillis()), todayArc)
        }
        if (show.horizon) drawPolyline(canvas, projector, smokeLine, horizonSamples())
        if (show.vertical) drawPolyline(canvas, projector, smokeLine, verticalSamples())
        if (show.segments) {
            drawSegments(canvas, projector)
            drawPoints(canvas, projector)
        }
    }

    /** 十字线：中心留空四段短线 + 取景圆环 + 中心月灰点。 */
    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val gap = dp(4f)
        val len = dp(12f)
        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, crossPaint)
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, crossPaint)
        canvas.drawCircle(cx, cy, dp(12f), crossPaint)
        canvas.drawCircle(cx, cy, dp(2.5f), moonDot)
    }

    /** 参考弧采样缓存：只依赖纬度与赤纬，姿态变化仅重投影，不重算天文位置。 */
    private val arcCache = HashMap<Int, List<Pair<Double, Double>>>()

    /**
     * 给定赤纬的全天太阳位置采样（按小时角 2 度步长）。
     * 用几何高度角（不含大气折射），与天际线求值的判定基准一致。
     */
    private fun arcSamples(declDeg: Double): List<Pair<Double, Double>> =
        arcCache.getOrPut((declDeg * 100.0).roundToInt()) {
            val out = ArrayList<Pair<Double, Double>>(181)
            var ha = -180.0
            while (ha <= 180.0) {
                val sun = Solar.positionFrom(latDeg, declDeg, ha, refraction = false)
                out.add(sun.azimuthDeg to sun.elevationDeg)
                ha += 2.0
            }
            out
        }

    /** 给定赤纬的全天参考弧（按小时角 2 度步长采样）。 */
    private fun drawSunArc(canvas: Canvas, projector: Projector, declDeg: Double, paint: Paint) {
        path.reset()
        var started = false
        for ((az, el) in arcSamples(declDeg)) {
            if (el < Solar.SUNRISE_THRESHOLD_DEG) {
                started = false
            } else if (projector.project(az, el, pt)) {
                if (started) path.lineTo(pt[0], pt[1]) else {
                    path.moveTo(pt[0], pt[1])
                    started = true
                }
            } else {
                started = false
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun horizonSamples(): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var offset = -75.0
        while (offset <= 75.0) {
            out.add(Angles.normalize360(aimAzDeg + offset) to 0.0)
            offset += 2.5
        }
        return out
    }

    private fun verticalSamples(): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var el = 0.0
        while (el <= 90.0) {
            out.add(aimAzDeg to el)
            el += 2.5
        }
        return out
    }

    private fun drawPolyline(
        canvas: Canvas,
        projector: Projector,
        paint: Paint,
        samples: List<Pair<Double, Double>>,
    ) {
        path.reset()
        var started = false
        for ((az, el) in samples) {
            if (projector.project(az, el, pt)) {
                if (started) path.lineTo(pt[0], pt[1]) else {
                    path.moveTo(pt[0], pt[1])
                    started = true
                }
            } else {
                started = false
            }
        }
        canvas.drawPath(path, paint)
    }

    /** 拍摄点连线：直接连线画白色实线，经地平线段拆三段画月灰虚线。 */
    private fun drawSegments(canvas: Canvas, projector: Projector) {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a.gapAfter) {
                drawPolyline(canvas, projector, moonLine, SkylineShape.viaHorizon(a.az, a.el, b.az, b.el))
            } else if (projector.project(a.az, a.el, pt)) {
                val x0 = pt[0]
                val y0 = pt[1]
                if (projector.project(b.az, b.el, pt)) {
                    canvas.drawLine(x0, y0, pt[0], pt[1], solidLine)
                }
            }
        }
    }

    /** 拍摄点：白色圆点 + 分区内序号。 */
    private fun drawPoints(canvas: Canvas, projector: Projector) {
        points.forEachIndexed { index, point ->
            if (projector.project(point.az, point.el, pt)) {
                canvas.drawCircle(pt[0], pt[1], dp(3.5f), dotFill)
                canvas.drawCircle(pt[0], pt[1], dp(3.5f), dotRing)
                canvas.drawText((index + 1).toString(), pt[0], pt[1] - dp(6f), labelPaint)
            }
        }
    }

    /** 世界方向到屏幕像素的针孔投影。 */
    private class Projector(
        pose: Pose,
        private val cx: Float,
        private val cy: Float,
        private val focal: Float,
    ) {
        private val right = pose.right
        private val up = pose.up
        private val forward = pose.forward

        fun project(azDeg: Double, elDeg: Double, out: FloatArray): Boolean {
            val az = azDeg * PI / 180.0
            val el = elDeg * PI / 180.0
            val cosEl = cos(el)
            val e = (sin(az) * cosEl).toFloat()
            val n = (cos(az) * cosEl).toFloat()
            val u = sin(el).toFloat()
            val x = e * right[0] + n * right[1] + u * right[2]
            val y = e * up[0] + n * up[1] + u * up[2]
            val z = e * forward[0] + n * forward[1] + u * forward[2]
            if (z <= 0.01f) return false
            out[0] = cx + x / z * focal
            out[1] = cy - y / z * focal
            return true
        }
    }
}