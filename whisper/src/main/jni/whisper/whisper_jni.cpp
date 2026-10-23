#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <sstream>
#include <string>
#include "whisper.h"

#define TAG "SigyiWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string jsonEscape(const char *value) {
    std::string out;
    if (value == nullptr) return out;
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(value); *p; ++p) {
        switch (*p) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += static_cast<char>(*p); break;
        }
    }
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sigyi_recap_whisper_WhisperNative_initContext(
        JNIEnv *env, jclass, jstring modelPath) {
    if (modelPath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context *ctx = whisper_init_from_file_with_params(
            path, whisper_context_default_params());
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sigyi_recap_whisper_WhisperNative_freeContext(
        JNIEnv *, jclass, jlong contextPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sigyi_recap_whisper_WhisperNative_transcribeWordTimestamps(
        JNIEnv *env, jclass, jlong contextPtr, jfloatArray audioData,
        jint numThreads, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx == nullptr || audioData == nullptr) return env->NewStringUTF("[]");

    const jsize sampleCount = env->GetArrayLength(audioData);
    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    const char *languageChars = language == nullptr ? "my" : env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = languageChars;
    params.n_threads = numThreads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.max_len = 1;
    params.split_on_word = true;

    std::string result = "[";
    if (whisper_full(ctx, params, samples, sampleCount) != 0) {
        LOGE("whisper_full failed");
        result += "]";
    } else {
        bool first = true;
        const int segmentCount = whisper_full_n_segments(ctx);
        for (int segment = 0; segment < segmentCount; ++segment) {
            const int tokenCount = whisper_full_n_tokens(ctx, segment);
            for (int token = 0; token < tokenCount; ++token) {
                const char *text = whisper_full_get_token_text(ctx, segment, token);
                if (text == nullptr || std::string(text).find_first_not_of(" \t\r\n") == std::string::npos) {
                    continue;
                }
                const int64_t startMs = whisper_full_get_token_t0(ctx, segment, token) * 10;
                const int64_t endMs = whisper_full_get_token_t1(ctx, segment, token) * 10;
                if (endMs <= startMs) continue;
                if (!first) result += ",";
                first = false;
                result += "{\"text\":\"" + jsonEscape(text) + "\",\"start_ms\":";
                result += std::to_string(startMs);
                result += ",\"end_ms\":";
                result += std::to_string(endMs);
                result += "}";
            }
        }
        result += "]";
    }

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    if (language != nullptr) env->ReleaseStringUTFChars(language, languageChars);
    return env->NewStringUTF(result.c_str());
}
