/**
 * @file FFMediaProbe.cpp
 * @brief 轻量级媒体信息探测（avformat + stream codecpar）及统一 JSON 填充
 */

#include "FFMediaProbe.h"
#include "FFVideoReader.h"
#include "header/Logger.h"

extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
}

void ff_fill_video_media_info_json(nlohmann::json &v, AVFormatContext *ic, int stream_index,
                                   bool use_hw, const char *codec_name_override) {
    if (!ic || stream_index < 0 || (unsigned) stream_index >= ic->nb_streams || !ic->streams[stream_index]) {
        return;
    }

    AVStream *stream = ic->streams[stream_index];
    AVCodecParameters *params = stream->codecpar;
    if (!params) {
        return;
    }

    v["width"] = params->width;
    v["height"] = params->height;
    v["use_hw"] = use_hw;

    if (codec_name_override && codec_name_override[0] != '\0') {
        v["codec_name"] = codec_name_override;
    } else {
        const AVCodec *codec = avcodec_find_decoder(params->codec_id);
        v["codec_name"] = codec ? codec->name : "unknown";
    }

    double duration = stream->duration * av_q2d(stream->time_base);
    if (duration <= 0.0 && ic->duration > 0) {
        duration = ic->duration * av_q2d(AV_TIME_BASE_Q);
    }
    v["duration"] = duration;

    AVRational sar = av_guess_sample_aspect_ratio(ic, stream, nullptr);
    AVRational dar{-1, 1};
    if (sar.num) {
        if (av_reduce(&dar.num, &dar.den, params->width * sar.num, params->height * sar.den,
                      1024 * 1024) <= 0) {
            dar = AVRational{-1, 1};
        }
    }
    v["dar"] = std::to_string(dar.num) + ":" + std::to_string(dar.den);

    v["rotate"] = FFVideoReader::getRotate(stream);

    AVRational fr = av_guess_frame_rate(ic, stream, nullptr);
    if (fr.den != 0) {
        v["fps"] = av_q2d(fr);
    } else {
        v["fps"] = 0.0;
    }
    v["frame_rate"] = std::to_string(fr.num) + ":" + std::to_string(fr.den);
}

void ff_fill_audio_media_info_json(nlohmann::json &a,
                                   const AVCodecParameters *params,
                                   const AVCodecContext *opened_ctx,
                                   const AVCodec *opened_codec) {
    if (opened_ctx) {
        a["codec_name"] = (opened_codec && opened_codec->name) ? opened_codec->name : "unknown";
        a["sample_rate"] = opened_ctx->sample_rate;
        const char *fmtName = av_get_sample_fmt_name(opened_ctx->sample_fmt);
        a["sample_fmt"] = fmtName ? fmtName : "unknown";
        a["channel"] = opened_ctx->ch_layout.nb_channels;
        return;
    }

    if (!params) {
        return;
    }
    const AVCodec *codec = avcodec_find_decoder(params->codec_id);
    a["codec_name"] = codec ? codec->name : "unknown";
    a["sample_rate"] = params->sample_rate;
    const char *fmtName = av_get_sample_fmt_name((AVSampleFormat) params->format);
    a["sample_fmt"] = fmtName ? fmtName : "unknown";
    a["channel"] = params->ch_layout.nb_channels;
}

std::string ff_probe_media_info(const std::string &path) {
    nlohmann::json root;
    root["path"] = path;

    AVFormatContext *ic = nullptr;
    int ret = avformat_open_input(&ic, path.c_str(), nullptr, nullptr);
    if (ret < 0) {
        LOGE("[FFMediaProbe] avformat_open_input failed: %d, %s", ret, path.c_str());
        return root.dump();
    }

    ret = avformat_find_stream_info(ic, nullptr);
    if (ret < 0) {
        LOGE("[FFMediaProbe] avformat_find_stream_info failed: %d", ret);
        avformat_close_input(&ic);
        return root.dump();
    }

    int video_index = -1;
    int audio_index = -1;
    for (unsigned i = 0; i < ic->nb_streams; i++) {
        const AVCodecParameters *par = ic->streams[i]->codecpar;
        if (!par) {
            continue;
        }
        if (par->codec_type == AVMEDIA_TYPE_VIDEO && video_index < 0) {
            video_index = (int) i;
        } else if (par->codec_type == AVMEDIA_TYPE_AUDIO && audio_index < 0) {
            audio_index = (int) i;
        }
    }

    if (video_index >= 0) {
        nlohmann::json v;
        ff_fill_video_media_info_json(v, ic, video_index, false, nullptr);
        root["video"] = v.dump();
    }

    if (audio_index >= 0) {
        nlohmann::json audioJson;
        ff_fill_audio_media_info_json(audioJson, ic->streams[audio_index]->codecpar, nullptr, nullptr);
        root["audio"] = audioJson.dump();
    }

    avformat_close_input(&ic);
    return root.dump();
}
