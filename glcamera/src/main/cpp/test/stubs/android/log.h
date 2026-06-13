//
// Host-only stub of <android/log.h> so the regression test can include the real
// util/ImageDef.h (which pulls in util/LogUtil.h) on a desktop g++ toolchain.
// The logging calls become no-ops; importantly they perform no heap allocation,
// so they do not perturb the allocation counters used by the test.
//
#ifndef STUB_ANDROID_LOG_H
#define STUB_ANDROID_LOG_H

#ifdef __cplusplus
extern "C" {
#endif

typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
} android_LogPriority;

static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void) prio;
    (void) tag;
    (void) fmt;
    return 0;
}

#ifdef __cplusplus
}
#endif

#endif // STUB_ANDROID_LOG_H
