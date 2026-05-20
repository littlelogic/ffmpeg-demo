/**
 * @file AudioDecoder.cpp
 * @brief 音频解码器实现
 *
 * 解码流程：
 *   1. prepare(): 查找解码器 → 初始化上下文 → 配置重采样器
 *   2. decode():  发送 packet → 接收 frame → 重采样 → 回调
 *   3. 重采样输出: 统一为 44100Hz / 立体声 / S16 格式
 */

#include "AudioDecoder.h"
#include "header/Logger.h"
#include "header/CommonUtils.h"
#include "../reader/FFMediaProbe.h"
#include "../main/MediaClock.h"

AudioDecoder::AudioDecoder(int index, AVFormatContext *ftx): BaseDecoder(index, ftx) {
    LOGI("AudioDecoder")
}

AudioDecoder::~AudioDecoder() {
    LOGI("~AudioDecoder")
    release();
}

/**
 * @brief 准备音频解码器
 * @details 流程：
 *   1. 查找音频解码器
 *   2. 初始化编码上下文
 *   3. 打开解码器
 *   4. 配置音频重采样器（SwrContext）
 *      - 输出: 44100Hz, 立体声(Stereo), S16 格式
 *      - 输入: 原始采样率, 原始声道布局, 原始格式
 */
bool AudioDecoder::prepare() {
    AVCodecParameters *params = mFtx->streams[getStreamIndex()]->codecpar;

    mAudioCodec = avcodec_find_decoder(params->codec_id);
    if (mAudioCodec == nullptr) {
        std::string msg = "[audio] not find audio decoder";
        if (mErrorMsgListener) {
            mErrorMsgListener(-1000, msg);
        }
        return false;
    }

    // init codec context
    mCodecContext = avcodec_alloc_context3(mAudioCodec);
    if (!mCodecContext) {
        std::string msg = "[audio] codec context alloc failed";
        if (mErrorMsgListener) {
            mErrorMsgListener(-2000, msg);
        }
        return false;
    }
    avcodec_parameters_to_context(mCodecContext, params);

    // open codec
    mCodecContext->flags2 |= AV_CODEC_FLAG2_SKIP_MANUAL;
    int ret = avcodec_open2(mCodecContext, mAudioCodec, nullptr);
    if (ret != 0) {
        std::string msg = "[audio] codec open failed";
        if (mErrorMsgListener) {
            mErrorMsgListener(-3000, msg);
        }
        return false;
    }

    mAvFrame = av_frame_alloc();

    mSwrContext = swr_alloc_set_opts(
            nullptr,
            AV_CH_LAYOUT_STEREO,
            AV_SAMPLE_FMT_S16,
            44100,

            (int64_t)mCodecContext->channel_layout,
            mCodecContext->sample_fmt,
            mCodecContext->sample_rate,
            0,
            nullptr
            );
    ret = swr_init(mSwrContext);

    LOGI("[audio] prepare, sample rate: %d, channels: %d, channel_layout: %" PRId64 ", fmt: %d, swr_init: %d",
         mCodecContext->sample_rate, mCodecContext->channels, mCodecContext->channel_layout, mCodecContext->sample_fmt, ret);

    mStartTimeMsForSync = -1;

    mMediaInfoJson.clear();
    if (false) {
        mMediaInfoJson["sample_rate"] = mCodecContext->sample_rate;
        mMediaInfoJson["sample_fmt"] = av_get_sample_fmt_name(mCodecContext->sample_fmt);
        mMediaInfoJson["channel"] = mCodecContext->ch_layout.nb_channels;
        mMediaInfoJson["codec_name"] = mAudioCodec->name;
    }
    ff_fill_audio_media_info_json(mMediaInfoJson, params, mCodecContext, mAudioCodec);

    return ret == 0;
}

/**
 * @brief 解码音频数据包
 * @details 流程：
 *   1. avcodec_send_packet: 发送编码包
 *   2. avcodec_receive_frame: 接收解码帧
 *   3. resample: 重采样为统一格式
 *   4. updateTimestamp: 更新时间戳
 *   5. 回调 OnFrameArrived
 */
