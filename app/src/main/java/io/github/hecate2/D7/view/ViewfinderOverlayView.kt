package io.github.hecate2.D7.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
            gapPathCache.clear()
            invalidate()
        }

    /** 十字线方位角（+180° 药丸后的显示值），供地平线与铅垂线参考。 */
    var aimAzDeg: Double = 180.0
        set(value) {
            field = value
            guideCacheAz = Double.NaN
        }

    /**
     * 地平线/铅垂线的缓存方位角。这两条参考线的采样只是固定的角度偏移集合，
     * 每帧真正变的只有 aimAzDeg 一个数，故整段重建缓存即可——姿态每秒刷新数十次，
     * 逐帧重建装箱列表会在采集时持续制造垃圾。
     */
    private var guideCacheAz = Double.NaN
    private var horizonAzimuths: DoubleArray = DoubleArray(0)
    private var verticalAzimuths: DoubleArray = DoubleArray(0)

    /** 焦距像素提供者（相机就绪后由 CameraController 计算，失败时用半视场角假设）。 */
    var focalPxProvider: ((Int, Int) -> Float)? = null

    /**
     * 按住快门的进度 0..1（长按阈值前逐渐填满）；NaN 表示不画。
     * 准星外再套一圈进度，让「按住」这段等待看得见；只在接受到动画帧时才重绘。
     */
    var pressProgress: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

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
    private val pressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = colorSmoke
        alpha = 88
    }
    private val pressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.press)
    }
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
    private val arcRect = RectF()

    /** 复用同一个投影器：姿态每帧变，但对象本身无需重建。 */
    private val projector = Projector()

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        drawCrosshair(canvas)
        drawPressProgress(canvas)
        val p = pose ?: return

        val focal = focalPxProvider?.invoke(width, height)
            ?: ((width / 2f) / tan(32.5 * PI / 180.0).toFloat())
        projector.reset(p, width / 2f, height / 2f, focal)

        val summerDecl = if (latDeg >= 0) 23.44 else -23.44
        if (show.summer) drawSunArc(canvas, projector, summerDecl, summerArc)
        if (show.equinox) drawSunArc(canvas, projector, 0.0, equinoxArc)
        if (show.winter) drawSunArc(canvas, projector, -summerDecl, winterArc)
        if (show.today) {
            drawSunArc(canvas, projector, Solar.declinationDeg(System.currentTimeMillis()), todayArc)
        }
        if (show.horizon || show.vertical) {
            ensureGuideAzimuths()
            if (show.horizon) drawGuide(canvas, projector, horizonAzimuths, groundLevel)
            if (show.vertical) drawGuide(canvas, projector, verticalAzimuths, verticalElevations)
        }
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

    /** 按住进度：准星外侧的灰色底环 + 琥珀色进度弧（顺时针从正上方起画）。 */
    private fun drawPressProgress(canvas: Canvas) {
        val progress = pressProgress
        if (progress.isNaN()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(19f)
        canvas.drawCircle(cx, cy, r, pressTrackPaint)
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep <= 0f) return
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, -90f, sweep, false, pressArcPaint)
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

    /** 地平线：沿方位展开 ±75°，仰角恒为 0。 */
    private val horizonOffsets = DoubleArray(61) { -75.0 + it * 2.5 }

    /** 铅垂线：沿仰角 0..90°，方位固定为当前十字线方位。 */
    private val verticalElevations = DoubleArray(37) { it * 2.5 }

    /** 地平线各采样点的仰角，恒为 0。 */
    private val groundLevel = DoubleArray(horizonOffsets.size)

    /**
     * 按给定的方位/仰角数组画一条参考线，避免逐帧装箱新的采样列表。
     * 两数组等长，逐点投影；背后点（z ≤ 0）断开，与 [drawPolyline] 行为一致。
     */
    private fun drawGuide(
        canvas: Canvas,
        projector: Projector,
        azimuths: DoubleArray,
        elevations: DoubleArray,
    ) {
        path.reset()
        var started = false
        for (i in azimuths.indices) {
            if (projector.project(azimuths[i], elevations[i], pt)) {
                if (started) path.lineTo(pt[0], pt[1]) else {
                    path.moveTo(pt[0], pt[1])
                    started = true
                }
            } else {
                started = false
            }
        }
        canvas.drawPath(path, smokeLine)
    }

    /** 按当前 aimAzDeg 重建两条参考线的方位角序列（NaN 判定保证每帧至多重建一次）。 */
    private fun ensureGuideAzimuths() {
        if (guideCacheAz == aimAzDeg) return
        guideCacheAz = aimAzDeg
        horizonAzimuths = DoubleArray(horizonOffsets.size) { i ->
            Angles.normalize360(aimAzDeg + horizonOffsets[i])
        }
        verticalAzimuths = DoubleArray(verticalElevations.size) { aimAzDeg }
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

    /**
     * 经地平线段的采样缓存，键为段起止点位。路径形状只取决于两端点，与姿态无关，
     * 而姿态每秒刷新数十次，故按点位缓存：新增/删除拍摄点时整体作废重建。
     */
    private val gapPathCache = HashMap<GapKey, List<Pair<Double, Double>>>()

    private data class GapKey(val az0: Double, val el0: Double, val az1: Double, val el1: Double)

    /** 拍摄点连线：直接连线画白色实线，经地平线段拆三段画月灰虚线。 */
    private fun drawSegments(canvas: Canvas, projector: Projector) {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a.gapAfter) {
                val key = GapKey(a.az, a.el, b.az, b.el)
                val samples = gapPathCache.getOrPut(key) {
                    SkylineShape.viaHorizon(a.az, a.el, b.az, b.el)
                }
                drawPolyline(canvas, projector, moonLine, samples)
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

    /** 世界方向到屏幕像素的针孔投影。可复用，姿态与视口尺寸在 [reset] 时刷新。 */
    private class Projector {
        private var cx = 0f
        private var cy = 0f
        private var focal = 0f
        private var right = FloatArray(3)
        private var up = FloatArray(3)
        private var forward = FloatArray(3)

        fun reset(pose: Pose, cx: Float, cy: Float, focal: Float) {
            this.cx = cx
            this.cy = cy
            this.focal = focal
            right = pose.right
            up = pose.up
            forward = pose.forward
        }

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