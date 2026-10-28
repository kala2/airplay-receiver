// JNI entry points for dev.personal.airplayrx.NativeBridge, and native → Kotlin callbacks.
// Part of the AirPlayRx app (GPL-3.0).

#include <android/log.h>
#include <jni.h>

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <plist/plist.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "dns_sd_shim.h"
#include "audio_sink.h"
#include "jni_util.h"
#include "server.h"
#include "video_sink.h"

extern "C" {
#include "raop.h"
}

#define LOG_TAG "AirPlayRx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jclass g_bridge_class = nullptr;          // global ref, lives for the process
jmethodID g_on_service_changed = nullptr;  // static void onServiceChanged(ZLjava/lang/String;…)

// Returns a JNIEnv for the current thread, attaching it if needed (*attached tells the caller
// to detach afterwards).
JNIEnv *get_env(bool *attached) {
    *attached = false;
    JNIEnv *env = nullptr;
    jint rc = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        *attached = true;
    } else if (rc != JNI_OK) {
        return nullptr;
    }
    return env;
}

// dns_sd shim listener. Runs synchronously on the thread calling nativeStart/nativeStop (a
// Kotlin worker thread, already attached), but attaches defensively in case UxPlay ever
// re-registers from one of its own threads.
void on_service_changed(void *, int registered, const char *name, const char *regtype,
                        uint16_t port, const uint8_t *txt, uint16_t txt_len) {
    bool attached = false;
    JNIEnv *env = get_env(&attached);
    if (!env) {
        LOGE("dns_sd listener: no JNIEnv");
        return;
    }
    jstring jname = env->NewStringUTF(name);
    jstring jtype = env->NewStringUTF(regtype);
    jbyteArray jtxt = nullptr;
    if (txt && txt_len > 0) {
        jtxt = env->NewByteArray(txt_len);
        if (jtxt) env->SetByteArrayRegion(jtxt, 0, txt_len, reinterpret_cast<const jbyte *>(txt));
    }
    // Out of memory leaves a pending exception: never call into Java with one.
    if (jname && jtype && !env->ExceptionCheck()) {
        env->CallStaticVoidMethod(g_bridge_class, g_on_service_changed,
                                  (jboolean) (registered != 0), jname, jtype, (jint) port, jtxt);
    }
    if (env->ExceptionCheck()) {
        LOGE("dns_sd listener: Kotlin threw");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (jname) env->DeleteLocalRef(jname);
    if (jtype) env->DeleteLocalRef(jtype);
    if (jtxt) env->DeleteLocalRef(jtxt);
    if (attached) g_vm->DetachCurrentThread();
}

// raop_init() rejects a callbacks struct without these two.
void noop_audio(void *, raop_ntp_t *, audio_decode_struct *) {}
void noop_video(void *, raop_ntp_t *, video_decode_struct *) {}

// Exercises each dependency once so a broken build fails loudly at startup, not mid-session.
bool self_test(std::string &report) {
    bool ok = true;

    // OpenSSL: SHA-256("abc") has a well-known digest.
    unsigned char md[32];
    unsigned int md_len = 0;
    static const unsigned char kExpected[4] = {0xba, 0x78, 0x16, 0xbf};
    if (EVP_Digest("abc", 3, md, &md_len, EVP_sha256(), nullptr) != 1 || md_len != 32 ||
        memcmp(md, kExpected, sizeof(kExpected)) != 0) {
        report += " sha256=FAIL";
        ok = false;
    }

    // libplist: build a dict and serialize it to binary plist, as UxPlay does for /info.
    plist_t dict = plist_new_dict();
    plist_dict_set_item(dict, "width", plist_new_uint(1920));
    char *bin = nullptr;
    uint32_t bin_len = 0;
    plist_to_bin(dict, &bin, &bin_len);
    if (!bin || bin_len < 8 || memcmp(bin, "bplist00", 8) != 0) {
        report += " plist=FAIL";
        ok = false;
    }
    plist_mem_free(bin);
    plist_free(dict);

    // UxPlay: create and destroy a server object, including the pairing key (Ed25519 via
    // OpenSSL) and the httpd object. No sockets are opened. raop_destroy() requires raop_init2()
    // to have succeeded (it asserts on the httpd). keyfile must be "" (not NULL) for "no file".
    raop_callbacks_t callbacks = {};
    callbacks.audio_process = noop_audio;
    callbacks.video_process = noop_video;
    raop_t *raop = raop_init(&callbacks);
    if (!raop) {
        report += " raop_init=FAIL";
        ok = false;
    } else if (raop_init2(raop, 0, "02:00:00:00:00:00", "") != 0) {
        report += " raop_init2=FAIL";
        ok = false;
        // Leaks the half-initialised raop: raop_destroy() would abort without an httpd.
    } else {
        raop_destroy(raop);
    }
    return ok;
}

// RAII wrapper for GetStringUTFChars.
class Utf {
  public:
    Utf(JNIEnv *env, jstring s) : env_(env), s_(s), c_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Utf() {
        if (c_) env_->ReleaseStringUTFChars(s_, c_);
    }
    const char *get() const { return c_ ? c_ : ""; }  // "" makes server_start reject it cleanly

  private:
    JNIEnv *env_;
    jstring s_;
    const char *c_;
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("dev/personal/airplayrx/NativeBridge");
    if (!local) return JNI_ERR;
    g_bridge_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_on_service_changed = env->GetStaticMethodID(
        g_bridge_class, "onServiceChanged", "(ZLjava/lang/String;Ljava/lang/String;I[B)V");
    if (!g_on_service_changed) return JNI_ERR;
    if (!airplayrx::jni_util_init(vm) || !airplayrx::video_sink_init(env, g_bridge_class) ||
        !airplayrx::audio_sink_init(env, g_bridge_class)) {
        return JNI_ERR;
    }
    dns_sd_shim_set_listener(on_service_changed, nullptr);
    return JNI_VERSION_1_6;
}

// Any thread. Cheap.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_personal_airplayrx_NativeBridge_nativeInfo(JNIEnv *env, jclass) {
    char buf[256];
    snprintf(buf, sizeof(buf), "UxPlay %s, libplist %s, %s", UXPLAY_VERSION, libplist_version(),
             OpenSSL_version(OPENSSL_VERSION));
    std::string report = buf;
    bool ok = self_test(report);
    if (ok) {
        LOGI("native ready: %s", report.c_str());
    } else {
        LOGE("native self-test failed: %s", report.c_str());
    }
    return env->NewStringUTF(report.c_str());
}

