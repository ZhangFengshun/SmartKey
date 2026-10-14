#include <jni.h>
#include <algorithm>
#include <mutex>
#include <string>
#include "aosp/include/pinyinime.h"

namespace {
std::mutex decoder_mutex;
bool opened = false;
constexpr size_t max_input = 39;

std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring candidate(JNIEnv* env, int index) {
    ime_pinyin::char16 text[256] = {};
    if (!opened || index < 0 || !ime_pinyin::im_get_candidate(index, text, 256)) {
        return env->NewStringUTF("");
    }
    return env->NewString(reinterpret_cast<const jchar*>(text), ime_pinyin::utf16_strlen(text));
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_open(JNIEnv* env, jobject, jstring dictionary) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (opened) ime_pinyin::im_close_decoder();
    // An empty user dictionary path disables persistent learning. The system
    // lexicon remains fully available without retaining typed phrases.
    opened = ime_pinyin::im_open_decoder(utf8(env, dictionary).c_str(), "");
    if (opened) {
        ime_pinyin::im_set_max_lens(max_input, 32);
        ime_pinyin::im_reset_search();
    } else {
        ime_pinyin::im_close_decoder();
    }
    return opened ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_search(JNIEnv* env, jobject, jstring input) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    const auto spelling = utf8(env, input);
    if (!opened || spelling.size() > max_input) return 0;
    return static_cast<jint>(ime_pinyin::im_search(spelling.c_str(), spelling.size()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_candidate(JNIEnv* env, jobject, jint index) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    return candidate(env, index);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_choose(JNIEnv*, jobject, jint index) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    return opened && index >= 0 ? static_cast<jint>(ime_pinyin::im_choose(index)) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_fixedLength(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    return opened ? static_cast<jint>(ime_pinyin::im_get_fixed_len()) : 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_spellingStarts(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    const ime_pinyin::uint16* starts = nullptr;
    const size_t count = opened ? ime_pinyin::im_get_spl_start_pos(starts) : 0;
    if (!starts || count == 0) return env->NewIntArray(0);
    jint values[64] = {};
    const size_t length = std::min(count + 1, size_t(64));
    for (size_t i = 0; i < length; ++i) values[i] = starts[i];
    auto result = env->NewIntArray(length);
    env->SetIntArrayRegion(result, 0, length, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_decodedLength(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    size_t decoded = 0;
    if (opened) ime_pinyin::im_get_sps_str(&decoded);
    return static_cast<jint>(decoded);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_reset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (opened) ime_pinyin::im_reset_search();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lovekey_open_pinyin_NativePinyin_close(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(decoder_mutex);
    if (opened) ime_pinyin::im_close_decoder();
    opened = false;
}
