# libffplayer 模块架构设计文档

## 一、模块概述

`libffplayer` 是一个基于 FFmpeg 的 Android 音视频播放器引擎模块，通过 JNI 将 C++ 层的播放能力暴露给 Java/Kotlin 层使用。

### 核心能力
- 🎬 **视频解码**：支持 H.264/H.265 软解和 Android MediaCodec 硬件加速
- 🔊 **音频解码**：支持多种音频格式解码和重采样
- 🔄 **音视频同步**：基于 MediaClock 的多策略主时钟（AUDIO/VIDEO/EXTERNAL），支持运行时降级与边界场景（无音轨/音轨晚开始/音轨提前结束）
- ⏩ **Seek 操作**：支持精准/快速 Seek
- 🖼️ **视频抽帧**：支持精准/快速抽帧
- 📝 **视频编码/写入**：支持 H.264 和 GIF 编码输出
- 🎨 **视频滤镜**：基于 FFmpeg AVFilter 的滤镜处理

---

## 二、整体架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Java / Kotlin 层                                │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  FFPlayer.kt                                                     │    │
│  │  ├── 实现 IPlayer 接口                                            │    │
│  │  ├── init() / prepare() / start() / pause() / stop() / seek()    │    │
│  │  └── 回调: onVideoFrameArrived / onAudioFrameArrived / ...        │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                              │ JNI                                       │
├──────────────────────────────┼───────────────────────────────────────────┤
│                          C++ Native 层                                   │
│                              │                                           │
│  ┌───────────────────────────┴──────────────────────────────────────┐    │
│  │  native-lib.cpp  (JNI 桥接层)                                     │    │
│  │  ├── JNI_OnLoad: 初始化                                           │    │
│  │  └── Java_com_xyq_libffplayer_FFPlayer_native*: JNI 方法映射      │    │
│  └───────────────────────────┬──────────────────────────────────────┘    │
│                              │                                           │
│  ┌───────────────────────────┴──────────────────────────────────────┐    │
│  │  FFMpegPlayer  (播放器核心引擎)                          main/    │    │
│  │  ├── 生命周期: init → prepare → start → playing → pause → stop   │    │
│  │  ├── 三线程架构:                                                  │    │
│  │  │   ├── ReadPacketThread  (读包线程)                             │    │
│  │  │   ├── VideoDecodeThread (视频解码线程)                         │    │
│  │  │   └── AudioDecodeThread (音频解码线程)                         │    │
│  │  ├── 视频帧渲染 (doRender)                                       │    │
│  │  ├── Seek 协调控制                                               │    │
│  │  ├── per-stream EOF 双标志 + atomic 去重的 onPlayCompleted        │    │
│  │  └── 拥有 MediaClock 并注入两个 Decoder                           │    │
│  └──┬──────────────────┬───────────────┬───────────────┬────────────┘    │
│     │                  │               │               │                 │
│  ┌──┴────────┐  ┌──────┴─────┐  ┌──────┴──────┐  ┌────┴──────┐         │
│  │ AVPacket  │  │  Video     │  │  Audio      │  │  FFFilter │         │
│  │ Queue     │  │  Decoder   │  │  Decoder    │  │           │         │
│  │  base/    │  │  decoder/  │  │  decoder/   │  │  filter/  │         │
│  └───────────┘  └──────┬─────┘  └──────┬──────┘  └───────────┘         │
│                        │               │                                 │
│                        ▼               ▼                                 │
│                  ┌──────────────────────────┐                            │
│                  │   MediaClock     main/   │ ← 共享主时钟                │
│                  │  (AUDIO/VIDEO/EXTERNAL)  │                            │
│                  └──────────────────────────┘                            │
│                  (Video/Audio Decoder 继承自 BaseDecoder, decoder/)       │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  独立功能模块                                                     │    │
│  │                                                                   │    │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────────┐  │    │
│  │  │ FFReader     │  │ FFVideoReader│  │ FFVideoWriter           │  │    │
│  │  │ (读包基类)   │  │ (视频抽帧)   │  │ (视频编码/写入)         │  │    │
│  │  │ reader/      │  │ reader/      │  │ writer/                 │  │    │
│  │  └─────────────┘  └──────────────┘  └─────────────────────────┘  │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  工具层                                                           │    │
│  │  ├── ImageDef.h       : 图像格式定义                              │    │
│  │  ├── FFConverter.h    : FFmpeg 枚举转换                           │    │
│  │  ├── MutexObj          : 互斥锁封装                               │    │
│  │  ├── TraceUtils        : 性能追踪                                 │    │
│  │  └── CompileSettings.h : 编码参数配置                             │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  第三方依赖 (vendor/)                                             │    │
│  │  ├── ffmpeg/  : FFmpeg 头文件                                     │    │
│  │  ├── libyuv/  : Google libyuv 图像处理库                         │    │
│  │  └── nlohmann/: JSON 序列化库                                     │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  预编译 FFmpeg 动态库 (libs/)                                     │    │
│  │  ├── libavutil.so      : 通用工具库                               │    │
│  │  ├── libavformat.so    : 封装/解封装库                            │    │
│  │  ├── libavcodec.so     : 编解码库                                 │    │
│  │  ├── libavfilter.so    : 滤镜库                                   │    │
│  │  ├── libswscale.so     : 图像缩放/转换库                         │    │
│  │  ├── libswresample.so  : 音频重采样库                             │    │
│  │  └── libyuv.so         : YUV 处理库                               │    │
│  └──────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 三、目录结构说明

