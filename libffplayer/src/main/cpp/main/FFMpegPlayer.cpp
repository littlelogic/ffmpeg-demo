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

FFMpegPlayer::FFMpegPlayer() {
    LOGI("FFMpegPlayer")
    mMutexObj = std::make_shared<MutexObj>();
}

FFMpegPlayer::~FFMpegPlayer() {
    mJvm = nullptr;
    mPlayerJni.reset();
    LOGI("~FFMpegPlayer")
}

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
    updatePlayerState(PlayerState::START);
    if (mReadPacketThread == nullptr) {
        mReadPacketThread = new std::thread(&FFMpegPlayer::ReadPacketLoop, this);
    }
}

/**
 * @brief 恢复播放
 * @details 恢复状态为 PLAYING，修复时间戳并唤醒等待的线程
 */
void FFMpegPlayer::resume() {
    updatePlayerState(PlayerState::PLAYING);
    if(mVideoDecoder) {
        mVideoDecoder->needFixStartTime();
    }
    if (mAudioDecoder) {
        mAudioDecoder->needFixStartTime();
    }
    mMutexObj->wakeUp();
}

/**
 * @brief 暂停播放
 */
void FFMpegPlayer::pause() {
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
    mVideoSeekPos = -1;
    mAudioSeekPos = -1;
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

            // check mIsSeek: play complete -> seek -> play
            if (mIsSeek && mVideoSeekPos < 0 && mAudioSeekPos < 0) {
                mIsSeek = false;
                LOGW("FFMpegPlayer::ReadPacketLoop, reset seek status")
            }
            continue;
        }

        // check is seek
        bool isSeeking = false;
        while (mVideoSeekPos >= 0 || mAudioSeekPos >= 0) {
            isSeeking = true;
            LOGI("seek wait...mVideoSeekPos: %f, mAudioSeekPos: %f", mVideoSeekPos, mAudioSeekPos)
            mMutexObj->wait();
        }
        if (isSeeking) {
            mIsSeek = false;
            LOGW("FFMpegPlayer::ReadPacketLoop, seek prepare has ready")
        }

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

/**
 * @brief 读取一个 AVPacket 并分发到对应队列
 * @details
 *   - 成功读取：根据 stream_index 推入视频或音频队列
 *   - 读取失败（EOF）：向两个队列发送 flush packet（size=0, data=nullptr）
 * @return 成功返回 0，EOF 或错误返回 -1
 */
