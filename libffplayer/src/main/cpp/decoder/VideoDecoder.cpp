/**
 * @file VideoDecoder.cpp
 * @brief 视频解码器实现文件
 *
 * 功能说明：
 * - 封装 FFmpeg 视频解码功能，支持硬件加速和软件解码
 * - 支持 H.264 和 H.265(HEVC) 编码格式
 * - 支持 Android MediaCodec 硬件加速
 * - 负责视频帧的解码、格式转换和时间戳管理
 * - 支持音视频同步、Seek 操作
 */

#include "VideoDecoder.h"
#include "header/Logger.h"
#include "header/CommonUtils.h"
#include "../reader/FFVideoReader.h"

// 全局变量：保存硬件解码器支持的像素格式
static enum AVPixelFormat hw_pix_fmt = AV_PIX_FMT_NONE;

/**
 * @brief 获取硬件解码支持的像素格式回调函数
 * @details FFmpeg 在初始化硬件解码器时会调用此回调函数，用于选择合适的硬件像素格式
 *
 * @param ctx 编码上下文
 * @param pix_fmts FFmpeg 提供的可用像素格式数组
 * @return 返回匹配的硬件像素格式，如果不支持则返回 AV_PIX_FMT_NONE
 */
static enum AVPixelFormat get_hw_format(AVCodecContext *ctx,
                                        const enum AVPixelFormat *pix_fmts) {
    const enum AVPixelFormat *p;

    // 遍历可用的像素格式，找到硬件支持的格式
    for (p = pix_fmts; *p != -1; p++) {
        if (*p == hw_pix_fmt) {
            LOGE("get HW surface format: %d", *p);
            return *p;
        }
    }

    LOGE("Failed to get HW surface format");
    return AV_PIX_FMT_NONE;
}

/**
 * @brief 构造函数
 * @param index 视频流索引
 * @param ftx 格式化上下文指针
 */
VideoDecoder::VideoDecoder(int index, AVFormatContext *ftx): BaseDecoder(index, ftx) {}

/**
 * @brief 析构函数
 * @details 自动调用 release() 释放所有资源
 */
VideoDecoder::~VideoDecoder() {
    release();
}

/**
 * @brief 设置渲染 Surface（用于硬件加速）
 * @param surface Android 的 Surface 对象（Java 对象引用）
 * @details 硬件加速解码时需要提供 Surface 对象以直接将解码帧输出到屏幕
 */
void VideoDecoder::setSurface(jobject surface) {
    mSurface = surface;
}

/**
 * @brief 准备解码器
 * @details 这是解码前的初始化流程，主要步骤包括：
 *   1. 解析视频参数（宽高、编码格式等）
 *   2. 选择合适的解码器（硬件或软件）
 *   3. 初始化解码上下文
 *   4. 配置硬件加速（如果可用）
 *   5. 打开解码器
 *   6. 初始化帧缓冲和时间戳
 *   7. 收集媒体信息元数据
 *
 * @return 成功返回 true，失败返回 false
 */
