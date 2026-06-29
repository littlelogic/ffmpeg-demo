/**
 * @file FFVideoReader.cpp
 * @brief 视频帧读取器实现
 *
 * 核心功能：
 * - getFrame(): 精准/快速抽帧，支持缩放和格式转换
 *   - YUV420P → ABGR（使用 libyuv，高性能）
 *   - 其他格式 → RGBA（使用 libswscale）
 * - getNextFrame(): 逐帧读取（用于 GIF 导出等场景）
 * - getRotate(): 获取视频旋转角度（从 metadata 或 displaymatrix）
 */

#include "FFVideoReader.h"
#include "header/Logger.h"
#include "header/CommonUtils.h"
#include <cassert>
#include "../vendor/libyuv/libyuv.h"

extern "C" {
#include "libavutil/display.h"
}

namespace {

constexpr double kPtsSecEpsilon = 1e-6;

/** 精准抽帧：当前帧是否达到目标时间（流 time_base 下的 pts） */
bool frameMatchesSeekTarget(const AVFrame *frame, int64_t targetPts, bool precise) {
    if (!precise) {
        return true;
    }
    if (frame->pts == AV_NOPTS_VALUE) {
        return false;
    }
    return frame->pts >= targetPts;
}

}  // namespace

FFVideoReader::FFVideoReader() {
    LOGI("FFVideoReader")
}

FFVideoReader::~FFVideoReader() {
    LOGI("~FFVideoReader")
    stopReadPacketThread(true);
    if (mSwsContext != nullptr) {
        sws_freeContext(mSwsContext);
        mSwsContext = nullptr;
    }

    mScaleBufferSize = -1;
    if (mScaleBuffer != nullptr) {
        free(mScaleBuffer);
        mScaleBuffer = nullptr;
    }

    if (mAvFrame != nullptr) {
        av_frame_free(&mAvFrame);
        av_free(mAvFrame);
        mAvFrame = nullptr;
    }
}

bool FFVideoReader::init(std::string &path) {
    mInit = FFReader::init(path);
    if (mInit) {
        mInit = selectTrack(Track_Video);
    }
    LOGI("[FFVideoReader], init: %d", mInit)
    return mInit;
}

void FFVideoReader::release() {
    stopReadPacketThread(true);
    FFReader::release();
    mLastPtsSec = -1.0;
}

void FFVideoReader::ensurePacketQueue() {
    if (mPacketQueue == nullptr) {
        mPacketQueue = std::make_shared<AVPacketQueue>(50);
    }
}

void FFVideoReader::startReadPacketThread() {
    ensurePacketQueue();
    if (mReadPacketThread != nullptr) {
        return;
    }
    mReadAbort.store(false);
    mReadEof.store(false);
    mReadPacketThread = new std::thread(&FFVideoReader::ReadPacketLoop, this);
}

void FFVideoReader::stopReadPacketThread(bool clearQueue) {
    mReadAbort.store(true);
    if (mPacketQueue != nullptr) {
        mPacketQueue->notify();
    }
    if (mReadPacketThread != nullptr) {
        mReadPacketThread->join();
        delete mReadPacketThread;
        mReadPacketThread = nullptr;
    }
    if (clearQueue && mPacketQueue != nullptr) {
        mPacketQueue->clear();
    }
    mReadEof.store(false);
}

void FFVideoReader::resetPacketPipeline(double ptsSec) {
    stopReadPacketThread(true);
    flush();
    seek(ptsSec);
    startReadPacketThread();
}

void FFVideoReader::ReadPacketLoop() {
    LOGI("[FFVideoReader], ReadPacketLoop start")
    while (!mReadAbort.load()) {
        int ret = readAvPacketToQueue();
        if (ret < 0) {
            break;
        }
    }
    LOGI("[FFVideoReader], ReadPacketLoop end")
}

