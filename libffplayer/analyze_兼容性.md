基于对源码（`FFMpegPlayer.cpp`、`VideoDecoder.cpp`、`AudioDecoder.cpp`、`CMakeLists.txt`、`ARCHITECTURE.md`）的分析，下面从**容器格式、视频编码、音频编码、像素格式、协议来源、平台**几个维度系统评估这个播放器的兼容性。

## 一、底层基础：用的是完整版 FFmpeg

`CMakeLists.txt:49-75` 链接了 FFmpeg 全套动态库：

```49:75:libffplayer/src/main/cpp/CMakeLists.txt
add_library( avcodec ...
add_library( avfilter ...
add_library( swscale ...
add_library( swresample ...
add_library( avformat ...
```

播放器**没有自己写解封装/解码**，调用的是标准 FFmpeg API（`avformat_open_input` / `avcodec_find_decoder`）。所以理论上 FFmpeg 编译时启用了什么 demuxer/decoder，这个播放器就支持什么。这是兼容性的"上限"。

> 项目里 `libffplayer/libs/${ABI}/lib*.so` 是预编译的 FFmpeg 二进制，未在仓库里看到 `build_ffmpeg.sh`，所以**实际开了哪些 demuxer/decoder 取决于你这套预编译 .so 的 configure 参数**。如果是常规 full build，下面的"理论支持"就是"实际支持"；如果做了裁剪，下面会说明哪几个格式有"明显风险点"。

## 二、容器格式（Demuxing）兼容性

容器层完全交给 `avformat_open_input` 处理：

```75:75:libffplayer/src/main/cpp/main/FFMpegPlayer.cpp
    int ret = avformat_open_input(&mFtx, path.c_str(), nullptr, nullptr);
```

代码里没有任何按扩展名挑 demuxer 的逻辑，所以理论上 **mp4 / mov / mkv / webm / flv / ts / m4a / mp3 / aac / wav / 3gp / avi / m4v / hls(m3u8) / dash** 等绝大多数 FFmpeg 支持的容器都能解析。代码上没看到任何特殊处理，意味着：

- 不挑容器，**主流格式都没问题**。
- HLS / DASH / RTMP 等需要协议层支持，要看预编译的 FFmpeg 是否带 `--enable-protocol=*` 和 `--enable-network`。
- 没看到 `avformat_network_init()` 调用，说明**当前主要面向本地文件**；网络流大概率能放，但更多需要看 FFmpeg 编译选项。

## 三、视频编码兼容性 —— 兼容面的"主要短板"在这

视频侧分两条路径：

### 1. 硬件解码（MediaCodec）：只列了 H.264 / H.265

```96:107:libffplayer/src/main/cpp/decoder/VideoDecoder.cpp
    switch (params->codec_id) {
        case AV_CODEC_ID_H264:
            mediacodecName = "h264_mediacodec";
            break;
        case AV_CODEC_ID_HEVC: ///H.265 ，等同：AV_CODEC_ID_H265
            mediacodecName = "hevc_mediacodec";
            break;
        default:
            useHwDecoder = false;
            LOGE("format(%d) not support hw decode, maybe rebuild ffmpeg so", params->codec_id)
            break;
    }
```

**短板**：硬件加速白名单只有 H.264 和 HEVC。FFmpeg/MediaCodec 在新版本里其实还能跑 **VP8、VP9、AV1、MPEG2、MPEG4、 ** 的硬件解码（`vp9_mediacodec`、`av1_mediacodec` 等），但代码里没有列。这意味着：
- VP9 / AV1（YouTube/WebM 常用）**只能走软解**，1080p 以上有性能压力。
- 老格式 MPEG2/MPEG4/Xvid 也只能软解。

### 2. 软件解码降级路径（兜底全靠 FFmpeg）

```166:182:libffplayer/src/main/cpp/decoder/VideoDecoder.cpp
                mVideoCodec = avcodec_find_decoder(params->codec_id);  // 降级到软件解码
            ...
        mVideoCodec = avcodec_find_decoder(params->codec_id);
```

走到这条路，**只要 FFmpeg 编进来了对应解码器，就能解**。所以 H.264/H.265/VP8/VP9/AV1/MPEG2/MPEG4/Xvid/ProRes/HEVC 10bit ... 都"能播"，性能取决于设备。

### 3. 还有一些"硬件解码兼容性"的小坑

```111:114:libffplayer/src/main/cpp/decoder/VideoDecoder.cpp
    if (useHwDecoder && (params->format != AV_PIX_FMT_YUV420P && params->format != AV_PIX_FMT_YUV420P10LE)) {
        useHwDecoder = false;
        LOGE("force use sw decoder for format: %s", av_get_pix_fmt_name(AVPixelFormat(params->format)))
    }
```

