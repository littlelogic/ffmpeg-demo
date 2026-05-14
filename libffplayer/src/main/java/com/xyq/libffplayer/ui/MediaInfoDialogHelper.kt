package com.xyq.libffplayer.ui

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.xyq.libffplayer.entity.MediaInfo
import com.xyq.libffplayer.utils.FFMpegUtils
import java.util.concurrent.Executors

/**
 * 在后台线程调用 [FFMpegUtils.probeMediaInfo]，在主线程弹出媒体信息对话框。
 */
object MediaInfoDialogHelper {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ffprobe-media-info").apply { isDaemon = true }
    }

    @JvmStatic
    fun show(activity: Activity, mediaPath: String) {
        if (mediaPath.isBlank()) {
            Toast.makeText(activity.applicationContext, "无效路径", Toast.LENGTH_SHORT).show()
            return
        }
        if (activity.isFinishing) return

        executor.execute {
            val json = FFMpegUtils.probeMediaInfo(mediaPath)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (json == null) {
                    Toast.makeText(activity, "媒体信息未加载", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val mediaInfo = MediaInfo(json)
                showDialogInternal(activity, mediaInfo)
            }
        }
    }

    fun formatMessage(mediaInfo: MediaInfo): String {
        return buildString {
            appendLine("=== 媒体信息 ===")
            appendLine()
            appendLine("文件路径: ${mediaInfo.path}")
            appendLine()

            if (mediaInfo.hasVideo) {
                appendLine("--- 视频信息 ---")
                appendLine("编码格式: ${mediaInfo.videoCodecName}")
                appendLine("分辨率: ${mediaInfo.width} x ${mediaInfo.height}")
                appendLine("帧率: ${mediaInfo.fps} fps")
                appendLine("宽高比: ${mediaInfo.dar}")
                appendLine("时长: ${String.format("%.2f", mediaInfo.duration)} 秒")
                appendLine("旋转角度: ${mediaInfo.rotate}°")
                appendLine("硬件加速: ${if (mediaInfo.useHw) "是" else "否"}")
                appendLine()
            }

            if (mediaInfo.hasAudio) {
                appendLine("--- 音频信息 ---")
                appendLine("编码格式: ${mediaInfo.audioCodecName}")
                appendLine("采样率: ${mediaInfo.sampleRate} Hz")
                appendLine("声道数: ${mediaInfo.channel}")
                appendLine("样本格式: ${mediaInfo.sampleFmt}")
            }
        }
    }

    private fun showDialogInternal(activity: Activity, mediaInfo: MediaInfo) {
        AlertDialog.Builder(activity)
            .setTitle("媒体信息")
            .setMessage(formatMessage(mediaInfo))
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
