package com.xyq.ffmpegdemo.timeline.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.xyq.ffmpegdemo.R
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import com.xyq.ffmpegdemo.timeline.TimelineConstants
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 一秒内帧细分随 zoom（secondSpanPx / cellWidth）变化。
 * 时间刻度的开始的位置和缩略图是对应的关系
 * 时间刻度用，TimeScaleView，这个类名称，
 * 视频默认是30帧每秒，实际视频fps> 30fps,自己按照时间抽帧，实际视频fps< 30fps,自己按照时间加帧，
 * 默认是一秒一格，可以放大到1/30秒一格，最小可以缩到10秒一格，
 * 手指可以放大或者缩小时间刻度，
 *
 * 时间刻度的一格，对应的下面的缩略图的一格，最大可以对应10秒，最小可以对应1帧的时间，上来默认对应1秒的时间，也就是1/30秒，手指可以放大或者缩小时间刻度，
 * 时间刻度的一格，的宽度，先设置为50dp，后面可以调整，
 * 时间总的开始和结束的位置需要时间的标注，一各最多需要一个时间标注，至少也需要一个时间标注。
 * 整个时间轴上，只绘制秒，和帧，在秒和帧的下面，绘制一个竖线，
 * 一秒的时间在view上的跨度1/10 * 一格的宽度 到 30 * 一格的宽度，默认是一格的宽度。
 * 当一秒的跨度 >= 2 * 一格的宽度,秒中间显示15f的文字，
 * 当一秒的跨度 >= 3 * 一格的宽度,秒中间显示10f，20f的文字，等分一秒的空间
 * 当一秒的跨度 >= 5 * 一格的宽度,秒中间显示的文字 6f,12f,18f,24f,，等分一秒的空间
 * 当一秒的跨度 >= 6 * 一格的宽度,秒中间显示的等分值为30/6，显示一次累加等分一秒的空间
 * 当一秒的跨度 >= 10 * 一格的宽度,秒中间显示的等分值为30/10，显示一次累加等分一秒的空间
 * 当一秒的跨度 >= 15 * 一格的宽度,秒中间显示的等分值为30/15，显示一次累加等分一秒的空间
 * 当一秒的跨度 == 30 * 一格的宽度,秒中间显示的等分值为1，显示一次累加等分一秒的空间
 *
 * 当一秒的跨度 < 2/2 * 一格的宽度,不显示帧数据，显示的单位为2秒，就是2的倍数才显示，
 * 当一秒的跨度 < 2/3 * 一格的宽度,不显示帧数据，显示的单位为3秒，就是3的倍数才显示，
 * 当一秒的跨度 < 2/4 * 一格的宽度,不显示帧数据，显示的单位为4秒，就是4的倍数才显示，
 * 当一秒的跨度 < 2/5 * 一格的宽度,不显示帧数据，显示的单位为5秒，就是5的倍数才显示，
 * 当一秒的跨度 < 2/6 * 一格的宽度,不显示帧数据，显示的单位为6秒，就是6的倍数才显示，
 * 当一秒的跨度 < 2/10 * 一格的宽度,不显示帧数据，显示的单位为10秒，就是10的倍数才显示，
 */
class TimeScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val config = TimelineConfig()
    private var contentScrollX = 0
    private var viewportWidthPx = 0

    private val density = resources.displayMetrics.density
    private val baselineOffsetPx = 1.5f * density
    private val minSecondLabelSpacingPx = 48f * density
    private val minFrameLabelSpacingPx = 28f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_scale_bg)
        style = Paint.Style.FILL
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_baseline)
        strokeWidth = 1f
    }
    private val secondTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_tick_major)
        strokeWidth = 1f
    }
    private val frameTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_tick_medium)
        strokeWidth = 1f
    }
    private val secondTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_label)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
        )
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val frameTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.timeline_label)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics
        )
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    fun setTimelineConfig(timeline: TimelineConfig) {
        config.durationSec = timeline.durationSec
        config.majorTickSpacingPx = timeline.majorTickSpacingPx
        config.pxPerSecond = timeline.pxPerSecond
        requestLayout()
        invalidate()
    }

    fun setContentScrollX(scrollX: Int) {
        val sx = scrollX.coerceAtLeast(0)
        if (contentScrollX != sx) {
            contentScrollX = sx
            invalidate()
        }
    }

    fun setViewportWidthPx(width: Int) {
        if (viewportWidthPx != width) {
            viewportWidthPx = width
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = config.contentWidthPx
        val widthSpec = if (w > 0) {
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(widthSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (config.durationSec <= 0 || config.secondSpanPx <= 0) return

        val h = height.toFloat()
        val viewW = width
        val visibleW = if (viewportWidthPx > 0) viewportWidthPx else viewW
        val baselineY = h - baselineOffsetPx
        val cellW = config.gridCellWidthPx
        val secSpanPx = config.secondSpanPx
        val secSpanCells = secSpanPx / cellW

        canvas.drawRect(0f, 0f, viewW.toFloat(), h, bgPaint)
        canvas.drawLine(0f, baselineY, viewW.toFloat(), baselineY, baselinePaint)

        val t0 = config.pxToTimeSec(contentScrollX.toFloat())
        val t1 = config.pxToTimeSec((contentScrollX + visibleW).toFloat())
        val duration = config.durationSec

        val secLabelStep = computeSecondLabelStep(secSpanPx, secSpanCells)
        val frameMarks = pickFrameMarks(secSpanCells)

        val firstSec = floor(t0).toInt().coerceAtLeast(0)
        val lastSec = ceil(t1).toInt() + 1

        for (sec in firstSec..lastSec) {
            if (sec > duration) break
            val secTime = sec.toDouble().coerceAtMost(duration)
            val x = config.timeSecToPx(secTime)
            if (x < -80f || x > viewW + 80f) continue

            val showSecondLabel = shouldShowSecondLabel(sec, secLabelStep, duration)
            if (showSecondLabel) {
                drawSecondMark(canvas, x, baselineY, h, formatSecondLabel(secTime))
            }

            if (secTime >= duration) continue
            val secEnd = (sec + 1).toDouble().coerceAtMost(duration)
            if (secEnd <= secTime) continue

            drawFrameMarksInSecond(canvas, sec, secEnd, baselineY, h, viewW, frameMarks)
        }

        if (abs(duration - floor(duration)) > 1e-6) {
            val endX = config.timeSecToPx(duration)
            if (endX in -80f..viewW + 80f) {
                drawSecondMark(canvas, endX, baselineY, h, formatSecondLabel(duration))
            }
        }
    }

    /** 秒标注：起止必标，中间按 step 降采样 */
    private fun shouldShowSecondLabel(sec: Int, step: Int, duration: Double): Boolean {
        if (sec == 0) return true
        if (abs(sec - floor(duration)) < 1e-6 || abs(sec - ceil(duration)) < 1e-6) return true
        return sec % step == 0
    }

    /**
     * 缩小视图：1 秒跨度低于阈值时，按 N 秒的倍数标注，且不显示帧。
     * 阈值 = 2/N × 一格宽度。
     */
    private fun coarseSecondLabelStep(secSpanCells: Float): Int? {
        if (secSpanCells >= 2f / 2f) return null
        return when {
            secSpanCells < 2f / 10f -> 10
            secSpanCells < 2f / 6f -> 6
            secSpanCells < 2f / 5f -> 5
            secSpanCells < 2f / 4f -> 4
            secSpanCells < 2f / 3f -> 3
            secSpanCells < 2f / 2f -> 2
            else -> null
        }
    }

    private fun computeSecondLabelStep(secSpanPx: Float, secSpanCells: Float): Int {
        coarseSecondLabelStep(secSpanCells)?.let { return it }
        if (secSpanPx <= 0) return 1
        var step = 1
        while (step * secSpanPx < minSecondLabelSpacingPx) {
            step++
        }
        return step
    }

    /**
     * 根据 1 秒占格宽倍数，取最高档帧标注（互斥，取最细一档）。
     * 1 秒跨度 < 1 格宽时不显示帧。
     */
    private fun pickFrameMarks(secSpanCells: Float): List<Int> {
        if (secSpanCells < 2f / 2f) return emptyList()
        return when {
            secSpanCells >= TimelineConstants.MAX_SEC_SPAN_MULTIPLIER - 0.01f ->
                frameStepMarks(30 / 30)
            secSpanCells >= 15f -> frameStepMarks(30 / 15)
            secSpanCells >= 10f -> frameStepMarks(30 / 10)
            secSpanCells >= 6f -> frameStepMarks(30 / 6)
            secSpanCells >= 5f -> frameStepMarks(30 / 5)
            secSpanCells >= 3f -> listOf(10, 20)
            secSpanCells >= 2f -> listOf(15)
            else -> emptyList()
        }
    }

    private fun frameStepMarks(step: Int): List<Int> {
        if (step <= 0) return emptyList()
        val marks = mutableListOf<Int>()
        var f = step
        while (f < TimelineConstants.FRAMES_PER_SEC) {
            marks.add(f)
            f += step
        }
        return marks
    }

    private fun drawFrameMarksInSecond(
        canvas: Canvas,
        sec: Int,
        secEnd: Double,
        baselineY: Float,
        h: Float,
        viewW: Int,
        frameMarks: List<Int>,
    ) {
        if (frameMarks.isEmpty()) return
        var lastLabelX = Float.NEGATIVE_INFINITY
        for (frame in frameMarks) {
            if (frame <= 0 || frame >= TimelineConstants.FRAMES_PER_SEC) continue
            val timeSec = sec + frame / TimelineConstants.NOMINAL_FPS
            if (timeSec >= secEnd - 1e-9) continue
            val x = config.timeSecToPx(timeSec)
            if (x < -40f || x > viewW + 40f) continue

            val label = "${frame}f"
            val textW = frameTextPaint.measureText(label)
            if (x - textW / 2f - lastLabelX < minFrameLabelSpacingPx && lastLabelX > Float.NEGATIVE_INFINITY) {
                drawTickLine(canvas, x, baselineY, h * 0.68f, frameTickPaint)
                continue
            }
            drawFrameMark(canvas, x, baselineY, h, label)
            lastLabelX = x + textW / 2f
        }
    }

    private fun drawSecondMark(canvas: Canvas, x: Float, baselineY: Float, h: Float, label: String) {
        val fm = secondTextPaint.fontMetrics
        val textY = (baselineY - 5f * density) - fm.descent
        val textW = secondTextPaint.measureText(label)
        val drawX = (x - textW / 2f).coerceAtLeast(0f)
        canvas.drawText(label, drawX, textY, secondTextPaint)
        val lineTop = textY + fm.descent + 2f * density
        drawTickLine(canvas, x, baselineY, lineTop, secondTickPaint)
    }

    private fun drawFrameMark(canvas: Canvas, x: Float, baselineY: Float, h: Float, label: String) {
        val fm = frameTextPaint.fontMetrics
        val textY = (baselineY - 4f * density) - fm.descent
        val textW = frameTextPaint.measureText(label)
        val drawX = (x - textW / 2f).coerceAtLeast(0f)
        canvas.drawText(label, drawX, textY, frameTextPaint)
        val lineTop = textY + fm.descent + 1.5f * density
        drawTickLine(canvas, x, baselineY, lineTop, frameTickPaint)
    }

    private fun drawTickLine(canvas: Canvas, x: Float, baselineY: Float, topY: Float, paint: Paint) {
        canvas.drawLine(x, topY, x, baselineY, paint)
    }

    private fun formatSecondLabel(timeSec: Double): String {
        val clamped = timeSec.coerceAtLeast(0.0)
        val totalSec = clamped.toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        val frac = clamped - totalSec
        if (frac > 1e-6) {
            val frame = (frac * TimelineConstants.NOMINAL_FPS).toInt()
                .coerceIn(0, TimelineConstants.FRAMES_PER_SEC - 1)
            return "%02d:%02d:%02d".format(min, sec, frame)
        }
        return "%02d:%02d".format(min, sec)
    }
}