```
libffplayer/src/main/
├── cpp/                            # C++ 源代码
│   ├── CMakeLists.txt              # CMake 构建配置
│   ├── native-lib.cpp              # JNI 桥接层
│   │
│   ├── main/                       # 播放器核心
│   │   ├── FFMpegPlayer.h/cpp      # 播放器引擎（核心调度）
│   │   └── MediaClock.h/cpp        # 音视频同步主时钟（AUDIO/VIDEO/EXTERNAL 三种模式）
│   │
│   ├── decoder/                    # 解码器
│   │   ├── BaseDecoder.h/cpp       # 解码器基类
│   │   ├── VideoDecoder.h/cpp      # 视频解码器（支持硬件加速）
│   │   └── AudioDecoder.h/cpp      # 音频解码器（支持重采样）
│   │
│   ├── base/                       # 基础设施
│   │   └── AVPacketQueue.h/cpp     # 线程安全的编码包队列
│   │
│   ├── reader/                     # 读取器
│   │   ├── FFReader.h/cpp          # 读包器基类
│   │   └── FFVideoReader.h/cpp     # 视频帧读取器（抽帧）
│   │
│   ├── writer/                     # 写入器
│   │   └── FFVideoWriter.h/cpp     # 视频编码输出
│   │
│   ├── filter/                     # 滤镜
│   │   └── FFFilter.h/cpp          # AVFilter 滤镜封装
│   │
│   ├── settings/                   # 配置
│   │   └── CompileSettings.h       # 编码参数配置
│   │
│   ├── utils/                      # 工具
│   │   ├── ImageDef.h              # 图像格式定义
│   │   ├── FFConverter.h           # FFmpeg 枚举转换
│   │   ├── MutexObj.h/cpp          # 互斥锁封装
│   │   ├── TraceUtils.h/cpp        # 性能追踪
│   │   └── FFMpegUtils.cpp         # FFmpeg 工具函数
│   │
│   └── vendor/                     # 第三方依赖头文件
│       ├── ffmpeg/                 # FFmpeg 头文件
│       ├── libyuv/                 # libyuv 头文件
│       └── nlohmann/               # nlohmann JSON 库
│
├── java/com/xyq/libffplayer/       # Kotlin 层
│   ├── FFPlayer.kt                 # 播放器 Kotlin 封装
│   └── utils/FFMpegUtils.kt        # 工具类
│
├── libs/                           # 预编译动态库
│   ├── arm64-v8a/                  # 64位 ARM
│   └── armeabi-v7a/                # 32位 ARM
│
└── AndroidManifest.xml
```

---

## 四、核心类关系图

