package com.xyq.ffmpegdemo.timeline

import kotlin.math.ceil

/**
 * 时间轴度量：一格固定宽度（默认 50dp），捏合缩放改变「1 秒占多少像素」。
 *
 * - 1 秒跨度：cellWidth/10 ~ 30*cellWidth，默认 = cellWidth（一格 1 秒）
 * - 对应一格时间：10s ~ 1/30s
 */
object TimelineConstants {
    const val NOMINAL_FPS = 30.0
    const val FRAMES_PER_SEC = 30

    /** 一格最小 1 帧 */
    const val MIN_GRID_SEC = 1.0 / 30.0
    /** 一格最大 10 秒 */
    const val MAX_GRID_SEC = 10.0
    /** 默认一格 1 秒 */
    const val DEFAULT_GRID_SEC = 1.0

    const val DEFAULT_MAJOR_TICK_SPACING_DP = 50f

    /** 1 秒最小像素 = cellWidth / MIN_SEC_SPAN_DIVISOR */
    const val MIN_SEC_SPAN_DIVISOR = 10f
    /** 1 秒最大像素 = cellWidth * MAX_SEC_SPAN_MULTIPLIER（1 帧 = 1 格宽） */
    const val MAX_SEC_SPAN_MULTIPLIER = 30f
}

class TimelineConfig(
    var durationSec: Double = 0.0,
    var majorTickSpacingPx: Float = 0f,
    /** 1 秒在视图上的像素宽度；0 表示使用默认（= majorTickSpacingPx） */
    var pxPerSecond: Float = 0f,
) {
    val gridCellWidthPx: Float
        get() = majorTickSpacingPx

    /** 有效 1 秒像素宽 */
    val secondSpanPx: Float
        get() {
            val cell = majorTickSpacingPx
            if (cell <= 0) return pxPerSecond
            val px = if (pxPerSecond > 0) pxPerSecond else cell
            return px.coerceIn(minSecondSpanPx, maxSecondSpanPx)
        }

    val minSecondSpanPx: Float
        get() = if (majorTickSpacingPx <= 0) 0f
        else majorTickSpacingPx / TimelineConstants.MIN_SEC_SPAN_DIVISOR

    val maxSecondSpanPx: Float
        get() = if (majorTickSpacingPx <= 0) 0f
        else majorTickSpacingPx * TimelineConstants.MAX_SEC_SPAN_MULTIPLIER

    /** 一格对应的时间（秒），与缩略图条带同步 */
    val gridIntervalSec: Double
        get() {
            val px = secondSpanPx
            return if (px > 0 && majorTickSpacingPx > 0) {
                (majorTickSpacingPx / px).toDouble()
            } else {
                TimelineConstants.DEFAULT_GRID_SEC
            }
        }

    val contentWidthPx: Int
        get() = if (durationSec <= 0 || secondSpanPx <= 0) 0
        else (durationSec * secondSpanPx).toInt().coerceAtLeast(1)

    val gridCellCount: Int
        get() = if (durationSec <= 0 || gridIntervalSec <= 0) 0
        else ceil(durationSec / gridIntervalSec).toInt().coerceAtLeast(1)

    fun timeSecToPx(timeSec: Double): Float = (timeSec * secondSpanPx).toFloat()

    fun pxToTimeSec(px: Float): Double =
        if (secondSpanPx <= 0) 0.0 else (px / secondSpanPx).toDouble()

    fun gridCellStartSec(index: Int): Double = index * gridIntervalSec

    fun gridCellSampleTimeSec(index: Int): Double {
        val start = gridCellStartSec(index)
        val end = (start + gridIntervalSec).coerceAtMost(durationSec)
        return (start + end) / 2.0
    }

    /** 1 秒占多少「格宽」倍数 */
    fun secondSpanInCells(): Float =
        if (majorTickSpacingPx > 0) secondSpanPx / majorTickSpacingPx else 1f

    companion object {
        @JvmStatic
        fun majorTickSpacingPx(density: Float): Float =
            TimelineConstants.DEFAULT_MAJOR_TICK_SPACING_DP * density

        @JvmStatic
        fun clampPxPerSecond(cellWidthPx: Float, rawPxPerSecond: Float): Float {
            if (cellWidthPx <= 0) return rawPxPerSecond
            val minPx = cellWidthPx / TimelineConstants.MIN_SEC_SPAN_DIVISOR
            val maxPx = cellWidthPx * TimelineConstants.MAX_SEC_SPAN_MULTIPLIER
            return rawPxPerSecond.coerceIn(minPx, maxPx)
        }

        @JvmStatic
        fun defaultPxPerSecond(cellWidthPx: Float): Float = cellWidthPx
    }
}
