// JNI bridge for com.aircursor.assistant.WhisperNative: load a model, turn 16 kHz mono audio into text.
#include <jni.h>
#include <mutex>
#include <string>
#include "ggml-backend.h"
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AirWhisper", __VA_ARGS__)
#else   // built on a computer for the tests of this bridge
#include <cstdio>
#define LOGI(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#endif

static std::once_flag g_backends;

static std::string str(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aircursor_assistant_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring libDir, jstring modelPath) {
    const std::string dir = str(env, libDir);
    // The CPU backends are separate libraries in the app's native folder: load the best one once.
    std::call_once(g_backends, [&] { ggml_backend_load_all_from_path(dir.c_str()); });
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    const std::string model = str(env, modelPath);
    whisper_context *ctx = whisper_init_from_file_with_params(model.c_str(), cp);
    LOGI("model %s: %s", model.c_str(), ctx ? "loaded" : "failed");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aircursor_assistant_WhisperNative_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray audio,
                                                           jstring language, jstring prompt, jint threads, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");
    const std::string lang = str(env, language);
    const std::string hint = str(env, prompt);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang.empty() ? "auto" : lang.c_str();
    p.translate = false;
    p.no_context = true;
    p.single_segment = true;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.max_tokens = 64;  // a spoken command, not a speech
    if (audioCtx > 0) p.audio_ctx = audioCtx;  // short clips: look at less than the full 30 s window
    if (!hint.empty()) p.initial_prompt = hint.c_str();

    const jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const int rc = whisper_full(ctx, p, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (rc != 0) return env->NewStringUTF("");

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aircursor_assistant_WhisperNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}
