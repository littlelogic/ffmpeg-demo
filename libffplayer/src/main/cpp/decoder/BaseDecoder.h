/**
 * @file BaseDecoder.h
 * @brief 解码器基类
 *
 * 功能说明：
 * - 定义解码器的通用接口（prepare/decode/seek/release/avSync）
 * - 管理公共属性：格式上下文、编码上下文、时间基、时长
 * - 提供错误回调和帧到达回调机制
 * - VideoDecoder 和 AudioDecoder 继承此类
 */

#ifndef FFMPEGDEMO_BASEDECODER_H
#define FFMPEGDEMO_BASEDECODER_H

#include <functional>
#include <string>
#include "../vendor/nlohmann/json.hpp"

extern "C" {
#include "../vendor/ffmpeg//libavcodec/avcodec.h"
#include "../vendor/ffmpeg/libavformat/avformat.h"
#include "../vendor/ffmpeg/libavutil/time.h"
}

#define DELAY_THRESHOLD 100 ///< 音视频同步最大延迟阈值（100ms）

class BaseDecoder {

public:
    BaseDecoder(int index, AVFormatContext *ftx);
    virtual ~BaseDecoder();

    virtual double getDuration();          ///< 获取时长（秒）
    virtual bool prepare();                ///< 准备解码器
    virtual int decode(AVPacket *packet);  ///< 解码一个包
    virtual void avSync(AVFrame *frame);   ///< 音视频同步
    virtual int seek(double pos);          ///< Seek 到指定位置（秒）
    virtual void flush();                  ///< 清空解码缓冲
    virtual void release();                ///< 释放资源

    AVCodecContext *getCodecContext();      ///< 获取编码上下文
    AVRational getTimeBase();              ///< 获取时间基
    bool isNeedResent() const;             ///< 是否需要重发 packet
    int getStreamIndex() const;            ///< 获取流索引
    std::string getMediaInfo();            ///< 获取媒体信息（JSON）
    void needFixStartTime();               ///< 标记需要修复同步时间

    void setErrorMsgListener(std::function<void(int, std::string &)> listener);  ///< 设置错误回调
    void setOnFrameArrived(std::function<void(AVFrame *)> listener);             ///< 设置帧到达回调

protected:
    AVFormatContext *mFtx = nullptr;       ///< 格式化上下文
    AVCodecContext *mCodecContext = nullptr; ///< 编码上下文
    AVRational mTimeBase{};                ///< 时间基（用于 PTS → 秒 的转换）
    double mDuration = 0;                  ///< 流时长（秒）
    AVFrame *mAvFrame = nullptr;           ///< 解码帧缓冲（复用）

    std::function<void(int, std::string &)> mErrorMsgListener = nullptr;   ///< 错误消息回调
    std::function<void(AVFrame *frame)> mOnFrameArrivedListener = nullptr; ///< 帧到达回调

    bool mNeedResent = false;              ///< 是否需要重发 packet（EAGAIN）
    int64_t mRetryReceiveCount = 7;        ///< EOF 重试接收计数（RETRY_RECEIVE_COUNT）
    bool mFixStartTime = false;            ///< 是否需要修复同步起始时间（Seek 后）
    nlohmann::json mMediaInfoJson;         ///< 媒体信息 JSON 对象

private:
    int mStreamIndex = -1;                 ///< 流索引
};

#endif //FFMPEGDEMO_BASEDECODER_H
