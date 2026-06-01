package com.xyq.ffmpegdemo.timeline.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import com.badlogic.utils.ALog
import com.badlogic.utils.Tools
import com.xyq.ffmpegdemo.timeline.ThumbCell
import com.xyq.ffmpegdemo.timeline.TimelineConfig
import com.xyq.ffmpegdemo.timeline.TimelineConstants
import com.xyq.libffplayer.utils.FFMpegUtils
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min

/**
 * 缩略图条带：一格宽度与时间刻度一格相同（默认 50dp），一格时间跨度与刻度同步。
 */
class VideoThumbSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    class DrawBean( var curFrameNum:Int,var curTime: Double, var left: Float){
        fun setData(curFrameNum_:Int, curTime_: Double,  left_: Float){
            curFrameNum = curFrameNum_
            curTime = curTime_
            left = left_
        }
    }

    companion object {
        private const val DEFAULT_CACHE_SIZE = 100 * 1024 * 1024 // 100MB
    }

    private val config = TimelineConfig()
    private var contentScrollX = 0
    private var viewportWidthPx = 0


    private val thumbnails = object : LruCache<Int, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        /*override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }*/
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

    val cellWidth = Tools.dip2px(Tools.getApplication(),TimelineConstants.DEFAULT_MAJOR_TICK_SPACING_DP)
    val cellHeight = Tools.dip2px(Tools.getApplication(),50f)
    val thumbCellList:ArrayList<ThumbCell> = ArrayList()
    var curDrawMap : HashMap<Int, ThumbCell> = HashMap()
    var tmpDrawMap : HashMap<Int, ThumbCell> = HashMap()
    val tmpDrawBean:ArrayList<DrawBean> = ArrayList()

    var TotalShowNum = 5

    init {
        Tools.getScreenSize(Tools.getApplication()).let {
            val totalWidthPx = minOf(it[0],it[1])
            val num = totalWidthPx / cellWidth + 3
            TotalShowNum = num
            ALog.e("-260531p1q-VideoThumbSliderView-init "
                    +" num:"+num
            )
            for (i in 1..num) {
                val id = -(i)
                val cell = ThumbCell(cellWidth.toFloat(),cellHeight.toFloat(),id)
                thumbCellList.add(cell)
                curDrawMap[id] = cell
                tmpDrawBean.add(DrawBean(0,0.0,0f))
            }
        }
    }

    var  ptrOfVideoThumb = 0L

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

    private val mSliderExecutor = Executors.newSingleThreadExecutor()
    val getThumbTaskList : LinkedBlockingQueue<ThumbCell> = LinkedBlockingQueue()
    val mHandler: Handler  = Handler(Looper.getMainLooper())


    fun asyncLoadThumbnail(curFrameNum: Int,curTime: Double,thumbCell:ThumbCell) {
        freeThumbnail(thumbCell)

        thumbCell.setTimeId(curFrameNum,curTime)
        getThumbTaskList.add(thumbCell)
        ALog.i("-260531p1q-VideoThumbSliderView-asyncLoadThumbnail-01 "
                + " curTime:"+curTime
                + " curFrameNum:"+curFrameNum
        )

        val future = mSliderExecutor.submit {

            val cell:ThumbCell = getThumbTaskList.remove()
            val bmp = FFMpegUtils.getSingleFrame(
                ptr = ptrOfVideoThumb,
                width = cell.width.toInt(),
                height = cell.width.toInt()
                ,curTime,true)
            mHandler.post {
                bmp?.let {
                    ALog.i("-260531p1q-VideoThumbSliderView-asyncLoadThumbnail-result "
                            + " curTime:"+curTime
                            + " curFrameNum:"+curFrameNum
                            + " bmp:"+it
                    )
                    thumbnails.put(cell.curFrameNum,it)
                    cell.setBitmap(it)
                    this@VideoThumbSliderView.invalidate()
                }
            }
        }
    }

    fun freeThumbnail(thumbCell:ThumbCell) {
        if (thumbCell.curFrameNum < 0) {
            return
        }
        getThumbTaskList.remove(thumbCell)
        thumbCell.free()
    }

    private val drawRect = RectF()



    var startBlank = 0
    val leftList:ArrayList<DrawBean> = ArrayList()

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (config.durationSec <= 0 || config.gridCellWidthPx <= 0) return

        val cellW = config.gridCellWidthPx
        val cellCount = config.gridCellCount
        val h = height.toFloat()
        val visibleW = if (viewportWidthPx > 0) viewportWidthPx else width
        val firstIndex = (contentScrollX / cellW).toInt().coerceAtLeast(0)
        val lastIndex = ((contentScrollX + visibleW) / cellW).toInt() + 1
        val finalLastIndex = min(lastIndex, cellCount - 1)
        leftList.clear()
        tmpDrawMap.clear()
        ALog.e("-260531p1q-VideoThumbSliderView-onDraw "
                +" firstIndex:"+firstIndex
                +" finalLastIndex:"+finalLastIndex
                +" curDrawMap.size:"+curDrawMap.size
        )
        var lastFrameNum = -1
        var startIndex = -1
        for (i in firstIndex.. finalLastIndex) {
            startIndex++
            val oriLeft = i * cellW
            val curTime = oriLeft / config.pxPerSecond
            var curFrameNum = (curTime * TimelineConstants.NOMINAL_FPS).toInt()
            if (curFrameNum <= lastFrameNum) {
                curFrameNum = lastFrameNum + 1
            }
            lastFrameNum = curFrameNum
            val left = oriLeft + startBlank
            val cellDuration = min(config.gridIntervalSec, config.durationSec - config.gridCellStartSec(i))
            val right = left + (cellDuration / config.gridIntervalSec * cellW).toFloat()

            ALog.i("-260531p1q-VideoThumbSliderView-onDraw "
                    +" curFrameNum:"+curFrameNum
                    +" curTime:"+curTime
            )
            val target = curDrawMap.remove(curFrameNum)
            if (target != null) {
                target.draw(canvas, left)
                tmpDrawMap[curFrameNum] = target
            } else {
                val bean = tmpDrawBean[startIndex]
                bean.setData(curFrameNum,curTime.toDouble(),left)
                leftList.add(bean)
                ///leftList.add(DrawBean(curFrameNum,curTime.toDouble(),left))
            }


            if (false) {
                //            val right = left + cellW
                val paint = if (i and 1 == 0) cellPaint else cellAltPaint
                canvas.drawRect(left, 0f, right, h, paint)
                canvas.drawRect(left, 0f, right, h, borderPaint)
            }
        }

        ALog.i("-260531p1q-VideoThumbSliderView-onDraw-69 "
                +" tmpDrawMap.size:"+tmpDrawMap.size
        )
        curDrawMap.values.forEachIndexed { index, thumbCell ->
            if (index < leftList.size) {
                val pair = leftList[index]
                val curFrameNum = pair.curFrameNum
                val curTime = pair.curTime
                tmpDrawMap[pair.curFrameNum] = thumbCell
                /////loadThumbnail(pair.first,pair.second,thumbCell)

                run{
                    var bitmap = thumbnails.get(curFrameNum)
                    if (bitmap == null) {
                        bitmap = thumbnails_b.get(curFrameNum)
                        if (bitmap == null) {
                            asyncLoadThumbnail(curFrameNum,curTime,thumbCell)
                        } else {
                            if (bitmap.isRecycled) {
                                asyncLoadThumbnail(curFrameNum,curTime,thumbCell)
                                thumbnails_b.remove(curFrameNum)
                                canvas.drawRect(drawRect, cellPaint)
                            } else {
                                thumbCell.setTimeId(pair.curFrameNum,pair.curTime)
                                thumbCell.setBitmap(bitmap)
                                thumbCell.draw(canvas,pair.left)
                            }
                        }
                    } else {
                        if (bitmap.isRecycled) {
                            asyncLoadThumbnail(curFrameNum,curTime,thumbCell)
                            thumbnails.remove(curFrameNum)
                            canvas.drawRect(drawRect, cellPaint)
                        } else {
                            thumbCell.setTimeId(pair.curFrameNum,pair.curTime)
                            thumbCell.setBitmap(bitmap)
                            thumbCell.draw(canvas,pair.left)
                        }
                    }

                }
            } else {
                tmpDrawMap[thumbCell.curFrameNum] = thumbCell
                /// 接触之前的加载
                ///-- freeThumbnail(thumbCell)
            }
        }
        if (TotalShowNum != tmpDrawMap.size) {
            ALog.e("-260531p1q-VideoThumbSliderView-onDraw-79 "
                    +" TotalShowNum:"+TotalShowNum
                    +" tmpDrawMap.size:"+tmpDrawMap.size
            )
        }

        curDrawMap.clear()
        val tmp = curDrawMap
        curDrawMap = tmpDrawMap
        tmpDrawMap = tmp
        if (TotalShowNum != curDrawMap.size) {
            ALog.e("-260531p1q-VideoThumbSliderView-onDraw-89 "
                    +" TotalShowNum:"+TotalShowNum
                    +" curDrawMap.size:"+curDrawMap.size
            )
        }

    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mSliderExecutor.shutdown()
    }
}
