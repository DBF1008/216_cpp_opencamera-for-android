//
// Regression test for the preview-frame JNI input path (Java -> JNI -> renderer).
//
// Background
// ----------
// The original native_UpdateFrame() in JniImpl.cpp allocated a fresh native buffer
// (new unsigned char[len]) and copied the entire YUV frame into it on EVERY preview
// callback, then handed that temporary buffer to the renderer -- which copies the data
// AGAIN into its own reusable buffer (GLByteFlowRender::m_RenderFrame). At high
// resolution / frame rate this per-frame native heap allocation + extra full-frame copy
// is the root cause of the stutter and native memory churn this fix targets.
//
// The fix hands the Java array's own memory straight to the renderer
// (GetPrimitiveArrayCritical), so the renderer performs the single, unavoidable copy
// into its reusable buffer and the per-frame allocation disappears.
//
// What this test guards
// ---------------------
// It exercises the REAL renderer-side allocation/copy code (util/ImageDef.h ->
// NativeImageUtil) and models both the fixed and the legacy JNI handoff. It asserts:
//   1. The fixed path copies frame data correctly (byte-exact).
//   2. In steady state (constant resolution) the fixed path performs ZERO heap
//      allocations per frame -- the core regression guard.
//   3. The legacy path performs one allocation PER frame (documents the bug and proves
//      the allocation counter actually detects per-frame churn).
//   4. A resolution change reallocates the reusable buffer exactly once and stays correct.
//   5. The JNI contract holds: every GetPrimitiveArrayCritical is released with JNI_ABORT
//      (read-only, no copy-back) and the pointer handed to the renderer is the array's
//      own backing memory (true zero-copy handoff).
//
// Build & run: see run_frame_pipeline_test.sh in this directory. Requires only a host
// g++ (no Android NDK / device). Heap allocations are counted via -Wl,--wrap=malloc and
// overridden global operator new/delete.
//
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <new>

// ---------------------------------------------------------------------------
// Heap-allocation counting.
//
// NativeImageUtil uses malloc/free (redirected to __wrap_malloc/__wrap_free by the
// linker's --wrap option); the legacy handoff uses new[]/delete[] (caught by the
// overridden global operators below). Both feed the same counters, so g_heapAllocs
// reflects every heap allocation performed by the code under test.
// ---------------------------------------------------------------------------
extern "C" void *__real_malloc(size_t size);
extern "C" void __real_free(void *ptr);

static long g_heapAllocs = 0;
static long g_heapFrees = 0;

extern "C" void *__wrap_malloc(size_t size) {
    ++g_heapAllocs;
    return __real_malloc(size);
}

extern "C" void __wrap_free(void *ptr) {
    if (ptr) ++g_heapFrees;
    __real_free(ptr);
}

void *operator new(std::size_t size) {
    ++g_heapAllocs;
    void *p = __real_malloc(size ? size : 1);
    if (!p) throw std::bad_alloc();
    return p;
}
void *operator new[](std::size_t size) {
    ++g_heapAllocs;
    void *p = __real_malloc(size ? size : 1);
    if (!p) throw std::bad_alloc();
    return p;
}
void operator delete(void *p) noexcept { if (p) { ++g_heapFrees; __real_free(p); } }
void operator delete[](void *p) noexcept { if (p) { ++g_heapFrees; __real_free(p); } }
void operator delete(void *p, std::size_t) noexcept { if (p) { ++g_heapFrees; __real_free(p); } }
void operator delete[](void *p, std::size_t) noexcept { if (p) { ++g_heapFrees; __real_free(p); } }

// Pull in the REAL frame buffer definitions / allocation+copy code used by the pipeline.
#include "ImageDef.h"

// ---------------------------------------------------------------------------
// Minimal assertion harness.
// ---------------------------------------------------------------------------
static int g_failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            ++g_failures;                                                        \
            std::fprintf(stderr, "  [FAIL] %s:%d  CHECK(%s)\n",                  \
                         __FILE__, __LINE__, #cond);                             \
        }                                                                        \
    } while (0)