// Worker thread (blocking). Calls NativeBridge.onServiceChanged on this same thread.
extern "C" JNIEXPORT jint JNICALL
Java_dev_personal_airplayrx_NativeBridge_nativeStart(JNIEnv *env, jclass, jstring name,
                                                     jstring mac, jint width, jint height,
                                                     jint fps) {
    Utf n(env, name);
    Utf m(env, mac);
    airplayrx::ServerConfig config;
    config.name = n.get();
    config.mac = m.get();
    config.width = width;
    config.height = height;
    config.refresh_rate = fps;
    config.max_fps = fps;
    return airplayrx::server_start(config);
}

// Worker thread (blocking). Calls NativeBridge.onServiceChanged on this same thread.
extern "C" JNIEXPORT void JNICALL
Java_dev_personal_airplayrx_NativeBridge_nativeStop(JNIEnv *, jclass) {
    airplayrx::server_stop();
}

// UxPlay mirror or audio thread, re-entrantly from NativeBridge.onVideoFrame/onAudioPacket
// (see FrameOffer in jni_util.h).
extern "C" JNIEXPORT jint JNICALL
Java_dev_personal_airplayrx_NativeBridge_nativeCopyFrame(JNIEnv *env, jclass, jobject dst) {
    return airplayrx::copy_offered_frame(env, dst);
}
