/**
 * @file FFMediaProbe.h
 * @brief 仅根据文件路径探测媒体信息（无需 Surface / 不打开解码器）
 *
 * 返回的 JSON 结构与 FFMpegPlayer::getMediaInfo 一致：`path`，以及字符串形式的
 * `video` / `audio` 子 JSON（与现有 Kotlin MediaInfo 解析兼容）。
 */

#ifndef FFMPEGDEMO_FFMEDIAPROBE_H
#define FFMPEGDEMO_FFMEDIAPROBE_H

#include <string>

std::string ff_probe_media_info(const std::string &path);

#endif // FFMPEGDEMO_FFMEDIAPROBE_H
