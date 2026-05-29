package com.xyq.libffplayer.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FFMpegUtils {

    init {
        System.loadLibrary("ffplayer")
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

    fun getVideoFrames(path: String,
                       width: Int,
                       height: Int,
                       precise: Boolean,
                       cb: VideoFrameArrivedInterface
    ) {
        if (path == "") return
        getVideoFramesCore(path, width, height, precise, cb)
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


    // Native方法声明
    external fun nativeInitVideoReader(path: String): Long
    external fun nativeVideoReaderSetSize(ptr: Long,width: Int, height: Int)
    external fun nativeCloseVideoReader(ptr: Long)

    /**
     * 从已 [nativeInitVideoReader] 的实例抽取单帧，像素写入 DirectByteBuffer（与 [getVideoFrames] 相同）。
     * 输出尺寸由 [nativeVideoReaderSetSize] 决定；未设置时使用原视频宽高。
     */
    fun getSingleFrame(ptr: Long, timestampSec: Double, precise: Boolean = true): ByteBuffer? {
        if (ptr == 0L) return null
        return nativeGetSingleFrame(ptr, timestampSec, precise)
    }

    private external fun nativeGetSingleFrame(
        ptr: Long,
        timestampSec: Double,
        precise: Boolean,
    ): ByteBuffer?


    private external fun nativeExportGif(videoPath: String, output: String): Boolean

}