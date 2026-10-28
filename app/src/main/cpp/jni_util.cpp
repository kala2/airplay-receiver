// Part of the AirPlayRx app (GPL-3.0).
#include "jni_util.h"

#include <android/log.h>
#include <pthread.h>

#include <cstring>

#define LOG_TAG "AirPlayRx"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace airplayrx {
namespace {

JavaVM *g_vm = nullptr;
pthread_key_t g_detach_key;

thread_local const uint8_t *t_offer_data = nullptr;
thread_local int t_offer_len = 0;

void detach_thread(void *) { g_vm->DetachCurrentThread(); }

}  // namespace

bool jni_util_init(JavaVM *vm) {
    g_vm = vm;
    return pthread_key_create(&g_detach_key, detach_thread) == 0;
}

JNIEnv *thread_env() {
    JNIEnv *env = nullptr;
    jint rc = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc != JNI_EDETACHED) return nullptr;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "AirPlayRx-uxplay", nullptr};
    if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    pthread_setspecific(g_detach_key, env);  // non-null value → detach_thread runs at exit
    return env;
}

bool clear_exception(JNIEnv *env, const char *where) {
    if (!env->ExceptionCheck()) return false;
    LOGE("%s: Kotlin threw", where);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

FrameOffer::FrameOffer(const uint8_t *data, int len) {
    t_offer_data = data;
    t_offer_len = len;
}

FrameOffer::~FrameOffer() {
    t_offer_data = nullptr;
    t_offer_len = 0;
}

jint copy_offered_frame(JNIEnv *env, jobject dst) {
    if (!t_offer_data || t_offer_len <= 0) return -1;
    void *addr = env->GetDirectBufferAddress(dst);
    jlong cap = env->GetDirectBufferCapacity(dst);
    if (!addr || cap < t_offer_len) return -1;
    memcpy(addr, t_offer_data, (size_t) t_offer_len);
    return t_offer_len;
}

}  // namespace airplayrx