int FFVideoReader::readAvPacketToQueue() {
    AVPacket *pkt = av_packet_alloc();
    if (pkt == nullptr) {
        return AVERROR(ENOMEM);
    }

    int ret = fetchAvPacket(pkt);
    if (ret == 0) {
        if (!pushPacketToQueue(pkt)) {
            av_packet_free(&pkt);
            return AVERROR_EXIT;
        }
        return 0;
    }

    av_packet_free(&pkt);
    if (!mReadAbort.load()) {
        AVPacket *flushPkt = av_packet_alloc();
        if (flushPkt != nullptr) {
            flushPkt->size = 0;
            flushPkt->data = nullptr;
            flushPkt->stream_index = getCurStreamIndex();
            if (!pushPacketToQueue(flushPkt)) {
                av_packet_free(&flushPkt);
            }
        }
        mReadEof.store(true);
    }
    return -1;
}

bool FFVideoReader::pushPacketToQueue(AVPacket *packet) {
    ensurePacketQueue();
    while (!mReadAbort.load() && mPacketQueue->isFull()) {
        mPacketQueue->wait(10);
    }
    if (mReadAbort.load()) {
        return false;
    }
    mPacketQueue->push(packet);
    return true;
}

int FFVideoReader::popPacketForDecode(AVPacket *packet) {
    startReadPacketThread();
    while (!mReadAbort.load()) {
        if (mPacketQueue != nullptr && !mPacketQueue->isEmpty()) {
            return mPacketQueue->popTo(packet);
        }
        if (mReadEof.load()) {
            return AVERROR_EOF;
        }
        if (mPacketQueue != nullptr) {
            mPacketQueue->wait(10);
        }
    }
    return AVERROR_EXIT;
}