bool VideoDecoder::prepare() {
    // ====== 第一步：解析视频流参数 ======
    AVStream *stream = mFtx->streams[getStreamIndex()];

    AVCodecParameters *params = stream->codecpar;
    mWidth = params->width;
    mHeight = params->height;

    // ====== 第二步：选择解码器（硬件优先） ======
    bool useHwDecoder = mSurface != nullptr;  // 有 Surface 才能用硬件加速
    std::string mediacodecName;

    // 根据编码格式选择对应的硬件解码器名称
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

    // 检查像素格式是否支持硬件解码（兼容性判断）
    // 这个兼容性的判断需要完善
    if (useHwDecoder && (params->format != AV_PIX_FMT_YUV420P && params->format != AV_PIX_FMT_YUV420P10LE)) {
        useHwDecoder = false;
        LOGE("force use sw decoder for format: %s", av_get_pix_fmt_name(AVPixelFormat(params->format)))
    }

    if (useHwDecoder) {
        // ====== 第三步：配置硬件加速 ======
        // 尝试查找 MediaCodec 硬件设备
        AVHWDeviceType type = av_hwdevice_find_type_by_name("mediacodec");
        /*if (type == AV_HWDEVICE_TYPE_NONE) {
            // 如果没找到，遍历所有可用的硬件设备类型
            while ((type = av_hwdevice_iterate_types(type)) != AV_HWDEVICE_TYPE_NONE) {
                LOGI("av_hwdevice_iterate_types: %d", type)
                // ✅ 可以在这里选择一个可用的硬件设备作为备选方案
            }
        }*/
        if (type == AV_HWDEVICE_TYPE_NONE) {
            // 如果没找到，遍历所有可用的硬件设备类型
            AVHWDeviceType availableType = AV_HWDEVICE_TYPE_NONE;
            while ((availableType = av_hwdevice_iterate_types(availableType)) != AV_HWDEVICE_TYPE_NONE) {
                LOGI("available hw device type: %s", av_hwdevice_get_type_name(availableType));
                // ✅ 可以在这里选择一个可用的硬件设备作为备选方案
                if (availableType == AV_HWDEVICE_TYPE_VIDEOTOOLBOX) {
                    type = availableType;  // 选择作为备选
                    break;
                }
            }
        }

        // 按名称查找硬件解码器（如 h264_mediacodec）
        const AVCodec *mediacodec = avcodec_find_decoder_by_name(mediacodecName.c_str());
        if (mediacodec) {
            LOGE("find %s", mediacodecName.c_str())

            // 查询硬件解码器的配置信息
            for (int i = 0; ; ++i) {
                const AVCodecHWConfig *config = avcodec_get_hw_config(mediacodec, i);
                if (!config) {
                    LOGE("Decoder: %s does not support device type: %s", mediacodec->name,
                         av_hwdevice_get_type_name(type))
                    break;
                }
                // 找到支持硬件设备上下文的配置
                if (config->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX && config->device_type == type) {
                    // 保存硬件像素格式（通常是 AV_PIX_FMT_MEDIACODEC）
                    hw_pix_fmt = config->pix_fmt;
                    LOGE("Decoder: %s support device type: %s, hw_pix_fmt: %d, AV_PIX_FMT_MEDIACODEC: %d", mediacodec->name,
                         av_hwdevice_get_type_name(type), hw_pix_fmt, AV_PIX_FMT_MEDIACODEC);
                    break;
                }
            }

            // 根据是否找到硬件配置，选择硬件或软件解码器
            if (hw_pix_fmt == AV_PIX_FMT_NONE) {
                LOGE("not use surface decoding")
                mVideoCodec = avcodec_find_decoder(params->codec_id);  // 降级到软件解码
            } else {
                mVideoCodec = mediacodec;
                // 创建硬件设备上下文
                int ret = av_hwdevice_ctx_create(&mHwDeviceCtx, type, nullptr, nullptr, 0);
                if (ret != 0) {
                    LOGE("av_hwdevice_ctx_create err: %d", ret)
                }
            }
        } else {
            LOGE("not find %s", mediacodecName.c_str())
            mVideoCodec = avcodec_find_decoder(params->codec_id);  // 降级到软件解码
        }
    } else {
        // ====== 第二步（备选）：使用软件解码器 ======
        mVideoCodec = avcodec_find_decoder(params->codec_id);
    }

    // ====== 第四步：验证解码器找到 ======
    if (mVideoCodec == nullptr) {
        std::string msg = "not find decoder";
        if (mErrorMsgListener) {
            mErrorMsgListener(-1000, msg);
        }
        return false;
    }

    // ====== 第五步：初始化解码器上下文 ======
    // init codec context
    mCodecContext = avcodec_alloc_context3(mVideoCodec);
    if (!mCodecContext) {
        std::string msg = "codec context alloc failed";
        if (mErrorMsgListener) {
            mErrorMsgListener(-2000, msg);
        }
        return false;
    }
    // 将流参数复制到编码上下文
    avcodec_parameters_to_context(mCodecContext, params);

    // ====== 第六步：配置硬件解码相关参数 ======
    if (mHwDeviceCtx) {
        // 设置获取硬件格式的回调函数
        mCodecContext->get_format = get_hw_format;
        // 设置硬件设备上下文
        mCodecContext->hw_device_ctx = av_buffer_ref(mHwDeviceCtx);

        // 如果提供了 Surface，初始化 MediaCodec 上下文
        if (mSurface != nullptr) {
            mMediaCodecContext = av_mediacodec_alloc_context();
            av_mediacodec_default_init(mCodecContext, mMediaCodecContext, mSurface);
        }
    }

    // ====== 第七步：打开解码器 ======
    // open codec
    int ret = avcodec_open2(mCodecContext, mVideoCodec, nullptr);
    if (ret != 0) {
        std::string msg = "codec open failed";
        if (mErrorMsgListener) {
            mErrorMsgListener(-3000, msg);
        }
        return false;
    }

    // ====== 第八步：初始化帧缓冲和时间戳 ======
    mAvFrame = av_frame_alloc();  // 分配解码帧缓冲
    mStartTimeMsForSync = -1;     // 初始化同步起始时间
    mRetryReceiveCount = RETRY_RECEIVE_COUNT;  // 初始化重试计数
    LOGI("codec name: %s, format: %s", mVideoCodec->name, av_get_pix_fmt_name(AVPixelFormat(params->format)))

    // ====== 第九步：收集媒体信息元数据 ======
    mMediaInfoJson.clear();
    mMediaInfoJson["width"] = mWidth;
    mMediaInfoJson["height"] = mHeight;
    mMediaInfoJson["use_hw"] = useHwDecoder;
    mMediaInfoJson["codec_name"] = mVideoCodec->name;
    mMediaInfoJson["duration"] = getDuration();
    auto ratio = getDisplayAspectRatio();
    mMediaInfoJson["dar"] = std::to_string(ratio.num) + ":" + std::to_string(ratio.den);
    mMediaInfoJson["rotate"] = getRotate();

    return true;
}

