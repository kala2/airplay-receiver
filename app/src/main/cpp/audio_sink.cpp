// Part of the AirPlayRx app (GPL-3.0).
#include "audio_sink.h"

#include <android/log.h>

#include <atomic>
#include <cmath>

#include "jni_util.h"

#define LOG_TAG "AirPlayRx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace airplayrx {
namespace {

constexpr unsigned char kCtAacEld = 8;

jclass g_class = nullptr;  // NativeBridge (global ref owned by jni_bridge.cpp)
jmethodID g_on_audio_packet = nullptr;
jmethodID g_on_audio_volume = nullptr;
jmethodID g_on_audio_flush = nullptr;

std::atomic<uint32_t> g_invalid{0};
std::atomic<uint32_t> g_unsupported{0};
std::atomic<bool> g_logged_unsupported{false};

// Lead statistics, owned by UxPlay's audio thread. Session end (httpd thread) only raises a flag;
// the audio thread does the reset itself, so the two never touch g_lead at once.
struct LeadWindow {
    uint64_t start_us = 0;
    uint32_t packets = 0;
    int64_t sum_us = 0;
    int64_t min_us = INT64_MAX;
    int64_t max_us = INT64_MIN;
};
LeadWindow g_lead;
std::atomic<bool> g_lead_reset{false};

void track_lead(uint64_t now_us, int64_t lead_us) {
    if (g_lead_reset.exchange(false)) g_lead = LeadWindow{};
    if (g_lead.start_us == 0) g_lead.start_us = now_us;
    g_lead.packets++;
    g_lead.sum_us += lead_us;
    if (lead_us < g_lead.min_us) g_lead.min_us = lead_us;
    if (lead_us > g_lead.max_us) g_lead.max_us = lead_us;
    if (now_us - g_lead.start_us >= 5000000 && g_lead.packets > 0) {
        LOGI("audio timing: %u packets in %.1f s, lead avg %lld ms [%lld, %lld]", g_lead.packets,
             (now_us - g_lead.start_us) / 1e6, (long long) (g_lead.sum_us / g_lead.packets / 1000),
             (long long) (g_lead.min_us / 1000), (long long) (g_lead.max_us / 1000));
        g_lead = LeadWindow{};
        g_lead.start_us = now_us;
    }
}

// Same check as UxPlay's audio_renderer_render_buffer(): AAC-ELD frames start with 0x8c-0x8e
// (modern iOS) or 0x80-0x82 (iOS 9/10).
bool looks_like_aac_eld(const uint8_t *data, int len) {
    if (len <= 0) return false;
    switch (data[0]) {
        case 0x8c: case 0x8d: case 0x8e: case 0x80: case 0x81: case 0x82: return true;
        default: return false;
    }
}

}  // namespace

bool audio_sink_init(JNIEnv *env, jclass bridge_class) {
    g_class = bridge_class;
    g_on_audio_packet = env->GetStaticMethodID(bridge_class, "onAudioPacket", "(IJ)V");
    g_on_audio_volume = env->GetStaticMethodID(bridge_class, "onAudioVolume", "(F)V");
    g_on_audio_flush = env->GetStaticMethodID(bridge_class, "onAudioFlush", "()V");
    return g_on_audio_packet && g_on_audio_volume && g_on_audio_flush;
}

void audio_sink_packet(const uint8_t *data, int len, unsigned char ct, uint64_t pts_us,
                       int64_t lead_us) {
    if (!data || len <= 0) return;
    if (ct != kCtAacEld) {
        // ALAC (ct 2) is audio-only AirPlay (music), an optional later milestone.
        g_unsupported++;
        if (!g_logged_unsupported.exchange(true)) {
            LOGW("audio: compression type %u not supported yet (only AAC-ELD, 8)", ct);
        }
        return;
    }
    if (!looks_like_aac_eld(data, len)) {
        if (g_invalid++ == 0) {
            LOGW("audio: invalid AAC-ELD packet (%d bytes, first byte 0x%02x) skipped", len,
                 len > 0 ? data[0] : 0);
        }
        return;
    }
    track_lead(pts_us, lead_us);
    JNIEnv *env = thread_env();
    if (!env) return;
    FrameOffer offer(data, len);
    env->CallStaticVoidMethod(g_class, g_on_audio_packet, (jint) len, (jlong) pts_us);
    clear_exception(env, "onAudioPacket");
}

float airplay_volume_to_gain(float db) {
    if (db == -144.0f || db <= -30.0f) return 0.0f;  // mute / bottom of the slider
    if (db >= 0.0f) return 1.0f;
    // UxPlay rescales the slider onto db_low..db_high; with its defaults (-30..0) that is the
    // identity, so the AirPlay dB value is the gain in dB.
    return std::pow(10.0f, 0.05f * db);
}

void audio_sink_volume(float airplay_db) {
    float gain = airplay_volume_to_gain(airplay_db);
    LOGI("audio: volume %.1f dB → gain %.3f", airplay_db, gain);
    JNIEnv *env = thread_env();
    if (!env) return;
    env->CallStaticVoidMethod(g_class, g_on_audio_volume, (jfloat) gain);
    clear_exception(env, "onAudioVolume");
}

void audio_sink_flush() {
    JNIEnv *env = thread_env();
    if (!env) return;
    env->CallStaticVoidMethod(g_class, g_on_audio_flush);
    clear_exception(env, "onAudioFlush");
}

void audio_sink_session_end() {
    uint32_t invalid = g_invalid.exchange(0);
    uint32_t unsupported = g_unsupported.exchange(0);
    g_logged_unsupported = false;
    g_lead_reset = true;
    if (invalid || unsupported) {
        LOGW("audio session: %u invalid, %u unsupported packets skipped", invalid, unsupported);
    }
}

}  // namespace airplayrx