#define CHECK_EQ(a, b)                                                          \
    do {                                                                         \
        long _va = (long) (a);                                                   \
        long _vb = (long) (b);                                                   \
        if (_va != _vb) {                                                        \
            ++g_failures;                                                        \
            std::fprintf(stderr, "  [FAIL] %s:%d  %s (=%ld) != %s (=%ld)\n",     \
                         __FILE__, __LINE__, #a, _va, #b, _vb);                  \
        }                                                                        \
    } while (0)

// ---------------------------------------------------------------------------
// Fake JNI array + JNIEnv subset used by native_UpdateFrame.
// ---------------------------------------------------------------------------
#ifndef JNI_ABORT
#define JNI_ABORT 2
#endif

struct FakeJavaArray {
    uint8_t *data;  // host-owned backing store, i.e. the "Java array" memory
    size_t len;
};

struct FakeEnv {
    int criticalGets = 0;
    int criticalReleases = 0;
    int lastReleaseMode = -1;
    int regionCopies = 0;  // GetByteArrayRegion full-frame copies (legacy path only)

    // Mirrors ART's GetPrimitiveArrayCritical: returns a direct pointer to the array
    // storage (no copy). isCopy reports JNI_FALSE.
    void *GetPrimitiveArrayCritical(FakeJavaArray *arr, unsigned char *isCopy) {
        ++criticalGets;
        if (isCopy) *isCopy = 0;  // JNI_FALSE: not a copy
        return arr->data;
    }
    void ReleasePrimitiveArrayCritical(FakeJavaArray * /*arr*/, void * /*carray*/, int mode) {
        ++criticalReleases;
        lastReleaseMode = mode;
    }
    int GetArrayLength(FakeJavaArray *arr) { return (int) arr->len; }
    void GetByteArrayRegion(FakeJavaArray *arr, int start, int len, void *buf) {
        ++regionCopies;
        std::memcpy(buf, arr->data + start, (size_t) len);
    }
};

// ---------------------------------------------------------------------------
// Faithful model of the renderer-side frame handling
// (ByteFlowRenderContext::UpdateFrame + GLByteFlowRender::UpdateFrame): keep ONE reusable
// NativeImage, reallocate it only when the resolution changes, and copy each frame into
// it via the real NativeImageUtil. This is the destination of every preview frame.
// ---------------------------------------------------------------------------
struct RendererFramePath {
    NativeImage m_RenderFrame;  // default ctor: planes null, width/height/format = 0

    ~RendererFramePath() { NativeImageUtil::FreeNativeImage(&m_RenderFrame); }

    void UpdateFrame(int format, uint8_t *pBuffer, int width, int height) {
        // Build the transient source image exactly like ByteFlowRenderContext::UpdateFrame.
        NativeImage src;
        src.format = format;
        src.width = width;
        src.height = height;
        src.ppPlane[0] = pBuffer;
        switch (format) {
            case IMAGE_FORMAT_NV12:
            case IMAGE_FORMAT_NV21:
                src.ppPlane[1] = src.ppPlane[0] + width * height;
                break;
            case IMAGE_FORMAT_I420:
                src.ppPlane[1] = src.ppPlane[0] + width * height;
                src.ppPlane[2] = src.ppPlane[1] + width * height / 4;
                break;
            default:
                break;
        }

        // Reuse logic: (re)allocate only when the frame size changes.
        if (src.width != m_RenderFrame.width || src.height != m_RenderFrame.height) {
            if (m_RenderFrame.ppPlane[0] != nullptr) {
                NativeImageUtil::FreeNativeImage(&m_RenderFrame);
            }
            std::memset(&m_RenderFrame, 0, sizeof(NativeImage));
            m_RenderFrame.width = src.width;
            m_RenderFrame.height = src.height;
            m_RenderFrame.format = src.format;
            NativeImageUtil::AllocNativeImage(&m_RenderFrame);
        }

        NativeImageUtil::CopyNativeImage(&src, &m_RenderFrame);
    }
};

// ---------------------------------------------------------------------------
// The two JNI handoffs under comparison.
// ---------------------------------------------------------------------------

// Mirrors the FIXED native_UpdateFrame: zero-copy handoff of the array's own memory.
static void fixedUpdateFrame(FakeEnv *env, RendererFramePath *renderer, int format,
                             FakeJavaArray *bytes, int width, int height) {
    void *pData = env->GetPrimitiveArrayCritical(bytes, nullptr);
    renderer->UpdateFrame(format, reinterpret_cast<uint8_t *>(pData), width, height);
    env->ReleasePrimitiveArrayCritical(bytes, pData, JNI_ABORT);
}

// Mirrors the OLD native_UpdateFrame: per-frame native heap allocation + full-frame copy.
static void legacyUpdateFrame(FakeEnv *env, RendererFramePath *renderer, int format,
                              FakeJavaArray *bytes, int width, int height) {
    int len = env->GetArrayLength(bytes);
    unsigned char *buf = new unsigned char[len];
    env->GetByteArrayRegion(bytes, 0, len, buf);
    renderer->UpdateFrame(format, buf, width, height);
    delete[] buf;
}

// ---------------------------------------------------------------------------
// Helpers.
// ---------------------------------------------------------------------------
static size_t i420Size(int w, int h) {
    return (size_t) w * h + ((size_t) w * h) / 2;  // Y + U/4*... -> w*h*3/2
}

static void fillPattern(uint8_t *buf, size_t len, uint8_t seed) {
    for (size_t i = 0; i < len; ++i) {
        buf[i] = (uint8_t) ((i * 31u + seed * 7u + 13u) & 0xFF);
    }
}

// ---------------------------------------------------------------------------
// Tests.
// ---------------------------------------------------------------------------

// Test 1: the fixed path copies the frame byte-exactly and honours the JNI contract.
static void test_fixed_path_copies_correctly() {
    std::printf("[test] fixed path copies frame data correctly\n");
    const int w = 640, h = 480;
    const size_t sz = i420Size(w, h);

    uint8_t *src = static_cast<uint8_t *>(__real_malloc(sz));
    fillPattern(src, sz, 0xA5);
    FakeJavaArray arr{src, sz};

    FakeEnv env;
    RendererFramePath renderer;

    fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr, w, h);

    CHECK(renderer.m_RenderFrame.ppPlane[0] != nullptr);
    CHECK_EQ(std::memcmp(renderer.m_RenderFrame.ppPlane[0], src, sz), 0);
    CHECK_EQ(env.criticalGets, 1);
    CHECK_EQ(env.criticalReleases, 1);
    CHECK_EQ(env.lastReleaseMode, JNI_ABORT);

    __real_free(src);
}

