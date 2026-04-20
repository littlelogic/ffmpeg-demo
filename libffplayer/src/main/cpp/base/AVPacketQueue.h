/**
 * @file AVPacketQueue.h
 * @brief 线程安全的 AVPacket 队列
 *
 * 功能说明：
 * - 基于 std::queue 实现的生产者-消费者队列
 * - 使用 pthread_mutex + pthread_cond 实现线程安全和等待通知
 * - 支持容量限制（isFull）
 * - 支持超时等待（wait with timeout）
 * - 用于读包线程和解码线程之间的数据传递
 */

#ifndef FFMPEGDEMO_AVPACKETQUEUE_H
#define FFMPEGDEMO_AVPACKETQUEUE_H

#include <queue>
#include <pthread.h>

extern "C" {
#include "../vendor/ffmpeg/libavcodec/packet.h"
#include "../vendor/ffmpeg/libavutil/avutil.h"
}

class AVPacketQueue {

public:
    AVPacketQueue(int64_t maxSize);       ///< 构造函数，设置最大容量
    ~AVPacketQueue();                      ///< 析构函数，清空队列

    void push(AVPacket *packet);           ///< 入队（生产者调用）
    AVPacket* pop();                       ///< 出队（消费者调用）
    int popTo(AVPacket *packet);           ///< 出队并拷贝到指定 packet
    void clear();                          ///< 清空队列并释放所有 packet

    bool isFull();                         ///< 是否已满
    bool isEmpty();                        ///< 是否为空

    void wait(unsigned int timeOutMs = -1); ///< 等待通知（支持超时）
    void notify();                         ///< 发送通知

private:
    int64_t mMaxSize = INT64_MAX;          ///< 最大容量
    std::queue<AVPacket *> mQueue;         ///< 底层队列
    pthread_cond_t mCond{};                ///< 条件变量
    pthread_mutex_t mMutex{};              ///< 互斥锁
};


#endif //FFMPEGDEMO_AVPACKETQUEUE_H
