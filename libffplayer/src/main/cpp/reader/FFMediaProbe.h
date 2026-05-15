/**
 * @file FFMediaProbe.h
 * @brief 媒体信息探测与 JSON 填充（与 Java MediaInfo 解析格式一致）
 *
 * - ff_probe_media_info：按路径探测（内部仅调用下方两个 ff_fill_*）
 * - ff_fill_video_media_info_json：视频轨字段一处完成（含 use_hw、codec_name）
 * - ff_fill_audio_media_info_json：音频轨字段一处完成（codecpar 与已打开解码器二选一由参数表达）
 */

#ifndef FFMPEGDEMO_FFMEDIAPROBE_H
#define FFMPEGDEMO_FFMEDIAPROBE_H

#include <string>

#include "../vendor/nlohmann/json.hpp"

extern "C" {
struct AVFormatContext;
struct AVCodecParameters;
struct AVCodecContext;
struct AVCodec;
}

/**
 * 填充视频轨 mMediaInfoJson 全部字段。
 * @param use_hw 是否报告为硬件相关路径（探测场景传 false）
 * @param codec_name_override 若非空则写入 codec_name；为空则按 codecpar 推导默认解码器名
 */
void ff_fill_video_media_info_json(nlohmann::json &v, AVFormatContext *ic, int stream_index,
                                   bool use_hw, const char *codec_name_override);

/**
 * 填充音频轨 mMediaInfoJson 全部字段。
 * @param params 流上的 codecpar（探测或非空时作为 opened_ctx 为空的唯一数据源）
 * @param opened_ctx 若已 avcodec_open2，传入则优先用上下文字段；探测路径传 nullptr
 * @param opened_codec 与 opened_ctx 配对；opened_ctx 为空时可忽略
 */
void ff_fill_audio_media_info_json(nlohmann::json &a,
                                   const AVCodecParameters *params,
                                   const AVCodecContext *opened_ctx,
                                   const AVCodec *opened_codec);

std::string ff_probe_media_info(const std::string &path);

#endif // FFMPEGDEMO_FFMEDIAPROBE_H
