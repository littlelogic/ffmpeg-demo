/**
 * @file FFMpegPlayer.h
 * @brief 播放器核心引擎头文件
 *
 * 功能说明：
 * - 管理音视频播放的完整生命周期
 * - 三线程架构：读包线程、视频解码线程、音频解码线程
 * - 协调音视频同步、Seek 操作、暂停/恢复
 * - 通过 JNI 回调将解码数据传递给 Java 层渲染
 */

#ifndef FFMPEGDEMO_FFMPEGPLAYER_H
#define FFMPEGDEMO_FFMPEGPLAYER_H

#include <jni.h>
#include <string>
#include <sstream>
#include <ctime>
#include <thread>
#include <mutex>
#include <atomic>
#include <vector>
#include <memory.h>
#include "header/Logger.h"
#include "../utils/MutexObj.h"
#include "../decoder/VideoDecoder.h"
#include "../decoder/AudioDecoder.h"
#include "../base/AVPacketQueue.h"
#include "../filter/FFFilter.h"
#include "MediaClock.h"

extern "C" {
#include "../vendor/ffmpeg/libavutil/avutil.h"
#include "../vendor/ffmpeg/libavutil/frame.h"
#include "../vendor/ffmpeg/libavutil/time.h"
#include "../vendor/ffmpeg/libavformat/avformat.h"
#include "../vendor/ffmpeg/libavcodec/avcodec.h"
#include "../vendor/ffmpeg/libavcodec/jni.h"
}

/**
 * @brief JNI 回调上下文
 * @details 保存 Java 层对象引用和回调方法 ID，用于 Native → Java 通信
 */
typedef struct PlayerJniContext {
    jobject instance;                    ///< Java FFPlayer 对象的全局引用
    jmethodID onVideoPrepared;           ///< 视频轨道准备完成回调
    jmethodID onAudioPrepared;           ///< 音频轨道准备完成回调
    jmethodID onVideoFrameArrived;       ///< 视频帧到达回调（传递 YUV/RGB 数据）
    jmethodID onAudioFrameArrived;       ///< 音频帧到达回调（传递 PCM 数据）
    jmethodID onPlayCompleted;           ///< 播放完成回调
    jmethodID onPlayProgress;            ///< 播放进度回调（时间戳 ms）

    void reset() {
        instance = nullptr;
        onVideoPrepared = nullptr;
        onAudioPrepared = nullptr;
        onVideoFrameArrived = nullptr;
        onAudioFrameArrived = nullptr;
        onPlayCompleted = nullptr;
        onPlayProgress = nullptr;
    }

    bool isValid() {
        return instance != nullptr &&
        onVideoPrepared != nullptr &&
        onAudioPrepared != nullptr &&
        onVideoFrameArrived != nullptr &&
        onAudioFrameArrived != nullptr &&
        onPlayCompleted != nullptr &&
        onPlayProgress != nullptr;
    }

} PlayerJniContext;

/**
 * @brief 播放器状态枚举
 * @details 状态转换: UNKNOWN → PREPARE → START → PLAYING ⇄ PAUSE → STOP
 */
enum PlayerState {
    UNKNOWN,    ///< 初始状态
    PREPARE,    ///< 解码器已准备
    START,      ///< 已启动（读包线程启动）
    PLAYING,    ///< 正在播放
    PAUSE,      ///< 暂停
    STOP        ///< 停止
};

enum SeekMode {
    SEEK_DEFAULT,    ///< 保持当前状态（播放中seek后继续播放，暂停中seek后保持暂停）
    SEEK_AND_PAUSE,  ///< seek 完成后切到暂停状态
    SEEK_AND_PLAY,   ///< seek 完成后切到播放状态
};

/**
 * @brief 播放器核心引擎类
 * @details 封装 FFmpeg 的完整播放流程，管理三个工作线程：
 *   - ReadPacketThread: 读取 AVPacket 并分发到队列
 *   - VideoDecodeThread: 从队列取包并解码视频帧
 *   - AudioDecodeThread: 从队列取包并解码音频帧
 */
class FFMpegPlayer {

public:

    FFMpegPlayer();

    ~FFMpegPlayer();

    /**
     * @brief 初始化 JNI 上下文
     * @details 获取 Java 层回调方法的 MethodID
     */
    void init(JNIEnv *env, jobject javaObj);

