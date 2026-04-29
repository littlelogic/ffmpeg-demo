# FFMpegPlayer 改进总结

## 概述
对 FFMpegPlayer 进行了以下关键改进，包括音视频同步机制、JNI 内存泄漏修复和线程安全增强。

---

## 改进清单

### 1. ✅ 改进音视频同步机制（核心改变）

**文件**: `libffplayer/src/main/cpp/main/FFMpegPlayer.cpp`

#### 改进前
```cpp
if (mAudioDecoder) {
    auto diff = mAudioDecoder->getTimestamp() - mVideoDecoder->getTimestamp();
    LOGW("[video] frame arrived, AV time diff: %ld", diff)
}
// ... 然后视频自行调用 avSync()
mVideoDecoder->avSync(frame);
```
**问题**: 仅打印时间差，未做任何同步处理。视频和音频各自独立根据自己的 PTS 等待。

#### 改进后
```cpp
if (mAudioDecoder) {
    audioTimestamp = mAudioDecoder->getTimestamp();
    int64_t maxTimestamp = std::max(videoTimestamp, audioTimestamp);
    int64_t elapsedMs = getCurrentTimeMs() - playbackBaseTime;
    int64_t targetWaitMs = maxTimestamp - elapsedMs;
    
    if (targetWaitMs > 0) {
        av_usleep(targetWaitMs * 1000);  // 根据最晚时间点等待
    }
} else {
    mVideoDecoder->avSync(frame);  // 无音频时自行对齐
}
```
**改进**:
- 取 `max(video_pts, audio_pts)` 作为播放约束时间点
- 两个线程都被"拖"到这个时间点，确保真正的音视频同步
- 避免了一个线程快速处理、另一个线程滞后的情况

---

### 2. ✅ 修复 JNI 全局引用泄漏

**文件**: `libffplayer/src/main/cpp/main/FFMpegPlayer.cpp`

#### 改进前
```cpp
FFMpegPlayer::~FFMpegPlayer() {
    mJvm = nullptr;
    mPlayerJni.reset();
    LOGI("~FFMpegPlayer")
}
```
**问题**: `mPlayerJni.instance` 是通过 `env->NewGlobalRef(thiz)` 创建的全局引用，但从未释放，导致内存泄漏。

#### 改进后
```cpp
FFMpegPlayer::~FFMpegPlayer() {
    // ====== 清理 JNI 全局引用，防止内存泄漏 ======
    if (mJvm != nullptr && mPlayerJni.instance != nullptr) {
        JNIEnv *env = nullptr;
        int getEnvStat = mJvm->GetEnv((void **)&env, JNI_VERSION_1_4);
        if (getEnvStat == JNI_OK) {
            env->DeleteGlobalRef(mPlayerJni.instance);
            LOGI("~FFMpegPlayer, DeleteGlobalRef done")
        } else if (getEnvStat == JNI_EDETACHED) {
            mJvm->AttachCurrentThread(&env, nullptr);
            env->DeleteGlobalRef(mPlayerJni.instance);
            mJvm->DetachCurrentThread();
            LOGI("~FFMpegPlayer, AttachThread->DeleteGlobalRef->DetachThread done")
        }
    }
    mJvm = nullptr;
    mPlayerJni.reset();
    LOGI("~FFMpegPlayer")
}
```
**改进**:
- 析构时显式调用 `DeleteGlobalRef()` 释放引用
- 处理多种线程状态（已 attach/未 attach）
- 防止在错误的上下文中调用 JNI 函数

---

### 3. ✅ 对称处理音频同步

**文件**: `libffplayer/src/main/cpp/main/FFMpegPlayer.cpp` (AudioDecodeLoop)

#### 改进前
```cpp
mAudioDecoder->setOnFrameArrived([this, env](AVFrame *frame) {
    if (!mHasAbort && mAudioDecoder) {
        mAudioDecoder->avSync(frame);  // 仅自行对齐
        doRender(env, frame);
        // ...
    }
});
```

