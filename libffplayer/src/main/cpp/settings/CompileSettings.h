//
// Created by 雪月清的随笔 on 14/6/23.
//

#ifndef FFMPEGDEMO_COMPILESETTINGS_H
#define FFMPEGDEMO_COMPILESETTINGS_H

/**
 * @file CompileSettings.h
 * @brief 编码参数配置
 *
 * 功能说明：
 * - 定义编码类型（H.264、GIF）
 * - 定义像素格式和媒体类型枚举
 * - CompileSettings 结构体封装所有编码参数
 * - 用于 FFVideoWriter 初始化编码器
 */

enum ENCODE_TYPE {
    ENCODE_TYPE_H264,  ///< H.264 视频编码
    ENCODE_TYPE_GIF    ///< GIF 动画编码
};

enum PixelFormat {
    PIX_FMT_RGB8       ///< RGB8 像素格式（用于 GIF）
};

enum MediaType {
    MEDIA_TYPE_VIDEO,  ///< 视频
    MEDIA_TYPE_AUDIO   ///< 音频
};

/**
 * @brief 编码参数配置结构体
 * @details 封装视频编码所需的所有参数
 */
typedef struct CompileSettings {
    ENCODE_TYPE encodeType = ENCODE_TYPE_H264;  ///< 编码类型

    int width = 0;                              ///< 视频宽度

    int height = 0;                             ///< 视频高度

    PixelFormat pixelFormat = PIX_FMT_RGB8;     ///< 像素格式

    MediaType mediaType = MEDIA_TYPE_VIDEO;     ///< 媒体类型

    int64_t bitRate = 4 * 1024 * 1024;          ///< 码率（默认 4Mbps）

    int gopSize = 30;                           ///< GOP 大小（关键帧间隔）

    int maxBFrameCount = 0;                     ///< 最大 B 帧数量

    int fps = 30;                               ///< 帧率

    void operator=(const CompileSettings &settings) {
        this->encodeType = settings.encodeType;
        this->width = settings.width;
        this->height = settings.height;
        this->pixelFormat = settings.pixelFormat;
        this->mediaType = settings.mediaType;
        this->bitRate = settings.bitRate;
        this->gopSize = settings.gopSize;
        this->maxBFrameCount = settings.maxBFrameCount;
        this->fps = settings.fps;
    }

} CompileSettings;


#endif //FFMPEGDEMO_COMPILESETTINGS_H
