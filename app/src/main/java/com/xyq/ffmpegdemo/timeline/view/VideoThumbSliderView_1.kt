package com.xyq.ffmpegdemo.timeline.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import com.xyq.ffmpegdemo.timeline.ThumbCell
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import com.xyq.ffmpegdemo.timeline.TimelineConstants
import kotlin.math.min

/**
 * 缩略图条带：一格宽度与时间刻度一格相同（默认 50dp），一格时间跨度与刻度同步。
 */
class VideoThumbSliderView_1 @JvmOverloads constructor(
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


    private val thumbnails = object : LruCache<Int, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }


    private val thumbnails_b = object : LruCache<Int, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

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


    fun loadThumbnail(curFrameNum: Int,curTime:Double) {

    }

    private val drawRect = RectF()

    var startBlank = 0
    val map1 : HashMap<Int, ThumbCell> = HashMap()

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
            val oriLeft = i * cellW
            val curTime = oriLeft * config.pxPerSecond
            val curFrameNum = (curTime * TimelineConstants.NOMINAL_FPS).toInt()
            val left = oriLeft + startBlank
            val cellDuration = min(config.gridIntervalSec, config.durationSec - config.gridCellStartSec(i))
            val right = left + (cellDuration / config.gridIntervalSec * cellW).toFloat()

            drawRect.set(left, 0f, right, h)


            var bitmap = thumbnails.get(curFrameNum)
            if (bitmap == null) {
                bitmap = thumbnails_b.get(curFrameNum)
                if (bitmap == null) {
                    loadThumbnail(curFrameNum,curTime.toDouble())
                } else {
                    if (bitmap.isRecycled) {
                        loadThumbnail(curFrameNum,curTime.toDouble())
                        thumbnails_b.remove(curFrameNum)
                        val paint = if (i and 1 == 0) cellPaint else cellAltPaint
                        canvas.drawRect(drawRect, paint)
                    } else {
                        canvas.drawBitmap(bitmap, null, drawRect, bitmapPaint)
                    }
                }
            } else {
                if (bitmap.isRecycled) {
                    loadThumbnail(curFrameNum,curTime.toDouble())
                    thumbnails.remove(curFrameNum)
                    val paint = if (i and 1 == 0) cellPaint else cellAltPaint
                    canvas.drawRect(drawRect, paint)
                } else {
                    canvas.drawBitmap(bitmap, null, drawRect, bitmapPaint)
                }
            }

            if (false) {
                //            val right = left + cellW
                val paint = if (i and 1 == 0) cellPaint else cellAltPaint
                canvas.drawRect(left, 0f, right, h, paint)
                canvas.drawRect(left, 0f, right, h, borderPaint)
            }
        }
    }
}
