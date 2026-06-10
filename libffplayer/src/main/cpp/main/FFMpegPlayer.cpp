/**
 * @file FFMpegPlayer.cpp
 * @brief 播放器核心引擎实现
 *
 * 核心架构：三线程模型（生产者-消费者模式）
 *   - ReadPacketThread:  读包线程（生产者）→ 读取 AVPacket 分发到队列
 *   - VideoDecodeThread: 视频解码线程（消费者）→ 解码、同步、渲染
 *   - AudioDecodeThread: 音频解码线程（消费者）→ 解码、重采样、同步
 *
 * 播放流程:
 *   init() → prepare() → start() → [playing] ⇄ [pause] → stop()
 */

#include "FFMpegPlayer.h"
#include "../vendor/nlohmann/json.hpp"
#include "header/CommonUtils.h"
#include <unistd.h>

FFMpegPlayer::FFMpegPlayer() {
    LOGI("FFMpegPlayer")
    mMutexObj = std::make_shared<MutexObj>();
    mMediaClock = std::make_shared<MediaClock>();
}

FFMpegPlayer::~FFMpegPlayer() {
    mJvm = nullptr;
    mPlayerJni.reset();
    LOGI("~FFMpegPlayer")
}

namespace {

/** UI 秒数 → 流 time_base 下的 seek 时间戳（含 start_time，并尽量对齐索引关键帧） */
int64_t buildStreamSeekTimestamp(AVFormatContext *ftx, int streamIdx, double timeS) {
    AVStream *st = ftx->streams[streamIdx];
    int64_t seekTs = av_rescale_q((int64_t)(timeS * AV_TIME_BASE), AV_TIME_BASE_Q, st->time_base);
    if (st->start_time != AV_NOPTS_VALUE) {
        seekTs += st->start_time;
    }
    const AVIndexEntry *entry = avformat_index_get_entry_from_timestamp(
            st, seekTs, AVSEEK_FLAG_BACKWARD);
    if (entry != nullptr && entry->timestamp != AV_NOPTS_VALUE) {
        int64_t indexedTs = entry->timestamp;
        LOGI("[seek] index keyframe ts=%" PRId64 " (wanted=%" PRId64 ")", indexedTs, seekTs);
        seekTs = indexedTs;
    }
    return seekTs;
}

}  // namespace

/**
 * @brief 初始化 JNI 回调上下文
 * @details 获取 Java 层 FFPlayer 对象的方法 ID，后续用于 Native → Java 回调
 */
void FFMpegPlayer::init(JNIEnv *env, jobject thiz) {
    jclass jclazz = env->GetObjectClass(thiz);
    if (jclazz == nullptr) {
        return;
    }

    mPlayerJni.reset();
    mPlayerJni.instance = env->NewGlobalRef(thiz);
    mPlayerJni.onVideoPrepared = env->GetMethodID(jclazz, "onNative_videoTrackPrepared", "(IID)V");
    mPlayerJni.onVideoFrameArrived = env->GetMethodID(jclazz, "onNative_videoFrameArrived", "(III[B[B[B)V");

    mPlayerJni.onAudioPrepared = env->GetMethodID(jclazz, "onNative_audioTrackPrepared", "()V");
    mPlayerJni.onAudioFrameArrived = env->GetMethodID(jclazz, "onNative_audioFrameArrived", "([BIZ)V");
    mPlayerJni.onPlayCompleted = env->GetMethodID(jclazz, "onNative_playComplete", "()V");
    mPlayerJni.onPlayProgress = env->GetMethodID(jclazz, "onNative_playProgress", "(D)V");
}

/**
 * @brief 准备播放器
 * @details 完整的准备流程：
 *   1. 注册 JVM 到 FFmpeg（用于 MediaCodec 硬件解码）
 *   2. 分配 AVFormatContext 并打开媒体文件
 *   3. 查找视频/音频流索引
 *   4. 初始化视频解码器（优先硬件加速，失败则降级软解）
 *   5. 初始化音频解码器（含重采样器）
 *   6. 创建视频/音频解码线程
 *   7. 回调 Java 层通知准备完成
 */
