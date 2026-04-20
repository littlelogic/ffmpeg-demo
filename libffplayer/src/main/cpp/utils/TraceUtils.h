//
// Created by 雪月清的随笔 on 19/4/23.
//

/**
 * @file TraceUtils.h
 * @brief Android Systrace 性能追踪工具
 *
 * 功能说明：
 * - 封装 Android ATrace API，用于在 Systrace 中标记代码段
 * - 使用 ATRACE_CALL() 宏自动标记当前函数
 * - ScopedTrace 利用 RAII 模式自动管理 begin/end
 *
 * 使用方式：
 *   void myFunc() {
 *       ATRACE_CALL();  // 自动标记函数名
 *       // ... 业务代码
 *   }
 *
 * @see https://developer.android.com/topic/performance/tracing/custom-events-native
 */

#ifndef FFMPEGDEMO_TRACEUTILS_H
#define FFMPEGDEMO_TRACEUTILS_H

#include <android/trace.h>
#include <string>
#include <dlfcn.h>

#define ENABLE_TRACE true

typedef void *(*fp_ATrace_beginSection) (const char* sectionName);
typedef void *(*fp_ATrace_endSection) ();

class TraceUtils {

public:
    TraceUtils() = delete;

    ~TraceUtils() = delete;

    static void init();

    static void beginSection(const std::string &sectionName);

    static void endSection();

private:
    static fp_ATrace_beginSection m_fpATraceBeginSection;
    static fp_ATrace_endSection m_fpATraceEndSection;
};


#define ATRACE_NAME(name) ScopedTrace ___tracer(name)

// ATRACE_CALL is an ATRACE_NAME that uses the current function name.
#define ATRACE_CALL() ATRACE_NAME(__FUNCTION__)

class ScopedTrace {
public:
    inline ScopedTrace(const char *name) {
        TraceUtils::beginSection(name);
    }

    inline ~ScopedTrace() {
        TraceUtils::endSection();
    }
};


#endif //FFMPEGDEMO_TRACEUTILS_H