    /**
     * @brief 准备播放器
     * @details 打开媒体文件、初始化解码器、创建解码线程
     * @param path 媒体文件路径
     * @param surface Android Surface（用于硬件解码，可为 nullptr）
     * @return 至少一个轨道准备成功返回 true
     */
    bool prepare(JNIEnv *env, std::string &path, jobject surface);

    void start();       ///< 开始播放（启动读包线程）
    void resume();      ///< 恢复播放
    void pause();       ///< 暂停播放
    void stop();        ///< 停止播放（释放所有资源和线程）

    void setMute(bool mute);           ///< 设置静音
    bool seek(double timeS);           ///< Seek 到指定时间（秒），保持当前状态
    bool seekAndPause(double timeS);   ///< Seek 到指定时间后暂停
    bool seekAndPlay(double timeS);    ///< Seek 到指定时间后播放
    void setPlayLimit(double startTimeS, double endTimeS); ///< 设置播放范围限制（秒）
    void clearPlayLimit();             ///< 清除播放范围限制
    double getDuration();              ///< 获取时长（秒）
    int getRotate();                   ///< 获取视频旋转角度
    void getMediaInfo(std::string &info); ///< 获取媒体信息（JSON）

private:
    JavaVM *mJvm = nullptr;                          ///< Java 虚拟机指针
    PlayerJniContext mPlayerJni{};                    ///< JNI 回调上下文

    std::string mPath = "";                          ///< 媒体文件路径

    volatile PlayerState mPlayerState = UNKNOWN;     ///< 播放器状态（volatile 保证线程可见性）

    bool mHasAbort = false;                          ///< 是否已终止
    bool mIsMute = false;                            ///< 是否静音
    bool mIsSeek = false;                            ///< 是否正在 Seek

    // per-stream EOF 标志：用于支持"音轨比视频短"等不对称结束的情况。
    // 只有当对方流也结束（或对方不存在）时才回调 onPlayCompleted。
    std::mutex mEofMutex;                            ///< 保护下面三个 EOF 状态的并发访问
    bool mVideoStreamEnded = false;                  ///< 视频流是否已解码完毕（mEofMutex 守护）
    bool mAudioStreamEnded = false;                  ///< 音频流是否已解码完毕（mEofMutex 守护）
    std::atomic<bool> mPlayCompletedNotified{false}; ///< 是否已回调 onPlayCompleted（保证只通知一次）
    std::atomic<bool> mPauseVideoPreviewRendered{false}; ///< PAUSE 预取预览是否已渲染至少一帧视频

    // 播放范围限制（秒），-1 表示无限制。UI 线程设置，解码线程读取。
    std::atomic<double> mPlayLimitStartS{-1.0};
    std::atomic<double> mPlayLimitEndS{-1.0};

    std::shared_ptr<MutexObj> mMutexObj = nullptr;   ///< 线程同步互斥锁

    // -1 表示无挂起 seek；std::atomic 避免 32-bit ARM 上对 double 的 torn read/write。
    static constexpr double kSeekPosUnset = -1.0;
    std::atomic<double> mVideoSeekPos{kSeekPosUnset}; ///< 视频 Seek 目标位置（秒，decode 线程清零）
    std::atomic<double> mAudioSeekPos{kSeekPosUnset}; ///< 音频 Seek 目标位置（秒，decode 线程清零）

    // UI 只往此列表追加 seek 请求；ReadPacketLoop 取最新并清空后执行 demuxer seek（合并连击 seek）。
    mutable std::mutex mWillSeekMutex;
    struct SeekRequest {
        double timeS;
        SeekMode mode;
    };
    std::vector<SeekRequest> mWillSeekPointsList;

    std::thread *mReadPacketThread = nullptr;        ///< 读包线程

    std::thread *mVideoThread = nullptr;             ///< 视频解码线程
    std::shared_ptr<AVPacketQueue> mVideoPacketQueue = nullptr; ///< 视频包队列
    std::shared_ptr<VideoDecoder> mVideoDecoder = nullptr;      ///< 视频解码器

