// Hands H.264 access units from UxPlay to the Kotlin VideoDecoder with a single copy, straight
// into a MediaCodec input buffer (see FrameOffer in jni_util.h).
// Part of the AirPlayRx app (GPL-3.0).
#pragma once

#include <jni.h>

#include <cstdint>

namespace airplayrx {

// Called once from JNI_OnLoad (after jni_util_init).
bool video_sink_init(JNIEnv *env, jclass bridge_class);

// UxPlay mirror thread, once per access unit. Frames the decoder cannot take yet are kept (in
// order, bounded) and retried before the next one: the iPhone sends a single keyframe per
// session, so dropping anything before the decoder is ready would leave it unable to decode.
void video_sink_frame(const uint8_t *data, int len, uint64_t pts_us);

// Mirror thread or httpd thread. Notifies Kotlin and discards any frames still waiting.
void video_sink_session(bool running);

// UxPlay mirror thread. Width/height as reported by the iPhone (the decoder's output format is
// authoritative; this is only a hint for the UI).
void video_sink_size(int width, int height);

}  // namespace airplayrx
