/**
 * @file ImageDef.h
 * @brief 图像格式常量定义
 *
 * 定义 Native → Java 传递帧数据时使用的格式标识
 * 与 Java 层的格式常量一一对应
 */

#ifndef FFMPEGDEMO_IMAGEDEF_H
#define FFMPEGDEMO_IMAGEDEF_H

// ====== 视频格式标识 ======
#define FMT_VIDEO_YUV420        0x00  ///< YUV420 平面格式（Y/U/V 三个分量）
#define FMT_VIDEO_NV12          0x01  ///< NV12 半平面格式（Y + 交错 UV）
#define FMT_VIDEO_RGBA          0x02  ///< RGBA 打包格式
#define FMT_VIDEO_MEDIACODEC    0x03  ///< MediaCodec 硬件解码输出（直接渲染到 Surface）
#define FMT_VIDEO_RGB           0x04  ///< RGB24 打包格式

// ====== 音频格式标识 ======
#define FMT_AUDIO_PCM           0x00  ///< PCM 原始音频数据

/**
 * @brief 原生音视频数据结构体
 * @details 用于在 Native 层传递音视频帧数据
 */
typedef struct _tag_NativeAvData {

    int width;
    int height;
    int format;
    uint8_t *datas[3];
    int lizeSize[3];

    _tag_NativeAvData() {
        width = 0;
        height = 0;
        format = FMT_VIDEO_YUV420;

        datas[0] = nullptr;
        datas[1] = nullptr;
        datas[2] = nullptr;

        lizeSize[0] = 0;
        lizeSize[1] = 0;
        lizeSize[2] = 0;
    }

} NativeAvData;

#endif //FFMPEGDEMO_IMAGEDEF_H