```
                         IPlayer (Java 接口)
                            │
                         FFPlayer (Kotlin)
                            │ JNI
                            ▼
                      native-lib.cpp
                            │
                            ▼
                    ┌──────────────┐
                    │ FFMpegPlayer │ ← 核心调度器
                    └──────┬───────┘
                           │ owns
            ┌──────────────┼──────────────┬──────────────┐
            │              │              │              │
            ▼              ▼              ▼              ▼
     ┌─────────────┐ ┌──────────┐ ┌──────────┐  ┌────────────┐
     │AVPacketQueue│ │  Video   │ │  Audio   │  │ MediaClock │
     │  (×2)       │ │ Decoder  │ │ Decoder  │  │ (shared)   │
     └─────────────┘ └────┬─────┘ └────┬─────┘  └─────┬──────┘
                          │            │              │
                          └──────┬─────┘    ◀────────┘
                                 │           inject (shared_ptr)
                          ┌──────┴─────┐
                          │BaseDecoder │ ← 持有 MediaClock 指针 + streamStartPtsMs
                          └────────────┘
```

---

## 五、线程模型

```
┌─────────────────────────────────────────────────────────────┐
│                       FFMpegPlayer                          │
│                                                             │
│  ┌──────────────────┐                                       │
│  │ ReadPacketThread  │ ← 读包线程                            │
│  │                  │                                       │
│  │  av_read_frame() ─┐                                      │
│  │                   │                                      │
│  └───────────────────┘                                      │
│                      │                                      │
│           ┌──────────┴──────────┐                           │
│           ▼                     ▼                           │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │ VideoPacketQueue │  │ AudioPacketQueue │  ← 线程安全队列   │
│  │   (容量: 50)     │  │   (容量: 50)     │                  │
│  └────────┬────────┘  └────────┬────────┘                   │
│           ▼                     ▼                           │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │ VideoDecodeLoop  │  │ AudioDecodeLoop  │ ← 解码线程        │
│  │                  │  │                  │                  │
│  │  decode()        │  │  decode()        │                  │
│  │  avSync()─┐      │  │  avSync()─┐      │                  │
│  │  doRender()│     │  │  doRender()│     │                  │
│  └────────────┼─────┘  └────────────┼─────┘                  │
│               │                     │                        │
│               ▼                     ▼                        │
│        ┌────────────────────────────────┐                    │
│        │         MediaClock             │ ← 共享主时钟         │
│        │  AUDIO_MASTER / VIDEO_MASTER / │                    │
│        │  EXTERNAL（含 pause/seek/EOF）  │                    │
│        └────────────────────────────────┘                    │
│                                                              │
│  同步机制:                                                    │
│  ├── MediaClock: 统一的"媒体时间"主时钟                         │
│  │   - AUDIO_MASTER: 音频更新, 视频对齐                        │
│  │   - VIDEO_MASTER: 视频更新, 无音轨时使用                     │
│  │   - EXTERNAL    : 系统时间外推, 音轨 EOF 后兜底              │
│  ├── per-stream EOF: mEofMutex + mPlayCompletedNotified     │
│  │   保证两路 EOF 并发时既不漏报也不重复报                       │
│  ├── MutexObj: 暂停/恢复/Seek 时线程间同步                     │
│  ├── AVPacketQueue: 生产者-消费者模型 (pthread_cond)           │
│  └── volatile 变量: mPlayerState, mVideoSeekPos, mAudioSeekPos│
└─────────────────────────────────────────────────────────────┘
```

---

## 六、播放流程

### 6.1 完整播放流程

