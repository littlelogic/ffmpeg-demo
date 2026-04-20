# libffplayer 模块架构设计文档

## 一、模块概述

`libffplayer` 是一个基于 FFmpeg 的 Android 音视频播放器引擎模块，通过 JNI 将 C++ 层的播放能力暴露给 Java/Kotlin 层使用。

### 核心能力
- 🎬 **视频解码**：支持 H.264/H.265 软解和 Android MediaCodec 硬件加速
- 🔊 **音频解码**：支持多种音频格式解码和重采样
- 🔄 **音视频同步**：基于时间戳的音视频同步机制
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
│  │  └── 回调: onVideoFrameArrived / onAudioFrameArrived / ...       │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                              │ JNI                                        │
├──────────────────────────────┼───────────────────────────────────────────┤
│                          C++ Native 层                                   │
│                              │                                           │
│  ┌───────────────────────────┴──────────────────────────────────────┐    │
│  │  native-lib.cpp  (JNI 桥接层)                                    │    │
│  │  ├── JNI_OnLoad: 初始化                                          │    │
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
│  │  └── Seek 协调控制                                               │    │
│  └──┬──────────────────┬───────────────┬───────────────┬────────────┘    │
│     │                  │               │               │                 │
│  ┌──┴────────┐  ┌──────┴─────┐  ┌──────┴──────┐  ┌────┴──────┐         │
│  │ AVPacket  │  │  Video     │  │  Audio      │  │  FFFilter │         │
│  │ Queue     │  │  Decoder   │  │  Decoder    │  │           │         │
│  │  base/    │  │  decoder/  │  │  decoder/   │  │  filter/  │         │
│  └───────────┘  └──────┬─────┘  └─────────────┘  └───────────┘         │
│                        │                                                 │
│                  ┌─────┴──────┐                                          │
│                  │  Base      │                                          │
│                  │  Decoder   │                                          │
│                  │  decoder/  │                                          │
│                  └────────────┘                                          │
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
                           │
            ┌──────────────┼──────────────┐
            │              │              │
            ▼              ▼              ▼
     ┌─────────────┐ ┌──────────┐ ┌──────────┐
     │AVPacketQueue│ │  Video   │ │  Audio   │
     │  (×2)       │ │ Decoder  │ │ Decoder  │
     └─────────────┘ └────┬─────┘ └────┬─────┘
                          │            │
                          └──────┬─────┘
                                 │
                          ┌──────┴─────┐
                          │BaseDecoder │
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
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ VideoPacketQueue │  │ AudioPacketQueue │  ← 线程安全队列  │
│  │   (容量: 50)     │  │   (容量: 50)     │                  │
│  └────────┬────────┘  └────────┬────────┘                  │
│           ▼                     ▼                           │
│  ┌─────────────────┐  ┌─────────────────┐                  │
│  │ VideoDecodeLoop  │  │ AudioDecodeLoop  │ ← 解码线程      │
│  │                  │  │                  │                  │
│  │  decode()        │  │  decode()        │                  │
│  │  avSync()        │  │  avSync()        │                  │
│  │  doRender()      │  │  doRender()      │                  │
│  └──────────────────┘  └──────────────────┘                  │
│                                                             │
│  同步机制:                                                   │
│  ├── MutexObj: 暂停/恢复/Seek 时线程间同步                    │
│  ├── AVPacketQueue: 生产者-消费者模型 (pthread_cond)          │
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
            └── 创建 AudioDecodeThread             // 音频解码线程

Java: FFPlayer.start()
  └→ JNI: nativeStart()
       └→ FFMpegPlayer::start()
            └── 创建 ReadPacketThread              // 读包线程

ReadPacketThread:                                   // 读包循环
  └→ av_read_frame()
       ├── video packet → VideoPacketQueue.push()
       └── audio packet → AudioPacketQueue.push()

VideoDecodeThread:                                  // 视频解码循环
  └→ VideoPacketQueue.pop()
       → VideoDecoder::decode()
         → avcodec_send_packet()
         → avcodec_receive_frame()
         → 格式转换 (如需要)
         → avSync() (音视频同步)
         → doRender() (JNI 回调 Java)

AudioDecodeThread:                                  // 音频解码循环
  └→ AudioPacketQueue.pop()
       → AudioDecoder::decode()
         → avcodec_send_packet()
         → avcodec_receive_frame()
         → resample() (重采样)
         → avSync() (音视频同步)
         → doRender() (JNI 回调 Java)
```

### 6.2 Seek 流程

```
Java: FFPlayer.seek(position)
  └→ FFMpegPlayer::seek()
       ├── mIsSeek = true
       ├── mVideoSeekPos = position     // 设置 Seek 目标
       ├── mAudioSeekPos = position
       ├── VideoPacketQueue.clear()     // 清空队列
       └── AudioPacketQueue.clear()

VideoDecodeThread (检测到 Seek):
  ├── VideoPacketQueue.clear()
  ├── VideoDecoder::seek()
  │   ├── flush()                      // 清空解码缓冲
  │   ├── avformat_seek_file()         // 执行 Seek
  │   └── mFixStartTime = true         // 标记修复时间戳
  ├── mVideoSeekPos = -1
  └── MutexObj.wakeUp()                // 唤醒读包线程

AudioDecodeThread (同理):
  └── AudioDecoder::seek()

ReadPacketThread:
  └── 等待 Seek 完成后继续读包
```

### 6.3 渲染数据流

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