int AudioDecoder::decode(AVPacket *avPacket) {
    int64_t start = getCurrentTimeMs();
    int sendRes = avcodec_send_packet(mCodecContext, avPacket);
    int index = av_index_search_timestamp(mFtx->streams[getStreamIndex()], avPacket->pts, AVSEEK_FLAG_BACKWARD);
    int64_t sendPoint = getCurrentTimeMs() - start;
    LOGI("[audio] avcodec_send_packet...pts: %" PRId64 ", res: %d, index: %d", avPacket->pts, sendRes, index)

    // avcodec_send_packet的-11表示要先读output，然后pkt需要重发
    mNeedResent = sendRes == AVERROR(EAGAIN);

    int receiveRes = AVERROR_EOF;
    int receiveCount = 0;
    do {
        start = getCurrentTimeMs();
        // avcodec_receive_frame的-11，表示需要发新帧
        receiveRes = avcodec_receive_frame(mCodecContext, mAvFrame);
        if (receiveRes != 0) {
            LOGE("[audio] avcodec_receive_frame err: %d, resent: %d", receiveRes, mNeedResent)
            av_frame_unref(mAvFrame);
            break;
        }

        int64_t receivePoint = getCurrentTimeMs() - start;
        auto ptsMs = mAvFrame->pts * av_q2d(mFtx->streams[getStreamIndex()]->time_base) * 1000;
        LOGI("[audio] avcodec_receive_frame...pts: %" PRId64 ", time: %f, need retry: %d", mAvFrame->pts, ptsMs, mNeedResent)

        int nb = resample(mAvFrame);

        updateTimestamp(mAvFrame);

        int out_channels = av_get_channel_layout_nb_channels(AV_CH_LAYOUT_STEREO);

        if (nb > 0) {
            mDataSize = nb * out_channels * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16);
            if (mOnFrameArrivedListener != nullptr) {
                mOnFrameArrivedListener(mAvFrame);
            }
        }
        mDataSize = 0;
        mNeedFlushRender = false;
        LOGI("swr_convert, dataSize: %d, nb: %d, out_channels: %d", mDataSize, nb, out_channels)

        av_frame_unref(mAvFrame);
        receiveCount++;

        LOGW("[audio] decode sendPoint: %" PRId64 ", receivePoint: %" PRId64 ", receiveCount: %d", sendPoint, receivePoint, receiveCount)
    } while (true);
    return receiveRes;
}

/**
 * @brief 音频重采样
 * @details 将解码帧从原始格式转换为 44100Hz / 立体声 / S16 格式
 * @param frame 解码后的音频帧
 * @return 输出的采样数，失败返回负值
 */
int AudioDecoder::resample(AVFrame *frame) {
    int out_nb = (int) av_rescale_rnd(frame->nb_samples, 44100, frame->sample_rate, AV_ROUND_UP);
    int out_channels = av_get_channel_layout_nb_channels(AV_CH_LAYOUT_STEREO);
    int size = av_samples_get_buffer_size(nullptr, out_channels, out_nb, AV_SAMPLE_FMT_S16, 1);
    if (mAudioBuffer == nullptr) {
        LOGI("audio frame, out_channels: %d, out_nb: %d, size: %d, ", out_channels, out_nb, size)
        mAudioBuffer = (uint8_t *) av_malloc(size);
    }

    int nb = swr_convert(
            mSwrContext,
            &mAudioBuffer,
            size / out_channels,
            (const uint8_t**)frame->data,
            frame->nb_samples
    );
    return nb;
}

void AudioDecoder::updateTimestamp(AVFrame *frame) {
    if (mStartTimeMsForSync < 0) {
        LOGE("update audio start time")
        mStartTimeMsForSync = getCurrentTimeMs();
    }

    // 优先使用 best_effort_timestamp，兼容个别容器没有 frame->pts 的情况
    int64_t pts = AV_NOPTS_VALUE;
    if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
        pts = frame->best_effort_timestamp;
    } else if (frame->pts != AV_NOPTS_VALUE) {
        pts = frame->pts;
    } else if (frame->pkt_dts != AV_NOPTS_VALUE) {
        pts = frame->pkt_dts;
    }

    if (pts != AV_NOPTS_VALUE) {
        mCurTimeStampMs = (int64_t)(pts * av_q2d(mTimeBase) * 1000);
    } else if (frame->pkt_duration > 0) {
        int64_t deltaMs = (int64_t)(frame->pkt_duration * av_q2d(mTimeBase) * 1000);
        mCurTimeStampMs += deltaMs;
    }

    // 兜底路径：无 MediaClock 时仍然修复本地系统锚点
    if (mFixStartTime) {
        int64_t normPts = mCurTimeStampMs - mStreamStartPtsMs;
        mStartTimeMsForSync = getCurrentTimeMs() - normPts;
        mFixStartTime = false;
        LOGE("fix audio start time (legacy fallback)")
    }
}

