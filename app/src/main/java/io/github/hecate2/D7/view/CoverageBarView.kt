package io.github.hecate2.D7.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R
import io.github.hecate2.D7.core.Skyline
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.util.toShotPoints
import kotlin.math.roundToInt

/**
 * 主方向半圆的方位覆盖条：北半球横轴自左向右为 90..270 度，南半球镜像为 270..90 度（经 0）。
 * 只统计外部建筑区的天际线覆盖，已达到的区间画白色，未达到画炭灰。
 */
class CoverageBarView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 所在纬度：南半球镜像横轴。 */
    var latDeg: Double = 39.0
        set(value) {
            field = value
            invalidate()
        }

    private var bins: BooleanArray = BooleanArray(0)

    private val density = resources.displayMetrics.density

    private val trackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.card2)
    }
    private val coveredFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.paper)
    }
    private val trackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.stroke)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = ContextCompat.getColor(context, R.color.smoke)
    }
    private val labelMidPaint = Paint(labelPaint).apply {
        color = ContextCompat.getColor(context, R.color.paper)
    }

    private val trackRect = RectF()
    private val clipPath = Path()

    /** 用外部建筑区的点列刷新覆盖。 */
    fun submitExternal(external: List<PointRecord>) {
        bins = if (external.size < 2) {
            BooleanArray(0)
        } else {
            Skyline(external.toShotPoints()).coverage()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val trackHeight = 12f * density
        val radius = trackHeight / 2f
        trackRect.set(0f, 0f, width.toFloat(), trackHeight)

        canvas.drawRoundRect(trackRect, radius, radius, trackFill)

        // 已达区间：按横向像素对应的方位角查覆盖采样
        if (bins.isNotEmpty()) {
            clipPath.reset()
            clipPath.addRoundRect(trackRect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            val steps = width
            var runStart = -1
            for (x in 0..steps) {
                val covered = x < steps && isCoveredAt(x.toFloat() / steps)
                if (covered && runStart < 0) {
                    runStart = x
                } else if (!covered && runStart >= 0) {
                    canvas.drawRect(runStart.toFloat(), 0f, x.toFloat(), trackHeight, coveredFill)
                    runStart = -1
                }
            }
            canvas.restore()
        }

        canvas.drawRoundRect(trackRect, radius, radius, trackStroke)

        // 刻度文字：两端与中点
        val baseline = height - 3f * density
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(startLabel, 0f, baseline, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(midLabel, width / 2f, baseline, labelMidPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(endLabel, width.toFloat(), baseline, labelPaint)
    }

    private val startLabel: String get() = if (latDeg >= 0) "90°" else "270°"
    private val midLabel: String get() = if (latDeg >= 0) "180°" else "0°"
    private val endLabel: String get() = if (latDeg >= 0) "270°" else "90°"

    /** 横轴比例（0 最左、1 最右）对应的方位角是否已被天际线覆盖。 */
    private fun isCoveredAt(fraction: Float): Boolean {
        val az = if (latDeg >= 0) {
            90.0 + fraction * 180.0
        } else {
            val mirrored = 270.0 + fraction * 180.0
            if (mirrored >= 360.0) mirrored - 360.0 else mirrored
        }
        return bins[az.roundToInt().coerceIn(0, 359)]
    }
}