bool FFMpegPlayer::prepare(JNIEnv *env, std::string &path, jobject surface) {
    mPath = path;
    // step0: 注册 JVM 到 FFmpeg，使 MediaCodec 硬件解码可用
    if (mJvm == nullptr) {
        env->GetJavaVM(&mJvm);
    }
    // not call, use NDKMediaCodec on ffmpeg6.0,
    av_jni_set_java_vm(mJvm, nullptr);

    // step1: alloc format context
    mFtx = avformat_alloc_context();

    // step2: open input file
    int ret = avformat_open_input(&mFtx, path.c_str(), nullptr, nullptr);
    if (ret < 0) {
        LOGE("avformat_open_input failed, ret: %d, err: %s", ret, av_err2str(ret))
        return false;
    }

    // step3: find video stream index
    avformat_find_stream_info(mFtx, nullptr);
    int videoIndex = -1;
    int audioIndex = -1;
    for (int i = 0; i < mFtx->nb_streams; i++) {
        if (mFtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoIndex = i;
        } else if (mFtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioIndex = i;
        }
    }

    mHasAbort = false;
    {
        std::lock_guard<std::mutex> lk(mEofMutex);
        mVideoStreamEnded = false;
        mAudioStreamEnded = false;
    }

    mPlayCompletedNotified.store(false);
    bool videoPrepared = false;
    // step4: prepare video decoder
    if (videoIndex >= 0) {
        LOGI("select video stream, index: %d", videoIndex)
        mVideoDecoder = std::make_shared<VideoDecoder>(videoIndex, mFtx);
        mVideoPacketQueue = std::make_shared<AVPacketQueue>(50);
        mVideoThread = new std::thread(&FFMpegPlayer::VideoDecodeLoop, this);
        mVideoDecoder->setErrorMsgListener([](int err, std::string &msg) {
            LOGE("[video] err code: %d, msg: %s", err, msg.c_str())
        });

        mVideoDecoder->setSurface(surface);
        videoPrepared = mVideoDecoder->prepare();
        bool isHwDecoder = surface != nullptr;
        if (surface != nullptr && !videoPrepared) {
            mVideoDecoder->release();
            LOGE("[video] hw decoder prepare failed, fallback to software decoder")
            mVideoDecoder->setSurface(nullptr);
            videoPrepared = mVideoDecoder->prepare();
            isHwDecoder = false;
        }

        if (!isHwDecoder && mEnableGridFilter) {
            mGridFilter = std::make_unique<FFFilter>();
            std::string graphInArgs;
            std::string filterDesc;
            FFFilter::createGridFilterDesc(mVideoDecoder->getCodecContext(), mVideoDecoder->getTimeBase(), graphInArgs, filterDesc);
            if (!mGridFilter->init(graphInArgs, filterDesc)) {
                mGridFilter = nullptr;
            }
        }

        if (mPlayerJni.isValid()) {
            AVRational dar = mVideoDecoder->getDisplayAspectRatio();
            double ratio = dar.num / (double)dar.den;
            env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onVideoPrepared, mVideoDecoder->getWidth(), mVideoDecoder->getHeight(), ratio);
        }
    }

    bool audioPrePared = false;
    // prepare audio decoder
    if (audioIndex >= 0) {
        LOGI("select audio stream, index: %d", audioIndex)
        mAudioDecoder = std::make_shared<AudioDecoder>(audioIndex, mFtx);
        audioPrePared = mAudioDecoder->prepare();
        if (audioPrePared) {
            mAudioPacketQueue = std::make_shared<AVPacketQueue>(50);
            mAudioThread = new std::thread(&FFMpegPlayer::AudioDecodeLoop, this);
            mAudioDecoder->setErrorMsgListener([](int err, std::string &msg) {
                LOGE("[audio] err code: %d, msg: %s", err, msg.c_str())
            });
            if (mPlayerJni.isValid()) {
                env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onAudioPrepared);
            }
        } else {
            mAudioDecoder = nullptr;
            mAudioPacketQueue = nullptr;
            mAudioThread = nullptr;
            LOGE("audio track prepared failed!!!")
        }
    }
    bool prepared = videoPrepared || audioPrePared;
    LOGI("videoPrepared: %d, audioPrePared: %d, path: %s", videoPrepared, audioPrePared, path.c_str())

    if (prepared) {
        // ====== 配置 MediaClock：归一化 stream start_time、确定 SyncType、注入解码器 ======
        mMediaClock->reset();

        // [&] 是Lambda表达式的捕获子句，表示以引用捕获
        auto streamStartMs = [&](int idx) -> int64_t {
            if (idx < 0 || idx >= (int) mFtx->nb_streams) return 0;
            AVStream *st = mFtx->streams[idx];
            if (st->start_time == AV_NOPTS_VALUE) return 0;
            return (int64_t)(st->start_time * av_q2d(st->time_base) * 1000);
        };

        int64_t videoStartMs = videoPrepared ? streamStartMs(videoIndex) : INT64_MAX;
        int64_t audioStartMs = audioPrePared ? streamStartMs(audioIndex) : INT64_MAX;
        int64_t mediaStartMs = std::min(videoStartMs, audioStartMs);
        if (mediaStartMs == INT64_MAX) mediaStartMs = 0;

        if (videoPrepared && mVideoDecoder) {
            mVideoDecoder->setStreamStartPtsMs(mediaStartMs);
            mVideoDecoder->setMediaClock(mMediaClock);
        }
        if (audioPrePared && mAudioDecoder) {
            mAudioDecoder->setStreamStartPtsMs(mediaStartMs);
            mAudioDecoder->setMediaClock(mMediaClock);
        }

        // 选择主时钟类型：有可用音轨则 AUDIO_MASTER；否则 VIDEO_MASTER；都没有则 EXTERNAL。
        if (audioPrePared) {
            mMediaClock->setSyncType(MediaClock::SyncType::AUDIO_MASTER);
        } else if (videoPrepared) {
            mMediaClock->setSyncType(MediaClock::SyncType::VIDEO_MASTER);
        } else {
            // 理论上不会到这里：外层 if (prepared) 保证至少一路就绪。
            // 留作防御：万一以后控制流被改动，至少能日志告警而不是 nowMs 一直返回 0。
            mMediaClock->setSyncType(MediaClock::SyncType::EXTERNAL);
        }
        mMediaClock->setStartPtsMs(mediaStartMs);
        // 不在此处启动主时钟：reset 后 mAnchorSysMs<0，nowMs 恒为 0，
        // 真正"起跑"在 start() 中调用 seekTo(0) 完成，避免 prepare→start 之间空跑造成首帧丢帧。
        LOGI("MediaClock configured: syncType=%d, mediaStartMs=%" PRId64, (int) mMediaClock->getSyncType(), mediaStartMs)

        updatePlayerState(PlayerState::PREPARE);
    }

    return prepared;
}

/**
 * @brief 开始播放
 * @details 创建并启动读包线程 ReadPacketThread
 */
void FFMpegPlayer::start() {
    LOGI("FFMpegPlayer::start, state: %d", mPlayerState)
    if (mPlayerState != PlayerState::PREPARE) {  // prepared failed
        return;
    }
    // 这里才真正"起跑"主时钟：把锚点设到 (mediaTime=0, sys=now)，nowMs 开始随系统时间外推。
    if (mMediaClock) {
        mMediaClock->seekTo(0);
    }
    updatePlayerState(PlayerState::START);
    if (mReadPacketThread == nullptr) {
        mReadPacketThread = new std::thread(&FFMpegPlayer::ReadPacketLoop, this);
    }
}

