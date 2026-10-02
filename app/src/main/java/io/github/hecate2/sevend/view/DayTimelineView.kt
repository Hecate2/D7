package io.github.hecate2.sevend.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import io.github.hecate2.sevend.R
import io.github.hecate2.sevend.util.Format
import kotlin.math.roundToInt

/**
 * 当天时间线：一条从日出到日落的横条，白 = 直射、炭灰 = 被挡，下方标注起止时刻。
 * 数据来自 core 的 DailySunlight（分钟序号自当地 0 时起）。
 */
class DayTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var sunrise: Int? = null
    private var sunset: Int? = null
    private var intervals: List<IntRange> = emptyList()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.stroke)
        style = Paint.Style.FILL
    }
    private val directPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.paper)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.smoke)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics,
        )
    }

    private val barHeight = 14f * resources.displayMetrics.density
    private val radius = barHeight / 2f
    private val labelGap = 6f * resources.displayMetrics.density
    private val clipPath = Path()
    private val rect = RectF()

    fun submit(sunriseMinute: Int?, sunsetMinute: Int?, visibleIntervals: List<IntRange>) {
        sunrise = sunriseMinute
        sunset = sunsetMinute
        intervals = visibleIntervals
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        if (w <= 0f) return

        rect.set(0f, 0f, w, barHeight)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val from = sunrise
        val to = sunset
        if (from != null && to != null && to > from) {
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val saved = canvas.save()
            canvas.clipPath(clipPath)
            for (interval in intervals) {
                val start = interval.first.coerceIn(from, to)
                val end = (interval.last + 1).coerceIn(from, to)
                if (end <= start) continue
                canvas.drawRect(x(start, from, to, w), 0f, x(end, from, to, w), barHeight, directPaint)
            }
            canvas.restoreToCount(saved)

            val baseline = height - 4f * resources.displayMetrics.density
            canvas.drawText(Format.clockMinute(from), 0f, baseline, textPaint)
            canvas.drawText(
                Format.clockMinute(to), w, baseline,
                textPaint.apply { textAlign = Paint.Align.RIGHT },
            )
            textPaint.textAlign = Paint.Align.CENTER
            if (intervals.size <= 2) {
                for (interval in intervals) {
                    for (minute in intArrayOf(interval.first, interval.last + 1)) {
                        if (minute <= from || minute >= to) continue
                        val cx = x(minute, from, to, w).coerceIn(22f, w - 22f)
                        canvas.drawText(Format.clockMinute(minute), cx, baseline, textPaint)
                    }
                }
            }
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun x(minute: Int, from: Int, to: Int, width: Float): Float =
        (minute - from).toFloat() / (to - from) * width

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (barHeight + labelGap + textPaint.textSize).roundToInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }
}