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

    /**
     * 提取单帧 RGBA 数据。
     *
     * @param path         视频文件路径
     * @param timestampSec 目标时间戳（秒）
     * @param width        目标宽度（<=0 自适应）
     * @param height       目标高度（<=0 自适应）
     * @param precise      是否精准抽帧
     * @return             RGBA 字节数组（width × height × 4），失败返回 null
     */
    fun getSingleFrame(
        path: String,
        timestampSec: Double,
        width: Int,
        height: Int,
        precise: Boolean = false,
    ): ByteArray? {
        if (path.isEmpty()) return null
        return nativeGetSingleFrame(path, timestampSec, width, height, precise)
    }

    private fun allocateFrame(width: Int, height: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    }

    private external fun nativeGetSingleFrame(
        path: String,
        timestampSec: Double,
        width: Int,
        height: Int,
        precise: Boolean,
    ): ByteArray?

    private external fun nativeExportGif(videoPath: String, output: String): Boolean

}