/**
 * @brief 恢复播放
 * @details 恢复主时钟（把锚点平移到 now，保证 nowMs 连续），状态置为 PLAYING，唤醒等待线程。
 */
void FFMpegPlayer::resume() {
    if (mMediaClock) {
        mMediaClock->resume();
    }
    updatePlayerState(PlayerState::PLAYING);
    // 兼容兜底：万一某个解码器仍走 legacy 路径，让它修复一次本地锚点
    if (mVideoDecoder) {
        mVideoDecoder->needFixStartTime();
    }
    if (mAudioDecoder) {
        mAudioDecoder->needFixStartTime();
    }
    mMutexObj->wakeUp();
}

/**
 * @brief 暂停播放
 * @details 冻结主时钟，状态置为 PAUSE。冻结期间 MediaClock::nowMs() 保持不变，
 *          因此暂停时长不会被计入"经过时间"，恢复后画面/声音不会跳一段。
 */
void FFMpegPlayer::pause() {
    if (mMediaClock) {
        mMediaClock->pause();
    }
    updatePlayerState(PlayerState::PAUSE);
}

/**
 * @brief 停止播放
 * @details 完整的停止流程：
 *   1. 设置状态为 STOP，唤醒所有等待线程
 *   2. 等待读包线程结束 (join)
 *   3. 清空视频队列，等待视频解码线程结束
 *   4. 清空音频队列，等待音频解码线程结束
 *   5. 释放 AVFormatContext
 */
void FFMpegPlayer::stop() {
    LOGI("FFMpegPlayer::stop")
    // wakeup read packet thread and release it
    updatePlayerState(PlayerState::STOP);
    mMutexObj->wakeUp();
    if (mReadPacketThread != nullptr) {
        LOGE("join read thread")
        mReadPacketThread->join();
        delete mReadPacketThread;
        mReadPacketThread = nullptr;
        LOGE("release read thread")
    }

    mHasAbort = true;
    mIsMute = false;
    mIsSeek = false;
    mVideoSeekPos.store(kSeekPosUnset);
    mAudioSeekPos.store(kSeekPosUnset);
    {
        std::lock_guard<std::mutex> lk(mWillSeekMutex);
        mWillSeekPointsList.clear();
    }
    {
        std::lock_guard<std::mutex> lk(mEofMutex);
        mVideoStreamEnded = false;
        mAudioStreamEnded = false;
    }
    mPlayCompletedNotified.store(false);
    mPath = "";

    // release video res
    if (mVideoThread != nullptr) {
        LOGE("join video thread")
        if (mVideoPacketQueue) {
            mVideoPacketQueue->clear();
        }
        mVideoThread->join();
        delete mVideoThread;
        mVideoThread = nullptr;
    }
    mVideoDecoder = nullptr;
    LOGE("release video res")

    // release audio res
    if (mAudioThread != nullptr) {
        LOGE("join audio thread")
        if (mAudioPacketQueue) {
            mAudioPacketQueue->clear();
        }
        mAudioThread->join();
        delete mAudioThread;
        mAudioThread = nullptr;
    }
    mAudioDecoder = nullptr;
    LOGE("release audio res")

    if (mFtx != nullptr) {
        avformat_close_input(&mFtx);
        avformat_free_context(mFtx);
        mFtx = nullptr;
        LOGI("format context...release")
    }

    if (mMediaClock) {
        mMediaClock->reset();
    }
}

/**
 * @brief 读包线程主循环
 * @details 工作流程：
 *   - 循环调用 av_read_frame() 读取 AVPacket
 *   - 根据流索引将 packet 分发到视频/音频队列
 *   - 处理暂停（wait）和 Seek（等待 Seek 完成）
 *   - 读到文件末尾时发送 flush packet 通知解码器
 */
void FFMpegPlayer::ReadPacketLoop() {
    LOGI("FFMpegPlayer::ReadPacketLoop start")
    while (mPlayerState != PlayerState::STOP) {
        if (mPlayerState == PlayerState::PAUSE) {
            LOGW("FFMpegPlayer::ReadPacketLoop wait")
            mMutexObj->wait();
            LOGW("FFMpegPlayer::ReadPacketLoop wakeup")
            // double check stop state
            // or check pause state for pause & seek

            // PAUSE 下 seek 只入 willSeekPointsList；wakeUp 后消费队列并预取一帧视频预览（无声）。
            drainWillSeekPointsList();
            prefetchPauseVideoFrame();
            continue;
        }

        // 每轮读包前先消费 seek 队列（取最新、合并连击 seek）。
        drainWillSeekPointsList();

        // read packet to queue
        updatePlayerState(PlayerState::PLAYING);
        bool isEnd = readAvPacketToQueue() != 0;
        if (isEnd) {
            LOGW("read av packet end, mPlayerState: %d", mPlayerState)
            if (mPlayerState == PlayerState::PLAYING) {
                updatePlayerState(PlayerState::PAUSE);
            }
        }
    }
    LOGI("FFMpegPlayer::ReadPacketLoop end")
}

double FFMpegPlayer::takeLatestSeekPoint() {
    std::lock_guard<std::mutex> lk222(mWillSeekMutex);
    if (mWillSeekPointsList.empty()) {
        return kSeekPosUnset;
    }
    double latest = mWillSeekPointsList.back();
    mWillSeekPointsList.clear();
    return latest;
}

void FFMpegPlayer::drainWillSeekPointsList() {
    double timeS = takeLatestSeekPoint();
    if (timeS < 0) {
        return;
    }
    LOGW("FFMpegPlayer::drainWillSeekPointsList apply latest=%f", timeS)
    applySeekInternal(timeS);
    // 每轮只处理一次 seek，本轮继续读包；若 apply 期间 UI 又入队，下轮再 drain。
}

