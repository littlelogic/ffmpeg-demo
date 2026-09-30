#include "../../main/cpp/main/MediaClock.h"

#include <cassert>
#include <chrono>
#include <thread>

int main() {
    MediaClock clock;
    clock.setSyncType(MediaClock::SyncType::VIDEO_MASTER);
    assert(clock.nowMs() == 0);

    // Ahead-of-playback frames must not advance a running playback clock.
    clock.seekTo(1000);
    for (int i = 0; i < 241; ++i) {
        clock.updateVideoClock(1000 + i * 17);
    }
    assert(clock.nowMs() >= 1000 && clock.nowMs() < 1500);
    auto beforeWait = clock.nowMs();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    assert(clock.nowMs() >= beforeWait + 10);

    clock.pause();
    auto frozen = clock.nowMs();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    assert(clock.nowMs() == frozen);

    // Paused seek preview may align to the actual selected frame.
    clock.seekTo(2000);
    clock.updateVideoClock(2016);
    assert(clock.nowMs() == 2016);
    clock.resume();
    clock.updateVideoClock(9000);
    assert(clock.nowMs() >= 2016 && clock.nowMs() < 2500);

    clock.seekTo(500);
    assert(clock.nowMs() >= 500 && clock.nowMs() < 1000);

    // Audio remains authoritative, and switching away preserves continuity.
    clock.setSyncType(MediaClock::SyncType::AUDIO_MASTER);
    clock.updateAudioClock(7000);
    clock.updateVideoClock(20000);
    assert(clock.nowMs() >= 7000 && clock.nowMs() < 7500);
    clock.setSyncType(MediaClock::SyncType::VIDEO_MASTER);
    clock.updateVideoClock(20000);
    assert(clock.nowMs() >= 7000 && clock.nowMs() < 7500);

    clock.reset();
    clock.setSyncType(MediaClock::SyncType::VIDEO_MASTER);
    clock.updateVideoClock(100);
    assert(clock.nowMs() >= 100 && clock.nowMs() < 600);
    return 0;
}
