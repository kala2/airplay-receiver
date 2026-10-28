// JNI helpers shared by the media sinks.
// Part of the AirPlayRx app (GPL-3.0).
#pragma once

#include <jni.h>

#include <cstdint>

namespace airplayrx {

// Called once from JNI_OnLoad.
bool jni_util_init(JavaVM *vm);

// JNIEnv for the calling thread. UxPlay threads are long-lived, so a thread is attached once and
// detached automatically when it exits (not per call). Returns nullptr on failure.
JNIEnv *thread_env();

// Logs, describes and clears a pending Java exception. Returns true if there was one.
bool clear_exception(JNIEnv *env, const char *where);

// Single-copy frame hand-off. Native code publishes the frame it is offering to Kotlin
// (thread-local, valid only for the duration of the JNI call), and Kotlin calls back
// NativeBridge.nativeCopyFrame(buffer) to copy it straight into a MediaCodec input buffer.
// Thread-local, so the video and audio threads never see each other's frame.
class FrameOffer {
  public:
    FrameOffer(const uint8_t *data, int len);
    ~FrameOffer();
    FrameOffer(const FrameOffer &) = delete;
    FrameOffer &operator=(const FrameOffer &) = delete;
};

// JNI nativeCopyFrame: copies the current thread's offered frame into dst (a direct buffer).
// Returns its length, or -1 if there is no offer or dst is too small / not direct.
jint copy_offered_frame(JNIEnv *env, jobject dst);

}  // namespace airplayrx
