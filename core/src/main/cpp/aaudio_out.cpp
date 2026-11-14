// An exclusive AAudio output stream: the phone's own DAC (headphone or line
// output) opened by OpenTune alone, at the track's own sample rate and format,
// so nothing is mixed or resampled on the way. A device may refuse exclusive
// use; the stream then comes back shared, and Kotlin is told which it got.

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <time.h>

#define TAG "OpenTuneAAudio"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Builder calls newer than the app's lowest Android, found at run time.
using SetUsage = void (*)(AAudioStreamBuilder*, int32_t);
using SetContentType = void (*)(AAudioStreamBuilder*, int32_t);

template <typename F>
F optional(const char* name) {
    static void* lib = dlopen("libaaudio.so", RTLD_NOW);
    return lib ? reinterpret_cast<F>(dlsym(lib, name)) : nullptr;
}

constexpr int32_t kUsageMedia = 1;         // AAUDIO_USAGE_MEDIA
constexpr int32_t kContentMusic = 2;       // AAUDIO_CONTENT_TYPE_MUSIC

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioOpen(JNIEnv*, jclass, jint rate, jint channels, jint format,
                                                            jint deviceId, jboolean exclusive) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return 0;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    // Exclusive (MMAP) streams are only offered on the low-latency path.
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, static_cast<aaudio_format_t>(format));
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(builder, deviceId);
    if (auto f = optional<SetUsage>("AAudioStreamBuilder_setUsage")) f(builder, kUsageMedia);
    if (auto f = optional<SetContentType>("AAudioStreamBuilder_setContentType")) f(builder, kContentMusic);
    AAudioStream* stream = nullptr;
    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK || stream == nullptr) {
        LOGW("open failed: %s", AAudio_convertResultToText(r));
        return 0;
    }
    // As deep a buffer as it has: music wants steadiness, not low latency.
    AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getBufferCapacityInFrames(stream));
    return reinterpret_cast<jlong>(stream);
}

/** What was opened: sharing mode, format, rate, channels, buffer frames, device id. */
JNIEXPORT jintArray JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioInfo(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<AAudioStream*>(handle);
    jint v[6] = {
        AAudioStream_getSharingMode(s), AAudioStream_getFormat(s), AAudioStream_getSampleRate(s),
        AAudioStream_getChannelCount(s), AAudioStream_getBufferSizeInFrames(s), AAudioStream_getDeviceId(s),
    };
    jintArray out = env->NewIntArray(6);
    env->SetIntArrayRegion(out, 0, 6, v);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioWrite(JNIEnv* env, jclass, jlong handle, jobject buffer, jint offset,
                                                             jint frames, jlong timeoutNanos) {
    auto* s = reinterpret_cast<AAudioStream*>(handle);
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr) return -1;
    return AAudioStream_write(s, data + offset, frames, timeoutNanos);
}

JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioStart(JNIEnv*, jclass, jlong handle) {
    return AAudioStream_requestStart(reinterpret_cast<AAudioStream*>(handle));
}

JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioPause(JNIEnv*, jclass, jlong handle) {
    return AAudioStream_requestPause(reinterpret_cast<AAudioStream*>(handle));
}

JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioFlush(JNIEnv*, jclass, jlong handle) {
    return AAudioStream_requestFlush(reinterpret_cast<AAudioStream*>(handle));
}

JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioStop(JNIEnv*, jclass, jlong handle) {
    return AAudioStream_requestStop(reinterpret_cast<AAudioStream*>(handle));
}

JNIEXPORT void JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioClose(JNIEnv*, jclass, jlong handle) {
    AAudioStream_close(reinterpret_cast<AAudioStream*>(handle));
}

/** Frames heard so far: from the stream's timestamp when it has one, else frames read out of it. */
JNIEXPORT jlong JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioFramesPlayed(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<AAudioStream*>(handle);
    int64_t frames = 0, nanos = 0;
    if (AAudioStream_getTimestamp(s, CLOCK_MONOTONIC, &frames, &nanos) == AAUDIO_OK) {
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t since = (now.tv_sec * 1000000000LL + now.tv_nsec) - nanos;
        if (since > 0 && AAudioStream_getState(s) == AAUDIO_STREAM_STATE_STARTED) {
            frames += since * AAudioStream_getSampleRate(s) / 1000000000LL;
        }
        int64_t written = AAudioStream_getFramesWritten(s);
        return frames < written ? frames : written;
    }
    return AAudioStream_getFramesRead(s);
}

JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_aaudioXRuns(JNIEnv*, jclass, jlong handle) {
    return AAudioStream_getXRunCount(reinterpret_cast<AAudioStream*>(handle));
}

}  // extern "C"