```
Java: FFPlayer.init()
  └→ JNI: nativeInit()
       └→ new FFMpegPlayer()

Java: FFPlayer.prepare(path, surface)
  └→ JNI: nativePrepare()
       └→ FFMpegPlayer::prepare()
            ├── avformat_alloc_context()          // 分配格式上下文
            ├── avformat_open_input()             // 打开媒体文件
            ├── avformat_find_stream_info()       // 查找流信息
            ├── VideoDecoder::prepare()           // 准备视频解码器
            │   ├── 尝试硬件加速 (MediaCodec)
            │   └── 降级软件解码
            ├── AudioDecoder::prepare()           // 准备音频解码器
            │   └── 初始化重采样 (SwrContext)
            ├── 创建 VideoDecodeThread             // 视频解码线程
            ├── 创建 AudioDecodeThread             // 音频解码线程
            └── 配置 MediaClock                    // 同步时钟初始化
                ├── 计算 mediaStartMs = min(各流 start_time)
                ├── 设置 SyncType:
                │     有音轨 → AUDIO_MASTER
                │     仅视频 → VIDEO_MASTER
                │     都没有 → EXTERNAL
                ├── 各 Decoder.setStreamStartPtsMs(mediaStartMs)
                └── 各 Decoder.setMediaClock(shared)
                  (MediaClock 此时尚未"起跑"，nowMs=0)

Java: FFPlayer.start()
  └→ JNI: nativeStart()
       └→ FFMpegPlayer::start()
            ├── MediaClock.seekTo(0)             // 此刻才"起跑"主时钟
            └── 创建 ReadPacketThread             // 读包线程

ReadPacketThread:                                   // 读包循环
  └→ av_read_frame()
       ├── video packet → VideoPacketQueue.push()
       ├── audio packet → AudioPacketQueue.push()
       └── EOF 时：向两个队列各推一个 flush packet (size=0,data=nullptr)

VideoDecodeThread:                                  // 视频解码循环
  └→ VideoPacketQueue.pop()
       → VideoDecoder::decode()
         → avcodec_send_packet()
         → avcodec_receive_frame()
         → updateTimestamp() (best_effort_timestamp)
         → 格式转换 (如需要)
         → avSync() ──┬─ diff < -100ms : 丢帧 (MediaCodec 走 release_buffer(0))
                      ├─ diff > +5ms  : sleep min(diff, 1000ms)
                      └─ VIDEO_MASTER : MediaClock.updateVideoClock(pts)
         → doRender() (JNI 回调 Java)
       → EOF: handleStreamEof(isVideo=true)

AudioDecodeThread:                                  // 音频解码循环
  └→ AudioPacketQueue.pop()
       → AudioDecoder::decode()
         → avcodec_send_packet()
         → avcodec_receive_frame()
         → resample() (重采样)
         → updateTimestamp() (best_effort_timestamp)
         → avSync() ──┬─ AUDIO_MASTER : MediaClock.updateAudioClock(pts)
                      └─ 系统时间节拍 sleep, 防 AudioTrack 缓冲被冲爆
         → doRender() (JNI 回调 Java)
       → EOF: handleStreamEof(isVideo=false)
              ├── MediaClock.onAudioEnded()  // 降级到 EXTERNAL
              └── 双方 done 才 onPlayCompleted (atomic 去重)
```

### 6.2 Seek 流程

```
Java: FFPlayer.seek(position)
  └→ FFMpegPlayer::seek()
       ├── mIsSeek = true
       ├── 复位 EOF 双标志 + mPlayCompletedNotified
       │     (支持"播完 → seek 回去 → 再次播完 → 再次回调")
       ├── mVideoSeekPos = position     // 设置 Seek 目标
       ├── mAudioSeekPos = position
       ├── VideoPacketQueue.clear()     // 清空队列
       ├── AudioPacketQueue.clear()
       └── MediaClock 联动:
             ├── setSyncType(有音轨? AUDIO_MASTER : VIDEO_MASTER)
             │     (上次 audio EOF 可能已降级到 EXTERNAL，这里恢复)
             └── seekTo(position * 1000)  // 统一拨锚点，
                                          //  消除"音/视频各自修复时间戳"
                                          //  造成的短暂失同步窗口

VideoDecodeThread (检测到 Seek):
  ├── VideoPacketQueue.clear()
  ├── VideoDecoder::seek()
  │   ├── flush()                       // 清空解码缓冲
  │   ├── avformat_seek_file()          // 执行 Seek
  │   └── mFixStartTime = true          // legacy fallback 标记（无 MediaClock 时使用）
  ├── mVideoSeekPos = -1
  └── MutexObj.wakeUp()                 // 唤醒读包线程

AudioDecodeThread (同理):
  └── AudioDecoder::seek()

ReadPacketThread:
  └── 等待 Seek 完成后继续读包
```

### 6.3 音视频同步（MediaClock 子系统）

#### 设计目标
- 用一个**统一的"媒体时间"主时钟**取代音/视频解码器各自维护的"系统时间锚点"。
- 提供运行时可降级的多种 SyncType，覆盖"无音轨""音轨不从 0 开始""音轨中途结束"等边界。
- 暂停/Seek 期间保证时钟连续，避免恢复时画面跳一段。