void FFVideoReader::getFrame(double ptsSec, int width, int height, uint8_t *buffer, bool precise) {
    int64_t start = getCurrentTimeMs();
    LOGI("[FFVideoReader], getFrame ptsSec: %f, mLastPtsSec: %f, width: %d, height: %d",
         ptsSec, mLastPtsSec, width, height)
    if (mLastPtsSec < 0.0) {
        LOGI("[FFVideoReader], first seek")
        resetPacketPipeline(ptsSec);
    } else if (!precise) {
        // 非精准：首帧即命中，每次需重新定位
        LOGI("[FFVideoReader], imprecise flush & seek")
        resetPacketPipeline(ptsSec);
    } else {
        const int lastKeyIndex = getKeyFrameIndex(mLastPtsSec);
        const int targetKeyIndex = getKeyFrameIndex(ptsSec);

        bool canIncrementalDecodeInGop = false;
        if (mMediaInfo.frame_to_second > kPtsSecEpsilon) {
            if (lastKeyIndex == targetKeyIndex && ptsSec > (mLastPtsSec + mMediaInfo.frame_to_second * 2.6)) {
                canIncrementalDecodeInGop = true;
            }
        }

        if (canIncrementalDecodeInGop) {
            LOGI("[FFVideoReader], same GOP forward, loop decode only")
            startReadPacketThread();
        } else {
            LOGI("[FFVideoReader], flush & seek (backward/equal/new GOP)")
            resetPacketPipeline(ptsSec);
        }
    }
    mLastPtsSec = ptsSec;

    AVCodecContext *codecContext = getCodecContext();
    MediaInfo mediaInfo = getMediaInfo();
    LOGI("[FFVideoReader], getFrame, origin: %dx%d, dst: %dx%d", mediaInfo.width, mediaInfo.height, width, height)
    bool needScale = mediaInfo.width != width || mediaInfo.height != height;

    AVFrame *frame = av_frame_alloc();
    AVPacket *pkt = av_packet_alloc();

    int64_t target = streamSeekTargetTs(mFtx, getCurStreamIndex(), ptsSec);
    bool find = false;
    bool inputExhausted = false;
    int decodeCount = 0;

    // send/receive 异步：先 drain receive，再 send；一个 packet 可能对应多帧。
    while (!find && !inputExhausted) {
        for (;;) {
            int receiveRes = avcodec_receive_frame(codecContext, frame);
            if (receiveRes == 0) {
                decodeCount++;
                LOGD("[FFVideoReader], receive ok, pts=%" PRId64 " target=%" PRId64 " precise=%d",
                     frame->pts, target, precise)
                if (frameMatchesSeekTarget(frame, target, precise)) {
                    find = true;
                    break;
                }
                av_frame_unref(frame);
                continue;
            }
            if (receiveRes == AVERROR(EAGAIN)) {
                break;
            }
            if (receiveRes == AVERROR_EOF) {
                inputExhausted = true;
                break;
            }
            LOGE("[FFVideoReader], avcodec_receive_frame failed: %d", receiveRes)
            inputExhausted = true;
            break;
        }
        if (find || inputExhausted) {
            break;
        }

        int ret = popPacketForDecode(pkt);
        if (ret < 0) {
            LOGE("[FFVideoReader], popPacketForDecode failed: %d", ret)
            inputExhausted = true;
            break;
        }

        bool isEof = pkt->size == 0 && pkt->data == nullptr;
        int sendRes = avcodec_send_packet(codecContext, isEof ? nullptr : pkt);
        av_packet_unref(pkt);
        if (sendRes == AVERROR(EAGAIN)) {
            continue;
        }
        if (sendRes < 0 && sendRes != AVERROR_EOF) {
            LOGE("[FFVideoReader], avcodec_send_packet failed: %d", sendRes)
            inputExhausted = true;
            break;
        }
    }

    if (find) {
        LOGE("[FFVideoReader], get frame decode done, pts: %" PRId64 ", time: %f, format: %d, consume: %" PRId64 ", decodeCount: %d",
             frame->pts,
             (frame->pts * av_q2d(mediaInfo.video_time_base)),
             frame->format,
             (getCurrentTimeMs() - start), decodeCount);

        copyFrameToBuffer(frame, width, height, buffer);
        mLastPtsSec = ptsSec;
    } else {
        // 解码未命中时勿保留“可增量解码”假设，下次强制重新 seek
        mLastPtsSec = -1.0;
    }

    av_packet_unref(pkt);
    av_packet_free(&pkt);
    av_free(pkt);

    av_frame_unref(frame);
    av_frame_free(&frame);
    av_free(frame);
}