void FFMpegPlayer::applySeekInternal(double timeS) {
    // 播放状态下 seek 时临时冻结时钟，减少音频线程对 MediaClock 的锁竞争，
    // 并跳过等待 audio flush，与 PAUSE 下 seek 等效。seek 完成后恢复时钟。
    bool wasPlaying = (mPlayerState == PlayerState::PLAYING);
    if (wasPlaying && mMediaClock) {
        mMediaClock->pause();
    }

    // seek 之后两条流都重新开始消费 packet，先把 EOF 标志清掉。
    {
        std::lock_guard<std::mutex> lk(mEofMutex);
        mVideoStreamEnded = false;
        mAudioStreamEnded = false;
    }
    mPlayCompletedNotified.store(false);

    if (mVideoDecoder != nullptr) {
        mVideoDecoder->cancelPrecisionSeekPending("FFMpegPlayer::applySeekInternal-1");
    }

    if (mVideoPacketQueue != nullptr) {
        // 注意顺序：先 clear 掉旧包，再设置 seekPos，最后 notify 解码线程（可能正在等包），让它尽快感知到 seek 事件并刷新状态。
        mVideoPacketQueue->clear();
        mVideoSeekPos.store(timeS);
        mVideoPacketQueue->notify();
    }
    if (mAudioPacketQueue != nullptr) {
        mAudioPacketQueue->clear();
        // 仍通知音频解码线程 flush；不等待 audio flush 完成，由音频线程异步处理。
        mAudioSeekPos.store(timeS);
        mAudioPacketQueue->notify();
    }

    if (mMediaClock) {
        if (mAudioDecoder) {
            mMediaClock->setSyncType(MediaClock::SyncType::AUDIO_MASTER);
        } else if (mVideoDecoder) {
            mMediaClock->setSyncType(MediaClock::SyncType::VIDEO_MASTER);
        }
        mMediaClock->seekTo((int64_t)(timeS * 1000));
    }

    int refStreamIdx = -1;
    AVRational refTb = AV_TIME_BASE_Q;
    if (mVideoDecoder != nullptr) {
        refStreamIdx = mVideoDecoder->getStreamIndex();
        refTb = mVideoDecoder->getTimeBase();
    } else if (mAudioDecoder != nullptr) {
        refStreamIdx = mAudioDecoder->getStreamIndex();
        refTb = mAudioDecoder->getTimeBase();
    }

    if (refStreamIdx >= 0 && mFtx != nullptr) {
        int64_t seekTs = buildStreamSeekTimestamp(mFtx, refStreamIdx, timeS);
        int sret = avformat_seek_file(mFtx, refStreamIdx,
                                      INT64_MIN, seekTs, INT64_MAX,
                                      AVSEEK_FLAG_BACKWARD);
        if (sret < 0) {
            int64_t globalTs = (int64_t)(timeS * AV_TIME_BASE);
            AVStream *st = mFtx->streams[refStreamIdx];
            if (st->start_time != AV_NOPTS_VALUE) {
                globalTs += av_rescale_q(st->start_time, st->time_base, AV_TIME_BASE_Q);
            }
            sret = av_seek_frame(mFtx, refStreamIdx, globalTs, AVSEEK_FLAG_BACKWARD);
            LOGW("[seek] avformat_seek_file failed, av_seek_frame ret=%d globalTs=%" PRId64,
                 sret, globalTs);
        }
        LOGI("260527seek [seek] demuxer seek done: timeS=%f refStream=%d ts=%" PRId64 " ret=%d",
             timeS, refStreamIdx, seekTs, sret)
    } else {
        LOGE("[seek] skip avformat_seek_file: timeS=%f refIdx=%d mFtx=%p",
             timeS, refStreamIdx, mFtx)
    }

    // 只等待 video decoder 完成 flush，audio 由其自身线程异步完成。
    int waitRounds = 0;
    static const int kSeekFlushMaxRounds = 400;// 约 2s（5ms * 400），避免读包线程永久卡死
    while (true) {
        bool videoPending = mVideoDecoder != nullptr && mVideoSeekPos.load() >= 0;
        if (!videoPending) {
            break;
        }
        if (++waitRounds > kSeekFlushMaxRounds) {
            LOGE("[seek] flush wait timeout, force clear seek flags timeS=%f videoSeek=%f audioSeek=%f",
                 timeS, mVideoSeekPos.load(), mAudioSeekPos.load())
            mVideoSeekPos.store(kSeekPosUnset);
            mAudioSeekPos.store(kSeekPosUnset);
            if (mVideoDecoder != nullptr) {
                mVideoDecoder->cancelPrecisionSeekPending("FFMpegPlayer::applySeekInternal-2");
            }
            if (mVideoPacketQueue != nullptr) {
                mVideoPacketQueue->notify();
            }
            if (mAudioPacketQueue != nullptr) {
                mAudioPacketQueue->notify();
            }
            break;
        }
        if (waitRounds % 20 == 1) {
            LOGI("seek wait for decoder flush...videoSeekPos: %f, audioSeekPos: %f",
                 mVideoSeekPos.load(), mAudioSeekPos.load())
        }
        if (mVideoPacketQueue != nullptr) {
            mVideoPacketQueue->notify();
        }
        if (mAudioPacketQueue != nullptr) {
            mAudioPacketQueue->notify();
        }
        mMutexObj->wakeUp();
        usleep(5000);
    }

    // 播放状态下恢复时钟，重置锚点确保音视频从 seek 目标位置干净启动。
    if (wasPlaying && mMediaClock) {
        mMediaClock->resume();
        if (mVideoDecoder) {
            mVideoDecoder->needFixStartTime();
        }
        if (mAudioDecoder) {
            mAudioDecoder->needFixStartTime();
        }
    }

    LOGW("FFMpegPlayer::applySeekInternal done, timeS=%f wasPlaying=%d", timeS, wasPlaying)
}

