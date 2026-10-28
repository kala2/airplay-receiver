// UxPlay server lifecycle, following desktop uxplay.cpp (v1.73.7): start_dnssd() →
// start_raop_server() → register_dnssd(), and the reverse on stop.
// Part of the AirPlayRx app (GPL-3.0).

#include "server.h"
#include "audio_sink.h"
#include "video_sink.h"
#include "video_stats.h"

#include <android/log.h>

#include <cstdlib>
#include <cstring>
#include <atomic>
#include <ctime>
#include <mutex>
#include <vector>

extern "C" {
#include "dnssd.h"
#include "logger.h"
#include "raop.h"
}

#define LOG_TAG "AirPlayRx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace airplayrx {
namespace {

std::mutex g_lock;  // guards g_raop, g_dnssd, g_port
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;
unsigned short g_port = 0;
VideoStats g_video_stats;

uint64_t monotonic_us() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000u + (uint64_t) ts.tv_nsec / 1000u;
}

// UxPlay's ntp_time_local is CLOCK_REALTIME in ns (raop_ntp_get_local_time()).
int64_t lead_us(uint64_t ntp_time_local_ns) {
    if (ntp_time_local_ns == 0) return 0;
    timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    int64_t now_ns = (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
    return ((int64_t) ntp_time_local_ns - now_ns) / 1000;
}

// ---- UxPlay logger → logcat ------------------------------------------------------------------
// Runs on any UxPlay thread. No JNI, so no attachment needed.
void log_callback(void *, int level, const char *msg) {
    int prio = level <= LOGGER_ERR       ? ANDROID_LOG_ERROR
               : level == LOGGER_WARNING ? ANDROID_LOG_WARN
               : level <= LOGGER_INFO    ? ANDROID_LOG_INFO
                                         : ANDROID_LOG_DEBUG;
    __android_log_print(prio, LOG_TAG, "uxplay: %s", msg);
}

// ---- RAOP callbacks ----------------------------------------------------------------------------
// UxPlay calls most of these without checking for NULL, so every one gets at least a stub.
// Behaviour mirrors desktop UxPlay's defaults (no PIN, no password, no client list, no HLS).
// All run on UxPlay's network threads. Video/audio are wired up in Milestones 4–6.

// UxPlay audio thread, once per (decrypted) audio packet.
void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    audio_sink_packet(data->data, data->data_len, data->ct, monotonic_us(),
                      lead_us(data->ntp_time_local));
}
// UxPlay mirror thread, once per H.264 access unit (Annex-B, decrypted).
void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    uint64_t now = monotonic_us();
    g_video_stats.on_frame(data->data, data->data_len, now, lead_us(data->ntp_time_local));
    // Arrival time as PTS: frames are rendered as soon as decoded, so it only has to increase.
    video_sink_frame(data->data, data->data_len, now);
}
void cb_video_pause(void *) {}
void cb_video_resume(void *) {}
void cb_conn_feedback(void *) {}
void cb_conn_reset(void *, int reason) { LOGI("connection reset (reason %d)", reason); }
void cb_video_reset(void *, reset_type_t type) {
    LOGI("video reset (type %d)", (int) type);
    g_video_stats.end_session("video reset");
}
// UxPlay opens extra short connections next to a mirroring session (e.g. an iPhone answering the
// BLE beacon, or a second client that gets rejected), and calls conn_init/conn_destroy for each.
// Like uxplay.cpp, count them and treat only "no connections left" as the end of a session.
std::atomic<int> g_open_connections{0};

void cb_conn_init(void *) { LOGI("client connected (%d open)", ++g_open_connections); }
void cb_conn_destroy(void *) {
    int open = --g_open_connections;
    if (open < 0) open = g_open_connections = 0;  // defensive: never go negative
    LOGI("client disconnected (%d open)", open);
    if (open > 0) return;  // another connection (the real session) is still alive
    g_video_stats.end_session("client disconnected");
    video_sink_session(false);  // idempotent; covers disconnects without a mirror stop
    audio_sink_session_end();
}
void cb_audio_flush(void *) { audio_sink_flush(); }
void cb_video_flush(void *) {}
double cb_audio_set_client_volume(void *) { return 0.0; }  // uxplay.cpp initial_volume
void cb_audio_set_volume(void *, float volume) { audio_sink_volume(volume); }
void cb_audio_set_metadata(void *, const void *, int) {}
void cb_audio_set_coverart(void *, const void *, int) {}
void cb_audio_stop_coverart_rendering(void *) {}
void cb_audio_remote_control_id(void *, const char *, const char *) {}
void cb_audio_set_progress(void *, uint32_t *, uint32_t *, uint32_t *) {}
void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *using_screen,
                         bool *is_media, uint64_t *format) {
    LOGI("audio format: ct=%u spf=%u usingScreen=%d isMedia=%d format=0x%llx", *ct, *spf,
         *using_screen, *is_media, (unsigned long long) *format);
}
void cb_video_report_size(void *, float *, float *, float *w, float *h) {
    LOGI("video size reported: %.0fx%.0f", *w, *h);
    video_sink_size((int) *w, (int) *h);
}
void cb_mirror_video_running(void *, bool running) {
    LOGI("mirror video running: %d", running);
    if (!running) g_video_stats.end_session("mirroring stopped");
    video_sink_session(running);
}

