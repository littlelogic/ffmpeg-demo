/**
 * @file VideoDecoder.h
 * @brief 视频解码器
 *
 * 功能说明：
 * - 继承 BaseDecoder，实现视频解码逻辑
 * - 支持 H.264/H.265 软件解码和 Android MediaCodec 硬件加速
 * - 使用 libswscale 进行像素格式转换（如 YUV420P10LE → RGBA）
 * - 支持音视频同步、Seek、旋转角度和宽高比获取
 */

#ifndef FFMPEGDEMO_VIDEODECODER_H
#define FFMPEGDEMO_VIDEODECODER_H

#include <jni.h>
#include <functional>
#include <string>
#include "BaseDecoder.h"

extern "C" {
#include "../vendor/ffmpeg/libavcodec/mediacodec.h"
#include "../vendor/ffmpeg/libswscale/swscale.h"
#include "../vendor/ffmpeg/libavutil/imgutils.h"
#include "../vendor/ffmpeg/libavutil/display.h"
}

class VideoDecoder: public BaseDecoder {

public:
    VideoDecoder(int index, AVFormatContext *ftx);
    ~VideoDecoder();

    int getWidth() const;                    ///< 获取视频宽度
    int getHeight() const;                   ///< 获取视频高度
    void setSurface(jobject surface);        ///< 设置硬件解码 Surface

    virtual double getDuration() override;
    virtual bool prepare() override;         ///< 准备解码器（含硬件加速配置）
    virtual int decode(AVPacket *packet) override;  ///< 解码视频包
    virtual void avSync(AVFrame *frame) override;   ///< 音视频同步
    virtual int seek(double pos) override;
    virtual void release() override;

    int64_t getTimestamp() const;             ///< 获取当前帧时间戳（ms）
    int getRotate();                         ///< 获取视频旋转角度
    AVRational getDisplayAspectRatio();      ///< 获取显示宽高比（DAR）

private:
    int mWidth = -1;                         ///< 视频宽度
    int mHeight = -1;                        ///< 视频高度

    int RETRY_RECEIVE_COUNT = 7;             ///< EOF 重试接收最大次数

    int64_t mStartTimeMsForSync = -1;        ///< 音视频同步起始时间（ms）
    int64_t mCurTimeStampMs = 0;             ///< 当前帧时间戳（ms）

    int64_t mSeekPos = INT64_MAX;            ///< Seek 目标位置（时间基单位）
    int64_t mSeekStartTimeMs = -1;           ///< Seek 开始时间（用于计算 Seek 耗时）
    int64_t mSeekEndTimeMs = -1;             ///< Seek 结束时间

    jobject mSurface = nullptr;              ///< Android Surface 对象（硬件解码渲染目标）

    AVBufferRef *mHwDeviceCtx = nullptr;     ///< 硬件设备上下文
    const AVCodec *mVideoCodec = nullptr;    ///< 视频解码器
    AVMediaCodecContext *mMediaCodecContext = nullptr; ///< MediaCodec 上下文
    SwsContext *mSwsContext = nullptr;        ///< 图像格式转换上下文（libswscale）

    void updateTimestamp(AVFrame *frame);     ///< 更新帧时间戳
    int swsScale(AVFrame *srcFrame, AVFrame *swFrame); ///< 像素格式转换
};


#endif //FFMPEGDEMO_VIDEODECODER_H
