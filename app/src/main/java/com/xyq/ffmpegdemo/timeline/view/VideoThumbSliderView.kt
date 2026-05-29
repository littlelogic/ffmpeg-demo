package com.xyq.ffmpegdemo.timeline.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import kotlin.math.min

/**
 * 缩略图条带：一格宽度与时间刻度一格相同（默认 50dp），一格时间跨度与刻度同步。
 *
 * 使用方:
 *  1. 调用 [setTimelineConfig] 同步时间轴配置
 *  2. 配置变更（缩放/换视频）时先调用 [clearThumbnails]
 *  3. 按 cell index 异步调用 [setThumbnail] 注入帧图
 *
 * 每格按 [TimelineConfig.gridCellSampleTimeSec] 取中间时间点作为缩略图时间戳。
 */
class VideoThumbSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_CACHE_SIZE = 100 * 1024 * 1024 // 100MB
    }

    private val config = TimelineConfig()
    private var contentScrollX = 0
    private var viewportWidthPx = 0

    /** LRU 缓存，key = cell index，按 Bitmap 字节数计量，默认 100MB */
    private val thumbnails = object : LruCache<Int, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    /** 复用 RectF 避免 onDraw 频繁分配 */
    private val drawRect = RectF()

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
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    var customHorizontalScrollView: CustomHorizontalScrollView? = null

    fun setOutParentView(customHorizontalScrollView_: CustomHorizontalScrollView) {
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

    /**
     * 注入指定 cell 的缩略图。
     * 应在主线程调用（或通过 post 切换到主线程）。
     */
    fun setThumbnail(index: Int, bitmap: Bitmap) {
        thumbnails.put(index, bitmap)
        invalidate()
    }

    /**
     * 清空所有缩略图缓存（时间轴配置变更或切换视频时调用）。
     */
    fun clearThumbnails() {
        thumbnails.evictAll()
        invalidate()
    }

    /**
     * 返回指定 cell 应抽取的视频时间戳（秒），供外部异步加载缩略图使用。
     * 取 cell 时间区间的中点。
     */
    fun cellSampleTimeSec(index: Int): Double = config.gridCellSampleTimeSec(index)

    /** 当前 grid cell 总数 */
    val cellCount: Int get() = config.gridCellCount

    /** 当前可见区域的 cell index 范围 */
    fun getVisibleCellRange(): IntRange {
        if (config.durationSec <= 0 || config.gridCellWidthPx <= 0) return IntRange.EMPTY
        val cellW = config.gridCellWidthPx
        val visibleW = if (viewportWidthPx > 0) viewportWidthPx else width
        val firstIndex = (contentScrollX / cellW).toInt().coerceAtLeast(0)
        val lastIndex = ((contentScrollX + visibleW) / cellW).toInt() + 1
        return firstIndex..kotlin.math.min(lastIndex, config.gridCellCount - 1)
    }

    /** 返回当前可见区域中尚未加载缩略图的 cell 索引列表 */
    fun getMissingVisibleCells(): List<Int> {
        val range = getVisibleCellRange()
        if (range.isEmpty()) return emptyList()
        return range.filter { thumbnails.get(it) == null }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = config.contentWidthPx
        customHorizontalScrollView?.let {
            w += it.width
            startBlank = it.width / 2
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

            drawRect.set(left, 0f, right, h)

            val bitmap = thumbnails.get(i)
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, drawRect, bitmapPaint)
            } else {
                val paint = if (i and 1 == 0) cellPaint else cellAltPaint
                canvas.drawRect(drawRect, paint)
            }

            canvas.drawRect(drawRect, borderPaint)
        }
    }
}
