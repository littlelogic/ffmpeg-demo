cmake_minimum_required(VERSION 3.18.1)

project("jwplayer")

message("CMAKE_ANDROID_ARCH_ABI: ${CMAKE_ANDROID_ARCH_ABI}")

# pre-built libs directory
set(prebuilt_lib_dir ${CMAKE_SOURCE_DIR}/../libs/${CMAKE_ANDROID_ARCH_ABI})

# static libs directory
set(static_lib_dir ${CMAKE_SOURCE_DIR}/../staticlibs/${CMAKE_ANDROID_ARCH_ABI})

# ffmpeg headers
set(ffmpeg_head_dir ${CMAKE_SOURCE_DIR}/vendor/ffmpeg)
include_directories(${ffmpeg_head_dir})

# libyuv headers
set(libyuv_head_dir ${CMAKE_SOURCE_DIR}/vendor/libyuv)
include_directories(${libyuv_head_dir}/libyuv.h)

# nlohmann (header-only)
set(nlohmann_dir ${CMAKE_SOURCE_DIR}/vendor/nlohmann)

# --- Pre-built shared library (FFmpeg merged) ---

add_library(jwfflib SHARED IMPORTED)
set_target_properties(jwfflib PROPERTIES IMPORTED_LOCATION ${prebuilt_lib_dir}/libjwfflib.so)

# --- Main shared library ---

add_library(
        jwplayer
        SHARED

        ${nlohmann_dir}/json.hpp

        ./decoder/BaseDecoder.cpp
        ./decoder/VideoDecoder.cpp
        ./decoder/AudioDecoder.cpp

        ./base/AVPacketQueue.cpp

        ./reader/FFReader.cpp
        ./reader/FFVideoReader.cpp
        ./reader/FFMediaProbe.cpp

        ./writer/FFVideoWriter.cpp

        ./settings/CompileSettings.h

        filter/FFFilter.cpp

        utils/ImageDef.h
        utils/FFConverter.h
        utils/FFMpegUtils.cpp
        utils/MutexObj.cpp
        utils/TraceUtils.cpp

        main/FFMpegPlayer.cpp
        main/MediaClock.cpp
        native-lib.cpp
)

find_library(log-lib log)

# base_utils header path
get_filename_component(module_main ${CMAKE_SOURCE_DIR} DIRECTORY)
get_filename_component(module_src ${module_main} DIRECTORY)
get_filename_component(module_root ${module_src} DIRECTORY)
get_filename_component(project_root ${module_root} DIRECTORY)
message("project_root: ${project_root}")
set(base_utils_header_root ${project_root}/libutils/src/main/cpp)

target_include_directories(jwplayer PRIVATE
        ${base_utils_header_root}/jni
        ${base_utils_header_root}/utils
)

target_compile_options(jwplayer PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
)

set(VERSION_SCRIPT ${CMAKE_SOURCE_DIR}/jwplayer.version)

target_link_libraries(
        jwplayer

        # FFmpeg + libyuv (pre-built shared library)
        jwfflib

        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${VERSION_SCRIPT}
        -Wl,-z,max-page-size=16384

        android
        ${log-lib}
        m
)