void FFMpegPlayer::prefetchPauseVideoFrame() {
    if (mPlayerState != PlayerState::PAUSE || mVideoDecoder == nullptr || mVideoPacketQueue == nullptr) {
        return;
    }
    mPauseVideoPreviewRendered.store(false);

    static const int MaxCount = 100;
    int num = 0;
    static const int kMaxPackets = 1000;
    int packetsRead = 0;
    for (; ; packetsRead++) {
        if (packetsRead < kMaxPackets) {
            if (mPlayerState != PlayerState::PAUSE || mHasAbort) {
                break;
            }
            if (mPauseVideoPreviewRendered.load()) {
                LOGI("[pause-preview] video frame rendered, packets read=%d", packetsRead)
                break;
            }
            if (readAvPacketToQueue(true) != 0) {
                LOGW("[pause-preview] read end or error at packet %d", packetsRead)
                break;
            }
        } else {
            /// 亦可以根据时间阈值。来判断是否要跳过后续包，避免死循环。
            if (mPlayerState == PlayerState::PAUSE) {
                if (num++ >= MaxCount) {
                    break;
                }
                packetsRead = 0;
            }
        }
    }


    if (!mPauseVideoPreviewRendered.load()) {
        LOGW("[pause-preview] num:%d packetsRead:%d, no frame rendered after %d packets, cancel precision gate"
             , num,packetsRead,packetsRead)
        if (mVideoDecoder != nullptr) {
            mVideoDecoder->cancelPrecisionSeekPending("prefetchPauseVideoFrame");
        }
    }
}

/**
 * @brief 读取一个 AVPacket 并分发到对应队列
 * @details
 *   - 成功读取：根据 stream_index 推入视频或音频队列
 *   - 读取失败（EOF）：向两个队列发送 flush packet（size=0, data=nullptr）
 * @return 成功返回 0，EOF 或错误返回 -1
 */
int FFMpegPlayer::readAvPacketToQueue(bool videoOnly) {
    AVPacket *avPacket = av_packet_alloc();
    int ret = av_read_frame(mFtx, avPacket);
    bool suc = false;
    if (ret == 0) {
        if (mVideoDecoder && mVideoPacketQueue && avPacket->stream_index == mVideoDecoder->getStreamIndex()) {
            suc = pushPacketToQueue(avPacket, mVideoPacketQueue);
        } else if (!videoOnly && mAudioDecoder && mAudioPacketQueue
                   && avPacket->stream_index == mAudioDecoder->getStreamIndex()) {
            suc = pushPacketToQueue(avPacket, mAudioPacketQueue);
        } else if (videoOnly) {
            // PAUSE 预览：丢弃音频及其它流 packet，避免写入 AudioTrack
            av_packet_free(&avPacket);
            av_freep(&avPacket);
            return 0;
        }
    } else {
        if (ret == AVERROR_EOF) {
            // 文件正常结束 → 发送 flush
            LOGI("EOF reached");
        } else if (ret == AVERROR(EAGAIN)) {
            // 临时错误（通常是网络），不中止，继续读
            LOGW("Temporary error (EAGAIN), will retry");
            //todo 不发送 flush，继续下一轮读取
        } else {
            // 其他致命错误 → 中止
            LOGE("Fatal error: %s", av_err2str(ret));
            // send flush packet
            ret = -1;
        }

        // 无论什么错误，都发送 flush packet
        // 文件读取到末尾（EOF）时，send flush packet
        if (mVideoPacketQueue) {
            AVPacket *videoFlushPkt = av_packet_alloc();
            videoFlushPkt->size = 0;
            videoFlushPkt->data = nullptr;
            if (!pushPacketToQueue(videoFlushPkt, mVideoPacketQueue)) {
                av_packet_free(&videoFlushPkt);
                av_freep(&videoFlushPkt);
            }
        }

        if (!videoOnly && mAudioPacketQueue) {
            AVPacket *audioFlushPkt = av_packet_alloc();
            audioFlushPkt->size = 0;
            audioFlushPkt->data = nullptr;
            if (!pushPacketToQueue(audioFlushPkt, mAudioPacketQueue)) {
                av_packet_free(&audioFlushPkt);
                av_freep(&audioFlushPkt);
            }
        }
        LOGE("read packet...end or failed: %d", ret)
        ret = -1;
    }

    if (!suc) {
        LOGI("av_read_frame, other...pts: %" PRId64 ", index: %d", avPacket->pts, avPacket->stream_index)
        av_packet_free(&avPacket);
        av_freep(&avPacket);
    }
    return ret;
}


bool FFMpegPlayer::haveNewSeekPoint() const {
    std::lock_guard<std::mutex> lk222(mWillSeekMutex);
    if (mWillSeekPointsList.empty()) {
        return false;
    }
    return true;
}

/**
 * @brief 将 AVPacket 推入指定队列
 * @details 如果队列已满，阻塞等待 10ms 直到有空间
 */
bool FFMpegPlayer::pushPacketToQueue(AVPacket *packet, const std::shared_ptr<AVPacketQueue>& queue) const {
    if (queue == nullptr) {
        return false;
    }

    while (queue->isFull()) {
        if (mPlayerState == PlayerState::PAUSE) {
            if (mPauseVideoPreviewRendered.load()) {
                {
                    if (haveNewSeekPoint()) {
                        return true;
                    }
                }
            }
        }
        queue->wait(10);
        LOGD("queue is full, wait 10ms, packet index: %d", packet->stream_index)
    }
    queue->push(packet);
    return true;
}

/**
 * @brief 视频解码线程主循环
 * @details 工作流程：
 *   1. AttachCurrentThread（非主线程需要 attach 到 JVM）
 *   2. 设置帧到达回调（解码帧 → 滤镜处理 → 音视频同步 → 渲染）
 *   3. 循环从队列取包并解码：
 *      - 检测 Seek 请求：清空队列、执行 Seek、唤醒读包线程
 *      - 等待新 packet 到达
 *      - 解码并处理 EAGAIN 重发
 *      - EOF 时触发播放完成
 *   4. DetachCurrentThread
 */
