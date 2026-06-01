/**
 * @file FFReader.h
 * @brief AVPacket 读取器基类
 *
 * 功能说明：
 * - 封装 FFmpeg 的媒体文件读取功能
 * - 支持选择视频/音频轨道
 * - 支持 Seek、关键帧索引查询
 * - 支持丢帧策略（DISCARD_NONREF/DISCARD_NONKEY）
 * - FFVideoReader 继承此类用于视频抽帧
 */

#ifndef FFMPEGDEMO_FFREADER_H
#define FFMPEGDEMO_FFREADER_H

#include <string>

extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libswscale/swscale.h"
#include "libavutil/imgutils.h"
}

enum TrackType {
    Track_Video,
    Track_Audio
};

enum DiscardType {
    DISCARD_NONE,   // discard nothing
    DISCARD_NONREF, // discard all non reference
    DISCARD_NONKEY  // discard all frames except keyframes
};

typedef struct MediaInfo {
    // video
    int width = -1;
    int height = -1;
    int videoIndex = -1;
    AVRational video_time_base;

    // audio
    int audioIndex = -1;
    AVRational audio_time_base;

    AVRational fps_base;
    double fps = -1;
    double frame_to_second = -1;

} MediaInfo;

/**
 * read AVPacket class
 */
class FFReader {

public:
    FFReader();
    virtual ~FFReader();

    virtual bool init(std::string &path);

    bool selectTrack(TrackType type);

    int fetchAvPacket(AVPacket *pkt);

    bool isKeyFrame(AVPacket *pkt);

    /**
     * 获取 timestamp 对应的关键帧 index，基于 BACKWARD
     * @param timestampSec 媒体时间，单位：秒（支持小数，如 1.5）
     */
    int getKeyFrameIndex(double timestampSec);

    double getDuration();

    /**
     * @param timestampSec 媒体时间，单位：秒（支持小数）
     */
    void seek(double timestampSec);

    void flush();

    void setDiscardType(DiscardType type);

    AVCodecContext *getCodecContext();

    AVCodecParameters *getCodecParameters();

    MediaInfo getMediaInfo();

    void release();

protected:
    AVFormatContext *mFtx = nullptr;
    MediaInfo mMediaInfo;

    /** 媒体秒 → 流 time_base PTS（含 start_time，与 FFMpegPlayer::buildStreamSeekTimestamp 前半一致） */
    static int64_t streamSeekTargetTs(AVFormatContext *ftx, int streamIdx, double timeSec);

    /** demuxer seek 用：streamSeekTargetTs + 索引关键帧对齐 */
    static int64_t buildDemuxerSeekTimestamp(AVFormatContext *ftx, int streamIdx, double timeSec);

    int getCurStreamIndex() const { return mCurStreamIndex; }

private:
    const AVCodec *mCodecArr[2]{nullptr, nullptr};
    AVCodecContext *mCodecContextArr[2]{nullptr, nullptr};

    int mVideoIndex = -1;
    int mAudioIndex = -1;
    int mCurStreamIndex = -1;
    TrackType mCurTrackType = Track_Video;

    DiscardType mDiscardType = DISCARD_NONE;

    int prepare();
};


#endif //FFMPEGDEMO_FFREADER_H
