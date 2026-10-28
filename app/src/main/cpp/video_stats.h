// Aggregated statistics for the incoming H.264 mirror stream.
// Logged at most once per interval — never per frame (logging per frame would cost CPU at 60 fps).
// Part of the AirPlayRx app (GPL-3.0).
#pragma once

#include <cstdint>
#include <mutex>

namespace airplayrx {

// What the first NAL units of one access unit contain. UxPlay delivers Annex-B data with
// 4-byte start codes, and prepends SPS+PPS to the first frame after a format change.
struct FrameInfo {
    bool valid = false;     // false if UxPlay flagged it (decrypt failure) or no start code
    bool has_sps = false;
    bool has_pps = false;
    bool is_idr = false;    // contains an IDR slice (keyframe)
};

// Inspects only the first few NAL headers (bounded scan), so it is cheap per frame.
FrameInfo inspect_h264(const uint8_t *data, int len);

class VideoStats {
  public:
    // UxPlay mirror thread. Logs a summary when the interval has elapsed, and notable events
    // (first SPS/PPS, first keyframe) once per session.
    // lead_us: the sender's presentation time (UxPlay ntp_time_local, wall clock) minus arrival
    // time. Positive = the frame arrived ahead of when it is meant to be shown.
    void on_frame(const uint8_t *data, int len, uint64_t now_us, int64_t lead_us);
    // Any thread. Logs a session total and resets.
    void end_session(const char *reason);

    static constexpr uint64_t kIntervalUs = 5'000'000;

  private:
    struct Window {
        uint64_t start_us = 0;
        uint32_t frames = 0;
        uint32_t keyframes = 0;
        uint32_t invalid = 0;
        uint64_t bytes = 0;
        int max_frame = 0;
        int64_t lead_sum_us = 0;
        int64_t lead_min_us = INT64_MAX;
        int64_t lead_max_us = INT64_MIN;
    };

    void log_window_locked(uint64_t now_us);

    std::mutex lock_;
    Window window_;
    uint64_t session_start_us_ = 0;
    uint64_t session_frames_ = 0;
    uint64_t session_bytes_ = 0;
    uint64_t session_invalid_ = 0;
    bool logged_sps_ = false;
    bool logged_idr_ = false;
};

}  // namespace airplayrx