void FFMpegPlayer::VideoDecodeLoop() {
    if (mVideoDecoder == nullptr || mVideoPacketQueue == nullptr) {
        return;
    }

    JNIEnv *env = nullptr;
    if (mJvm->GetEnv((void **)&env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        mJvm->AttachCurrentThread(&env, nullptr);
        LOGE("[video] AttachCurrentThread")
    }

    mVideoDecoder->setOnFrameArrived([this, env](AVFrame *frame) {
        if (mHasAbort || !mVideoDecoder) {
            LOGE("[video] setOnFrameArrived, has abort")
            return;
        }

        // ====== 同步：统一交给 VideoDecoder::avSync(基于 MediaClock) ======
        bool shouldRender = mVideoDecoder->avSync(frame);

        if (!shouldRender) {
            // 落后过多 → 丢帧。MediaCodec 路径必须显式归还输出 buffer，否则会耗尽。
            if (frame->format == AV_PIX_FMT_MEDIACODEC) {
                av_mediacodec_release_buffer((AVMediaCodecBuffer *) frame->data[3], 0);
            }
            return;
        }

        // ====== 滤镜处理 ======
        AVFrame *finalFrame = nullptr;
        if (mGridFilter != nullptr) {
            finalFrame = mGridFilter->process(frame);
        }
        if (finalFrame == nullptr) {
            finalFrame = frame;
        }

        doRender(env, finalFrame);
        mVideoDecoder->onPrecisionSeekFrameDisplayed();

        if (mPlayerState == PlayerState::PAUSE) {
            mPauseVideoPreviewRendered.store(true);
            // 目标帧已上屏，丢弃预取阶段队列里剩余的 packet，避免再闪中间帧
            if (mVideoPacketQueue != nullptr) {
//                mVideoPacketQueue->clear();
            }
        }

        // 无音轨时，进度由视频驱动回调
        if (!mAudioDecoder && mPlayerJni.isValid()) {
            double timestamp = mVideoDecoder->getTimestamp();
            env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onPlayProgress, timestamp);
        }
    });

    while (true) {
        if (mHasAbort) {
            LOGE("[video] has abort...")
            break;
        }

//        LOGE("260527seek VideoDecodeLoop 11")
        if (mVideoSeekPos.load() >= 0) {
//            mVideoPacketQueue->clear();
            mVideoDecoder->seek(mVideoSeekPos.load());
            mVideoSeekPos.store(kSeekPosUnset);
            LOGI("clear video queue via seek")
            mMutexObj->wakeUp();
            continue;
        }

//        LOGE("260527seek VideoDecodeLoop 21")
        if (mVideoPacketQueue->isEmpty()) {
            mVideoPacketQueue->wait(10);
            continue;
        }


        if (mPlayerState == PlayerState::PAUSE) {
            if (mPauseVideoPreviewRendered.load()) {
                if (mVideoPacketQueue != nullptr) {
//                    mVideoPacketQueue->clear();
                }
                continue;
            }
        }
        LOGE("260527seek VideoDecodeLoop 31")
        AVPacket *packet = av_packet_alloc();
        if (packet != nullptr) {
            int ret = mVideoPacketQueue->popTo(packet);
            if (ret == 0) {
                do {
                    ret = mVideoDecoder->decode(packet);
                } while (mVideoDecoder->isNeedResent());

                av_packet_unref(packet);
                av_packet_free(&packet);
                if (ret == AVERROR_EOF) {
                    LOGE("VideoDecodeLoop AVERROR_EOF")
                    handleStreamEof(/*isVideo=*/true, env);
                }
            } else {
                LOGE("VideoDecodeLoop pop packet failed...")
            }
            av_packet_free(&packet);
        }
    }

    mVideoPacketQueue->clear();
    mVideoPacketQueue = nullptr;

    mJvm->DetachCurrentThread();
    LOGE("[video] DetachCurrentThread");
}

/**
 * @brief 音频解码线程主循环
 * @details 与视频解码线程类似，但：
 *   - 不做滤镜处理
 *   - 播放完成以音频 EOF 为准（有音轨时）
 *   - 回调播放进度
 */
void FFMpegPlayer::AudioDecodeLoop() {
    if (mAudioDecoder == nullptr || mAudioPacketQueue == nullptr) {
        return;
    }

    JNIEnv *env = nullptr;
    if (mJvm->GetEnv((void **)&env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        mJvm->AttachCurrentThread(&env, nullptr);
        LOGE("[audio] AttachCurrentThread")
    }

    mAudioDecoder->setOnFrameArrived([this, env](AVFrame *frame) {
        if (!mHasAbort && mAudioDecoder) {
            // PAUSE 预览只刷视频，不向 AudioTrack 送 PCM
            if (mPlayerState == PlayerState::PAUSE) {
                return;
            }
            mAudioDecoder->avSync(frame);
            doRender(env, frame);
            if (mPlayerJni.isValid()) {
                double timestamp = mAudioDecoder->getTimestamp();
                env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onPlayProgress, timestamp);
            }
        } else {
            LOGE("[audio] setOnFrameArrived, has abort")
        }
    });

    while (true) {
        if (mHasAbort) {
            LOGE("[audio] has abort...")
            break;
        }

        if (mAudioSeekPos.load() >= 0) {
            mAudioPacketQueue->clear();
            mAudioDecoder->seek(mAudioSeekPos.load());
            mAudioSeekPos.store(kSeekPosUnset);
            LOGI("clear audio queue via seek")
            mMutexObj->wakeUp();
            continue;
        }

        if (mAudioPacketQueue->isEmpty()) {
            mAudioPacketQueue->wait(10);
            continue;
        }

        AVPacket *packet = av_packet_alloc();
        if (packet != nullptr) {
            int ret = mAudioPacketQueue->popTo(packet);
            if (ret == 0) {
                do {
                    ret = mAudioDecoder->decode(packet);
                } while (mAudioDecoder->isNeedResent());

                av_packet_unref(packet);
                av_packet_free(&packet);
                if (ret == AVERROR_EOF) {
                    LOGE("AudioDecodeLoop AVERROR_EOF")
                    handleStreamEof(/*isVideo=*/false, env);
                }
            } else {
                LOGE("AudioDecodeLoop pop packet failed...")
            }
            av_packet_free(&packet);
        }
    }

    mAudioPacketQueue->clear();
    mAudioPacketQueue = nullptr;

    mJvm->DetachCurrentThread();
    LOGE("[audio] DetachCurrentThread");
}

