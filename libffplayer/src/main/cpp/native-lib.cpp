/**
 * @file native-lib.cpp
 * @brief JNI 桥接层 - 连接 Java/Kotlin 层和 C++ 播放器引擎
 *
 * 职责：
 * - 提供 JNI 方法映射，将 Java 层调用转发给 FFMpegPlayer
 * - 管理 FFMpegPlayer 实例的生命周期（通过 native handle 指针）
 * - JNI_OnLoad 中初始化全局资源
 *
 * 对应 Java 类: com.xyq.libffplayer.FFPlayer
 */

#include <jni.h>
#include "main/FFMpegPlayer.h"
#include "utils/TraceUtils.h"
#include "ScopedUtfChars.h"

/**
 * @brief JNI 库加载时的初始化入口
 * @details 在 System.loadLibrary("ffplayer") 时被自动调用
 */
extern "C"
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("ffmpegdemo JNI_OnLoad")
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    TraceUtils::init();
    return JNI_VERSION_1_6;
}

/**
 * @brief 创建播放器实例
 * @return 返回 FFMpegPlayer 对象的 native 指针（作为 long handle）
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeInit(JNIEnv *env, jobject thiz) {
    ATRACE_CALL();
    auto *player = new FFMpegPlayer();
    player->init(env, thiz);
    return reinterpret_cast<long>(player);
}

/**
 * @brief 准备播放器（打开媒体文件、初始化解码器）
 * @param handle FFMpegPlayer 的 native 指针
 * @param path 媒体文件路径
 * @param surface Android Surface 对象（用于硬件解码直接渲染，可为 null）
 * @return 成功返回 true
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativePrepare(JNIEnv *env, jobject thiz, jlong handle,
                                                      jstring path, jobject surface) {
    ATRACE_CALL();
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    ScopedUtfChars scopedPath(env, path);
    std::string s_path = scopedPath.c_str();
    bool result = false;
    if (player) {
        result = player->prepare(env,s_path, surface);
    }
    return result;
}

/**
 * @brief 开始播放（启动读包线程）
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeStart(JNIEnv *env, jobject thiz, jlong handle) {
    ATRACE_CALL();
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        player->start();
    }
}

/**
 * @brief 停止播放（释放所有线程和资源）
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeStop(JNIEnv *env, jobject thiz, jlong handle) {
    ATRACE_CALL();
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        player->stop();
    }
}

/**
 * @brief 释放播放器实例（销毁 native 对象）
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    ATRACE_CALL();
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    delete player;
}

/**
 * @brief 获取媒体时长（秒）
 */
extern "C"
JNIEXPORT jdouble JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeGetDuration(JNIEnv *env, jobject thiz, jlong handle) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player == nullptr) {
        return 0;
    }
    return player->getDuration();
}

/**
 * @brief Seek 到指定位置
 * @param position 目标位置（秒）
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeSeek(JNIEnv *env, jobject thiz, jlong handle,
                                                   jdouble position) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player == nullptr) {
        return false;
    }
    return player->seek(position);
}

/**
 * @brief 设置静音状态
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeSetMute(JNIEnv *env, jobject thiz, jlong handle,
                                                      jboolean mute) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        player->setMute(mute);
    }
}

/**
 * @brief 恢复播放
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeResume(JNIEnv *env, jobject thiz, jlong handle) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        player->resume();
    }
}

/**
 * @brief 暂停播放
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativePause(JNIEnv *env, jobject thiz, jlong handle) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        player->pause();
    }
}

/**
 * @brief 获取视频旋转角度
 * @return 旋转角度（0, 90, 180, 270）
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeGetRotate(JNIEnv *env, jobject thiz, jlong handle) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        return player->getRotate();
    }
    return 0;
}

/**
 * @brief 获取媒体信息（JSON 格式）
 * @return JSON 字符串，包含视频和音频的元数据信息
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_xyq_libffplayer_FFPlayer_nativeGetMediaInfo(JNIEnv *env, jobject thiz, jlong handle) {
    auto *player = reinterpret_cast<FFMpegPlayer *>(handle);
    if (player) {
        std::string info;
        player->getMediaInfo(info);
        return env->NewStringUTF(info.c_str());
    }
    return nullptr;
}