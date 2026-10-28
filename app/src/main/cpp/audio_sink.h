// Hands AAC-ELD packets from UxPlay to the Kotlin AudioPlayer (single copy, see FrameOffer).
// Part of the AirPlayRx app (GPL-3.0).
#pragma once

#include <jni.h>

#include <cstdint>

namespace airplayrx {

// Called once from JNI_OnLoad (after jni_util_init).
bool audio_sink_init(JNIEnv *env, jclass bridge_class);

// UxPlay audio thread, once per packet. ct is UxPlay's compression type (8 = AAC-ELD, the
// screen-mirroring format). Invalid or unsupported packets are counted and skipped; audio has no
// keyframes, so unlike video a lost packet only costs ~11 ms of sound.
// lead_us: sender presentation time minus arrival (see VideoStats::on_frame); aggregated and
// logged every 5 s as "audio timing".
void audio_sink_packet(const uint8_t *data, int len, unsigned char ct, uint64_t pts_us,
                       int64_t lead_us);

// UxPlay httpd thread: AirPlay volume in dB (-30..0, -144 = mute) → linear gain for Kotlin.
void audio_sink_volume(float airplay_db);

// UxPlay threads: drop buffered audio (e.g. the iPhone paused or seeked).
void audio_sink_flush();

// Any thread: logs per-session counts and resets them.
void audio_sink_session_end();

// Pure: AirPlay dB → linear gain, as uxplay.cpp audio_set_volume() with its defaults
// (flat -30..0 dB, no taper). Exposed for unit tests.
float airplay_volume_to_gain(float airplay_db);

}  // namespace airplayrx