/**
 * @brief 解码数据包
 * @details 这是核心的解码流程，实现编码包 -> 原始视频帧的转换
 *
 * 流程说明：
 * 1. 判断是否为 EOF（End of File）标记
 * 2. 将编码包发送给解码器
 * 3. 循环接收解码后的帧
 * 4. 对每个帧进行处理：
 *    - 更新时间戳用于音视频同步
 *    - 检查 Seek 完成
 *    - 检查像素格式，必要时进行格式转换
 *    - 将帧通过回调函数传递给渲染器
 * 5. 处理解码缓冲和重试逻辑
 *
 * @param avPacket 待解码的编码包（可能是 EOF 标记）
 * @return 成功返回 0，失败或 EOF 返回错误代码
 */
int VideoDecoder::decode(AVPacket *avPacket) {
    int64_t start = getCurrentTimeMs();

    // ====== 第一步：判断是否为 EOF 标记 ======
    // 主动塞到队列中的 flush 帧（用于让解码器输出缓冲中的帧）
    bool isEof = avPacket->size == 0 && avPacket->data == nullptr;

    // ====== 第二步：将编码包发送给解码器 ======
    int sendRes = avcodec_send_packet(mCodecContext, avPacket);
    int64_t sendPoint = getCurrentTimeMs() - start;

    bool isKeyFrame = avPacket->flags & AV_PKT_FLAG_KEY;
    LOGI("[video] avcodec_send_packet...pts: %" PRId64 ", dts: %" PRId64 ", isKeyFrame: %d, res: %d, isEof: %d", avPacket->pts, avPacket->dts, isKeyFrame, sendRes, isEof)

    // avcodec_send_packet 的 -11 表示要先读 output，然后 pkt 需要重发
    mNeedResent = sendRes == AVERROR(EAGAIN);

    // ====== 第三步：循环接收解码后的帧 ======
    int receiveRes = AVERROR_EOF;
    int receiveCount = 0;
    do {
        start = getCurrentTimeMs();
        // avcodec_receive_frame 的 -11，表示需要发新帧，需要累计需要的数据，才能继续解码
        receiveRes = avcodec_receive_frame(mCodecContext, mAvFrame);
        if (receiveRes == 0) {
            LOGI("2592p2w3 [video] avcodec_receive_frame-ok avPacket->pts: %ld  mAvFrame->pts: %" PRId64,
                 avPacket->pts,mAvFrame->pts)
        }

        // 如果是 EOF 标记但没收到 EOF 结果，需要重试
        if (isEof && receiveRes != AVERROR_EOF && mRetryReceiveCount >= 0) {
            mNeedResent = true;
            mRetryReceiveCount--;
            LOGE("[video] send eof, not receive eof...retry count: %" PRId64, mRetryReceiveCount)
        }

        // ====== 第四步：处理接收错误 ======
        if (receiveRes != 0) {
            LOGE("[video] avcodec_receive_frame err: %d, resent: %d, retry count: %" PRId64, receiveRes, mNeedResent, mRetryReceiveCount)
            av_frame_unref(mAvFrame);  // 释放帧资源

            // 如果是 EOF 且收到 EOF 结果，重置重试计数
            if (isEof && receiveRes == AVERROR_EOF) {
                mRetryReceiveCount = RETRY_RECEIVE_COUNT;
            }

            // 如果已达最大重试次数，强制返回 EOF
            if (isEof && mRetryReceiveCount < 0) {
                receiveRes = AVERROR_EOF;
                mRetryReceiveCount = RETRY_RECEIVE_COUNT;
                mNeedResent = false;
            }
            break;
        }

        // ====== 第五步：处理有效的解码帧 ======
        int64_t receivePoint = getCurrentTimeMs() - start;

        // 更新时间戳（用于音视频同步）
        updateTimestamp(mAvFrame);

        // 检查 Seek 是否完成（当帧的 PTS 大于等于 Seek 目标位置）
        if (mAvFrame->pts >= mSeekPos) {
            mSeekPos = INT64_MAX;
            mSeekEndTimeMs = getCurrentTimeMs();
            int64_t precisionSeekConsume = mSeekEndTimeMs - mSeekStartTimeMs;
            LOGE("[video] avcodec_receive_frame...pts: %" PRId64 ", precision seek consume: %" PRId64, mAvFrame->pts, precisionSeekConsume)
        }

        // ====== 第六步：处理像素格式 ======
        // 检查宽高是否为偶数（某些图像处理需要偶数尺寸）
        bool isEvenEdge = isEven(mAvFrame->width) && isEven(mAvFrame->height);

        // 如果是硬件格式、RGB24 或满足条件的 YUV 格式，直接输出
        if (mAvFrame->format == hw_pix_fmt || mAvFrame->format == AV_PIX_FMT_RGB24 || (isEvenEdge && (mAvFrame->format == AV_PIX_FMT_YUV420P || mAvFrame->format == AV_PIX_FMT_NV12))) {
            if (mOnFrameArrivedListener) {
                mOnFrameArrivedListener(mAvFrame);  // 回调渲染器
            }
        }
        // 其他格式需要转换为 RGBA 后才能渲染（如 YUV420P10LE 等）
        else if (mAvFrame->format != AV_PIX_FMT_NONE) {
            AVFrame *swFrame = av_frame_alloc();  // 分配输出帧缓冲
            // 计算 RGBA 格式所需的缓冲大小
            int size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, mAvFrame->width, mAvFrame->height, 1);
            auto *buffer = static_cast<uint8_t *>(av_malloc(size * sizeof(uint8_t)));
            // 关联缓冲到帧结构
            av_image_fill_arrays(swFrame->data, swFrame->linesize, buffer, AV_PIX_FMT_RGBA, mAvFrame->width, mAvFrame->height, 1);

            // 执行格式转换（使用双线性插值）
            if (swsScale(mAvFrame, swFrame) > 0) {
                if (mOnFrameArrivedListener) {
                    mOnFrameArrivedListener(swFrame);  // 回调渲染器
                }
            }

            // 释放临时帧和缓冲
            av_frame_free(&swFrame);
            av_freep(&swFrame);
            av_free(buffer);
        } else {
            LOGE("[video] frame format is AV_PIX_FMT_NONE")
        }

        // 释放当前帧数据（不释放结构体，供下一次使用）
        av_frame_unref(mAvFrame);
        receiveCount++;

        LOGW("[video] decode sendPoint: %" PRId64 ", receivePoint: %" PRId64 ", receiveCount: %d", sendPoint, receivePoint, receiveCount)
    } while (true);  // 继续接收直到出错或 EOF

    return receiveRes;
}