int64_t AudioDecoder::getTimestamp() const {
    return mCurTimeStampMs;
}

/**
 * @brief 音频同步
 *
 * 设计要点：
 *   - 音频通常不丢样本（耳朵很敏感），所以这里不会返回 false。
 *   - 当 SyncType == AUDIO_MASTER 时，把"即将写入 AudioTrack 的这一段 PCM 对应的 PTS"
 *     上报给 MediaClock，作为视频侧对齐的主时钟。
 *   - 同时仍保留一段本地系统时间节拍（与历史行为一致），目的是不要让解码侧
 *     用 NON_BLOCKING write 把 AudioTrack 缓冲冲爆。等后续把 AudioTrack 改成
 *     BLOCKING 写或独立 PCMQueue + RenderThread 时，可以删除此处的 sleep。
 */
bool AudioDecoder::avSync(AVFrame *frame) {
    int64_t normPts = mCurTimeStampMs - mStreamStartPtsMs;

    if (mMediaClock && mMediaClock->getSyncType() == MediaClock::SyncType::AUDIO_MASTER) {
        mMediaClock->updateAudioClock(normPts);
    } else if (mMediaClock) {
        // 即使不是主时钟，也更新一下"上次更新时间"用于 staleness 检测
        mMediaClock->updateAudioClock(normPts);
    }

    // 节拍：本地系统时间锚点（节流喂入，避免 AudioTrack 缓冲被瞬间冲爆）
    int64_t elapsedTimeMs = getCurrentTimeMs() - mStartTimeMsForSync;
    int64_t diff = normPts - elapsedTimeMs;
    LOGI("[audio] avSync, pts: %" PRId64 " ms, diff: %" PRId64 " ms", normPts, diff)
    if (diff > AV_SYNC_FORWARD_NOSLEEP_MS) {
        int64_t sleepMs = diff > AV_SYNC_AUDIO_PACE_CAP_MS ? AV_SYNC_AUDIO_PACE_CAP_MS : diff;
        av_usleep(sleepMs * 1000);
    }
    return true;
}

double AudioDecoder::getDuration() {
    return mDuration;
}

int AudioDecoder::seek(double pos) {
    // ★ demuxer 的 avformat_seek_file 已由 ReadPacketLoop 统一执行（见 VideoDecoder::seek 注释）。
    //   这里只做"解码器本地"的事：
    //     1. avcodec_flush_buffers 清空 codec 缓冲；
    //     2. mFixStartTime / mNeedFlushRender 用于本地锚点回退 & 通知 AudioTrack flush。
    flush();
    LOGE("[audio] decoder-side seek prep: pos=%f", pos)
    mFixStartTime = true;
    mNeedFlushRender = true;
    return 0;
}

void AudioDecoder::release() {
    mFixStartTime = false;
    mNeedFlushRender = false;
    if (mAudioBuffer != nullptr) {
        av_free(mAudioBuffer);
        mAudioBuffer = nullptr;
        LOGI("[audio] buffer...release")
    }

    if (mAvFrame != nullptr) {
        av_frame_free(&mAvFrame);
        av_freep(&mAvFrame);
        LOGI("[audio] av frame...release")
    }

    if (mSwrContext != nullptr) {
        swr_free(&mSwrContext);
        mSwrContext = nullptr;
        LOGI("[audio] sws context...release")
    }

    if (mCodecContext != nullptr) {
        avcodec_free_context(&mCodecContext);
        mCodecContext = nullptr;
        LOGI("[audio] codec...release")
    }
}

