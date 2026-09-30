#include "../../main/cpp/base/AVPacketQueue.h"

#include <cassert>
#include <thread>

int main() {
    AVPacketQueue queue(2);
    assert(queue.pop() == nullptr);
    assert(queue.getStats().packets == 0 && queue.getStats().bytes == 0);

    AVPacket *packet = av_packet_alloc();
    assert(packet && av_new_packet(packet, 100) == 0);
    assert(av_packet_new_side_data(packet, AV_PKT_DATA_NEW_EXTRADATA, 20));
    queue.push(packet);
    const auto first = queue.getStats();
    assert(first.packets == 1 && !first.full);
    assert(first.bytes == sizeof(AVPacket) + 100 + sizeof(AVPacketSideData) + 20);

    AVPacket *eof = av_packet_alloc();
    assert(eof);
    queue.push(eof);
    const auto full = queue.getStats();
    assert(full.packets == 2 && full.full);
    assert(full.bytes == first.bytes + sizeof(AVPacket));

    AVPacket *output = av_packet_alloc();
    assert(output && queue.popTo(output) == 0);
    assert(output->size == 100 && output->side_data_elems == 1);
    assert(queue.getStats().bytes == sizeof(AVPacket));
    assert(!queue.getStats().full);
    av_packet_unref(output);
    assert(queue.popTo(output) == 0);
    assert(output->data == nullptr && output->size == 0);
    assert(queue.getStats().bytes == 0 && queue.isEmpty());
    av_packet_free(&output);

    packet = av_packet_alloc();
    assert(packet && av_new_packet(packet, 200) == 0);
    queue.push(packet);
    packet = queue.pop();
    assert(packet && packet->size == 200 && queue.getStats().bytes == 0);
    queue.push(packet);
    queue.clear();
    assert(queue.getStats().packets == 0 && queue.getStats().bytes == 0);
    queue.clear();

    std::thread producer([&] {
        for (int i = 0; i < 2000; ++i) {
            AVPacket *next = av_packet_alloc();
            assert(next && av_new_packet(next, 64) == 0);
            queue.push(next);
        }
    });
    for (int i = 0; i < 2000;) {
        AVPacket *next = queue.pop();
        if (next) {
            ++i;
            av_packet_free(&next);
        } else {
            std::this_thread::yield();
        }
        const auto stats = queue.getStats();
        assert(stats.bytes == stats.packets * (sizeof(AVPacket) + 64));
    }
    producer.join();
    assert(queue.getStats().bytes == 0 && queue.isEmpty());
}