bool FFVideoReader::copyFrameToBuffer(AVFrame *frame, int width, int height, uint8_t *buffer) {
    if (frame == nullptr || buffer == nullptr || width <= 0 || height <= 0) {
        return false;
    }

    MediaInfo mediaInfo = getMediaInfo();
    bool needScale = frame->width != width || frame->height != height;

    if (frame->format == AV_PIX_FMT_NONE) {
        return false;
    } else if (frame->format == AV_PIX_FMT_YUV420P) {
        if (needScale) {
            int64_t scaleBufferSize = width * height * 3 / 2;
            if (mScaleBuffer && scaleBufferSize != mScaleBufferSize) {
                free(mScaleBuffer);
                mScaleBuffer = nullptr;
            }
            mScaleBufferSize = scaleBufferSize;
            if (mScaleBuffer == nullptr) {
                mScaleBuffer = (uint8_t *) malloc(scaleBufferSize);
            }
            if (mScaleBuffer == nullptr) {
                return false;
            }

            auto scaleBuffer = mScaleBuffer;
            libyuv::I420Scale(
                    frame->data[0], frame->linesize[0],
                    frame->data[1], frame->linesize[1],
                    frame->data[2], frame->linesize[2],
                    mediaInfo.width, mediaInfo.height,
                    scaleBuffer, width,
                    scaleBuffer + width * height, width / 2,
                    scaleBuffer + width * height * 5 / 4, width / 2,
                    width, height, libyuv::kFilterNone);

            libyuv::I420ToABGR(scaleBuffer, width,
                               scaleBuffer + width * height, width / 2,
                               scaleBuffer + width * height * 5 / 4, width / 2,
                               buffer, width * 4, width, height);
        } else {
            libyuv::I420ToABGR(frame->data[0], frame->linesize[0],
                               frame->data[1], frame->linesize[1],
                               frame->data[2], frame->linesize[2],
                               buffer, width * 4, width, height);
        }
        return true;
    }

    AVFrame *swFrame = av_frame_alloc();
    if (swFrame == nullptr) {
        return false;
    }
    unsigned int size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
    auto *rgbaBuffer = static_cast<uint8_t *>(av_malloc(size * sizeof(uint8_t)));
    if (rgbaBuffer == nullptr) {
        av_frame_free(&swFrame);
        return false;
    }
    av_image_fill_arrays(swFrame->data, swFrame->linesize, rgbaBuffer, AV_PIX_FMT_RGBA,
                         width, height, 1);

    SwsContext *swsContext = sws_getCachedContext(mSwsContext,
                                                  frame->width, frame->height,
                                                  AVPixelFormat(frame->format),
                                                  width, height, AV_PIX_FMT_RGBA,
                                                  SWS_BICUBIC, nullptr, nullptr, nullptr);
    if (swsContext == nullptr) {
        LOGE("[FFVideoReader], sws_getCachedContext failed")
        av_free(rgbaBuffer);
        av_frame_free(&swFrame);
        return false;
    }
    mSwsContext = swsContext;

    int ret = sws_scale(mSwsContext,
                        reinterpret_cast<const uint8_t *const *>(frame->data),
                        frame->linesize,
                        0,
                        frame->height,
                        swFrame->data,
                        swFrame->linesize);
    if (ret <= 0) {
        LOGE("[FFVideoReader], sws_scale failed, ret: %d", ret)
        av_free(rgbaBuffer);
        av_frame_free(&swFrame);
        return false;
    }

    uint8_t *src = swFrame->data[0];
    int srcStride = swFrame->linesize[0];
    int dstStride = width * 4;
    int copyStride = FFMIN(srcStride, dstStride);
    for (int i = 0; i < height; i++) {
        memcpy(buffer + i * dstStride, src + i * srcStride, copyStride);
    }

    av_free(rgbaBuffer);
    av_frame_free(&swFrame);
    return true;
}

double FFVideoReader::frameTimestampSec(AVFrame *frame) const {
    if (frame == nullptr || mFtx == nullptr || mMediaInfo.videoIndex < 0) {
        return -1.0;
    }
    int64_t pts = frame->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE) {
        pts = frame->pts;
    }
    if (pts == AV_NOPTS_VALUE) {
        pts = frame->pkt_dts;
    }
    if (pts == AV_NOPTS_VALUE) {
        return -1.0;
    }
    AVStream *stream = mFtx->streams[mMediaInfo.videoIndex];
    if (stream->start_time != AV_NOPTS_VALUE) {
        pts -= stream->start_time;
    }
    return pts * av_q2d(stream->time_base);
}