/**
 * @brief 图像格式转换（使用 libswscale）
 * @details 将源帧从一种像素格式和分辨率转换到目标格式和分辨率
 *
 * 流程说明：
 * 1. 首次调用时初始化 SWS 上下文
 * 2. 使用 SWS 库进行图像缩放和格式转换
 * 3. 更新目标帧的格式信息
 *
 * @param srcFrame 源帧（解码得到的帧）
 * @param swFrame 目标帧（转换后的帧）
 * @return 成功返回转换的行数，失败返回 -1
 */
int VideoDecoder::swsScale(AVFrame *srcFrame, AVFrame *swFrame) {
    // ====== 第一步：初始化 SWS 上下文（首次使用时） ======
    if (mSwsContext == nullptr) {
        // 创建 SWS 上下文用于格式转换
        // 参数说明：
        // - 源宽度、高度、像素格式
        // - 目标宽度、高度、像素格式（RGBA）
        // - SWS_BICUBIC：使用双线性插值算法
        mSwsContext = sws_getContext(srcFrame->width, srcFrame->height, AVPixelFormat(srcFrame->format),
                                     srcFrame->width, srcFrame->height, AV_PIX_FMT_RGBA,
                                     SWS_BICUBIC, nullptr, nullptr, nullptr);
        if (!mSwsContext) {
            return -1;
        }
    }

    // ====== 第二步：执行格式转换 ======
    // transform
    int ret = sws_scale(mSwsContext,
              reinterpret_cast<const uint8_t *const *>(srcFrame->data),  // 源数据指针数组
              srcFrame->linesize,    // 源行字节数数组
              0,                     // 从第 0 行开始
              srcFrame->height,      // 转换行数
              swFrame->data,         // 目标数据指针数组
              swFrame->linesize      // 目标行字节数数组
    );

    // ====== 第三步：更新目标帧信息 ======
    // buffer is right, but some property lost
    if (swFrame->format == AV_PIX_FMT_NONE) {
        swFrame->format = AV_PIX_FMT_RGBA;  // 设置像素格式
        swFrame->width = srcFrame->width;   // 设置宽度
        swFrame->height = srcFrame->height; // 设置高度
    }

    return ret;
}