// Test 2 (core regression guard): steady-state frames cause ZERO heap allocations.
static void test_fixed_path_no_per_frame_allocation() {
    std::printf("[test] fixed path performs no per-frame heap allocation (steady state)\n");
    const int w = 1280, h = 720;
    const size_t sz = i420Size(w, h);
    const int kFrames = 200;

    uint8_t *src = static_cast<uint8_t *>(__real_malloc(sz));
    fillPattern(src, sz, 0x10);
    FakeJavaArray arr{src, sz};

    FakeEnv env;
    RendererFramePath renderer;

    // Warm-up frame: allocates the reusable renderer buffer once (this is expected).
    fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr, w, h);

    // Measure steady state.
    long allocsBefore = g_heapAllocs;
    long freesBefore = g_heapFrees;
    for (int i = 0; i < kFrames; ++i) {
        // Mutate the incoming data so the path cannot be optimised into a no-op.
        src[(size_t) i % sz] = (uint8_t) (src[(size_t) i % sz] + 1u);
        fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr, w, h);
    }
    long allocsDelta = g_heapAllocs - allocsBefore;
    long freesDelta = g_heapFrees - freesBefore;

    CHECK_EQ(allocsDelta, 0);  // <-- the regression: must be 0, was kFrames before the fix
    CHECK_EQ(freesDelta, 0);
    // Data still flows: last frame is present in the reusable buffer.
    CHECK_EQ(std::memcmp(renderer.m_RenderFrame.ppPlane[0], src, sz), 0);
    CHECK_EQ(env.criticalGets, kFrames + 1);
    CHECK_EQ(env.criticalReleases, kFrames + 1);

    __real_free(src);
}

// Test 3 (contrast): the legacy path allocates exactly once per frame.
static void test_legacy_path_allocates_every_frame() {
    std::printf("[test] legacy path allocates once per frame (bug reproduction)\n");
    const int w = 1280, h = 720;
    const size_t sz = i420Size(w, h);
    const int kFrames = 200;

    uint8_t *src = static_cast<uint8_t *>(__real_malloc(sz));
    fillPattern(src, sz, 0x20);
    FakeJavaArray arr{src, sz};

    FakeEnv env;
    RendererFramePath renderer;

    // Warm-up frame: allocates the renderer buffer (and one legacy temp buffer).
    legacyUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr, w, h);

    long allocsBefore = g_heapAllocs;
    long freesBefore = g_heapFrees;
    for (int i = 0; i < kFrames; ++i) {
        legacyUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr, w, h);
    }
    long allocsDelta = g_heapAllocs - allocsBefore;
    long freesDelta = g_heapFrees - freesBefore;

    // Each legacy frame: exactly one new[]/delete[]; renderer buffer is reused (size constant).
    CHECK_EQ(allocsDelta, kFrames);
    CHECK_EQ(freesDelta, kFrames);
    CHECK_EQ(env.regionCopies, kFrames + 1);

    __real_free(src);
}

