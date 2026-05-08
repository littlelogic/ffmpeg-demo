/**
 * @file MediaClock.h
 * @brief 音视频同步主时钟
 *
 * 作用：
 *  - 统一替换原来音/视频解码器各自维护的 `mStartTimeMsForSync` 锚点。
 *  - 提供三种同步类型，并支持运行时降级：
 *      AUDIO_MASTER  → 以音频实际播放进度为主时钟（推荐，存在音轨时使用）
 *      VIDEO_MASTER  → 以视频帧 PTS 为主时钟（仅当无音频或音轨失败时使用）
 *      EXTERNAL      → 系统时钟外推（无音频且无视频时；或音频暂时不可用的兜底）
 *  - 内部以 (anchorMediaMs, anchorSysMs) 二元组记录锚点；
 *    nowMs() = anchorMediaMs + (sys_now - anchorSysMs)。
 *  - 暂停时冻结 nowMs，恢复时把 anchorSysMs 平移过去，避免恢复瞬间画面跳一大段。
 *  - Seek 时统一锚点，避免音/视频各自修复时间戳带来的短暂失同步。
 *
 * 线程安全：所有 public 接口均加锁。
 */

#ifndef FFMPEGDEMO_MEDIACLOCK_H
#define FFMPEGDEMO_MEDIACLOCK_H

#include <cstdint>
#include <mutex>

class MediaClock {
public:
    enum class SyncType {
        AUDIO_MASTER,
        VIDEO_MASTER,
        EXTERNAL,
    };

    MediaClock();
    ~MediaClock() = default;

    /// 完全复位：清空锚点、解除暂停、SyncType 设回 EXTERNAL
    void reset();

    void setSyncType(SyncType t);
    SyncType getSyncType() const;

    /**
     * 音频渲染端调用：上报"刚要写入到 AudioTrack 的这一段 PCM 对应的媒体 PTS"。
     * 仅在 SyncType == AUDIO_MASTER 时影响主时钟，否则只是更新 lastUpdate 时间用于 staleness 检测。
     */
    void updateAudioClock(int64_t mediaPtsMs);

    /**
     * 视频渲染端调用：仅在 SyncType == VIDEO_MASTER 时把当前帧 PTS 设为主时钟锚点。
     */
    void updateVideoClock(int64_t mediaPtsMs);

    /// 当前主时钟读取的"媒体时间(ms)"。暂停时返回冻结值。
    int64_t nowMs() const;

    /// 距离上一次 updateAudioClock/updateVideoClock 多久（用于检测时钟陈旧）。
    /// 从未更新过则返回 INT64_MAX。
    int64_t lastUpdateAgeMs() const;

    void pause();
    void resume();
    bool isPaused() const;

    /// Seek 完成时调用：把主时钟锚点直接拨到新位置（同时平移 anchorSysMs）。
    void seekTo(int64_t mediaPtsMs);

    /// 音频流结束/失效：若当前是 AUDIO_MASTER，则切到 EXTERNAL，并以当前 nowMs 作为锚点继续外推。
    void onAudioEnded();

    /// 媒体起始时间归一化（多个流 start_time 不一致时使用），仅供查询，
    /// 时间戳归一化目前由解码器侧负责，这里仅记录方便日志/外部读取。
    void setStartPtsMs(int64_t v);
    int64_t getStartPtsMs() const;

private:
    static int64_t sysNowMs();
    int64_t nowMsLocked() const;
    void setAnchorLocked(int64_t mediaMs, int64_t sysMs);

    mutable std::mutex mMu;
    SyncType mType{SyncType::EXTERNAL};

    // 当前锚点：在 sys = mAnchorSysMs 时刻，主时钟读数 = mAnchorMediaMs。
    int64_t mAnchorMediaMs{0};
    int64_t mAnchorSysMs{-1};

    // 最近一次被"渲染端"更新的系统时间，用于 staleness。
    int64_t mLastUpdateSysMs{-1};

    // 暂停态：暂停时 nowMs 冻结在 mPauseFrozenMediaMs。
    bool mPaused{false};
    int64_t mPauseFrozenMediaMs{0};

    // 媒体起始 pts（多个流 start_time 不一致时使用，单位 ms）。
    int64_t mStartPtsMs{0};
};

#endif // FFMPEGDEMO_MEDIACLOCK_H
