#pragma once
// The AOSP decoder only uses ALOGD in an optional performance-debug block.
// NDK apps do not have the platform-private cutils header.
#include <android/log.h>
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "PinyinPerformance", __VA_ARGS__)