/**
 * @brief 获取当前视频时间戳（毫秒）
 * @return 返回当前帧的时间戳（毫秒）
 */
int64_t VideoDecoder::getTimestamp() const {
    return mCurTimeStampMs;
}

/**
 * @brief 更新视频帧的时间戳
 * @details 从帧的 PTS/DTS 中提取时间戳，用于音视频同步
 *
 * 流程说明：
 * 1. 初始化同步起始时间（首次调用）
 * 2. 从帧中提取 DTS 或 PTS
 * 3. 将时间戳从秒转换为毫秒
 * 4. 处理 Seek 后的时间戳修复
 *
 * @param frame 解码后的视频帧
 */
void VideoDecoder::updateTimestamp(AVFrame *frame) {
    // ====== 第一步：初始化同步起始时间 ======
    if (mStartTimeMsForSync < 0) {
        LOGE("update video start time")
        mStartTimeMsForSync = getCurrentTimeMs();
    }

    // ====== 第二步：从帧中提取 PTS（Presentation Time Stamp） ======
    /*int64_t pts = 0;
    // 优先使用 DTS（Decoding Time Stamp），如果不可用则使用 PTS
    if (frame->pkt_dts != AV_NOPTS_VALUE) {
        pts = frame->pkt_dts;
    } else if (frame->pts != AV_NOPTS_VALUE) {
        pts = frame->pts;
    }*/
    int64_t pts = 0;
    if (frame->pts != AV_NOPTS_VALUE) {
        pts = frame->pts;  // 优先用显示时间戳（正确的音视频同步）
    } else if (frame->pkt_dts != AV_NOPTS_VALUE) {
        pts = frame->pkt_dts;  // 降级方案（某些格式没有 PTS）
    } else {
        // 如果都没有，使用帧号估算（最后的保险）
        pts = frame->coded_picture_number;
    }
    LOGD("[video] updateTimestamp: pts=%ld, dts=%ld",
         (frame->pts != AV_NOPTS_VALUE ? frame->pts : -1),
         (frame->pkt_dts != AV_NOPTS_VALUE ? frame->pkt_dts : -1));



    // ====== 第三步：时间戳转换（秒 -> 毫秒） ======
    // av_q2d() 函数将有理数转为浮点数
    // mTimeBase 是时间基数（如 1/25 表示 25fps）
    mCurTimeStampMs = (int64_t)(pts * av_q2d(mTimeBase) * 1000);

    // ====== 第四步：处理 Seek 后的时间戳修复 ======
    if (mFixStartTime) {
        // 重新计算同步起始时间，以适应 Seek 后的新位置
        mStartTimeMsForSync = getCurrentTimeMs() - mCurTimeStampMs;
        mFixStartTime = false;
        LOGE("fix video start time")
    }
}