#### SyncType
```
AUDIO_MASTER   ← 默认（存在音轨时使用）：音频更新 clock，视频对齐 clock
VIDEO_MASTER   ← 无音轨时使用：视频驱动 clock
EXTERNAL       ← 兜底：纯系统时钟外推
                  · 音轨提前 EOF 后自动降级到此模式
                  · MediaClock 起跑前的"未起跑"状态也用此实现
```

#### 内部状态模型
```
nowMs() = mAnchorMediaMs + (sysNow - mAnchorSysMs)        (运行中)
        = mPauseFrozenMediaMs                              (暂停中)
        = mAnchorMediaMs                                   (mAnchorSysMs<0, 即"未起跑")
```
关键操作：
| 调用 | 效果 |
|---|---|
| `seekTo(ptsMs)`         | 锚点 ← (ptsMs, sysNow)，清 staleness 标记 |
| `pause()`               | 冻结 nowMs，记 mPauseFrozenMediaMs |
| `resume()`              | 锚点 sys ← sysNow（保证 nowMs 连续，不跳） |
| `updateAudioClock(pts)` | 仅 AUDIO_MASTER 时把锚点设为 (pts, sysNow) |
| `updateVideoClock(pts)` | 仅 VIDEO_MASTER 时把锚点设为 (pts, sysNow) |
| `onAudioEnded()`        | AUDIO_MASTER → EXTERNAL，并以当前 nowMs 为新锚点 |

#### 同步阈值（`BaseDecoder.h`）
```
AV_SYNC_FORWARD_NOSLEEP_MS  = 5    // 视频领先 ≤5ms 直接渲染
AV_SYNC_FORWARD_MAX_SLEEP_MS = 1000 // 单次 sleep 上限
AV_SYNC_DROP_MS             = 100  // 视频落后 >100ms 丢帧
AV_SYNC_AUDIO_PACE_CAP_MS   = 500  // 音频节拍 sleep 上限
```

#### 视频侧 avSync 决策树
```
normPts = videoPts - streamStartPtsMs
diff    = normPts - MediaClock.nowMs()

diff < -100ms   → 丢帧（return false）
                  · MediaCodec 路径必须 av_mediacodec_release_buffer(buf, 0)
                    归还输出槽位，否则会耗尽
diff > +5ms     → av_usleep(min(diff, 1000ms) * 1000)
其他            → 立即渲染

if SyncType == VIDEO_MASTER:
    MediaClock.updateVideoClock(normPts)
```

#### 音频侧 avSync
```
normPts = audioPts - streamStartPtsMs

if SyncType == AUDIO_MASTER:
    MediaClock.updateAudioClock(normPts)   // 推动主时钟

# 本地系统时间节拍（兜底）：避免 NON_BLOCKING write 把 AudioTrack 缓冲冲爆
diff = normPts - (sysNow - localStartTime)
if diff > +5ms:
    av_usleep(min(diff, 500ms) * 1000)
return true   # 音频不丢样本
```

#### 时间戳归一化（多流 start_time 对齐）
- prepare 时计算 `mediaStartMs = min(stream->start_time)`
- 每条流的 `streamStartPtsMs` 都设为同一个 `mediaStartMs`
- 解码器内部时间换算：`normPts = (pts * av_q2d(time_base) * 1000) - streamStartPtsMs`
- 这样无论"音频晚开始"还是"视频晚开始"，都在统一坐标系内对齐

#### PTS 提取兼容性
按优先级：
1. `frame->best_effort_timestamp`（FFmpeg 综合 pts/dts/解码顺序的最优值，能正确处理 B 帧）
2. `frame->pts`
3. `frame->pkt_dts`
4. 都缺失时：用 `frame->pkt_duration` 累加（VFR 兜底）

> 不再使用已 deprecated 的 `frame->coded_picture_number`。

#### Per-stream EOF 双标志（音轨/视频长度不一致）
```
mEofMutex (std::mutex)        守护下面两个布尔
mVideoStreamEnded
mAudioStreamEnded
mPlayCompletedNotified (atomic_bool)  保证 onPlayCompleted 只发一次

handleStreamEof(isVideo):
    lock(mEofMutex)
        set 自己的 ended = true
        if !isVideo: MediaClock.onAudioEnded()   // 音频结束 → clock 降级
        videoDone = !mVideoDecoder || mVideoStreamEnded
        audioDone = !mAudioDecoder || mAudioStreamEnded
        shouldComplete = videoDone && audioDone
    unlock
    if shouldComplete: onPlayCompleted (CAS 去重)
```

