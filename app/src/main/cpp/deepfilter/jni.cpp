#include "denoiser.h"
#include <jni.h>
#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_pockettts_android_engine_WavSamples_nativeDecode(JNIEnv* env, jobject, jstring path, jint startMs, jint endMs) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return nullptr;
    drwav wav{};
    const bool opened = drwav_init_file(&wav, chars, nullptr) != 0;
    env->ReleaseStringUTFChars(path, chars);
    if (!opened) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Cannot decode WAV"); return nullptr; }
    struct Close { drwav* wav; ~Close() { drwav_uninit(wav); } } close{&wav};
    try {
        if (wav.channels < 1 || wav.channels > 8 || wav.sampleRate < 8000 || wav.sampleRate > 192000 || startMs < 0 || endMs < -1)
            throw std::invalid_argument("Invalid WAV format or selection");
        const auto first = static_cast<uint64_t>(startMs) * wav.sampleRate / 1000;
        const auto last = endMs == -1 ? wav.totalPCMFrameCount : static_cast<uint64_t>(endMs) * wav.sampleRate / 1000;
        if (last > wav.totalPCMFrameCount || first >= last || last - first < wav.sampleRate * 3ULL || last - first > wav.sampleRate * 30ULL)
            throw std::invalid_argument("Select between 3 and 30 seconds");
        if (!drwav_seek_to_pcm_frame(&wav, first)) throw std::invalid_argument("Cannot seek WAV");
        std::vector<float> mono(last - first), block(4096 * wav.channels);
        for (size_t offset = 0; offset < mono.size();) {
            const auto frames = std::min<size_t>(4096, mono.size() - offset);
            if (drwav_read_pcm_frames_f32(&wav, frames, block.data()) != frames)
                throw std::invalid_argument("Truncated WAV data");
            for (size_t i = 0; i < frames; ++i) {
                double sum = 0;
                for (unsigned ch = 0; ch < wav.channels; ++ch) {
                    const float value = block[i * wav.channels + ch];
                    if (!std::isfinite(value)) throw std::invalid_argument("Non-finite WAV samples");
                    sum += std::clamp(value, -1.f, 1.f);
                }
                mono[offset + i] = static_cast<float>(sum / wav.channels);
            }
            offset += frames;
        }
        const auto pcm = speech_core::Resampler::resample(mono.data(), mono.size(), wav.sampleRate, 24000);
        auto result = env->NewFloatArray(pcm.size());
        if (result) env->SetFloatArrayRegion(result, 0, pcm.size(), pcm.data());
        return result;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what()); return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_pockettts_android_engine_WavSamples_nativeInspect(JNIEnv* env, jobject, jstring path, jint maxSeconds, jobject cancellation) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return nullptr;
    drwav wav{};
    const bool opened = drwav_init_file(&wav, chars, nullptr) != 0;
    env->ReleaseStringUTFChars(path, chars);
    if (!opened) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Cannot decode WAV"); return nullptr; }
    struct Close { drwav* wav; ~Close() { drwav_uninit(wav); } } close{&wav};
    try {
        if (wav.channels < 1 || wav.channels > 8 || wav.sampleRate < 8000 || wav.sampleRate > 192000 ||
            maxSeconds <= 0 || !wav.totalPCMFrameCount || wav.totalPCMFrameCount > wav.sampleRate * static_cast<uint64_t>(maxSeconds))
            throw std::invalid_argument("Invalid WAV length or format");
        const auto cancelled = cancellation ? env->GetMethodID(env->GetObjectClass(cancellation), "get", "()Z") : nullptr;
        // Fixed envelope and small decode block even for a long imported recording.
        std::vector<float> result(513, 0.f), block(4096 * wav.channels);
        result[0] = static_cast<float>(wav.totalPCMFrameCount * 1000 / wav.sampleRate);
        for (uint64_t offset = 0; offset < wav.totalPCMFrameCount;) {
            if (cancelled && env->CallBooleanMethod(cancellation, cancelled)) throw std::invalid_argument("Audio scan cancelled");
            const auto frames = std::min<uint64_t>(4096, wav.totalPCMFrameCount - offset);
            if (drwav_read_pcm_frames_f32(&wav, frames, block.data()) != frames) throw std::invalid_argument("Truncated WAV data");
            for (uint64_t i = 0; i < frames; ++i) {
                float peak = 0;
                for (unsigned ch = 0; ch < wav.channels; ++ch) {
                    const float value = block[i * wav.channels + ch];
                    if (!std::isfinite(value)) throw std::invalid_argument("Non-finite WAV samples");
                    peak = std::max(peak, std::min(1.f, std::abs(value)));
                }
                const auto bin = 1 + (offset + i) * 512 / wav.totalPCMFrameCount;
                result[bin] = std::max(result[bin], peak);
            }
            offset += frames;
        }
        auto array = env->NewFloatArray(result.size());
        if (array) env->SetFloatArrayRegion(array, 0, result.size(), result.data());
        return array;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what()); return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_pockettts_android_engine_DeepFilterNet3Denoiser_nativeCreate(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    try {
        std::string file(chars);
        env->ReleaseStringUTFChars(path, chars);
        chars = nullptr;
        return reinterpret_cast<jlong>(new pocket_denoise::Denoiser(file.c_str()));
    } catch (const std::exception& error) {
        if (chars) env->ReleaseStringUTFChars(path, chars);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_pockettts_android_engine_DeepFilterNet3Denoiser_nativeProcess(JNIEnv* env, jobject, jlong handle, jfloatArray samples) {
    try {
        if (!handle || !samples) throw std::invalid_argument("Denoiser is closed or PCM is missing");
        const auto count = env->GetArrayLength(samples);
        if (count < 72000 || count > 720000) throw std::invalid_argument("Invalid recording length");
        std::vector<float> input(count);
        env->GetFloatArrayRegion(samples, 0, count, input.data());
        if (env->ExceptionCheck()) return nullptr;
        auto output = reinterpret_cast<pocket_denoise::Denoiser*>(handle)->process(input);
        auto result = env->NewFloatArray(count);
        if (result) env->SetFloatArrayRegion(result, 0, count, output.data());
        return result;
    } catch (const std::exception& error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_org_pockettts_android_engine_DeepFilterNet3Denoiser_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<pocket_denoise::Denoiser*>(handle);
}