/**
 * @brief 获取视频宽度
 * @return 返回视频画面的宽度（像素）
 */
int VideoDecoder::getWidth() const {
    return mWidth;
}

/**
 * @brief 获取视频高度
 * @return 返回视频画面的高度（像素）
 */
int VideoDecoder::getHeight() const {
    return mHeight;
}

/**
 * @brief 获取视频时长
 * @return 返回视频时长（秒）
 */
double VideoDecoder::getDuration() {
    return mDuration;
}

/**
 * @brief 音视频同步函数
 * @details 根据当前帧的时间戳和播放器实际运行时间，进行同步控制
 *
 * 流程说明：
 * 1. 计算播放器从开始运行到现在的实际耗时
 * 2. 计算当前帧应该播放的时间与实际时间的差值
 * 3. 如果帧超前，则睡眠以等待（同步）
 * 4. 如果帧滞后，则继续播放下一帧（丢帧）
 *
 * @param frame 当前视频帧（用于上下文信息）
 */
void VideoDecoder::avSync(AVFrame *frame) {
    // 计算播放器从开始到现在的实际经过时间
    int64_t elapsedTimeMs = getCurrentTimeMs() - mStartTimeMsForSync;

    // 计算当前帧时间与播放器实际时间的差值
    int64_t diff = mCurTimeStampMs - elapsedTimeMs;

    // 限制最大延迟（防止同步延迟过长）
    diff = FFMIN(diff, DELAY_THRESHOLD);

    LOGI("[video] avSync, pts: %" PRId64 "ms, diff: %" PRId64 "ms", mCurTimeStampMs, diff)

    // 如果帧超前，则睡眠等待（微秒级）
    if (diff > 0) {
        av_usleep(diff * 1000);
    }
}

/**
 * @brief Seek 到指定位置
 * @details 快进/快退到媒体中的指定时间位置
 *
 * 流程说明：
 * 1. 清空解码缓冲
 * 2. 计算目标 Seek 位置（转换时间单位）
 * 3. 调用 FFmpeg 的 Seek 函数
 * 4. 标记需要修复时间戳
 * 5. 记录 Seek 开始时间以计算 Seek 精度
 *
 * @param pos 目标位置（秒）
 * @return 成功返回 0，失败返回错误代码
 */
