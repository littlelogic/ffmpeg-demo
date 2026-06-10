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
import com.xyq.ffmpegdemo.R
import com.xyq.ffmpegdemo.player.MyPlayer
import com.xyq.ffmpegdemo.timeline.BothSidesBlankMode
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
    /** 可见区 / 时间轴配置变化时为 true；仅 invalidate 画 Bitmap 时不重建，避免 onDraw 里重复重算 */
    private var contentDirty = true


    private val preciseThumbnails = object : LruCache<Int, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        /*override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }*/
    }


    ///todo 待完善
    private val noPeciseThumbnails = object : LruCache<Int, Bitmap>(DEFAULT_CACHE_SIZE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        /*override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }*/
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
    val cellHeight = Tools.getgetDimension(Tools.getApplication(),R.dimen.video_thumb_slider_height)
    val thumbCellList:ArrayList<ThumbCell> = ArrayList()
    var curDrawMap : HashMap<Int, ThumbCell?> = HashMap()
    var tmpDrawMap : HashMap<Int, ThumbCell?> = HashMap()
    val tmpDrawBean:ArrayList<DrawBean> = ArrayList()
    val drawThumbCellList:ArrayList<ThumbCell?> = ArrayList()
    val oriThumbCellList:ArrayList<ThumbCell> = ArrayList()

    var totalShowNum = 5

    init {
        Tools.getScreenSize(Tools.getApplication()).let {
            val totalWidthPx = minOf(it[0],it[1])
            val num = totalWidthPx / cellWidth + 3
            totalShowNum = num
            ALog.e("-260531p1q-VideoThumbSliderView-init "
                    +" num:"+num
            )
            for (i in 1..num) {
                val id = -(i)
                val cell = ThumbCell(cellWidth.toFloat(),cellHeight.toFloat(),id)
                thumbCellList.add(cell)
                drawThumbCellList.add(cell)
                oriThumbCellList.add(cell)
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

    var mPlayer: MyPlayer? = null

    fun setPlayer(player_: MyPlayer?) {
        mPlayer = player_
    }


    fun setTimelineConfig(timeline: TimelineConfig) {
        config.durationSec = timeline.durationSec
        config.majorTickSpacingPx = timeline.majorTickSpacingPx
        config.pxPerSecond = timeline.pxPerSecond
        markContentDirty()
        requestLayout()
        invalidate()
    }

    fun setContentScrollX(scrollX: Int) {
        val sx = scrollX.coerceAtLeast(0)
        if (contentScrollX != sx) {
            contentScrollX = sx
            markContentDirty()
            invalidate()
        }
    }

    fun setViewportWidthPx(width: Int) {
        if (viewportWidthPx != width) {
            viewportWidthPx = width
            markContentDirty()
            invalidate()
        }
    }

    private fun markContentDirty() {
        contentDirty = true
        ///ensureDrawContentUpdated()
    }

    /** 首屏或仅 invalidate 时，在绘制前补一次可见区绑定；缩略图回调 invalidate 不会触发 */
    private fun ensureDrawContentUpdated() {
        if (!contentDirty) return
        if (config.durationSec <= 0 || config.gridCellWidthPx <= 0) return
        changeDrawContent()
        contentDirty = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            markContentDirty()
            invalidate()
        }
    }


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = config.contentWidthPx
        startBlank = 0
        if (TimelineConstants.bothSidesBlankMode == BothSidesBlankMode.fixed) {
            TimelineConstants.bothSidesBlankPx
        } else {
            customHorizontalScrollView?.let {
                startBlank = it.width/2
            }
        }
        w += (startBlank * 2)

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

        mSliderExecutor.submit {
            val cell = getThumbTaskList.remove()
            // 以 cell 上 setTimeId 写入的为准，避免闭包里的 curTime/curFrameNum 与 dequeue 到的 cell 不一致
            val frameNum = cell.curFrameNum
            val timeSec = cell.curTime
            val precise = if (mPlayer?.isPlaying() == true &&false ) {
                false
            } else {
                true
            }
            val bmp = FFMpegUtils.getSingleFrame(
                ptr = ptrOfVideoThumb,
                width = cell.width.toInt(),
                height = cell.height.toInt(),
                timestampSec = timeSec,
                precise = precise,
            )
            mHandler.post {
                bmp?.let {
                    ALog.i("-260531p1q-VideoThumbSliderView-asyncLoadThumbnail-result "
                            + " curTime:" + timeSec
                            + " curFrameNum:" + frameNum
                            + " bmp:" + it
                    )
                    if (precise) {
                        preciseThumbnails.put(frameNum, it)
                        if (cell.curFrameNum == frameNum) {
                            cell.setBitmap(it,true)
                        }
                    } else {
                        noPeciseThumbnails.put(frameNum, it)
                        if (cell.curFrameNum == frameNum) {
                            cell.setBitmap(it,false)
                        }
                    }
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

    fun sortDrawBitmap(header:ThumbCell?,tailer :ThumbCell?,startIndex:Int,endIndex:Int) {
        if (startIndex > endIndex) {
            return
        }
        var lastThumbCell = header
        if (lastThumbCell != null && tailer != null) {
            for (i in startIndex..endIndex) {
                val target = drawThumbCellList[i]
                if (target != null) {
                    if (target.tmpIsValid()) {
                        if (target.tmpFrameNum > lastThumbCell!!.tmpFrameNum) {
                            if (target.tmpFrameNum < tailer.tmpFrameNum) {
                                lastThumbCell = target
                            } else {
                                target.tmpSetTmpData(lastThumbCell)
                            }
                        } else {
                            target.tmpSetTmpData(lastThumbCell)
                        }
                    } else {
                        target.tmpSetTmpData(lastThumbCell)
                    }
                }
            }
        } else if (header == null && tailer != null) {
            lastThumbCell = tailer
            for (i in endIndex downTo startIndex) {
                val target = drawThumbCellList[i]
                if (target != null) {
                    if (target.tmpIsValid()) {
                        if (target.tmpFrameNum < lastThumbCell!!.tmpFrameNum) {
                            lastThumbCell = target
                        } else {
                            target.tmpSetTmpData(lastThumbCell)
                        }
                    } else {
                        target.tmpSetTmpData(lastThumbCell)
                    }
                }
            }
        } else if (header != null && tailer == null) {
            for (i in startIndex..endIndex) {
                val target = drawThumbCellList[i]
                if (target != null) {
                    if (target.tmpIsValid()) {
                        if (target.tmpFrameNum > lastThumbCell!!.tmpFrameNum) {
                            lastThumbCell = target
                        } else {
                            target.tmpSetTmpData(lastThumbCell)
                        }
                    } else {
                        target.tmpSetTmpData(lastThumbCell)
                    }
                }
            }
        }
    }

    var startBlank = 0
    val leftList:ArrayList<DrawBean> = ArrayList()

    fun changeDrawContent() {
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
                +" -----------------"
        )
        for (i in 0 until drawThumbCellList.size) {
            drawThumbCellList[i] = null
            oriThumbCellList[i].thisDrawing = false
        }

        var lastFrameNum = -1
        var startIndex = -1
        val drawNumber = finalLastIndex - firstIndex + 1

        for (i in firstIndex.. finalLastIndex) {
            startIndex++
            val oriLeft = i * cellW
            val tmpCurTime = oriLeft / config.pxPerSecond
            val seconds = tmpCurTime.toInt()
            val leftFrameNum = ((tmpCurTime - seconds) * TimelineConstants.NOMINAL_FPS).toInt()
            val curTime = seconds + leftFrameNum / TimelineConstants.NOMINAL_FPS
            var curFrameNum = seconds * TimelineConstants.FRAMES_PER_SEC + leftFrameNum

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
            val target = curDrawMap[curFrameNum]
            if (target != null) {
                target.setDrawX(left)
                target.thisDrawing = true
                curDrawMap[curFrameNum] = null
                tmpDrawMap[curFrameNum] = target
                drawThumbCellList[startIndex] = target
            } else {
                val bean = tmpDrawBean[startIndex]
                bean.setData(curFrameNum,curTime.toDouble(),left)
                leftList.add(bean)
            }
        }

        ALog.i("-260531p1q-VideoThumbSliderView-onDraw-69 "
                +" tmpDrawMap.size:"+tmpDrawMap.size
        )

        var index = -1
        var indexOfWillDraw = 0
        oriThumbCellList.forEach { thumbCell ->
            if (thumbCell.thisDrawing) {
                return@forEach
            }
            index++

            if (index < leftList.size) {

                val pair = leftList[index]
                val curFrameNum = pair.curFrameNum
                val curTime = pair.curTime
                tmpDrawMap[pair.curFrameNum] = thumbCell
                thumbCell.setDrawX(pair.left)

                for (i in indexOfWillDraw until drawThumbCellList.size) {
                    if (drawThumbCellList[i] == null) {
                        drawThumbCellList[i] = thumbCell
                        indexOfWillDraw = i + 1
                        break
                    }
                }

                run{
                    var bitmap = preciseThumbnails.get(curFrameNum)
                    if (bitmap == null) {
                        bitmap = noPeciseThumbnails.get(curFrameNum)
                        if (bitmap == null) {
                            asyncLoadThumbnail(curFrameNum,curTime,thumbCell)
                        } else {
                            if (bitmap.isRecycled) {
                                asyncLoadThumbnail(curFrameNum,curTime,thumbCell)
                                noPeciseThumbnails.remove(curFrameNum)
                            } else {
                                thumbCell.setTimeId(pair.curFrameNum,pair.curTime)
                                thumbCell.setBitmap(bitmap,false)
                            }
                        }
                    } else {
                        if (bitmap.isRecycled) {
                            asyncLoadThumbnail(curFrameNum,curTime,thumbCell)
                            preciseThumbnails.remove(curFrameNum)
                        } else {
                            thumbCell.setTimeId(pair.curFrameNum,pair.curTime)
                            thumbCell.setBitmap(bitmap,true)
                            thumbCell.thisDrawing = true
                        }
                    }
                }

                if (thumbCell.drawX == 0f){
                    ALog.e("-260531p1q-VideoThumbSliderView-onDraw-79 thumbCell.drawX == 0f"
                            +" TotalShowNum:"+totalShowNum
                    )
                }
            } else {
                tmpDrawMap[thumbCell.curFrameNum] = thumbCell
                /// 接触之前的加载
                ///-- freeThumbnail(thumbCell)
            }
        }

        if (totalShowNum != tmpDrawMap.size) {
            ALog.e("-260531p1q-VideoThumbSliderView-onDraw-79 "
                    +" TotalShowNum:"+totalShowNum
                    +" tmpDrawMap.size:"+tmpDrawMap.size
            )
        }

        curDrawMap.clear()
        val tmp = curDrawMap
        curDrawMap = tmpDrawMap
        tmpDrawMap = tmp
        if (totalShowNum != curDrawMap.size) {
            ALog.e("-260531p1q-VideoThumbSliderView-onDraw-89 "
                    +" TotalShowNum:"+totalShowNum
                    +" curDrawMap.size:"+curDrawMap.size
            )
        }

        logTestInfo("info1",drawNumber)

        var lastThumbCell:ThumbCell? = null
        var lastThumbCellIndex = -1
        for (i in 0 until drawNumber) {
            val cell = drawThumbCellList[i]
            if (cell != null && cell.realIsValid()) {
                cell.tmpSetRealData()
                sortDrawBitmap(lastThumbCell,cell,lastThumbCellIndex+1,i-1)
                lastThumbCell = cell
                lastThumbCellIndex = i
            }
        }

        logTestInfo("info2",drawNumber)

        if (lastThumbCell != null) {
            if (lastThumbCellIndex < drawNumber - 1) {
                sortDrawBitmap(lastThumbCell,null,lastThumbCellIndex+1,drawNumber - 1)
            }
            logTestInfo("info3",drawNumber)
        } else {
            lastThumbCell = null
            lastThumbCellIndex = -1
            for (i in 0 until drawNumber) {
                if (drawThumbCellList[i]?.tmpIsValid() == true) {
                    val curCell = drawThumbCellList[i]
                    if ((lastThumbCellIndex+1) < i) {
                        for (j in (lastThumbCellIndex+1) until i) {
                            drawThumbCellList[j]?.tmpSetTmpData(curCell)
                        }
                    } else {
                        val last = lastThumbCell   // 此时 last 被推断为非空类型
                        if (last != null && lastThumbCellIndex + 1 == i) {
                            if (curCell!!.tmpFrameNum < last!!.tmpFrameNum) {
                                curCell.tmpSetTmpData(last)
                            }
                        }
                    }
                    lastThumbCell = curCell
                    lastThumbCellIndex = i
                }
            }
            logTestInfo("info4",drawNumber)
            if (lastThumbCell != null) {
                if (lastThumbCellIndex < drawNumber - 1) {
                    for (j in (lastThumbCellIndex+1) until drawNumber) {
                        drawThumbCellList[j]?.tmpSetTmpData(lastThumbCell)
                    }
                }
            } else {
                ALog.e("-260531p1q-VideoThumbSliderView-onDraw-91 lastThumbCell==null"
                        +" TotalShowNum:"+totalShowNum
                )
            }
        }

        logTestInfo("info5",drawNumber)

        if (true) {
            var lastFrameNum = -11
            for (i in 0 until drawNumber) {
                val cell = drawThumbCellList[i] ?: continue

                if ((cell.realBmp == null && cell.tmpBmp == null)) {
                    ALog.e("-260531p1q-VideoThumbSliderView-onDraw-92 bmp is null"
                            +" drawNumber:"+drawNumber
                    )
                }

                if (cell.realIsValid()) {
                    if (cell.curFrameNum < lastFrameNum) {
                        ALog.e("-260531p1q-VideoThumbSliderView-onDraw-92 not sort1"
                                +" cell.curFrameNum:"+cell.curFrameNum
                                +" lastFrameNum:"+lastFrameNum
                        )
                    }
                    lastFrameNum = cell.curFrameNum
                } else if (cell.tmpIsValid()) {
                    if (cell.tmpFrameNum < lastFrameNum) {
                        ALog.e("-260531p1q-VideoThumbSliderView-onDraw-92 not sort2"
                                +" cell.tmpFrameNum:"+cell.tmpFrameNum
                                +" lastFrameNum:"+lastFrameNum
                        )
                    }
                    lastFrameNum = cell.tmpFrameNum
                }



            }
        }

    }

    fun logTestInfo(tag:String, drawNumber:Int) {
        val stringBuilder : StringBuilder = StringBuilder()
        for (i in 0 until drawNumber) {
            val cell = drawThumbCellList[i] ?: continue
            val realBmpIsNull = cell.realBmp == null
            val tmpBmpIsNull = cell.tmpBmp == null
            stringBuilder.append("{index:").append(i)
                .append(",realBmpIsNull:").append(realBmpIsNull)
                .append(",tmpBmpIsNull:").append(tmpBmpIsNull)
                .append("}")
//                    .append("\n")
        }
        ALog.e("-260531p1q-VideoThumbSliderView-onDraw-drawThumbCellList"
                +" "+tag+":"+stringBuilder.toString()
        )
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureDrawContentUpdated()
        var lastBmp: Bitmap? = null
//        for (i in 0 until drawThumbCellList.size) {
//            if (drawThumbCellList[i]?.realBmp != null){
//                lastBmp = drawThumbCellList[i]?.realBmp
//                break
//            }
//        }
//        if (lastBmp == null) {
//            ALog.e("-260531p1q-VideoThumbSliderView-onDraw-83 (lastBmp == null)"
//                    +" TotalShowNum:"+totalShowNum
//            )
//        }
        for (i in 0 until drawThumbCellList.size) {
//            if (drawThumbCellList[i]?.realBmp != null){
//                lastBmp = drawThumbCellList[i]?.realBmp
//            } else {
//                drawThumbCellList[i]?.tmpBmp = lastBmp
//            }
            drawThumbCellList[i]?.drawSelf(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mSliderExecutor.shutdown()
        FFMpegUtils.nativeCloseVideoReader(ptrOfVideoThumb)
    }
}
