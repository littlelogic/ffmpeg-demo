package com.xyq.ffmpegdemo.timeline.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import kotlin.math.min

/**
 * 缩略图条带：一格宽度与时间刻度一格相同（默认 50dp），一格时间跨度与刻度同步。
 */
class VideoThumbSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val config = TimelineConfig()
    private var contentScrollX = 0
    private var viewportWidthPx = 0

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2626.toInt()
    }
    private val cellAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1717.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    var customHorizontalScrollView : CustomHorizontalScrollView? = null

    fun setOutParentView(customHorizontalScrollView_ : CustomHorizontalScrollView) {
        customHorizontalScrollView = customHorizontalScrollView_
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
        var w = config.contentWidthPx
        customHorizontalScrollView?.let {
            w += it.width
            startBlank = it.width/2
        }
        val widthSpec = if (w > 0) {
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(widthSpec, heightMeasureSpec)
    }

    var startBlank = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (config.durationSec <= 0 || config.gridCellWidthPx <= 0) return

        val cellW = config.gridCellWidthPx
        val cellCount = config.gridCellCount
        val h = height.toFloat()
        val visibleW = if (viewportWidthPx > 0) viewportWidthPx else width
        val firstIndex = (contentScrollX / cellW).toInt().coerceAtLeast(0)
        val lastIndex = ((contentScrollX + visibleW) / cellW).toInt() + 1

        for (i in firstIndex..min(lastIndex, cellCount - 1)) {
            val left = i * cellW + startBlank
            val cellDuration = min(config.gridIntervalSec, config.durationSec - config.gridCellStartSec(i))
            val right = left + (cellDuration / config.gridIntervalSec * cellW).toFloat()
            val paint = if (i and 1 == 0) cellPaint else cellAltPaint
            canvas.drawRect(left, 0f, right, h, paint)
            canvas.drawRect(left, 0f, right, h, borderPaint)
        }
    }
}