仅当源像素格式是 **YUV420P 或 YUV420P10LE** 才允许走硬件。代码注释也说了"这个兼容性的判断需要完善"。也就是说：
- YUV422P / YUV444P / NV21 等不常见格式 → 强制软解。
- 10bit HEVC（HDR10 常用 YUV420P10LE）放行硬解，但**真正能不能 HDR10 显示** 取决于 Surface/渲染端，这个文件管不到。

### 4. prepare 失败时的全局回退

`FFMpegPlayer.cpp:115-121` 还有一道兜底：

```115:121:libffplayer/src/main/cpp/main/FFMpegPlayer.cpp
        if (surface != nullptr && !videoPrepared) {
            mVideoDecoder->release();
            LOGE("[video] hw decoder prepare failed, fallback to software decoder")
            mVideoDecoder->setSurface(nullptr);
            videoPrepared = mVideoDecoder->prepare();
            isHwDecoder = false;
        }
```

硬解 prepare 失败会自动重来一遍走软解。**鲁棒性是好的**。

## 四、音频编码兼容性 —— 完全交给 FFmpeg

```36:38:libffplayer/src/main/cpp/decoder/AudioDecoder.cpp
    AVCodecParameters *params = mFtx->streams[getStreamIndex()]->codecpar;
    mAudioCodec = avcodec_find_decoder(params->codec_id);
```

直接 `avcodec_find_decoder(codec_id)`，**没有白名单**。所以 AAC / MP3 / Opus / Vorbis / FLAC / AC3 / EAC3 / PCM / ALAC 这些几乎都能解。

输出端做了**统一重采样**：

```71:82:libffplayer/src/main/cpp/decoder/AudioDecoder.cpp
    mSwrContext = swr_alloc_set_opts(
            nullptr,
            AV_CH_LAYOUT_STEREO,
            AV_SAMPLE_FMT_S16,
            44100,
            ...
```

任何源都被强制转成 **44.1kHz / Stereo / S16**，再交给 Java 层 AudioTrack。这意味着：
- 兼容性极强（不管原始多少声道、什么采样率，都能播）；
- 但**有损"原汁原味"**：48kHz 源会被重采样到 44.1kHz；5.1 声道会被混到立体声；24bit / float 也会被截到 16bit。
- 如果上层希望支持 Hi-Res / 多声道环绕，需要改这里。

## 五、像素格式 → 渲染兼容性

```343:370:libffplayer/src/main/cpp/decoder/VideoDecoder.cpp
        if (mAvFrame->format == hw_pix_fmt || mAvFrame->format == AV_PIX_FMT_RGB24 || (isEvenEdge && (mAvFrame->format == AV_PIX_FMT_YUV420P || mAvFrame->format == AV_PIX_FMT_NV12))) {
            ...直接渲染
        } else if (mAvFrame->format != AV_PIX_FMT_NONE) {
            // 转 RGBA 后再渲染
            sws_scale(... AV_PIX_FMT_RGBA ...);
        }
```

直通格式是 `MEDIACODEC / RGB24 / YUV420P / NV12`，其余（YUV422P / YUV444P / YUV420P10LE / NV21 / GRAY8 等）都通过 `sws_scale` 转成 RGBA 软件转换后再上屏。

`FFMpegPlayer::doRender`（`FFMpegPlayer.cpp:672-771`）的 Java 回调也只处理这五种：YUV420P / NV12 / RGBA / RGB24 / MEDIACODEC + 音频 FLTP。**只要源能被 FFmpeg 解出来，就能映射到这五种之一**，理论无死角。代价是**奇异格式都要走 CPU 上的 swscale**（开销不小）。

注意一个限制：

```340:340:libffplayer/src/main/cpp/decoder/VideoDecoder.cpp
        bool isEvenEdge = isEven(mAvFrame->width) && isEven(mAvFrame->height);
```

宽高奇数的 YUV420P / NV12 会**被强制走 RGBA 转换路径**（不是不能播，是开销大一点）。

## 六、Seek / 旋转 / DAR 等元信息

- Seek：用 `avformat_seek_file(... AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_FRAME)`（`VideoDecoder.cpp:598`、`AudioDecoder.cpp:258`），对常见 mp4/mkv/mov 都精确；对**只能按 byte seek** 的格式（如裸 H.264 流、某些 ts）会差一些。
- 旋转：从 stream metadata 读 rotation（`VideoDecoder.cpp:671-674`），常见 mp4 自带的 90°/180°/270° 都能拿到。
- 显示宽高比：通过 `av_guess_sample_aspect_ratio` 计算 DAR（`VideoDecoder.cpp:687-704`），非方形像素的 PAL/NTSC DV 也能正确显示比例。

## 七、协议 / 来源兼容性

