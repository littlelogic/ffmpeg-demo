package com.xyq.ffmpegdemo.timeline.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.badlogic.utils.ALog
import com.xyq.ffmpegdemo.R
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import com.xyq.ffmpegdemo.timeline.TimelineConstants
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 时间刻度层，与下方缩略图条带左缘 t=0 对齐，共用 [TimelineConfig]。
 *
 * ## 度量
 * - 一格宽度固定（默认 50dp，见 [TimelineConstants.DEFAULT_MAJOR_TICK_SPACING_DP]）
 * - 捏合缩放改变「1 秒占多少像素」：cellWidth/10 ~ 30×cellWidth，默认 = cellWidth（1 格 1 秒）
 * - 对应一格时间：10s ~ 1/30s（1 帧）
 *
 * ## 绘制规则
 * - 只绘制「秒」与「帧」；有文字时，文字下方画竖线；无文字则不画线
 * - 起点、终点必标时间
 *
 * ## 放大（1 秒跨度 ≥ 2×格宽）：秒内帧标注，取满足条件的最高档
 * | 1秒跨度 | 帧标注 |
 * |---------|--------|
 * | ≥ 2×格宽 | 15f |
 * | ≥ 3×格宽 | 10f, 20f |
 * | ≥ 5×格宽 | 6f, 12f, 18f, 24f |
 * | ≥ 6×格宽 | 步长 5f |
 * | ≥ 10×格宽 | 步长 3f |
 * | ≥ 15×格宽 | 步长 2f |
 * | = 30×格宽 | 步长 1f（1f~29f） |
 *
 * ## 缩小（1 秒跨度 < 1×格宽）：不显示帧，秒标注按 N 的倍数（阈值 = 2/N×格宽）
 * 10s / 6s / 5s / 4s / 3s / 2s
 */
class TimeScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val config = TimelineConfig()

    /** 可见区域左缘在内容坐标中的 x（用于裁剪绘制范围） */
    private var contentScrollX = 0

    /** 可见时间轴区域宽度 */
    private var visibleWidthPx = 0

    /** 可见时间轴右缘（timeline 本地坐标） */
    private var visibleRightPx = 0

    private val density = resources.displayMetrics.density
    private val baselineOffsetPx = 1.5f * density
    /** 秒标注最小水平间距，正常缩放下用于降采样 */
    private val minSecondLabelSpacingPx = 48f * density
    /** 帧标注过密时只画竖线、不画文字 */
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

    /** 同步时间轴配置；宽度随 [TimelineConfig.contentWidthPx] 变化 */
    fun setTimelineConfig(timeline: TimelineConfig) {
        config.durationSec = timeline.durationSec
        config.majorTickSpacingPx = timeline.majorTickSpacingPx
        config.pxPerSecond = timeline.pxPerSecond
        requestLayout()
        invalidate()
    }

    /** 设置可见区间 [leftPx, rightPx)（timeline 本地坐标，已扣除 header/tailer 占位） */
    fun setVisibleRange(leftPx: Int, rightPx: Int) {
        val left = leftPx.coerceAtLeast(0)
        val right = rightPx.coerceAtLeast(left)
        if (contentScrollX != left || visibleRightPx != right) {
            contentScrollX = left
            visibleRightPx = right
            visibleWidthPx = right - left
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
        // 可见区间：与 ScrollView 视口 ∩ timeline 内容区（滑到尽头时右侧可能是 tailer，不满一屏）
        val visibleLeft = contentScrollX.toFloat()
        val visibleRight = when {
            visibleRightPx > 0 -> visibleRightPx.toFloat()
            visibleWidthPx > 0 -> (visibleLeft + visibleWidthPx).coerceAtMost(viewW.toFloat())
            else -> viewW.toFloat()
        }
        val visibleW = visibleRight - visibleLeft
        val baselineY = h - baselineOffsetPx
        val cellW = config.gridCellWidthPx
        val secSpanPx = config.secondSpanPx
        // 1 秒占几格宽，放大/缩小分档都基于此比值
        val secSpanCells = secSpanPx / cellW

        ALog.i("TimeScaleView-onDraw-"
                + " secSpanPx:" + secSpanPx
                + " contentScrollX:" + contentScrollX
                + " visibleRightPx:" + visibleRightPx
                + " visibleLeft:" + visibleLeft
                + " visibleRight:" + visibleRight
                + " visibleW:" + visibleW
                + " viewW:" + viewW
                + " cellW:" + cellW
        )

        canvas.drawRect(0f, 0f, viewW.toFloat(), h, bgPaint)
        canvas.drawLine(0f, baselineY, viewW.toFloat(), baselineY, baselinePaint)

        val t0 = config.pxToTimeSec(visibleLeft)
        val t1 = config.pxToTimeSec(visibleRight)
        val duration = config.durationSec

        val secLabelStep = computeSecondLabelStep(secSpanPx, secSpanCells)
        val frameMarks = pickFrameMarks(secSpanCells)

        // 向下取整
        val firstSec = floor(t0).toInt().coerceAtLeast(0)
        val lastSec = ceil(t1).toInt() + 1

        for (sec in firstSec..lastSec) {
            if (sec > duration) break
            val secTime = sec.toDouble().coerceAtMost(duration)
            val x = config.timeSecToPx(secTime)
            if (x < visibleLeft - 80f || x > visibleRight + 80f) continue

            val showSecondLabel = shouldShowSecondLabel(sec, secLabelStep, duration)
            if (showSecondLabel) {
                drawSecondMark(canvas, x, baselineY, h, formatSecondLabel(secTime))
            }
            // 无秒标注时不画竖线

            if (secTime >= duration) continue
            val secEnd = (sec + 1).toDouble().coerceAtMost(duration)
            if (secEnd <= secTime) continue

            drawFrameMarksInSecond(canvas, sec, secEnd, baselineY, h, visibleLeft, visibleRight, frameMarks)
        }

        // 终点非整秒时单独标注（如 10.5s）
        if (abs(duration - floor(duration)) > 1e-6) {
            val endX = config.timeSecToPx(duration)
            if (endX in (visibleLeft - 80f)..(visibleRight + 80f)) {
                drawSecondMark(canvas, endX, baselineY, h, formatSecondLabel(duration))
            }
        }
    }

    /** 是否显示该整秒位置的秒标注：0 与 duration 必显，其余按 [step] 倍数 */
    private fun shouldShowSecondLabel(sec: Int, step: Int, duration: Double): Boolean {
        if (sec == 0) return true
        if (abs(sec - floor(duration)) < 1e-6 || abs(sec - ceil(duration)) < 1e-6) return true
        return sec % step == 0
    }

    /**
     * 缩小视图下的秒标注步长（秒）；null 表示走正常 1 秒粒度。
     * 条件：secSpanCells < 2/N × 格宽 → 每 N 秒标注一次。
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

    /** 秒标注间隔：优先 coarse 分档，否则按像素密度降采样 */
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
     * 当前缩放下，每一秒内要标注的帧号列表（不含 0 和 30）。
     * secSpanCells < 1 时不显示帧。
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

    /** 按步长生成帧号：step, 2×step, … 直到 29 */
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

    /** 在 [sec, secEnd) 内绘制帧标注 */
    private fun drawFrameMarksInSecond(
        canvas: Canvas,
        sec: Int,
        secEnd: Double,
        baselineY: Float,
        h: Float,
        visibleLeft: Float,
        visibleRight: Float,
        frameMarks: List<Int>,
    ) {
        if (frameMarks.isEmpty()) return
        var lastLabelX = Float.NEGATIVE_INFINITY
        for (frame in frameMarks) {
            if (frame <= 0 || frame >= TimelineConstants.FRAMES_PER_SEC) continue
            val timeSec = sec + frame / TimelineConstants.NOMINAL_FPS
            if (timeSec >= secEnd - 1e-9) continue
            val x = config.timeSecToPx(timeSec)
            if (x < visibleLeft - 40f || x > visibleRight + 40f) continue

            val label = "${frame}f"
            val textW = frameTextPaint.measureText(label)
            // 帧文字过密：仅保留竖线，避免重叠
            if (x - textW / 2f - lastLabelX < minFrameLabelSpacingPx && lastLabelX > Float.NEGATIVE_INFINITY) {
                drawTickLine(canvas, x, baselineY, h * 0.68f, frameTickPaint)
                continue
            }
            drawFrameMark(canvas, x, baselineY, h, label)
            lastLabelX = x + textW / 2f
        }
    }

    /** 秒：文字 + 下方竖线 */
    private fun drawSecondMark(canvas: Canvas, x: Float, baselineY: Float, h: Float, label: String) {
        val fm = secondTextPaint.fontMetrics
        val textY = (baselineY - 5f * density) - fm.descent
        val textW = secondTextPaint.measureText(label)
        val drawX = (x - textW / 2f).coerceAtLeast(0f)
        canvas.drawText(label, drawX, textY, secondTextPaint)
        val lineTop = textY + fm.descent + 2f * density
        drawTickLine(canvas, x, baselineY, lineTop, secondTickPaint)
    }

    /** 帧：文字 + 下方竖线 */
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

    /** 整秒：MM:SS；带小数：MM:SS:帧号（30fps 名义） */
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
