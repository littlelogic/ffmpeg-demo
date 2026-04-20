/**
 * @file AudioDecoder.h
 * @brief 音频解码器
 *
 * 功能说明：
 * - 继承 BaseDecoder，实现音频解码逻辑
 * - 使用 libswresample 进行音频重采样（统一输出 44100Hz 立体声 S16 格式）
 * - 支持音视频同步
 * - 支持 Seek 操作
 */

#ifndef FFMPEGDEMO_AUDIODECODER_H
#define FFMPEGDEMO_AUDIODECODER_H

#include <functional>
#include <string>
#include "../utils/ImageDef.h"
#include "BaseDecoder.h"

extern "C" {
#include "../vendor/ffmpeg/libswresample/swresample.h"
#include "../vendor/ffmpeg/libavutil/imgutils.h"
}

class AudioDecoder: public BaseDecoder {

public:
    AudioDecoder(int index, AVFormatContext *ftx);
    ~AudioDecoder();

    virtual double getDuration() override;

    virtual bool prepare() override;      ///< 准备解码器和重采样器

    virtual int decode(AVPacket *packet) override;  ///< 解码并重采样

    virtual void avSync(AVFrame *frame) override;   ///< 音视频同步

    virtual int seek(double pos) override;

    virtual void release() override;

    int64_t getTimestamp() const;          ///< 获取当前时间戳（ms）

    int64_t mCurTimeStampMs = 0;          ///< 当前帧时间戳（ms）

    bool mNeedFlushRender = false;        ///< Seek 后需要通知渲染器刷新

    int mDataSize = 0;                    ///< 重采样后的数据大小（字节）

    uint8_t *mAudioBuffer = nullptr;      ///< 重采样输出缓冲

private:
    int64_t mStartTimeMsForSync = -1;     ///< 同步起始时间

    const AVCodec *mAudioCodec = nullptr; ///< 音频解码器

    SwrContext *mSwrContext = nullptr;     ///< 重采样上下文（libswresample）

    void updateTimestamp(AVFrame *frame);  ///< 更新时间戳

    int resample(AVFrame *frame);          ///< 执行重采样
};


#endif //FFMPEGDEMO_AUDIODECODER_H