`avformat_open_input(path)` 直接使用传入的字符串：本地文件路径是肯定能播的；`http://` / `https://` / `rtmp://` / `rtsp://` 等取决于 FFmpeg `--enable-protocol=*`。代码里没有 `avformat_network_init()`，**网络流支持需要预编译 FFmpeg 时主动开启**。

## 八、平台 / 架构兼容性

- 目标平台：**Android only**（`av_jni_set_java_vm`、`MediaCodec`、`Surface`、`AudioTrack`，CMake 链接 `android` 系统库）。
- Surface 上的硬解走 MediaCodec（NDK API ≥ 21 普及），硬件 Surface 直渲性能好；但**不带 Surface 时（比如纯抽帧）**，会走软解 + RGBA 转换，与平台无关，跨设备也能跑。
- ABI：取决于 `libffplayer/libs/${CMAKE_ANDROID_ARCH_ABI}/`，常见就是 `armeabi-v7a` / `arm64-v8a`，理论也可加 `x86_64`。

## 九、综合评估表

| 维度 | 兼容情况 | 备注 |
|---|---|---|
| 容器（mp4/mkv/mov/webm/ts/flv/avi/m3u8…） | **优** | 取决于 FFmpeg 编译；代码无白名单 |
| H.264 / H.265 | **优** | 硬解 + 软解双路径，自动降级 |
| VP9 / AV1 | **中** | 仅软解（白名单未开 `vp9_mediacodec`/`av1_mediacodec`） |
| MPEG2 / MPEG4 / Xvid | **中** | 仅软解 |
| 10bit HEVC（HDR10 内容） | **中** | 解码路径放行了，但显示端是否 HDR 取决于 Surface |
| 主流像素格式 YUV420P / NV12 | **优** | 直通渲染 |
| 罕见像素格式 YUV422P/YUV444P/10bit/NV21 | **中** | swscale 转 RGBA，开销较高 |
| AAC / MP3 / Opus / FLAC / AC3 等音频 | **优** | 全部交给 `avcodec_find_decoder`，无白名单 |
| 多声道 / 高采样率音频 | **差** | 强制下混到 Stereo / 44.1kHz / S16 |
| 网络流（HTTP/HTTPS/HLS/RTMP/RTSP） | **取决于 FFmpeg 编译** | 代码不阻挡，但没主动初始化 protocol；需 FFmpeg `--enable-protocol`/`--enable-network` |
| 旋转 / DAR / 字符元信息 | **优** | 完整支持 |
| 字幕 | **不支持** | `prepare()` 只查 `AVMEDIA_TYPE_VIDEO/AUDIO`，没处理 SUBTITLE 流 |
| 多视频/多音频流（音轨切换） | **不支持** | 只取最后一个匹配的 stream，无切换 API |

## 十、几个潜在改进点（如果要扩展兼容性）

1. **硬解白名单加 VP9 / AV1**：在 `VideoDecoder.cpp:96-107` 加 `case AV_CODEC_ID_VP9 / AV_CODEC_ID_AV1`，对应 `vp9_mediacodec` / `av1_mediacodec`，即可在新设备上硬解 YouTube 同源内容。
2. **像素格式白名单更宽**：`VideoDecoder.cpp:111` 那个 YUV420P / YUV420P10LE 限制可以去掉，让 FFmpeg 自己尝试硬解，失败再降级。
3. **音频 keep-original**：`AudioDecoder.cpp:71-82` 写死的 44.1kHz/Stereo/S16 其实可以读源参数，按设备 AudioTrack 能力选择最接近的高保真组合。
4. **字幕支持**：`FFMpegPlayer::prepare` 里加 `AVMEDIA_TYPE_SUBTITLE` 分支，结合 ASS/SRT 渲染。
5. **多音轨切换**：保存所有 audio stream 索引并暴露切换 API。
6. **网络流**：`avformat_open_input` 之前 `avformat_network_init()`，并暴露 `AVDictionary` 参数（如 `rw_timeout`、`reconnect`）。

## 总结

**对"主流本地视频"兼容性很好**：mp4/mov/mkv 容器 + H.264/H.265 视频 + AAC/MP3 音频，硬解 + 软解 + 自动降级 + Seek + 旋转都齐全，是这个播放器最稳的舒适区。

**有明显短板的场景**：
- 新编码 VP9 / AV1（只软解）
- 字幕（不支持）
- 多音轨切换（不支持）
- Hi-Res / 多声道音频（被强制下混到 44.1kHz Stereo S16）
- 网络流（要看 FFmpeg 是否带协议）

整体设计是"稳健 + 可扩展"，瓶颈不在播放器代码本身，而在硬解白名单和编译时 FFmpeg 配置——拓宽这两块，兼容面立刻能上一个台阶。