int VideoDecoder::seek(double pos) {
    // 清空解码缓冲
    flush();

    // 将位置转换为流的时间基数单位
    int64_t seekPos = av_rescale_q((int64_t)(pos * AV_TIME_BASE), AV_TIME_BASE_Q, mTimeBase);

    // 执行 Seek 操作
    // AVSEEK_FLAG_BACKWARD：优先查找关键帧（速度快但可能不精确）
    // AVSEEK_FLAG_FRAME：逐帧定位（精确但速度慢）
    int ret = avformat_seek_file(mFtx, getStreamIndex(),
                                 INT64_MIN, seekPos, INT64_MAX, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_FRAME);

    LOGE("[video] seek to: %f, seekPos: %" PRId64 ", ret: %d", pos, seekPos, ret)

    // Seek 后需要恢复起始时间以保证音视频同步
    mFixStartTime = true;
    mSeekPos = seekPos;
    mSeekStartTimeMs = getCurrentTimeMs();

    return ret;
}

/**
 * @brief 释放解码器资源
 * @details 清理所有分配的 FFmpeg 上下文和缓冲
 *
 * 流程说明：
 * 1. 重置时间戳相关状态
 * 2. 释放视频帧缓冲
 * 3. 释放图像缩放上下文
 * 4. 释放 MediaCodec 上下文（Android 硬件解码）
 * 5. 释放硬件设备上下文
 * 6. 释放解码器上下文
 *
 * 注意：这个函数在析构函数中会自动调用
 */
void VideoDecoder::release() {
    mFixStartTime = false;
    mStartTimeMsForSync = -1;

    // 释放视频帧
    if (mAvFrame != nullptr) {
        av_frame_free(&mAvFrame);
        av_freep(&mAvFrame);
        LOGI("av frame...release")
    }

    // 释放图像缩放上下文（libswscale）
    if (mSwsContext != nullptr) {
        sws_freeContext(mSwsContext);
        mSwsContext = nullptr;
        LOGI("sws context...release")
    }

    // 释放 MediaCodec 上下文（Android 硬件加速解码）
    if (mMediaCodecContext != nullptr) {
        av_mediacodec_default_free(mCodecContext);
        mMediaCodecContext = nullptr;
        LOGI("mediacodec context...release")
    }

    // 释放硬件设备上下文
    if (mHwDeviceCtx != nullptr) {
        av_buffer_unref(&mHwDeviceCtx);
        mHwDeviceCtx = nullptr;
        LOGI("hw device context...release")
    }

    // 释放编码上下文（最后释放）
    if (mCodecContext != nullptr) {
        avcodec_free_context(&mCodecContext);
        mCodecContext = nullptr;
        LOGI("codec...release")
    }
}

/**
 * @brief 获取视频旋转角度
 * @details 从视频流的元数据中提取旋转信息（如播放时需旋转 90 度等）
 *
 * @return 返回旋转角度（通常为 0, 90, 180, 270）
 */
int VideoDecoder::getRotate() {
    AVStream *stream = mFtx->streams[getStreamIndex()];
    return FFVideoReader::getRotate(stream);
}

/**
 * @brief 获取视频显示宽高比
 * @details 从视频流的元数据中提取样本宽高比，计算并返回显示宽高比
 *
 * 流程说明：
 * 1. 获取样本宽高比（Sample Aspect Ratio, SAR）
 * 2. 结合视频分辨率计算显示宽高比（Display Aspect Ratio, DAR）
 * 3. 化简比例（如 1920:1080 -> 16:9）
 *
 * @return 返回宽高比的分子和分母（num:den 格式）
 */
AVRational VideoDecoder::getDisplayAspectRatio() {
    AVStream *stream = mFtx->streams[getStreamIndex()];
    AVRational sar, dar{-1, 1};

    // 从视频流猜测样本宽高比
    sar = av_guess_sample_aspect_ratio(mFtx, stream, nullptr);

    if (sar.num) {
        AVCodecParameters *params = stream->codecpar;
        // 计算显示宽高比：DAR = SAR * 视频宽 / 视频高
        if (av_reduce(&dar.num, &dar.den, params->width * sar.num, params->height * sar.den, 1024 * 1024) > 0) {
            LOGI("sample_aspect_ratio: %d:%d, display_aspect_ratio: %d:%d", sar.num, sar.den, dar.num, dar.den)
        }
    } else {
        LOGI("sample_aspect_ratio: N/A, display_aspect_ratio: N/A")
    }
    return dar;
}