为什么需要互斥锁：两路 EOF 可能"几乎同时"到达，仅靠 `volatile` 在 SMP 上会因为写写/读读乱序导致：
- A 写 audioEnded=true / B 写 videoEnded=true 都尚未传播
- A 读 videoEnded=false / B 读 audioEnded=false → 双方都跳过 → **永远不通知完成**

`mEofMutex` 串行化"写自己 + 读对方"消除该窗口；`mPlayCompletedNotified` 的 atomic CAS 保证哪怕都决定要通知也只发一次。

#### 边界场景一览
| 场景 | 行为 |
|---|---|
| 无音轨 | prepare 选 VIDEO_MASTER，video 自驱动 clock |
| 仅音轨（理论场景） | AUDIO_MASTER，无视频 |
| 音视频起点不一致（静默前导） | 用 min(start_time) 归一化，视频先行用 EXTERNAL 外推；首帧音频 update 后无缝接管 |
| 音轨比视频短 | audio EOF → onAudioEnded() 切 EXTERNAL，video 按系统时钟把剩下的帧播完 → video EOF 才回调 onPlayCompleted |
| 视频比音频短 | video EOF 时不通知（audio 未结束），等 audio EOF |
| 暂停 / 恢复 | clock.pause() 冻结 → clock.resume() 锚点平移，nowMs 连续 |
| Seek（含播完后回拖） | 复位 EOF 标志 + 恢复 SyncType + clock.seekTo |
| 几乎同时 EOF 的并发 | 互斥锁 + atomic 保证不漏发也不重发 |

---

### 6.4 渲染数据流

```
doRender() 根据 AVFrame 格式分发:

  YUV420P    → 分离 Y/U/V 三个平面 → JNI 回调 Java
  NV12       → 分离 Y/UV 两个平面  → JNI 回调 Java
  RGBA       → 直接传递 RGBA 数据   → JNI 回调 Java
  RGB24      → 直接传递 RGB 数据    → JNI 回调 Java
  MEDIACODEC → av_mediacodec_release_buffer() → 直接渲染到 Surface
  FLTP(音频) → 重采样后的 PCM 数据  → JNI 回调 Java
```

---

## 七、FFmpeg 模块使用汇总

| FFmpeg 库 | 用途 | 使用位置 |
|-----------|------|---------|
| **libavformat** | 封装/解封装（打开文件、读包、Seek） | FFMpegPlayer, FFReader, FFVideoWriter |
| **libavcodec** | 编解码（H.264/H.265/音频解码、H.264/GIF编码） | VideoDecoder, AudioDecoder, FFVideoWriter, FFReader |
| **libavutil** | 工具函数（时间处理、内存管理、帧管理） | 全局使用 |
| **libswscale** | 图像缩放和像素格式转换 | VideoDecoder, FFVideoReader, FFVideoWriter |
| **libswresample** | 音频重采样 | AudioDecoder |
| **libavfilter** | 视频滤镜（drawgrid 等） | FFFilter |

---

## 八、关键设计模式

### 8.1 生产者-消费者模式
- **生产者**：ReadPacketThread 读取 AVPacket
- **队列**：AVPacketQueue（线程安全，容量限制 50）
- **消费者**：VideoDecodeThread / AudioDecodeThread

### 8.2 模板方法模式
- `BaseDecoder` 定义解码器接口
- `VideoDecoder` / `AudioDecoder` 实现具体逻辑

### 8.3 回调模式
- C++ → Java：通过 JNI 回调（PlayerJniContext）
- 解码器 → 播放器：通过 `std::function` 回调（OnFrameArrived）

### 8.4 状态机
```
UNKNOWN → PREPARE → START → PLAYING ⇄ PAUSE → STOP
```

### 8.5 共享主时钟 + 策略降级（MediaClock）
- **共享对象**：`MediaClock` 由 `FFMpegPlayer` 拥有（`std::shared_ptr`），注入到两个解码器
- **策略 (SyncType)**：AUDIO_MASTER / VIDEO_MASTER / EXTERNAL，运行时可降级
- **降级时机**：音轨 EOF → `onAudioEnded()` 自动从 AUDIO_MASTER 切到 EXTERNAL
- **线程安全**：内部 `std::mutex` 保护所有读写

