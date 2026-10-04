package io.github.hecate2.D7.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R
import kotlin.math.ceil

/**
 * 瞄准准星：中心留空四段短线 + 圆环 + 中心月灰点。
 *
 * 取景器（[ViewfinderOverlayView]）与结果页点开的大图（[AimCrosshairView]）共用这一份画法。
 * 大图正中那个点必须就是拍照时对的那个点，两处各写一遍粗细与留白，迟早会走偏到不像同一个准星。
 */
class AimCrosshair(context: Context) {

    private val density = context.resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.paper)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.moon)
    }

    /**
     * 画布的最小边长（像素）：四段短线的最外端再加上半个线宽，否则端点会被裁掉一点。
     * 尺寸由画法自己报，用它的 View 就不必另抄一遍 4 + 12。
     *
     * 取偶数是为了让中心落在整像素上：奇数边长会把中心推到半个像素，四段短线的抗锯齿
     * 就会左右不对称，准星看上去是歪的。
     */
    val sizePx: Int get() {
        val raw = ceil(2f * EXTENT_DP * density + STROKE_DP * density).toInt()
        return raw + (raw and 1)
    }

    /** 以 [cx] [cy] 为中心画准星。 */
    fun draw(canvas: Canvas, cx: Float, cy: Float) {
        val gap = GAP_DP * density
        val len = LEN_DP * density
        canvas.drawLine(cx - gap - len, cy, cx - gap, cy, stroke)
        canvas.drawLine(cx + gap, cy, cx + gap + len, cy, stroke)
        canvas.drawLine(cx, cy - gap - len, cx, cy - gap, stroke)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + len, stroke)
        canvas.drawCircle(cx, cy, RING_DP * density, stroke)
        canvas.drawCircle(cx, cy, DOT_DP * density, dot)
    }

    private companion object {
        /** 中心留空的半宽。 */
        const val GAP_DP = 4f

        /** 每段短线的长度。 */
        const val LEN_DP = 12f

        /** 圆环半径。 */
        const val RING_DP = 12f

        /** 中心点半径。 */
        const val DOT_DP = 2.5f

        const val STROKE_DP = 1.5f

        /** 准星外接半径，即 [sizePx] 的一半所依据的那一段。 */
        const val EXTENT_DP = GAP_DP + LEN_DP
    }
}

/**
 * 结果页点开大图时叠在照片正中的准星。
 *
 * 画在正中的理由：拍照记录的方位与仰角取的是后摄视轴（[io.github.hecate2.D7.sensor.Pose] 的
 * forward），针孔投影下它对应的就是画面正中那一点，取景器的十字线也一直画在 width/2、height/2。
 * 大图按 fitCenter 缩放并居中，照片中心与屏幕上这个 View 的中心重合，所以居中画即落在瞄准点上。
 * 若哪天照片改成非居中的裁剪，这里必须跟着改。
 */
class AimCrosshairView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val crosshair = AimCrosshair(context)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = crosshair.sizePx
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        crosshair.draw(canvas, width / 2f, height / 2f)
    }
}
