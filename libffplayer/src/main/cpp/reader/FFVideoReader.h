/**
 * @file FFVideoReader.h
 * @brief 视频帧读取器（抽帧工具）
 *
 * 功能说明：
 * - 继承 FFReader，专用于视频帧提取
 * - 支持精准抽帧和快速抽帧
 * - 支持图像缩放（使用 libyuv）
 * - 支持多种像素格式转换（YUV420P → RGBA 等）
 * - 支持获取视频旋转信息
 */

#ifndef FFMPEGDEMO_FFVIDEOREADER_H
#define FFMPEGDEMO_FFVIDEOREADER_H

#include "FFReader.h"
#include <functional>

class FFVideoReader: public FFReader {

public:
    FFVideoReader();
    ~FFVideoReader();

    bool init(std::string &path) override;

    static int getRotate(AVStream *stream);

    int getRotate();

    void getFrame(int64_t pts, int width, int height, uint8_t *buffer, bool precise = true);

    void getNextFrame(const std::function<void(AVFrame *)>& frameArrivedCallback);

private:
    bool mInit = false;

    int64_t mLastPts = -1;

    SwsContext *mSwsContext = nullptr;

    uint8_t *mScaleBuffer = nullptr;
    int64_t mScaleBufferSize = -1;

    AVFrame *mAvFrame = nullptr;
};


#endif //FFMPEGDEMO_FFVIDEOREADER_H