### 8.6 双标志 + 一次性通知（per-stream EOF）
- `std::mutex` 串行化"写自己 + 读对方"，消除并发窗口
- `std::atomic<bool>` + CAS 保证 `onPlayCompleted` 只回调一次
- 在 `prepare/seek/stop` 路径上同步复位，支持"播完 → seek → 再播完"

---

## 九、硬件加速策略

```
                   有 Surface?
                      │
              ┌───────┴───────┐
              │ Yes           │ No
              ▼               ▼
         H.264/H.265?    软件解码
              │
        ┌─────┴─────┐
        │ Yes       │ No
        ▼           ▼
  尝试 MediaCodec  软件解码
        │
   ┌────┴────┐
   │ 成功    │ 失败
   ▼         ▼
 硬件解码   降级软件解码
```

---

## 十、像素格式处理流程

```
解码输出 AVFrame
    │
    ├── MEDIACODEC → 直接渲染到 Surface
    ├── YUV420P   → 直接输出（偶数宽高）
    ├── NV12      → 直接输出（偶数宽高）
    ├── RGB24     → 直接输出
    ├── YUV420P10LE → swsScale() → RGBA → 输出
    └── 其他格式   → swsScale() → RGBA → 输出
```

---

## 十一、音频处理流程

```
解码 → AVFrame (原始格式, 如 FLTP)
    │
    └→ resample (SwrContext)
         ├── 输入: 原始采样率, 原始声道布局, 原始格式
         ├── 输出: 44100Hz, 立体声, S16
         └→ PCM 数据 → JNI 回调 Java 播放
```

---

## 十二、依赖关系

### 外部依赖
| 库 | 版本 | 用途 |
|---|---|---|
| FFmpeg | 6.0+ | 音视频编解码引擎 |
| libyuv | - | YUV 图像处理（缩放、格式转换） |
| nlohmann/json | - | JSON 序列化（媒体信息输出） |

### 内部依赖
| 模块 | 依赖 |
|---|---|
| libffplayer | libbase (IPlayer 接口), libutils |



```
mAnchorMediaMs，"记录最近一次校准点的媒体时间。开始播放、恢复播放、Seek、每一帧主时钟流（audio 或 video）的上报都会刷新它"
mAnchorSysMs，"记录最近一次校准点对应的系统时间，与上面成对出现"

return mAnchorMediaMs + (sysNowMs() - mAnchorSysMs);
//     └────┬────┘     └────────────┬────────────┘
//   最近一次校准时        从那次校准到现在
//   "媒体到了哪里"        系统时间走了多久
```


```
mStartTimeMsForSync 是 "PTS=0 这一刻对应的系统墙钟时间"，用作 legacy 模式下的音视频同步参考点：
首帧时建立（now）
Seek 后用 mFixStartTime 标记延迟修正（now - normPts）
avSync 通过 now - mStartTimeMsForSync 推导"应播放进度"，与帧 PTS 比较来决定渲染/等待/丢帧
现已被 MediaClock 取代为主路径，只在未注入 MediaClock 时作为兜底使用。
```

```
2. 关键帧表（"每个 I 帧的时间戳"）
FFmpeg 内部有一个 AVStream::index_entries，对绝大多数容器来说关键帧已经索引化了：

AVStream *st = mFtx->streams[videoIndex];
int n = avformat_index_get_entries_count(st);   // FFmpeg ≥ 4.4
for (int i = 0; i < n; ++i) {
    const AVIndexEntry *e = avformat_index_get_entry(st, i);
    int64_t ptsMs = (int64_t)(e->timestamp * av_q2d(st->time_base) * 1000);
    bool isKey = e->flags & AVINDEX_KEYFRAME;   // mp4/mkv 通常只索引关键帧
    int64_t bytePos = e->pos;
}
代价：O(K)（K 是关键帧数），不需要解码、不需要扫流。这是实现"关键帧缩略图列表"或"快速跳到关键帧"的标准做法，几乎所有视频编辑/播放器都是这么做的。

注意：旧版 FFmpeg 直接访问 st->internal->index_entries，6.x 之后建议用 avformat_index_get_entry() 这套 API。
```