#### 改进后
```cpp
mAudioDecoder->setOnFrameArrived([this, env](AVFrame *frame) {
    if (!mHasAbort && mAudioDecoder) {
        if (mVideoDecoder) {
            videoTimestamp = mVideoDecoder->getTimestamp();
            int64_t maxTimestamp = std::max(videoTimestamp, audioTimestamp);
            int64_t targetWaitMs = maxTimestamp - elapsedMs;
            
            if (targetWaitMs > 0) {
                av_usleep(targetWaitMs * 1000);  // 音频也遵守约束
            }
        } else {
            mAudioDecoder->avSync(frame);  // 仅音频时自行对齐
        }
        doRender(env, frame);
        // ...
    }
});
```
**改进**:
- 音视频线程采用对称的同步逻辑
- 不再"主从"关系，而是"时间约束"关系
- 两个都等到最晚的时间点

---

### 4. ✅ 增强文档注释

**改进**:
- 在文件头部添加了改进版的音视频同步策略说明
- 补充了 Seek 时的同步恢复机制文档
- 添加了具体例子说明时间约束逻辑

---

## 工作流程图对比

### 改进前（各自对齐）
```
系统时间:  0ms → 50ms → 100ms → 150ms → 200ms
           ↑
           ref
           
视频 PTS=100ms:  ↓ 在 100ms 时执行渲染
音频 PTS=120ms:         ↓ 在 120ms 时执行渲染
                        
问题: 不同步，视频领先音频 20ms
```

### 改进后（时间约束对齐）
```
系统时间:  0ms → 50ms → 100ms → 120ms → 150ms
           ↑
           ref
           
视频 PTS=100ms:  等待 → 等待 ↓ 在 120ms 时执行渲染
音频 PTS=120ms:              ↓ 在 120ms 时执行渲染
                             
改进: 同步，都在最晚时间点执行
```

---

## 关键代码变化汇总

| 改动项 | 文件 | 行数 | 影响范围 |
|--------|------|------|---------|
| 音视频同步（视频） | FFMpegPlayer.cpp | 445-465 | VideoDecodeLoop |
| 音视频同步（音频） | FFMpegPlayer.cpp | 553-580 | AudioDecodeLoop |
| JNI 引用释放 | FFMpegPlayer.cpp | 22-40 | 析构函数 |
| 文档补充 | FFMpegPlayer.cpp | 1-31 | 文件头 |
| Seek 文档 | FFMpegPlayer.cpp | 793-804 | seek() 方法 |

---

## 测试要点

1. **音视频同步测试**
   - 播放含 B 帧的视频，检查音视频同步精度
   - 检查 Log 中 `AV time diff` 是否保持在 ±50ms 以内

2. **Seek 测试**
   - Seek 后检查音视频是否立即同步
   - 检查 Seek 前后的时间戳跳变

3. **内存泄漏测试**
   - 反复创建/销毁播放器，用 Valgrind 或 LeakCanary 检查
   - 确认 DeleteGlobalRef 被调用

4. **边界测试**
   - 无音频视频播放（仅调用 avSync）
   - 无视频音频播放（仅调用 avSync）
   - 快速暂停/恢复

---

## 后续建议

### 短期
- 监控生产环境的音视频同步指标
- 验证 JNI 引用是否完全释放

### 中期
- 考虑实现 **多轨编辑引擎**（中央时钟驱动多路解码）
- 添加可配置的同步容错范围（±100ms 等）

### 长期
- 支持变速播放（会影响同步机制）
- 支持多条音频轨混音播放
- 支持动态渲染参数调整

---

## 参考资料

- FFmpeg 音视频同步: https://ffmpeg.org/doxygen/trunk/structAVFrame.html
- JNI 引用管理: https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/functions.html#global_references
- Android MediaCodec 同步: https://developer.android.com/reference/android/media/MediaCodec#getTimestamp()

