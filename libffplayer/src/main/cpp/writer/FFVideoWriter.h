/**
 * @file FFVideoWriter.h
 * @brief 视频编码输出器
 *
 * 功能说明：
 * - 封装 FFmpeg 的视频编码和封装输出功能
 * - 支持 H.264 和 GIF 编码格式
 * - 输入 AVFrame → 格式转换 → 编码 → 写入文件
 * - 支持配置编码参数（分辨率、帧率、码率、GOP 等）
 */

//
// Created by 雪月清的随笔 on 14/5/23.
//

#ifndef FFMPEGDEMO_FFVIDEOWRITER_H
#define FFMPEGDEMO_FFVIDEOWRITER_H

extern "C" {
#include "libavformat/avformat.h"
#include "../vendor/ffmpeg//libavcodec/avcodec.h"
#include "../vendor/ffmpeg/libavutil/imgutils.h"
#include "../vendor/ffmpeg/libswscale/swscale.h"
}

#include <string>
#include "../settings/CompileSettings.h"

class FFVideoWriter {
public:
    FFVideoWriter();

    ~FFVideoWriter();

    bool init(std::string &outputPath, CompileSettings &settings);

    void encode(AVFrame *frame);

    void signalEof();

    void release();

private:
    AVFormatContext *mOutputFtx = nullptr;
    AVCodecContext *mCodecContext = nullptr;
    AVCodecParameters *mCodecParameters = nullptr;
    AVStream *mStream = nullptr;

    SwsContext *mSwsContext = nullptr;
    AVFrame *mAvFrame = nullptr;

    int mFrameIndex = 0;
};

#endif //FFMPEGDEMO_FFVIDEOWRITER_H
