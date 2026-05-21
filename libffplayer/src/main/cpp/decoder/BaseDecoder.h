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
#include <memory>
#include <string>
#include "../vendor/nlohmann/json.hpp"

extern "C" {
#include "../vendor/ffmpeg//libavcodec/avcodec.h"
#include "../vendor/ffmpeg/libavformat/avformat.h"
#include "../vendor/ffmpeg/libavutil/time.h"
}

class MediaClock;

// 同步控制阈值（毫秒），均为相对 MediaClock 的"媒体时间"差值。
#define AV_SYNC_FORWARD_NOSLEEP_MS  5    ///< 视频领先 ≤5ms：直接渲染，不 sleep
#define AV_SYNC_FORWARD_MAX_SLEEP_MS 1000 ///< 单次 sleep 上限，避免异常 PTS 导致死等
#define AV_SYNC_DROP_MS             100  ///< 视频落后超过该值：丢帧
#define AV_SYNC_AUDIO_PACE_CAP_MS   500  ///< 音频节拍单次 sleep 兜底上限

class BaseDecoder {

public:
    BaseDecoder(int index, AVFormatContext *ftx);
    virtual ~BaseDecoder();

    virtual double getDuration();          ///< 获取时长（秒）
    virtual bool prepare();                ///< 准备解码器
    virtual int decode(AVPacket *packet);  ///< 解码一个包
    /**
     * 音视频同步入口。
     * @return true  -> 调用方应继续渲染该帧
     *         false -> 调用方应丢弃该帧（视频落后过多时使用）
     */
    virtual bool avSync(AVFrame *frame);
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

    /// 注入主时钟（FFMpegPlayer 在 prepare 时调用）
    void setMediaClock(std::shared_ptr<MediaClock> clock);

    /// 设置流的起始 PTS（毫秒），用于把不同流的时间戳统一到"媒体起点为 0"的坐标系。
    void setStreamStartPtsMs(int64_t ms);
    int64_t getStreamStartPtsMs() const;

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

    std::shared_ptr<MediaClock> mMediaClock = nullptr; ///< 主时钟（由 FFMpegPlayer 注入）
    int64_t mStreamStartPtsMs = 0;         ///< 当前流的起始 PTS（用于跨流归一化）

    /** 流 time_base 下的 PTS（best_effort / pts / dts），用于精确 seek 比较 */
    static int64_t framePtsInStreamTb(const AVFrame *frame);

    /** 将帧 PTS 转为毫秒（无有效 PTS 返回 AV_NOPTS_VALUE） */
    int64_t framePtsMs(const AVFrame *frame) const;

    /** 与 MediaClock 对齐的归一化媒体时间（ms） */
    int64_t frameNormPtsMs(const AVFrame *frame) const;

    /// 精确 seek 目标（归一化 ms，-1 表示未激活）；播放/暂停 seek 后首帧须 >= 此值才输出
    int64_t mPrecisionSeekTargetNormMs = -1;

    /** 精确 seek 期间是否应丢弃该帧（无 PTS 也丢弃） */
    bool shouldDropForPrecisionSeek(const AVFrame *frame) const;

    /** updateTimestamp 之后调用：normPts 为归一化媒体 ms，达标则结束精确 seek 状态 */
    void markPrecisionSeekCompleteIfReached(int64_t normPts);

private:
    int mStreamIndex = -1;                 ///< 流索引
};

#endif //FFMPEGDEMO_BASEDECODER_H
