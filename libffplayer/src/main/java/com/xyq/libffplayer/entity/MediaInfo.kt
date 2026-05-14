package com.xyq.libffplayer.entity

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 解析 [com.xyq.libffplayer.FFPlayer.getMediaInfo] / [com.xyq.libffplayer.utils.FFMpegUtils.probeMediaInfo] 返回的 JSON。
 */
class MediaInfo(json: String?) {

    var path = ""

    var hasVideo = false
    var useHw = false
    var videoCodecName = ""
    var dar = ""
    var width = 0
    var height = 0
    var duration = 0.0
    var rotate = 0
    var fps = 0
    var frameRate = ""

    var hasAudio = false
    var audioCodecName = ""
    var channel = 0
    var sampleFmt = ""
    var sampleRate = 0

    init {
        json?.let {
            val obj = JSONObject(it)
            try {
                path = obj.getString("path")

                hasVideo = obj.has("video")
                if (hasVideo) {
                    val video = JSONObject(obj.getString("video"))
                    videoCodecName = video.getString("codec_name")
                    useHw = video.getBoolean("use_hw")
                    dar = video.getString("dar")
                    width = video.getInt("width")
                    height = video.getInt("height")
                    duration = video.getDouble("duration")
                    rotate = video.getInt("rotate")
                    fps = video.optDouble("fps", 0.0).roundToInt()
                    frameRate = video.getString("frame_rate")
                }

                hasAudio = obj.has("audio")
                if (hasAudio) {
                    val audio = JSONObject(obj.getString("audio"))
                    audioCodecName = audio.getString("codec_name")
                    channel = audio.getInt("channel")
                    sampleFmt = audio.getString("sample_fmt")
                    sampleRate = audio.getInt("sample_rate")
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun toString(): String {
        return "MediaInfo(path='$path', hasVideo=$hasVideo, useHw=$useHw, videoCodecName='$videoCodecName'" +
                ", dar='$dar', " +
                "width=$width, " +
                "height=$height, " +
                "duration=$duration,秒 " +
                "rotate=$rotate, " +
                "fps=$fps, " +
                "frameRate=$frameRate, " +
                "hasAudio=$hasAudio, audioCodecName='$audioCodecName', channel=$channel, sampleFmt='$sampleFmt', sampleRate=$sampleRate)"
    }
}
