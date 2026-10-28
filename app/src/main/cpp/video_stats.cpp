// Part of the AirPlayRx app (GPL-3.0).
#include "video_stats.h"

#include <android/log.h>

#define LOG_TAG "AirPlayRx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace airplayrx {

namespace {
// SPS + PPS are a few dozen bytes; anything past this is slice data we don't need to look at.
constexpr int kMaxScan = 512;
constexpr int kMaxNals = 4;

bool start_code_at(const uint8_t *p, int i, int len) {
    return i + 4 <= len && p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1;
}
}  // namespace

FrameInfo inspect_h264(const uint8_t *data, int len) {
    FrameInfo info;
    if (!data || !start_code_at(data, 0, len)) return info;  // UxPlay sets data[0]=1 if invalid
    info.valid = true;
    int pos = 0;
    int limit = len < kMaxScan ? len : kMaxScan;
    for (int nals = 0; nals < kMaxNals && pos + 4 < len; nals++) {
        int type = data[pos + 4] & 0x1f;
        if (type == 7) info.has_sps = true;
        else if (type == 8) info.has_pps = true;
        else if (type == 5) info.is_idr = true;
        if (type == 1 || type == 5) break;  // first slice reached; the rest is picture data
        int next = pos + 5;
        while (next < limit && !start_code_at(data, next, len)) next++;
        if (next >= limit) break;
        pos = next;
    }
    return info;
}

void VideoStats::on_frame(const uint8_t *data, int len, uint64_t now_us, int64_t lead_us) {
    FrameInfo info = inspect_h264(data, len);
    std::lock_guard<std::mutex> guard(lock_);
    if (session_start_us_ == 0) {
        session_start_us_ = now_us;
        window_.start_us = now_us;
    }
    if (info.has_sps && info.has_pps && !logged_sps_) {
        LOGI("video: SPS+PPS received (%d-byte frame)", len);
        logged_sps_ = true;
    }
    if (info.is_idr && !logged_idr_) {
        LOGI("video: first keyframe (IDR) received, %d bytes", len);
        logged_idr_ = true;
    }
    window_.frames++;
    window_.bytes += (uint64_t) len;
    if (info.is_idr) window_.keyframes++;
    if (!info.valid) window_.invalid++;
    if (len > window_.max_frame) window_.max_frame = len;
    window_.lead_sum_us += lead_us;
    if (lead_us < window_.lead_min_us) window_.lead_min_us = lead_us;
    if (lead_us > window_.lead_max_us) window_.lead_max_us = lead_us;
    session_frames_++;
    session_bytes_ += (uint64_t) len;
    if (!info.valid) session_invalid_++;

    if (now_us - window_.start_us >= kIntervalUs) log_window_locked(now_us);
}

void VideoStats::log_window_locked(uint64_t now_us) {
    double secs = (double) (now_us - window_.start_us) / 1e6;
    if (secs <= 0) return;
    LOGI("video stats: %.1f fps, %.2f Mbit/s, %u frames (%u key, %u invalid), max frame %d B, "
         "lead avg %lld ms [%lld, %lld]",
         window_.frames / secs, window_.bytes * 8.0 / secs / 1e6, window_.frames,
         window_.keyframes, window_.invalid, window_.max_frame,
         window_.frames ? (long long) (window_.lead_sum_us / window_.frames / 1000) : 0LL,
         (long long) (window_.lead_min_us / 1000), (long long) (window_.lead_max_us / 1000));
    if (window_.invalid) LOGW("video: %u frames failed decryption in this interval", window_.invalid);
    window_ = Window{};
    window_.start_us = now_us;
}

void VideoStats::end_session(const char *reason) {
    std::lock_guard<std::mutex> guard(lock_);
    if (session_start_us_ != 0) {
        LOGI("video session ended (%s): %llu frames, %.1f MB, %llu invalid", reason,
             (unsigned long long) session_frames_, session_bytes_ / 1e6,
             (unsigned long long) session_invalid_);
    }
    window_ = Window{};
    session_start_us_ = 0;
    session_frames_ = session_bytes_ = session_invalid_ = 0;
    logged_sps_ = logged_idr_ = false;
}

}  // namespace airplayrx