int FFMpegPlayer::readAvPacketToQueue() {
    AVPacket *avPacket = av_packet_alloc();
    int ret = av_read_frame(mFtx, avPacket);
    bool suc = false;
    if (ret == 0) {
        if (mVideoDecoder && mVideoPacketQueue && avPacket->stream_index == mVideoDecoder->getStreamIndex()) {
            suc = pushPacketToQueue(avPacket, mVideoPacketQueue);
        } else if (mAudioDecoder && mAudioPacketQueue && avPacket->stream_index == mAudioDecoder->getStreamIndex()) {
            suc = pushPacketToQueue(avPacket, mAudioPacketQueue);
        }
    } else {
        // send flush packet
        AVPacket *videoFlushPkt = av_packet_alloc();
        videoFlushPkt->size = 0;
        videoFlushPkt->data = nullptr;
        if (!pushPacketToQueue(videoFlushPkt, mVideoPacketQueue)) {
            av_packet_free(&videoFlushPkt);
            av_freep(&videoFlushPkt);
        }

        AVPacket *audioFlushPkt = av_packet_alloc();
        audioFlushPkt->size = 0;
        audioFlushPkt->data = nullptr;
        if (!pushPacketToQueue(audioFlushPkt, mAudioPacketQueue)) {
            av_packet_free(&audioFlushPkt);
            av_freep(&audioFlushPkt);
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

/**
 * @brief 将 AVPacket 推入指定队列
 * @details 如果队列已满，阻塞等待 10ms 直到有空间
 *          如果正在 Seek，丢弃 packet
 */
bool FFMpegPlayer::pushPacketToQueue(AVPacket *packet, const std::shared_ptr<AVPacketQueue>& queue) const {
    if (queue == nullptr) {
        return false;
    }

    bool suc = false;
    while (queue->isFull()) {
        queue->wait(10);
        LOGD("queue is full, wait 10ms, packet index: %d", packet->stream_index)
    }
    if (!mIsSeek) {
        queue->push(packet);
        suc = true;
    } else {
        LOGE("discard packet for seek, packet index: %d", packet->stream_index)
    }
    return suc;
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
        if (!mHasAbort && mVideoDecoder) {
            if (mAudioDecoder) {
                auto diff = mAudioDecoder->getTimestamp() - mVideoDecoder->getTimestamp();
                LOGW("[video] frame arrived, AV time diff: %" PRId64, diff)
            }
            AVFrame *finalFrame = nullptr;
            if (mGridFilter != nullptr) {
                finalFrame = mGridFilter->process(frame);
            }
            if (finalFrame == nullptr) {
                finalFrame = frame;
            }
            mVideoDecoder->avSync(frame);
            doRender(env, finalFrame);
            if (!mAudioDecoder && mPlayerJni.isValid()) { // no audio track
                double timestamp = mVideoDecoder->getTimestamp();
                env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onPlayProgress, timestamp);
            }
        } else {
            LOGE("[video] setOnFrameArrived, has abort")
        }
    });

    while (true) {
        if (mVideoSeekPos >= 0) {
            mVideoPacketQueue->clear();
            mVideoDecoder->seek(mVideoSeekPos);
            mVideoSeekPos = -1;
            LOGI("clear video queue via seek")
            mMutexObj->wakeUp();
        }

        while (!mHasAbort && mVideoPacketQueue->isEmpty() && mVideoSeekPos < 0) {
            LOGE("[video] no packet, wait...")
            mVideoPacketQueue->wait();
        }

        if (mHasAbort) {
            LOGE("[video] has abort...")
            break;
        }

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
                    if (!mAudioDecoder) { // 存在音轨以音频播放结束为准
                        onPlayCompleted(env);
                    }
                }
            } else {
                LOGE("VideoDecodeLoop pop packet failed...")
            }
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
        if (mAudioSeekPos >= 0) {
            mAudioPacketQueue->clear();
            mAudioDecoder->seek(mAudioSeekPos);
            mAudioSeekPos = -1;
            LOGI("clear audio queue via seek")
            mMutexObj->wakeUp();
        }

        while (!mHasAbort && mAudioPacketQueue->isEmpty() && mAudioSeekPos < 0) {
             LOGE("[audio] no packet, wait...")
             mAudioPacketQueue->wait();
        }

        if (mHasAbort) {
            LOGE("[audio] has abort...")
            break;
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
                    onPlayCompleted(env);
                }
            } else {
                LOGE("AudioDecodeLoop pop packet failed...")
            }
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
 * @brief Seek 到指定位置
 * @details Seek 协调流程：
 *   1. 设置 mIsSeek 标志
 *   2. 设置视频/音频 Seek 目标位置
 *   3. 清空视频/音频包队列
 *   4. 视频/音频解码线程检测到 Seek 位置后执行实际 Seek
 *   5. Seek 完成后唤醒读包线程继续读包
 */
bool FFMpegPlayer::seek(double timeS) {
    LOGI("seek to: %f, player state: %d", timeS, mPlayerState)
    mIsSeek = true;
    if (mVideoPacketQueue != nullptr) {
        mVideoSeekPos = timeS;
        mVideoPacketQueue->clear();
    }
    if (mAudioPacketQueue != nullptr) {
        mAudioSeekPos = timeS;
        mAudioPacketQueue->clear();
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
    if (mPlayerJni.isValid()) {
        env->CallVoidMethod(mPlayerJni.instance, mPlayerJni.onPlayCompleted);
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
