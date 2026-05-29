/**
 * @file FFReader.cpp
 * @brief AVPacket 读取器基类实现
 *
 * 核心功能：
 * - init(): 打开媒体文件
 * - selectTrack(): 选择视频或音频轨道并初始化解码器
 * - fetchAvPacket(): 读取下一个 AVPacket（过滤指定流）
 * - seek(): Seek 到指定时间位置
 * - getKeyFrameIndex(): 查找关键帧索引
 */

#include "FFReader.h"
#include "header/Logger.h"

int64_t FFReader::streamSeekTargetTs(AVFormatContext *ftx, int streamIdx, double timeSec) {
    if (timeSec < 0.0) {
        timeSec = 0.0;
    }
    AVStream *st = ftx->streams[streamIdx];
    int64_t seekTs = av_rescale_q((int64_t) (timeSec * AV_TIME_BASE), AV_TIME_BASE_Q, st->time_base);
    if (st->start_time != AV_NOPTS_VALUE) {
        seekTs += st->start_time;
    }
    return seekTs;
}

int64_t FFReader::buildDemuxerSeekTimestamp(AVFormatContext *ftx, int streamIdx, double timeSec) {
    int64_t seekTs = streamSeekTargetTs(ftx, streamIdx, timeSec);
    AVStream *st = ftx->streams[streamIdx];
    const AVIndexEntry *entry = avformat_index_get_entry_from_timestamp(
            st, seekTs, AVSEEK_FLAG_BACKWARD);
    if (entry != nullptr && entry->timestamp != AV_NOPTS_VALUE) {
        LOGI("[FFReader] index keyframe ts=%" PRId64 " (wanted=%" PRId64 ")",
             entry->timestamp, seekTs);
        seekTs = entry->timestamp;
    }
    return seekTs;
}

FFReader::FFReader() {
    LOGI("FFReader")
}

FFReader::~FFReader() {
    release();
    LOGI("~FFReader")
}

bool FFReader::init(std::string &path) {
    mFtx = avformat_alloc_context();
    int ret = avformat_open_input(&mFtx, path.c_str(), nullptr, nullptr);
    if (ret < 0) {
        LOGE("[FFReader], avformat_open_input failed, ret: %d, err: %s", ret, av_err2str(ret))
        return false;
    }
    return true;
}

void FFReader::release() {
    for (int i = 0; i < 2; i++) {
        AVCodecContext *codecContext = mCodecContextArr[i];
        if (codecContext != nullptr) {
            avcodec_free_context(&codecContext);
        }
        mCodecContextArr[i] = nullptr;
        mCodecArr[i] = nullptr;
    }

    if (mFtx != nullptr) {
        avformat_close_input(&mFtx);
        avformat_free_context(mFtx);
        mFtx = nullptr;
    }

    mCurStreamIndex = -1;
    mVideoIndex = -1;
    mAudioIndex = -1;
    LOGI("[FFReader], release")
}

bool FFReader::selectTrack(TrackType type) {
    mCurTrackType = type;
    if (mCodecContextArr[type] != nullptr) {
        mCurStreamIndex = type == Track_Video ? mVideoIndex : mAudioIndex;
        return true;
    }

    if (mVideoIndex == -1 || mAudioIndex == -1) {
        avformat_find_stream_info(mFtx, nullptr);
        int codec_type;
        for (int i = 0; i < mFtx->nb_streams; i++) {
            codec_type = mFtx->streams[i]->codecpar->codec_type;
            if (codec_type == AVMEDIA_TYPE_VIDEO) {
                mVideoIndex = i;
            } else if (codec_type == AVMEDIA_TYPE_AUDIO) {
                mAudioIndex = i;
            }
        }
    }
    mCurStreamIndex = type == Track_Video ? mVideoIndex : mAudioIndex;
    LOGI("[FFReader], electTrack, type: %d, index: %d", type, mCurStreamIndex)
    int ret = prepare();
    return ret == 0;
}

