/**
 * @file MediaClock.cpp
 * @brief 主时钟实现
 */

#include "MediaClock.h"

#include <climits>
#include <sys/time.h>

MediaClock::MediaClock() = default;

int64_t MediaClock::sysNowMs() {
    struct timeval tv{};
    gettimeofday(&tv, nullptr);
    return static_cast<int64_t>(tv.tv_sec) * 1000 + tv.tv_usec / 1000;
}

void MediaClock::reset() {
    std::lock_guard<std::mutex> lk(mMu);
    mType = SyncType::EXTERNAL;
    mAnchorMediaMs = 0;
    mAnchorSysMs = -1;
    mLastUpdateSysMs = -1;
    mPaused = false;
    mPauseFrozenMediaMs = 0;
    mStartPtsMs = 0;
}

void MediaClock::setSyncType(SyncType t) {
    std::lock_guard<std::mutex> lk(mMu);
    if (mType == t) {
        return;
    }
    // 切换 SyncType 时，保持当前 nowMs 连续：以当前读数作为新锚点。
    // 注意：如果时钟尚未"起跑"（mAnchorSysMs < 0），不要在这里把它点亮——
    // 仍保留 sys=-1 状态，待 seekTo() 或第一次 update*Clock() 真正起跑。
    int64_t curMedia = nowMsLocked();
    bool wasUnstarted = (mAnchorSysMs < 0);
    mType = t;
    if (wasUnstarted) {
        mAnchorMediaMs = curMedia;
        mAnchorSysMs = -1;
    } else {
        setAnchorLocked(curMedia, sysNowMs());
    }
}

MediaClock::SyncType MediaClock::getSyncType() const {
    std::lock_guard<std::mutex> lk(mMu);
    return mType;
}

void MediaClock::setAnchorLocked(int64_t mediaMs, int64_t sysMs) {
    mAnchorMediaMs = mediaMs;
    mAnchorSysMs = sysMs;
    if (mPaused) {
        mPauseFrozenMediaMs = mediaMs;
    }
}

int64_t MediaClock::nowMsLocked() const {
    if (mPaused) {
        return mPauseFrozenMediaMs;
    }
    if (mAnchorSysMs < 0) {
        return mAnchorMediaMs;
    }
    return mAnchorMediaMs + (sysNowMs() - mAnchorSysMs);
}

void MediaClock::updateAudioClock(int64_t mediaPtsMs) {
    std::lock_guard<std::mutex> lk(mMu);
    int64_t sys = sysNowMs();
    mLastUpdateSysMs = sys;
    if (mType != SyncType::AUDIO_MASTER) {
        return;
    }
    if (mPaused) {
        // 暂停时不推进时钟，但锚点先记下，以便恢复后立即生效。
        mAnchorMediaMs = mediaPtsMs;
        mAnchorSysMs = sys;
        mPauseFrozenMediaMs = mediaPtsMs;
        return;
    }
    setAnchorLocked(mediaPtsMs, sys);
}

void MediaClock::updateVideoClock(int64_t mediaPtsMs) {
    std::lock_guard<std::mutex> lk(mMu);
    int64_t sys = sysNowMs();
    mLastUpdateSysMs = sys;
    if (mType != SyncType::VIDEO_MASTER) {
        return;
    }
    if (mPaused) {
        mAnchorMediaMs = mediaPtsMs;
        mAnchorSysMs = sys;
        mPauseFrozenMediaMs = mediaPtsMs;
        return;
    }
    setAnchorLocked(mediaPtsMs, sys);
}

int64_t MediaClock::nowMs() const {
    std::lock_guard<std::mutex> lk(mMu);
    return nowMsLocked();
}

int64_t MediaClock::lastUpdateAgeMs() const {
    std::lock_guard<std::mutex> lk(mMu);
    if (mLastUpdateSysMs < 0) {
        return INT64_MAX;
    }
    return sysNowMs() - mLastUpdateSysMs;
}

void MediaClock::pause() {
    std::lock_guard<std::mutex> lk(mMu);
    if (mPaused) {
        return;
    }
    mPauseFrozenMediaMs = nowMsLocked();
    mPaused = true;
}

void MediaClock::resume() {
    std::lock_guard<std::mutex> lk(mMu);
    if (!mPaused) {
        return;
    }
    // 把锚点平移到"恢复一瞬间 = 冻结值"，确保 nowMs 连续。
    mAnchorMediaMs = mPauseFrozenMediaMs;
    mAnchorSysMs = sysNowMs();
    mPaused = false;
}

bool MediaClock::isPaused() const {
    std::lock_guard<std::mutex> lk(mMu);
    return mPaused;
}

void MediaClock::seekTo(int64_t mediaPtsMs) {
    std::lock_guard<std::mutex> lk(mMu);
    setAnchorLocked(mediaPtsMs, sysNowMs());
    mLastUpdateSysMs = -1;  // seek 后清空"陈旧"判断，等待新的渲染端上报。
}

void MediaClock::onAudioEnded() {
    std::lock_guard<std::mutex> lk(mMu);
    if (mType != SyncType::AUDIO_MASTER) {
        return;
    }
    int64_t curMedia = nowMsLocked();
    mType = SyncType::EXTERNAL;
    setAnchorLocked(curMedia, sysNowMs());
}

void MediaClock::setStartPtsMs(int64_t v) {
    std::lock_guard<std::mutex> lk(mMu);
    mStartPtsMs = v;
}

int64_t MediaClock::getStartPtsMs() const {
    std::lock_guard<std::mutex> lk(mMu);
    return mStartPtsMs;
}
