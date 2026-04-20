/**
 * @file BaseDecoder.cpp
 * @brief 解码器基类实现
 *
 * 提供解码器的公共功能：
 * - 构造时从流中提取时间基和时长
 * - flush 操作清空解码缓冲
 * - 回调管理（错误回调、帧到达回调）
 */

#include "BaseDecoder.h"
#include "header/Logger.h"

/**
 * @brief 构造函数
 * @details 从流中提取时间基（time_base）和时长（duration）
 * @param index 流索引
 * @param ftx 格式化上下文
 */
BaseDecoder::BaseDecoder(int index, AVFormatContext *ftx) {
    mStreamIndex = index;
    mFtx = ftx;
    mTimeBase = mFtx->streams[index]->time_base;
    mDuration = mFtx->streams[index]->duration * av_q2d(mTimeBase);
    LOGE("[BaseDecoder], index: %d, duration: %f, time base: {num: %d, den: %d}",
         index, mDuration, mTimeBase.num, mTimeBase.den);
    if (mDuration < 0.0) {
        mDuration = 0.0;
    }
}

BaseDecoder::~BaseDecoder() = default;

/**
 * @brief 获取媒体总时长
 * @details 使用 AVFormatContext 的 duration 字段（比流级别更准确）
 * @return 时长（秒）
 */
double BaseDecoder::getDuration() {
    return mFtx->duration * av_q2d(AV_TIME_BASE_Q);
}

bool BaseDecoder::prepare() {
    return false;
}

int BaseDecoder::decode(AVPacket *packet) {
    return 0;
}

void BaseDecoder::release() {

}

void BaseDecoder::setErrorMsgListener(std::function<void(int, std::string &)> listener) {
    mErrorMsgListener = std::move(listener);
}

void BaseDecoder::setOnFrameArrived(std::function<void(AVFrame *)> listener) {
    mOnFrameArrivedListener = std::move(listener);
}

int BaseDecoder::getStreamIndex() const {
    return mStreamIndex;
}

void BaseDecoder::avSync(AVFrame *frame) {
}

int BaseDecoder::seek(double pos) {
    return -1;
}

/**
 * @brief 清空解码缓冲
 * @details 调用 avcodec_flush_buffers 清空编码器内部缓冲，Seek 前必须调用
 */
void BaseDecoder::flush() {
    if (mCodecContext != nullptr) {
        avcodec_flush_buffers(mCodecContext);
    }
}

bool BaseDecoder::isNeedResent() const {
    return mNeedResent;
}

void BaseDecoder::needFixStartTime() {
    mFixStartTime = true;
}

AVCodecContext *BaseDecoder::getCodecContext() {
    return mCodecContext;
}

AVRational BaseDecoder::getTimeBase() {
    return mTimeBase;
}

std::string BaseDecoder::getMediaInfo() {
    return mMediaInfoJson.dump();
}