int FFReader::prepare() {
    if (mCurStreamIndex < 0) {
        LOGE("[FFReader], prepare failed, index invalid.")
        return -1;
    }

    TrackType type = mCurTrackType;
    LOGI("[FFReader], prepare, index: %d, type: %d", mCurStreamIndex, type)
    AVCodecParameters *params = mFtx->streams[mCurStreamIndex]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(params->codec_id);
    if (codec == nullptr) {
        LOGE("[FFReader], prepare failed, no codec")
        return -2;
    }
    mCodecArr[type] = codec;

    AVCodecContext *codecContext = avcodec_alloc_context3(mCodecArr[type]);
    if (codecContext == nullptr) {
        LOGE("[FFReader], prepare failed, no codec ctx")
        return -3;
    }
    mCodecContextArr[type] = codecContext;

    avcodec_parameters_to_context(codecContext, params);
    if (type == Track_Video) {
        mMediaInfo.videoIndex = mVideoIndex;
        mMediaInfo.video_time_base = mFtx->streams[mCurStreamIndex]->time_base;
        mMediaInfo.width = codecContext->width;
        mMediaInfo.height = codecContext->height;
        if (mDiscardType != DISCARD_NONE) {
            switch (mDiscardType) {
                case DISCARD_NONREF: {
                    codecContext->skip_frame = AVDISCARD_NONREF;
                    break;
                }
                case DISCARD_NONKEY: {
                    codecContext->skip_frame = AVDISCARD_NONKEY;
                    break;
                }
                default:
                    break;
            }
        }
    } else if (type == Track_Audio) {
        mMediaInfo.audioIndex = mAudioIndex;
        mMediaInfo.audio_time_base = mFtx->streams[mCurStreamIndex]->time_base;
    }

    int ret = avcodec_open2(codecContext, mCodecArr[type], nullptr);
    if (ret != 0) {
        LOGE("[FFReader], open codec failed, name: %s, ret: %d", mCodecArr[type]->name, ret)
    }
    return ret;
}

int FFReader::fetchAvPacket(AVPacket *pkt) {
    int ret = -1;
    while (av_read_frame(mFtx, pkt) == 0) {
        if (pkt->stream_index == mFtx->streams[mCurStreamIndex]->index) {
            ret = 0;
            break;
        }
        av_packet_unref(pkt);
    }
    if (ret != 0) {
        av_packet_unref(pkt);
    }
    return ret;
}

AVCodecContext *FFReader::getCodecContext() {
    return mCodecContextArr[mCurTrackType];
}

AVCodecParameters *FFReader::getCodecParameters() {
    return mFtx->streams[mCurStreamIndex]->codecpar;
}

int FFReader::getKeyFrameIndex(double timestampSec) {
    int64_t target = streamSeekTargetTs(mFtx, mCurStreamIndex, timestampSec);
    int index = av_index_search_timestamp(
            mFtx->streams[mCurStreamIndex], target, AVSEEK_FLAG_BACKWARD);
    index = FFMAX(index, 0);
    return index;
}

void FFReader::flush() {
    LOGI("[FFReader], avcodec_flush_buffers")
    avcodec_flush_buffers(mCodecContextArr[mCurTrackType]);
}

void FFReader::seek(double timestampSec) {
    int64_t seekPos = buildDemuxerSeekTimestamp(mFtx, mCurStreamIndex, timestampSec);
    // 仅 BACKWARD：seekPos 是时间戳（已 rescale 到流 time_base），不是帧号。
    // AVSEEK_FLAG_FRAME 会把 seekPos 当帧索引解释，导致定位错误。
    int ret = avformat_seek_file(mFtx, mCurStreamIndex, INT64_MIN, seekPos, INT64_MAX, AVSEEK_FLAG_BACKWARD);
    if (ret < 0) {
        int64_t globalTs = (int64_t) (timestampSec * AV_TIME_BASE);
        AVStream *st = mFtx->streams[mCurStreamIndex];
        if (st->start_time != AV_NOPTS_VALUE) {
            globalTs += av_rescale_q(st->start_time, st->time_base, AV_TIME_BASE_Q);
        }
        ret = av_seek_frame(mFtx, mCurStreamIndex, globalTs, AVSEEK_FLAG_BACKWARD);
        LOGW("[FFReader] avformat_seek_file failed, av_seek_frame ret=%d globalTs=%" PRId64
             " timestampSec=%f",
             ret, globalTs, timestampSec)
    } else {
        LOGI("[FFReader] avformat_seek_file ok, timestampSec=%f, seekPos=%" PRId64, timestampSec, seekPos)
    }
}

bool FFReader::isKeyFrame(AVPacket *pkt) {
    return pkt->flags & AV_PKT_FLAG_KEY;
}

MediaInfo FFReader::getMediaInfo() {
    return mMediaInfo;
}

double FFReader::getDuration() {
    int64_t duration = mFtx->streams[mCurStreamIndex]->duration;
    AVRational time_base = mFtx->streams[mCurStreamIndex]->time_base;
    return duration * av_q2d(time_base);
}

void FFReader::setDiscardType(DiscardType type) {
    mDiscardType = type;
}