/**
 * @brief 处理行对齐的数据拷贝
 * @details AVFrame 的 linesize 可能大于实际宽度（行对齐），需要逐行拷贝去除 padding
 * @param component 目标 Java 字节数组
 * @param width 实际宽度
 * @param height 行数
 * @param lineStride AVFrame 的行字节数（可能含 padding）
 * @param pixelStride 每像素字节数
 * @param src 源数据指针
 */
void FFMpegPlayer::checkStrideAndFill(JNIEnv *env, jbyteArray *component, int width, int height,
                                      int lineStride, int pixelStride, uint8_t *src) {
    int lineLength = width * pixelStride;
    if (lineLength >= lineStride) {
        env->SetByteArrayRegion(*component, 0, lineLength * height, reinterpret_cast<const jbyte *>(src));
    } else {
        uint8_t *pSrc = src;
        for (int i = 0; i < height; i++) {
            env->SetByteArrayRegion(*component, lineLength * i, lineLength, reinterpret_cast<const jbyte *>(pSrc));
            pSrc += lineStride;
        }
    }
}

/**
 * @brief 渲染帧数据
 * @details 根据 AVFrame 的像素格式进行不同处理：
 *
 *   YUV420P    → 分离 Y/U/V 三个平面 → 回调 Java（FMT_VIDEO_YUV420）
 *   NV12       → 分离 Y/UV 两个平面  → 回调 Java（FMT_VIDEO_NV12）
 *   RGBA       → 直接传递 RGBA 数据   → 回调 Java（FMT_VIDEO_RGBA）
 *   RGB24      → 直接传递 RGB 数据    → 回调 Java（FMT_VIDEO_RGB）
 *   MEDIACODEC → av_mediacodec_release_buffer() → 直接渲染到 Surface
 *   FLTP(音频) → 重采样后的 PCM 数据  → 回调 Java
 */
void FFMpegPlayer::doRender(JNIEnv *env, AVFrame *avFrame) {
    if (avFrame->format == AV_PIX_FMT_YUV420P) {
        if (!avFrame->data[0] || !avFrame->data[1] || !avFrame->data[2]) {
            LOGE("doRender failed, no yuv buffer")
            return;
        }

        int ySize = avFrame->width * avFrame->height;
        auto y = env->NewByteArray(ySize);
        checkStrideAndFill(env, &y, avFrame->width, avFrame->height, avFrame->linesize[0], 1, avFrame->data[0]);

        auto u = env->NewByteArray(ySize / 4);
        checkStrideAndFill(env, &u, avFrame->width / 2, avFrame->height / 2, avFrame->linesize[1], 1, avFrame->data[1]);

        auto v = env->NewByteArray(ySize / 4);
        checkStrideAndFill(env, &v, avFrame->width / 2, avFrame->height / 2, avFrame->linesize[2], 1, avFrame->data[2]);

        if (mPlayerJni.isValid()) {
            env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onVideoFrameArrived,
                                avFrame->width, avFrame->height, FMT_VIDEO_YUV420, y, u, v);
        }

        env->DeleteLocalRef(y);
        env->DeleteLocalRef(u);
        env->DeleteLocalRef(v);
    } else if (avFrame->format == AV_PIX_FMT_NV12) {
        if (!avFrame->data[0] || !avFrame->data[1]) {
            LOGE("doRender failed, no nv21 buffer")
            return;
        }

        int ySize = avFrame->width * avFrame->height;
        auto y = env->NewByteArray(ySize);
        checkStrideAndFill(env, &y, avFrame->width, avFrame->height, avFrame->linesize[0], 1, avFrame->data[0]);

        auto uv = env->NewByteArray(ySize / 2);
        checkStrideAndFill(env, &uv, avFrame->width / 2, avFrame->height / 2, avFrame->linesize[1], 2, avFrame->data[1]);

        if (mPlayerJni.isValid()) {
            env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onVideoFrameArrived,
                                avFrame->width, avFrame->height, FMT_VIDEO_NV12, y, uv, nullptr);
        }

        env->DeleteLocalRef(y);
        env->DeleteLocalRef(uv);
    } else if (avFrame->format == AV_PIX_FMT_RGBA) {
        if (!avFrame->data[0]) {
            LOGE("doRender failed, no rgba buffer")
            return;
        }

        int size = avFrame->width * avFrame->height * 4;
        auto rgba = env->NewByteArray(size);
        checkStrideAndFill(env, &rgba, avFrame->width, avFrame->height, avFrame->linesize[0], 4, avFrame->data[0]);

        if (mPlayerJni.isValid()) {
            env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onVideoFrameArrived,
                                avFrame->width, avFrame->height, FMT_VIDEO_RGBA, rgba, nullptr, nullptr);
        }

        env->DeleteLocalRef(rgba);
    } else if (avFrame->format == AV_PIX_FMT_RGB24) {
        if (!avFrame->data[0]) {
            LOGE("doRender failed, no rgb24 buffer")
            return;
        }

        int size = avFrame->width * avFrame->height * 3;
        auto rgb24 = env->NewByteArray(size);
        checkStrideAndFill(env, &rgb24, avFrame->width, avFrame->height, avFrame->linesize[0], 3, avFrame->data[0]);

        if (mPlayerJni.isValid()) {
            env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onVideoFrameArrived,
                                avFrame->width, avFrame->height, FMT_VIDEO_RGB, rgb24, nullptr, nullptr);
        }

        env->DeleteLocalRef(rgb24);
    } else if (avFrame->format == AV_PIX_FMT_MEDIACODEC) {
        /// 传 1 是“显示这帧”；如果传 0，通常是仅释放不显示（你代码里也有注释掉的 0 版本）
        /// release = 归还解码器输出槽位；render=1 = 同时提交给 Surface 显示
        int result =  av_mediacodec_release_buffer((AVMediaCodecBuffer *)avFrame->data[3], 1);
//        int result =  av_mediacodec_release_buffer((AVMediaCodecBuffer *)avFrame->data[3], 0);
        LOGI("[video] 2592p2w3 av_mediacodec_release_buffer result:%d",result)
    } else if (avFrame->format == AV_SAMPLE_FMT_FLTP) {
        int dataSize = mAudioDecoder->mDataSize;
        bool flushRender = mAudioDecoder->mNeedFlushRender;
        if (dataSize > 0) {
            uint8_t *audioBuffer = mAudioDecoder->mAudioBuffer;
            if (mIsMute) {
                memset(audioBuffer, 0, dataSize);
            }
            auto jByteArray = env->NewByteArray(dataSize);
            env->SetByteArrayRegion(jByteArray, 0, dataSize, reinterpret_cast<const jbyte *>(audioBuffer));

            if (mPlayerJni.isValid()) {
                env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onAudioFrameArrived, jByteArray, dataSize, flushRender);
            }
            env->DeleteLocalRef(jByteArray);
        }
    }
}

