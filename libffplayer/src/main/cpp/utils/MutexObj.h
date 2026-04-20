/**
 * @file MutexObj.h
 * @brief 互斥锁 + 条件变量封装
 *
 * 功能说明：
 * - 封装 pthread_mutex + pthread_cond 为简单的等待/唤醒机制
 * - 用于播放器线程间同步（暂停、恢复、Seek 等场景）
 */

//
// Created by 雪月清的随笔 on 2023/4/18.
//

#ifndef FFMPEGDEMO_MUTEXOBJ_H
#define FFMPEGDEMO_MUTEXOBJ_H

#include <pthread.h>

class MutexObj {

public:
    MutexObj();

    ~MutexObj();

    void wakeUp();

    void wait();

private:
    pthread_cond_t mCond{};
    pthread_mutex_t mMutex{};
};


#endif //FFMPEGDEMO_MUTEXOBJ_H