// Never log the client's keys; device id/model/name are fine.
void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    // name/model come from the client and may be NULL.
    LOGI("connection request from \"%s\" (%s)", name ? name : "?", model ? model : "?");
    (void) deviceid;
    *admit = true;
}
void cb_display_pin(void *, char *) {}
void cb_register_client(void *, const char *, const char *, const char *) {}
bool cb_check_register(void *, const char *) { return true; }
const char *cb_passwd(void *, int *len) {
    *len = 0;  // no password
    return nullptr;
}
void cb_export_dacp(void *, const char *, const char *) {}
int cb_video_set_codec(void *, video_codec_t codec) {
    LOGI("video codec: %s", codec == VIDEO_CODEC_H264 ? "H.264" : "H.265/unknown");
    return codec == VIDEO_CODEC_H264 ? 0 : -1;  // H.265 is not advertised (feature bit 42 off)
}
void cb_on_video_play(void *, const char *, const float) {}
void cb_on_video_scrub(void *, const float) {}
void cb_on_video_rate(void *, const float) {}
void cb_on_video_stop(void *) {}
void cb_on_video_acquire_playback_info(void *, playback_info_t *) {}
float cb_on_video_playlist_remove(void *) { return 0.0f; }

raop_callbacks_t make_callbacks() {
    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_reset = cb_video_reset;
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    return cbs;
}

// "02:1a:2b:3c:4d:5e" → 6 bytes, as uxplay.cpp parse_hw_addr().
bool parse_mac(const char *mac, std::vector<char> &out) {
    out.clear();
    if (strlen(mac) != 17) return false;
    for (int i = 0; i < 17; i += 3) {
        char *end = nullptr;
        long b = strtol(mac + i, &end, 16);
        if (end != mac + i + 2 || b < 0 || b > 255) return false;
        out.push_back((char) b);
    }
    return true;
}

// Same overrides as uxplay.cpp start_dnssd() with default options. Bits 0–31 that it sets one by
// one add up to FEATURES_1 (0x5A7FFEE6) already, so only the option-dependent ones are needed.
void set_features(dnssd_t *dnssd) {
    dnssd_set_airplay_features(dnssd, 0, 0);   // HLS video (hls_support = false)
    dnssd_set_airplay_features(dnssd, 4, 0);   // HLS (hls_support = false)
    dnssd_set_airplay_features(dnssd, 42, 0);  // H.265 (h265_support = false)
    dnssd_set_airplay_features(dnssd, 27, 0);  // legacy pairing (setup_legacy_pairing = false)
}

// Order matters: dnssd_unregister_*() frees dnssd->name/hw_addr once both
// services are withdrawn, but UxPlay's httpd thread reads them (GET /info, pairing) until it is
// stopped. So: stop httpd → unregister → destroy raop → destroy dnssd.
void teardown_locked() {
    if (g_raop) raop_stop_httpd(g_raop);  // idempotent; joins the httpd thread
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    g_port = 0;
}

}  // namespace

int server_start(const ServerConfig &config) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_raop) return g_port;
    g_open_connections = 0;

    std::vector<char> hw_addr;
    if (!parse_mac(config.mac, hw_addr)) {
        LOGE("invalid MAC \"%s\"", config.mac);
        return -1;
    }

    int err = 0;
    g_dnssd = dnssd_init(config.name, (int) strlen(config.name), hw_addr.data(),
                         (int) hw_addr.size(), &err, 0 /* no pin/password */);
    if (err || !g_dnssd) {
        LOGE("dnssd_init failed: %d", err);
        g_dnssd = nullptr;
        return -2;
    }
    set_features(g_dnssd);

    raop_callbacks_t cbs = make_callbacks();
    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        teardown_locked();
        return -3;
    }
    raop_set_log_callback(g_raop, log_callback, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);
    // keyfile "" → UxPlay derives a persistent pairing key from the device id, so the iPhone
    // sees the same identity across restarts as long as the MAC is unchanged.
    if (raop_init2(g_raop, 0 /* nohold */, config.mac, "") != 0) {
        LOGE("raop_init2 failed");
        // As uxplay.cpp: raop_destroy() would assert without an httpd. This leaks UxPlay's
        // logger (raop_t is opaque to us); only on a failed start, accepted.
        free(g_raop);
        g_raop = nullptr;
        teardown_locked();
        return -4;
    }
    // Before httpd starts: the /info handler asserts raop->dnssd. Also hands the
    // pairing public key (created by raop_init2) to dnssd for the TXT records.
    raop_set_dnssd(g_raop, g_dnssd);

    raop_set_plist(g_raop, "width", config.width);
    raop_set_plist(g_raop, "height", config.height);
    raop_set_plist(g_raop, "refreshRate", config.refresh_rate);
    raop_set_plist(g_raop, "maxFPS", config.max_fps);

    // Ports 0 = let the OS choose, as UxPlay's default.
    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0 || port == 0) {
        LOGE("raop_start_httpd failed");
        teardown_locked();
        return -5;
    }
    raop_set_port(g_raop, port);
    g_port = port;

    if ((err = dnssd_register_raop(g_dnssd, port)) != 0) {
        LOGE("dnssd_register_raop failed: %d", err);
        teardown_locked();
        return -6;
    }
    if ((err = dnssd_register_airplay(g_dnssd, port)) != 0) {
        LOGE("dnssd_register_airplay failed: %d", err);
        teardown_locked();
        return -7;
    }
    LOGI("server started on port %u as \"%s\" (features 0x%llX, display %dx%d@%d, maxFPS %d)",
         port, config.name, (unsigned long long) dnssd_get_airplay_features(g_dnssd),
         config.width, config.height, config.refresh_rate, config.max_fps);
    return port;
}

void server_stop() {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_raop && !g_dnssd) return;
    teardown_locked();
    LOGI("server stopped");
}

}  // namespace airplayrx