void FFMpegPlayer::setMute(bool mute) {
    mIsMute = mute;
}

double FFMpegPlayer::getDuration() {
    if (mAudioDecoder != nullptr) {
        return mAudioDecoder->getDuration();
    }

    if (mVideoDecoder != nullptr) {
        return mVideoDecoder->getDuration();
    }
    return 0;
}

/**
 * @brief Seek 到指定位置（仅入队，由 ReadPacketLoop 消费）
 * @details UI/JNI 只写入 willSeekPointsList 并唤醒读包线程；
 *          demuxer seek、清队列、主时钟等在 drainWillSeekPointsList → applySeekInternal 中执行。
 */
bool FFMpegPlayer::seek(double timeS) {
    LOGI("260527seek enqueue seek: %f, player state: %d", timeS, mPlayerState)
    {
        std::lock_guard<std::mutex> lk(mWillSeekMutex);
        // 高频拖动只保留最新目标，避免列表无限增长。
        if (mWillSeekPointsList.empty()) {
            mWillSeekPointsList.push_back(timeS);
        } else {
            mWillSeekPointsList.back() = timeS;
        }
    }
    if (mMutexObj) {
        mMutexObj->wakeUp();
    }
    return true;
}

/**
 * @brief 更新播放器状态
 * @details 状态变化时打印日志
 */
void FFMpegPlayer::updatePlayerState(PlayerState state) {
    if (mPlayerState != state) {
        LOGI("updatePlayerState from %d to %d", mPlayerState, state);
        mPlayerState = state;
    }
}

int FFMpegPlayer::getRotate() {
    if (mVideoDecoder) {
        return mVideoDecoder->getRotate();
    }
    return 0;
}

void FFMpegPlayer::onPlayCompleted(JNIEnv *env) {
    // 用 atomic compare-exchange 保证只回调一次。
    bool expected = false;
    /*
        原子地比较 mPlayCompletedNotified 的当前值与 expected 是否相等：
        如果相等：将 mPlayCompletedNotified 设置为 true，并返回 true。
        如果不相等：将 expected 更新为 mPlayCompletedNotified 的当前值，并返回 false
     */
    if (!mPlayCompletedNotified.compare_exchange_strong(expected, true)) {
        return;
    }
    if (mPlayerJni.isValid()) {
        env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onPlayCompleted);
    }
}

/**
 * @brief 流级别 EOF 处理
 * @details 在 mEofMutex 保护下设置自身 EOF 标志，并在两路（或仅有的那路）都结束时
 *          调用 onPlayCompleted。
 *          - 解决了"两条 decode 线程几乎同时 EOF"在 SMP 上的写写/读读乱序导致的双方都漏发或重复发问题。
 *          - 对于音频流结束，额外触发 MediaClock::onAudioEnded() 把主时钟降级到 EXTERNAL，
 *            让视频继续按系统时钟外推播完队列里剩余的帧。
 */
void FFMpegPlayer::handleStreamEof(bool isVideo, JNIEnv *env) {
    bool shouldComplete = false;
    {
        std::lock_guard<std::mutex> lk(mEofMutex);
        if (isVideo) {
            mVideoStreamEnded = true;
        } else {
            mAudioStreamEnded = true;
            // 音频结束：把主时钟降级，避免视频侧依赖音频推动 clock。
            if (mMediaClock) {
                mMediaClock->onAudioEnded();
            }
        }
        bool videoDone = (!mVideoDecoder) || mVideoStreamEnded;
        bool audioDone = (!mAudioDecoder) || mAudioStreamEnded;
        shouldComplete = videoDone && audioDone;
    }
    if (shouldComplete) {
        onPlayCompleted(env); // 内部会用 atomic 去重
    }
}

/**
 * @brief 获取媒体信息
 * @details 返回 JSON 格式的媒体元数据，包含：
 *   - path: 文件路径
 *   - video: 视频信息（宽高、编码、时长、旋转角度、宽高比等）
 *   - audio: 音频信息（采样率、声道、编码等）
 */
void FFMpegPlayer::getMediaInfo(std::string &info) {
    nlohmann::json j;
    j["path"] = mPath;
    if (mVideoDecoder) {
        j["video"] = mVideoDecoder->getMediaInfo();
    }
    if (mAudioDecoder) {
        j["audio"] = mAudioDecoder->getMediaInfo();
    }
    info = j.dump();
}
