package io.github.hecate2.D7.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R

/**
 * 全年曲线：横轴一年 365 天，纵轴每日直射时长（小时），
 * 选中日期处画月灰圆点，底部标 1 月 / 6 月 / 12 月刻度。
 */
class YearCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values: List<Double>? = null
    private var markerIndex: Int = -1

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.stroke)
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.paper)
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.moon)
        style = Paint.Style.FILL
    }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.smoke)
        textSize = sp(11f)
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics,
    )

    private val labelH = 16f * resources.displayMetrics.density
    private val topPad = 6f * resources.displayMetrics.density
    private val markerR = 3.5f * resources.displayMetrics.density
    private val path = Path()

    /** 月份中点在天序中的近似下标（非闰年），用于 1/6/12 月刻度。 */
    private val monthTicks = listOf(15 to 1, 165 to 6, 349 to 12)

    fun submit(minutesPerDay: List<Double>?, selectedDayIndex: Int) {
        values = minutesPerDay
        markerIndex = selectedDayIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val chartBottom = h - labelH
        val chartHeight = chartBottom - topPad
        if (w <= 0f || chartHeight <= 0f) return

        canvas.drawLine(0f, topPad, 0f, chartBottom, axisPaint)
        canvas.drawLine(0f, chartBottom, w, chartBottom, axisPaint)

        val data = values
        if (data == null || data.isEmpty()) {
            val baseline = chartBottom - 4f * resources.displayMetrics.density
            canvas.drawText(context.getString(R.string.result_computing), 6f, baseline, textPaint)
            drawMonthTicks(canvas, 365, w, h)
            return
        }

        val maxMinutes = data.max().coerceAtLeast(60.0)
        val n = data.size
        path.reset()
        for (i in 0 until n) {
            val px = if (n == 1) 0f else i.toFloat() / (n - 1) * w
            val py = chartBottom - (data[i] / maxMinutes * chartHeight).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, curvePaint)

        if (markerIndex in 0 until n) {
            val px = if (n == 1) 0f else markerIndex.toFloat() / (n - 1) * w
            val py = chartBottom - (data[markerIndex] / maxMinutes * chartHeight).toFloat()
            val cx = px.coerceIn(markerR, w - markerR)
            canvas.drawCircle(cx, py, markerR, markerPaint)
            canvas.drawCircle(cx, py, markerR, markerRingPaint)
        }

        textPaint.textSize = sp(10f)
        canvas.drawText(context.getString(R.string.result_year_tip), 4f, topPad + textPaint.textSize, textPaint)
        textPaint.textSize = sp(11f)
        drawMonthTicks(canvas, n, w, h)
    }

    private fun drawMonthTicks(canvas: Canvas, n: Int, w: Float, h: Float) {
        val baseline = h - 3f * resources.displayMetrics.density
        textPaint.textAlign = Paint.Align.CENTER
        for ((dayIndex, month) in monthTicks) {
            val index = dayIndex.coerceAtMost(n - 1)
            val px = if (n <= 1) 0f else index.toFloat() / (n - 1) * w
            val cx = px.coerceIn(16f, w - 16f)
            canvas.drawText(context.getString(R.string.result_year_month, month), cx, baseline, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }
}