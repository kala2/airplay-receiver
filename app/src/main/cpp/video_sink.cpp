// Part of the AirPlayRx app (GPL-3.0).
#include "video_sink.h"

#include <android/log.h>

#include <deque>
#include <mutex>
#include <vector>

#include "jni_util.h"

#define LOG_TAG "AirPlayRx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace airplayrx {
namespace {

// Must match VideoDecoder's submit codes in Kotlin (QUEUED = 0 needs no name here).
constexpr jint kNotReady = 1;
constexpr jint kDropped = 2;

// Enough for several seconds of mirroring at typical bitrates while the screen comes up.
constexpr size_t kMaxPendingBytes = 16 * 1024 * 1024;

jclass g_class = nullptr;  // NativeBridge (global ref owned by jni_bridge.cpp)
jmethodID g_on_video_frame = nullptr;
jmethodID g_on_mirror_state = nullptr;
jmethodID g_on_video_size = nullptr;

struct PendingFrame {
    std::vector<uint8_t> data;
    uint64_t pts_us;
};
std::mutex g_pending_lock;  // pending is touched by the mirror thread and session end
std::deque<PendingFrame> g_pending;
size_t g_pending_bytes = 0;
uint32_t g_hold_events = 0;  // times frames had to wait this session (guarded by g_pending_lock)

jint submit(JNIEnv *env, const uint8_t *data, int len, uint64_t pts_us) {
    FrameOffer offer(data, len);
    jint rc = env->CallStaticIntMethod(g_class, g_on_video_frame, (jint) len, (jlong) pts_us);
    if (clear_exception(env, "onVideoFrame")) return kDropped;
    return rc;
}

void clear_pending_locked() {
    g_pending.clear();
    g_pending_bytes = 0;
}

}  // namespace

bool video_sink_init(JNIEnv *env, jclass bridge_class) {
    g_class = bridge_class;
    g_on_video_frame = env->GetStaticMethodID(bridge_class, "onVideoFrame", "(IJ)I");
    g_on_mirror_state = env->GetStaticMethodID(bridge_class, "onMirrorState", "(Z)V");
    g_on_video_size = env->GetStaticMethodID(bridge_class, "onVideoSize", "(II)V");
    return g_on_video_frame && g_on_mirror_state && g_on_video_size;
}

void video_sink_frame(const uint8_t *data, int len, uint64_t pts_us) {
    if (!data || len <= 0) return;
    JNIEnv *env = thread_env();
    if (!env) return;

    std::lock_guard<std::mutex> guard(g_pending_lock);
    // Oldest first: retry what is waiting before the new frame, preserving decode order.
    while (!g_pending.empty()) {
        PendingFrame &f = g_pending.front();
        if (submit(env, f.data.data(), (int) f.data.size(), f.pts_us) == kNotReady) break;
        g_pending_bytes -= f.data.size();
        g_pending.pop_front();
    }
    if (g_pending.empty() && submit(env, data, len, pts_us) != kNotReady) return;

    if (g_pending_bytes + (size_t) len > kMaxPendingBytes) {
        LOGE("video: decoder not ready for too long; discarding %zu waiting frames",
             g_pending.size());
        clear_pending_locked();
        return;
    }
    if (g_pending.empty() && g_hold_events++ == 0) {
        LOGI("video: decoder not ready yet, holding frames (normal at session start)");
    }
    g_pending.push_back({std::vector<uint8_t>(data, data + len), pts_us});
    g_pending_bytes += (size_t) len;
}

void video_sink_session(bool running) {
    if (!running) {
        std::lock_guard<std::mutex> guard(g_pending_lock);
        if (g_hold_events > 0) LOGI("video: frames had to wait %u times this session", g_hold_events);
        g_hold_events = 0;
        clear_pending_locked();
    }
    JNIEnv *env = thread_env();
    if (!env) return;
    env->CallStaticVoidMethod(g_class, g_on_mirror_state, (jboolean) running);
    clear_exception(env, "onMirrorState");
}

void video_sink_size(int width, int height) {
    JNIEnv *env = thread_env();
    if (!env) return;
    env->CallStaticVoidMethod(g_class, g_on_video_size, (jint) width, (jint) height);
    clear_exception(env, "onVideoSize");
}

}  // namespace airplayrx
