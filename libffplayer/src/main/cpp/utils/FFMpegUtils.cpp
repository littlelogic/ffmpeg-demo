/**
 * @file FFMpegUtils.cpp
 * @brief FFmpeg 工具函数 JNI 实现
 *
 * 功能说明：
 * - getVideoFramesCore: 批量抽帧（精准/快速模式），支持缩放和旋转
 * - nativeProbeMediaInfo: 仅根据路径探测媒体信息（无需 Surface）
 * - nativeExportGif: 将视频导出为 GIF 动画
 *
 * 对应 Java 类: com.xyq.libffplayer.utils.FFMpegUtils
 */

#include <jni.h>
#include <inttypes.h>
#include <memory.h>
#include <string>
#include "../reader/FFVideoReader.h"
#include "../reader/FFMediaProbe.h"
#include "../writer/FFVideoWriter.h"
#include "header/Logger.h"
#include "ScopedUtfChars.h"
#include "ScopedPrimitiveArray.h"

/**
 * @brief 批量视频抽帧
 * @details 工作流程：
 *   1. 初始化 FFVideoReader（设置丢帧策略 DISCARD_NONREF）
 *   2. 计算输出尺寸（等比缩放）
 *   3. 回调 Java 获取时间戳数组
 *   4. 遍历时间戳，逐帧提取并回调 Java
 *
 * @param path 视频文件路径
 * @param width 目标宽度（<=0 自适应）
 * @param height 目标高度（<=0 自适应）
 * @param precise 是否精准抽帧
 * @param cb Java 回调对象
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_getVideoFramesCore(JNIEnv *env, jobject thiz,
                                                             jstring path, jint width, jint height,
                                                             jboolean precise, jobject cb) {
    // path
    ScopedUtfChars scopedPath(env, path);
    std::string s_path = scopedPath.c_str();

    // frameAllocator
    jclass jclazz = env->GetObjectClass(thiz);
    assert(jclazz != nullptr);

    jmethodID allocateFrame = env->GetMethodID(jclazz, "allocateFrame",
                                               "(II)Ljava/nio/ByteBuffer;");
    assert(allocateFrame != nullptr);

    // callback
    jclazz = env->GetObjectClass(cb);
    assert(jclazz != nullptr);

    jmethodID onFetchStart = env->GetMethodID(jclazz, "onFetchStart", "(D)[D");
    jmethodID onProgress = env->GetMethodID(jclazz, "onProgress", "(Ljava/nio/ByteBuffer;DIIII)Z");
    jmethodID onFetchEnd = env->GetMethodID(jclazz, "onFetchEnd", "()V");

    auto *reader = new FFVideoReader();
    reader->setDiscardType(DISCARD_NONREF);
    reader->init(s_path);

    int videoWidth = reader->getMediaInfo().width;
    int videoHeight = reader->getMediaInfo().height;
    if (width <= 0 && height <= 0) {
        width = videoWidth;
        height = videoHeight;
    } else if (width > 0 && height <= 0) { // scale base width
        width += width % 2;
        if (width > videoWidth) {
            width = videoWidth;
        }
        height = (jint)(1.0 * width * videoHeight / videoWidth);
        height += height % 2;
    } else if (width <= 0) { // scale base height
        height += height % 2;
        if (height > videoHeight) {
            height = videoHeight;
        }
        width = (jint)(1.0 * height * videoWidth / videoHeight);
    }
    LOGI("video size: %dx%d, get frame size: %dx%d", videoWidth, videoHeight, width, height)

    auto timestamps = (jdoubleArray)env->CallObjectMethod(cb, onFetchStart, reader->getDuration());
    /// RAII 包装器，安全访问 Java double 数组
    ScopedDoubleArrayRW tsArr(env, timestamps);
    int size = env->GetArrayLength(timestamps);
    LOGI("timestamps size: %d", size)

    int rotate = reader->getRotate();

    for (int i = 0; i < size; i++) {
        /// 分配（一般是 ByteBuffer.allocateDirect(...)）,只有此种，在c++中才可以方法
        jobject jByteBuffer = env->CallObjectMethod(thiz, allocateFrame, width, height);
        auto *buffer = (uint8_t *)env->GetDirectBufferAddress(jByteBuffer);
        /// 在这块已有内存上清零，不分配内存。
        memset(buffer, 0, width * height * 4);
        reader->getFrame((double) tsArr[i], width, height, buffer, precise);
        jboolean abort = !env->CallBooleanMethod(cb, onProgress, jByteBuffer, (jdouble)tsArr[i], width, height, rotate, i);
        if (abort) {
            LOGE("onProgress abort")
            break;
        }
    }

    reader->release();
    delete reader;

    env->CallVoidMethod(cb, onFetchEnd);
}

/**
 * @brief 探测媒体信息 JSON（与 FFPlayer.getMediaInfo 外层结构一致，无需创建播放器）
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_nativeProbeMediaInfo(JNIEnv *env, jobject thiz, jstring path) {
    ScopedUtfChars scopedPath(env, path);
    std::string s_path = scopedPath.c_str();
    std::string info = ff_probe_media_info(s_path);
    return env->NewStringUTF(info.c_str());
}

/**
 * @brief 导出 GIF 动画
 * @details 工作流程：
 *   1. 初始化 FFVideoReader（设置丢帧策略 DISCARD_NONKEY，只读关键帧）
 *   2. 初始化 FFVideoWriter（GIF 编码，10fps）
 *   3. 循环读帧 → 编码 → 写入
 *   4. 写入尾部信息
 *
 * @param video_path 输入视频路径
 * @param output 输出 GIF 路径
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_nativeExportGif(JNIEnv *env, jobject thiz,
                                                          jstring video_path, jstring output) {
    ScopedUtfChars input_path(env, video_path);
    ScopedUtfChars output_path(env, output);
    std::string inputPath = input_path.c_str();
    std::string outputPath = output_path.c_str();

    // init reader
    auto reader = std::make_unique<FFVideoReader>();
    reader->setDiscardType(DISCARD_NONKEY);
    reader->init(inputPath);

    // init writer
    auto codecParam = reader->getCodecParameters();
    CompileSettings compileSettings;
    compileSettings.width = codecParam->width;
    compileSettings.height = codecParam->height;
    compileSettings.encodeType = ENCODE_TYPE_GIF;
    compileSettings.pixelFormat = PIX_FMT_RGB8;
    compileSettings.mediaType = MEDIA_TYPE_VIDEO;
    compileSettings.bitRate = codecParam->bit_rate;
    compileSettings.gopSize = 0;
    compileSettings.fps = 10;

    auto writer = std::make_unique<FFVideoWriter>();
    writer->init(outputPath, compileSettings);

    // loop encode
    int ret = 0;
    while (true) {
        reader->getNextFrame([&](AVFrame *avFrame) {
            if (avFrame != nullptr) {
                writer->encode(avFrame);
            } else {
                ret = -1;
            }
        });
        if (ret < 0) {
            break;
        }
    }
    writer->signalEof();

    return true;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_nativeInitVideoReader(JNIEnv *env, jobject thiz,
                                                                 jstring path) {
    auto *reader = new FFVideoReader();
    reader->setDiscardType(DISCARD_NONREF);
    ScopedUtfChars scopedPath(env, path);
    std::string s_path = scopedPath.c_str();
    if (!reader->init(s_path)) {
        reader->release();
        delete reader;
        return reinterpret_cast<jlong>(nullptr);
    }

    int videoWidth = reader->getMediaInfo().width;
    int videoHeight = reader->getMediaInfo().height;
    if (videoWidth <= 0 || videoHeight <= 0) {
        reader->release();
        delete reader;
        return reinterpret_cast<jlong>(nullptr);
    }

    reader->setSize(videoWidth, videoHeight);
    return reinterpret_cast<jlong>(reader);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_nativeVideoReaderSetSize(JNIEnv *env, jobject thiz,
                                                                    jlong ptr, jint width,
                                                                    jint height) {
    if (ptr == 0) {
        // 可选：抛出 Java 异常
        /*jclass clazz = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(clazz, "Native instance is null");*/
        return;
    }
    auto *reader = reinterpret_cast<FFVideoReader*>(ptr);
    int videoWidth = reader->getMediaInfo().width;
    int videoHeight = reader->getMediaInfo().height;
    if (width <= 0 && height <= 0) {
        width = videoWidth;
        height = videoHeight;
    } else if (width > 0 && height <= 0) { // scale base width
        width += width % 2;
        if (width > videoWidth) {
            width = videoWidth;
        }
        height = (jint)(1.0 * width * videoHeight / videoWidth);
        height += height % 2;
    } else if (width <= 0) { // scale base height
        height += height % 2;
        if (height > videoHeight) {
            height = videoHeight;
        }
        width = (jint)(1.0 * height * videoWidth / videoHeight);
    }
    reader->setSize(width,height);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_nativeCloseVideoReader(JNIEnv *env, jobject thiz,
                                                                  jlong ptr) {
    auto *reader = reinterpret_cast<FFVideoReader*>(ptr);
    reader->release();
    delete reader;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_xyq_libffplayer_utils_FFMpegUtils_nativeGetSingleFrame(JNIEnv *env, jobject thiz,
                                                                jlong ptr, jdouble timestamp_sec,
                                                                jboolean precise) {
    if (ptr == 0) {
        return nullptr;
    }

    auto *reader = reinterpret_cast<FFVideoReader*>(ptr);
    int width = reader->getTargetWidth();
    int height = reader->getTargetHeight();
    if (width <= 0 || height <= 0) {
        width = reader->getMediaInfo().width;
        height = reader->getMediaInfo().height;
    }
    if (width <= 0 || height <= 0) {
        return nullptr;
    }

    jclass jclazz = env->GetObjectClass(thiz);
    if (jclazz == nullptr) {
        return nullptr;
    }
    jmethodID allocateFrame = env->GetMethodID(jclazz, "allocateFrame",
                                               "(II)Ljava/nio/ByteBuffer;");
    if (allocateFrame == nullptr) {
        return nullptr;
    }

    jobject jByteBuffer = env->CallObjectMethod(thiz, allocateFrame, width, height);
    if (jByteBuffer == nullptr) {
        return nullptr;
    }

    auto *buffer = (uint8_t *) env->GetDirectBufferAddress(jByteBuffer);
    if (buffer == nullptr) {
        return nullptr;
    }
    jlong capacity = env->GetDirectBufferCapacity(jByteBuffer);
    int byteCount = width * height * 4;
    if (capacity < byteCount) {
        LOGE("[nativeGetSingleFrame] direct buffer too small: capacity=%" PRId64 " need=%d",
             (int64_t) capacity, byteCount)
        return nullptr;
    }

    memset(buffer, 0, byteCount);
    reader->getFrame((double) timestamp_sec, width, height, buffer, (bool) precise);
    return jByteBuffer;
}