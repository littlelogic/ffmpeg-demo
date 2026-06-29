package com.xyq.libffplayer.utils

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FFMpegUtils {

    private const val TAG = "FFMpegUtils"

    init {
        System.loadLibrary("jwplayer")
    }

    interface VideoFrameArrivedInterface {
        /**
         * @param duration
         * 给定视频时长，返回待抽帧的pts arr，单位为s
         */
        fun onFetchStart(duration: Double): DoubleArray

        /**
         * 每抽帧一次回调一次
         */
        fun onProgress(frame: ByteBuffer, timestamps: Double, width: Int, height: Int, rotate: Int, index: Int): Boolean

        /**
         * 抽帧动作结束
         */
        fun onFetchEnd()
    }

    interface VideoFrameRangeArrivedInterface {
        fun onFetchStart(
            duration: Double,
            width: Int,
            height: Int,
            rotate: Int,
            renderToSurface: Boolean
        ) {}

        /**
         * @param frame 软解时为 RGBA DirectByteBuffer；Surface 硬解渲染时为 null。
         * @param renderToSurface true 表示该帧已经提交到传入的 Surface。
         */
        fun onFrame(
            frame: ByteBuffer?,
            timestamp: Double,
            width: Int,
            height: Int,
            rotate: Int,
            index: Int,
            renderToSurface: Boolean
        ): Boolean

        fun onFetchEnd()
    }

    fun getVideoFrames(path: String,
                       width: Int,
                       height: Int,
                       precise: Boolean,
                       cb: VideoFrameArrivedInterface
    ) {
        if (path == "") return
        getVideoFramesCore(path, width, height, precise, cb)
    }

    fun getVideoFramesInRange(
        path: String,
        startTime: Double,
        endTime: Double,
        width: Int = 0,
        height: Int = 0,
        surface: Surface? = null,
        cb: VideoFrameRangeArrivedInterface
    ) {
        if (path.isEmpty() || endTime < startTime) return
        if (surface != null && renderVideoFramesInRangeToSurface(path, startTime, endTime, surface, cb)) {
            return
        }
        nativeGetVideoFramesInRange(path, startTime, endTime, width, height, cb)
    }

    /**
     * 仅根据本地文件路径探测媒体信息，无需 Surface / 无需创建 [com.xyq.libffplayer.FFPlayer]。
     *
     * 返回 JSON 字符串，结构与 [com.xyq.libffplayer.FFPlayer.getMediaInfo] 一致：
     * `path`、`video` / `audio` 为嵌套的 JSON 字符串字段（与现有解析逻辑兼容）。
     * 探测阶段 `use_hw` 固定为 false；`codec_name` 为 `avcodec_find_decoder` 对应的解码器名（通常为软解名）。
     */
    fun probeMediaInfo(path: String): String? {
        if (path.isEmpty()) return null
        return nativeProbeMediaInfo(path)
    }

    private external fun getVideoFramesCore(path: String,
                                            width: Int,
                                            height: Int,
                                            precise: Boolean,
                                            cb: VideoFrameArrivedInterface
    )

    private external fun nativeGetVideoFramesInRange(
        path: String,
        startTime: Double,
        endTime: Double,
        width: Int,
        height: Int,
        cb: VideoFrameRangeArrivedInterface
    )

    private external fun nativeProbeMediaInfo(path: String): String

    /**
     * 将输入视频的关键帧导出为gif
     */
    fun exportGif(videoPath: String, output: String): Boolean {
        return nativeExportGif(videoPath, output)
    }

    private fun allocateFrame(width: Int, height: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun renderVideoFramesInRangeToSurface(
        path: String,
        startTime: Double,
        endTime: Double,
        surface: Surface,
        cb: VideoFrameRangeArrivedInterface
    ): Boolean {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var codecStarted = false
        var callbackStarted = false
        try {
            val startUs = (startTime.coerceAtLeast(0.0) * 1_000_000L).toLong()
            val endUs = (endTime.coerceAtLeast(startTime) * 1_000_000L).toLong()
            extractor = MediaExtractor()
            extractor.setDataSource(path)

            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrack = i
                    format = trackFormat
                    break
                }
            }
            val videoFormat = format ?: return false
            if (videoTrack < 0) return false
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return false

            val videoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val videoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val duration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            } else {
                0.0
            }
            val rotate = try {
                if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                }
            } catch (_: Throwable) {
                0
            }

            extractor.selectTrack(videoTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codec.codecInfo.isSoftwareOnly) {
                Log.i(TAG, "surface range decode skip software codec: ${codec.name}")
                return false
            }
            codec.configure(videoFormat, surface, null, 0)
            codec.start()
            codecStarted = true

            cb.onFetchStart(duration, videoWidth, videoHeight, rotate, true)
            callbackStarted = true

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameIndex = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0 || sampleTimeUs > endUs) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            inputBuffer?.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    sampleTimeUs,
                                    extractor.sampleFlags
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "range surface decoder output format: ${codec.outputFormat}")
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val timestampUs = info.presentationTimeUs
                            val render = info.size > 0 && timestampUs in startUs..endUs
                            codec.releaseOutputBuffer(outputIndex, render)
                            if (render) {
                                val keepGoing = cb.onFrame(
                                    null,
                                    timestampUs / 1_000_000.0,
                                    videoWidth,
                                    videoHeight,
                                    rotate,
                                    frameIndex,
                                    true
                                )
                                frameIndex++
                                if (!keepGoing) {
                                    outputDone = true
                                }
                            }
                            if (eos || timestampUs > endUs) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            cb.onFetchEnd()
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "surface range decode failed: ${t.message}", t)
            if (callbackStarted) {
                cb.onFetchEnd()
            }
            return callbackStarted
        } finally {
            if (codecStarted) {
                try {
                    codec?.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor?.release()
            } catch (_: Throwable) {
            }
        }
    }


    // Native方法声明
    external fun nativeInitVideoReader(path: String): Long
    external fun nativeVideoReaderSetSize(ptr: Long,width: Int, height: Int)
    external fun nativeCloseVideoReader(ptr: Long)

    /**
     * 从已 [nativeInitVideoReader] 的实例抽取单帧，像素写入 DirectByteBuffer（与 [getVideoFrames] 相同）。
     * @param timestampSec 媒体时间，单位：秒，支持小数（如 1.5 表示 1.5 秒处）
     * 输出尺寸由 [nativeVideoReaderSetSize] 决定；未设置时使用原视频宽高。
     */
    fun getSingleFrame(ptr: Long, timestampSec: Double, precise: Boolean = true): ByteBuffer? {
        if (ptr == 0L) return null
        return nativeGetSingleFrame(ptr, timestampSec, precise)
    }

    fun getSingleFrame(
        ptr: Long,
        width : Int,
        height : Int,
        timestampSec: Double,
        precise: Boolean,
    ): Bitmap?{
        val buffer = getSingleFrame(ptr, timestampSec, precise) ?: return null
        buffer.rewind()
        // 这里假设输出格式为 RGBA8888，根据实际情况调整
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    }

    private external fun nativeGetSingleFrame(
        ptr: Long,
        timestampSec: Double,
        precise: Boolean,
    ): ByteBuffer?


    private external fun nativeExportGif(videoPath: String, output: String): Boolean

}