void FFVideoReader::getNextFrame(const std::function<void(AVFrame *)>& frameArrivedCallback) {
    if (mAvFrame == nullptr) {
        mAvFrame = av_frame_alloc();
    }
    startReadPacketThread();
    AVCodecContext *codecContext = getCodecContext();
    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = nullptr;
    bool inputExhausted = false;

    while (!frame && !inputExhausted) {
        for (;;) {
            int receiveRes = avcodec_receive_frame(codecContext, mAvFrame);
            if (receiveRes == 0) {
                frame = mAvFrame;
                LOGD("[FFVideoReader], getNextFrame receive ok, pts=%" PRId64, mAvFrame->pts)
                break;
            }
            if (receiveRes == AVERROR(EAGAIN)) {
                break;
            }
            if (receiveRes == AVERROR_EOF) {
                inputExhausted = true;
                break;
            }
            LOGE("[FFVideoReader], getNextFrame receive failed: %d", receiveRes)
            inputExhausted = true;
            break;
        }
        if (frame || inputExhausted) {
            break;
        }

        int ret = popPacketForDecode(pkt);
        if (ret < 0) {
            LOGE("[FFVideoReader], getNextFrame popPacketForDecode failed: %d", ret)
            inputExhausted = true;
            break;
        }

        bool isEof = pkt->size == 0 && pkt->data == nullptr;
        int sendRes = avcodec_send_packet(codecContext, isEof ? nullptr : pkt);
        LOGD("[FFVideoReader], getNextFrame sendRes: %d, isKeyFrame: %d", sendRes, isKeyFrame(pkt))
        av_packet_unref(pkt);
        if (sendRes == AVERROR(EAGAIN)) {
            continue;
        }
        if (sendRes < 0 && sendRes != AVERROR_EOF) {
            LOGE("[FFVideoReader], getNextFrame send failed: %d", sendRes)
            inputExhausted = true;
            break;
        }
    }

    av_packet_free(&pkt);

    if (frameArrivedCallback) {
        frameArrivedCallback(frame);
    }
    if (frame != nullptr) {
        av_frame_unref(mAvFrame);
    }
}

bool FFVideoReader::getFramesInRange(
        double startSec,
        double endSec,
        const std::function<bool(AVFrame *, double, int)>& frameArrivedCallback) {
    if (startSec < 0.0) {
        startSec = 0.0;
    }
    if (endSec < startSec) {
        return false;
    }

    resetPacketPipeline(startSec);

    int frameIndex = 0;
    bool completed = true;
    bool aborted = false;
    while (!aborted) {
        bool gotFrame = false;
        getNextFrame([&](AVFrame *frame) {
            if (frame == nullptr) {
                completed = true;
                aborted = true;
                return;
            }

            gotFrame = true;
            double timestampSec = frameTimestampSec(frame);
            if (timestampSec < 0.0) {
                return;
            }
            if (timestampSec + kPtsSecEpsilon < startSec) {
                return;
            }
            if (timestampSec - kPtsSecEpsilon > endSec) {
                completed = true;
                aborted = true;
                return;
            }

            if (frameArrivedCallback && !frameArrivedCallback(frame, timestampSec, frameIndex)) {
                completed = false;
                aborted = true;
                return;
            }
            frameIndex++;
        });

        if (!gotFrame) {
            break;
        }
    }

    stopReadPacketThread(true);
    flush();
    mLastPtsSec = -1.0;
    return completed;
}

int FFVideoReader::getRotate(AVStream *stream) {
    const AVDictionaryEntry *tag = nullptr;
    while ((tag = av_dict_iterate(stream->metadata, tag))) {
        LOGI("[video] metadata: %s, %s", tag->key, tag->value)
    }

    tag = av_dict_get(stream->metadata, "rotate", nullptr, 0);
    LOGE("try getRotate from tag(rotate): %s", tag == nullptr ? "-1" : tag->value)
    int rotate;
    if (tag != nullptr) {
        rotate = atoi(tag->value);
    } else {
        uint8_t* displayMatrix = av_stream_get_side_data(stream,AV_PKT_DATA_DISPLAYMATRIX, nullptr);
        double theta = 0;
        if (displayMatrix) {
            theta = -av_display_rotation_get((int32_t*) displayMatrix);
        }
        rotate = (int)theta;
    }

    LOGE("getRotate: %d", rotate)
    if (rotate < 0) { // CCW -> CC(Clockwise)
        rotate %= 360;
        rotate += 360;
        LOGE("getRotate fix: %d", rotate)
    }

    return rotate < 0 ? 0 : rotate;
}

int FFVideoReader::getRotate() {
    return getRotate(mFtx->streams[mMediaInfo.videoIndex]);
}

void FFVideoReader::setSize(int width, int height) {
    mTargetWidth = width;
    mTargetHeight = height;
}