    std::thread *mAudioThread = nullptr;             ///< 音频解码线程
    std::shared_ptr<AVPacketQueue> mAudioPacketQueue = nullptr; ///< 音频包队列
    std::shared_ptr<AudioDecoder> mAudioDecoder = nullptr;      ///< 音频解码器

    AVFormatContext *mFtx = nullptr;                 ///< FFmpeg 格式化上下文

    std::shared_ptr<MediaClock> mMediaClock = nullptr; ///< 主时钟（音视频同步参考）

    // 网格滤镜，软解有效，硬解用 OpenGL 实现
    bool mEnableGridFilter = false;                  ///< 是否启用网格滤镜
    std::unique_ptr<FFFilter> mGridFilter = nullptr; ///< 网格滤镜实例

    /**
     * @brief 渲染帧数据
     * @details 根据帧格式（YUV420P/NV12/RGBA/RGB24/MEDIACODEC/FLTP）分发处理
     */
    void doRender(JNIEnv *env, AVFrame *avFrame);

    /** @brief 处理行对齐的数据拷贝 */
    static void checkStrideAndFill(JNIEnv *env, jbyteArray *component, int width, int height, int lineStride, int pixelStride, uint8_t *src);

    /** @brief 读取一个 AVPacket 并分发到对应队列；videoOnly 时仅入视频队列（PAUSE 预览用） */
    int readAvPacketToQueue(bool videoOnly = false);

    /** @brief 将 AVPacket 推入指定队列（阻塞等待队列不满） */
    bool pushPacketToQueue(AVPacket *packet, const std::shared_ptr<AVPacketQueue>& queue) const;

    void ReadPacketLoop();      ///< 读包线程主循环
    void VideoDecodeLoop();     ///< 视频解码线程主循环
    void AudioDecodeLoop();     ///< 音频解码线程主循环

    /**
     * @brief 从 willSeekPointsList 取出最新目标并清空列表（加锁）
     * @return 最新 seek 请求；列表为空返回 {kSeekPosUnset, SEEK_DEFAULT}
     */
    SeekRequest takeLatestSeekPoint();

    bool haveNewSeekPoint() const;

    /**
     * @brief 消费 seek 队列（仅 ReadPacketLoop 调用，每轮最多处理一次）
     * @details 取最新 → applySeekInternal 一次 → return，使本轮仍能 readAvPacketToQueue。
     *          列表若仍有待处理（高频拖动），下轮再 drain。
     */
    void drainWillSeekPointsList();

    /**
     * @brief 执行单次 seek（清队列、主时钟、avformat_seek_file、等解码 flush、状态切换）
     * @details 仅 ReadPacketLoop 调用
     */
    void applySeekInternal(double timeS, SeekMode mode);

    /** @brief PAUSE 状态下 seek 后读包，直到解码并渲染一帧视频（不读音频） */
    void prefetchPauseVideoFrame();

    /** @brief 是否已设置有效播放范围（start/end 均 >= 0） */
    bool hasPlayLimit() const;

    /** @brief 当前播放位置（秒）；无 decoder 时返回 0 */
    double getCurrentPositionS() const;

    /** @brief 当前位置是否在播放范围内 */
    bool isPositionInPlayLimit(double posS) const;

    /** @brief 将 seek 目标钳位到播放范围内 */
    double clampSeekTime(double timeS) const;

    /** @brief 将播放范围裁剪为合法闭区间；duration 未就绪时只做基础校验 */
    bool normalizePlayLimit(double *startTimeS, double *endTimeS);

    /** @brief duration 就绪后重新裁剪已保存的播放范围 */
    void normalizeStoredPlayLimit();

    /** @brief 到达播放限制末尾时停止继续读包/解码，并触发完成回调 */
    void handlePlayLimitEnd(JNIEnv *env);

    void updatePlayerState(PlayerState state);  ///< 更新播放器状态
    void onPlayCompleted(JNIEnv *env);          ///< 播放完成处理（已带去重）

    /**
     * @brief 流级别 EOF 处理（线程安全）
     * @details 标记本流结束；当所有存在的流都结束时，仅触发一次 onPlayCompleted。
     * @param isVideo 标记视频流（true）还是音频流（false）
     */
    void handleStreamEof(bool isVideo, JNIEnv *env);
};


#endif //FFMPEGDEMO_FFMPEGPLAYER_H
