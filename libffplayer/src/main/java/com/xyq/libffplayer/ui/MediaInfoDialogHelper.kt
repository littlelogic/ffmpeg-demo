package com.xyq.libffplayer.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
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
    fun asyncShow(activity: Activity, mediaPath: String) {
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

    @JvmStatic
    fun syncShow(activity: Activity, mediaInfo : MediaInfo) {
        if (mediaInfo == null) {
            Toast.makeText(activity.applicationContext, "无效数据", Toast.LENGTH_SHORT).show()
            return
        }
        if (activity.isFinishing) return

        showDialogInternal(activity, mediaInfo)
    }

    @JvmStatic
    fun syncShow(activity: Activity, mediaPath: String) {
        if (mediaPath.isBlank()) {
            Toast.makeText(activity.applicationContext, "无效路径", Toast.LENGTH_SHORT).show()
            return
        }
        if (activity.isFinishing) return

        val json = FFMpegUtils.probeMediaInfo(mediaPath)
        if (json == null) {
            Toast.makeText(activity, "媒体信息未加载", Toast.LENGTH_SHORT).show()
            return
        }
        val mediaInfo = MediaInfo(json)
        showDialogInternal(activity, mediaInfo)
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
                val frameRateModeText = when (mediaInfo.frameRateMode) {
                    "CFR" -> "恒定帧率 (CFR)"
                    "VFR" -> "可变帧率 (VFR)"
                    else -> "未知"
                }
                appendLine("帧率类型: $frameRateModeText")
                appendLine("平均/基础帧率: ${String.format("%.2f", mediaInfo.avgFps)} / ${String.format("%.2f", mediaInfo.rFps)} fps")
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
        val message = formatMessage(mediaInfo)
        val spannable = SpannableStringBuilder(message)
        val path = mediaInfo.path
        if (path.isNotEmpty()) {
            val start = message.indexOf(path)
            if (start >= 0) {
                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        copyPathToClipboard(activity, path)
                    }
                }, start, start + path.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("媒体信息")
            .setMessage(spannable)
            .setPositiveButton("确定") { d, _ -> d.dismiss() }
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun copyPathToClipboard(activity: Activity, path: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("媒体文件路径", path))
        Toast.makeText(activity, "已复制路径到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
