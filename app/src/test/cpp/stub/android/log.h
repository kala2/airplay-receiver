/* Host stand-in for <android/log.h> so native logic can be unit-tested on macOS. */
#pragma once
#include <stdio.h>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (printf("[%s] ", tag), printf(__VA_ARGS__), printf("\n"))