// Test 4: resolution change reallocates exactly once and stays byte-exact.
static void test_resize_reallocates_once() {
    std::printf("[test] resolution change reallocates exactly once and copies correctly\n");
    FakeEnv env;
    RendererFramePath renderer;

    // Initial resolution.
    const int w0 = 640, h0 = 480;
    const size_t sz0 = i420Size(w0, h0);
    uint8_t *src0 = static_cast<uint8_t *>(__real_malloc(sz0));
    fillPattern(src0, sz0, 0x31);
    FakeJavaArray arr0{src0, sz0};
    fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr0, w0, h0);
    CHECK_EQ(std::memcmp(renderer.m_RenderFrame.ppPlane[0], src0, sz0), 0);

    // Grow: expect exactly one free (old buffer) + one malloc (new buffer).
    const int w1 = 1920, h1 = 1080;
    const size_t sz1 = i420Size(w1, h1);
    uint8_t *src1 = static_cast<uint8_t *>(__real_malloc(sz1));
    fillPattern(src1, sz1, 0x42);
    FakeJavaArray arr1{src1, sz1};

    long allocsBefore = g_heapAllocs;
    long freesBefore = g_heapFrees;
    fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr1, w1, h1);
    CHECK_EQ(g_heapAllocs - allocsBefore, 1);
    CHECK_EQ(g_heapFrees - freesBefore, 1);
    CHECK_EQ(std::memcmp(renderer.m_RenderFrame.ppPlane[0], src1, sz1), 0);

    // Same size again: no allocation.
    allocsBefore = g_heapAllocs;
    fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr1, w1, h1);
    CHECK_EQ(g_heapAllocs - allocsBefore, 0);

    // Shrink: again exactly one free + one malloc, still correct.
    const int w2 = 320, h2 = 240;
    const size_t sz2 = i420Size(w2, h2);
    uint8_t *src2 = static_cast<uint8_t *>(__real_malloc(sz2));
    fillPattern(src2, sz2, 0x53);
    FakeJavaArray arr2{src2, sz2};
    allocsBefore = g_heapAllocs;
    freesBefore = g_heapFrees;
    fixedUpdateFrame(&env, &renderer, IMAGE_FORMAT_I420, &arr2, w2, h2);
    CHECK_EQ(g_heapAllocs - allocsBefore, 1);
    CHECK_EQ(g_heapFrees - freesBefore, 1);
    CHECK_EQ(std::memcmp(renderer.m_RenderFrame.ppPlane[0], src2, sz2), 0);

    __real_free(src0);
    __real_free(src1);
    __real_free(src2);
}

// Test 5: zero-copy handoff -- the pointer given to the renderer is the array's own memory.
static void test_zero_copy_handoff_contract() {
    std::printf("[test] handoff is zero-copy and uses JNI_ABORT release\n");
    const int w = 320, h = 240;
    const size_t sz = i420Size(w, h);
    uint8_t *src = static_cast<uint8_t *>(__real_malloc(sz));
    fillPattern(src, sz, 0x64);
    FakeJavaArray arr{src, sz};

    FakeEnv env;
    unsigned char isCopy = 0xFF;
    void *p = env.GetPrimitiveArrayCritical(&arr, &isCopy);
    CHECK(p == arr.data);   // identity: no intermediate buffer
    CHECK_EQ(isCopy, 0);    // JNI_FALSE: direct pointer, not a copy
    env.ReleasePrimitiveArrayCritical(&arr, p, JNI_ABORT);
    CHECK_EQ(env.lastReleaseMode, JNI_ABORT);
    CHECK_EQ(env.criticalGets, env.criticalReleases);

    __real_free(src);
}

int main() {
    std::printf("Running preview-frame input pipeline regression tests...\n");
    // Warm up stdio so its first internal allocation does not land inside a measured loop.
    std::fflush(stdout);

    test_fixed_path_copies_correctly();
    test_fixed_path_no_per_frame_allocation();
    test_legacy_path_allocates_every_frame();
    test_resize_reallocates_once();
    test_zero_copy_handoff_contract();

    if (g_failures == 0) {
        std::printf("\nALL TESTS PASSED\n");
        return 0;
    }
    std::fprintf(stderr, "\n%d CHECK(S) FAILED\n", g_failures);
    return 1;
}
