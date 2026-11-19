/*
 * otaudio: the desktop app's sound output, on miniaudio.
 *
 * One output is a playback device and a ring buffer in front of it. The app
 * writes decoded samples into the ring from its own thread (ot_write, which
 * never blocks: it says how many frames fitted); miniaudio's callback takes
 * them out on the audio thread and plays silence when the ring runs dry. So no
 * Java code ever runs on the audio thread.
 *
 * Called from Kotlin through JNA (desktop/src/main/kotlin/com/opentune/desktop/NativeAudio.kt).
 * Everything here is plain C with no Java headers, so one file builds on every system.
 */
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_ENGINE
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#if defined(_WIN32)
#define OT_API __declspec(dllexport)
#else
#define OT_API __attribute__((visibility("default")))
#endif

/* The sample formats the app asks for, as numbers JNA passes easily. */
enum { OT_F32 = 1, OT_S16 = 2, OT_S24 = 3, OT_S32 = 4 };

static ma_format to_ma(int f) {
    switch (f) {
        case OT_F32: return ma_format_f32;
        case OT_S16: return ma_format_s16;
        case OT_S24: return ma_format_s24;
        case OT_S32: return ma_format_s32;
        default: return ma_format_unknown;
    }
}

static int from_ma(ma_format f) {
    switch (f) {
        case ma_format_f32: return OT_F32;
        case ma_format_s16: return OT_S16;
        case ma_format_s24: return OT_S24;
        case ma_format_s32: return OT_S32;
        default: return 0;
    }
}

/* ---- The context and the device list -------------------------------------------- */

#define OT_MAX_DEVICES 64
#define OT_NAME_LEN 256

static ma_context g_context;
static int g_ready = 0;
static ma_mutex g_list_lock;
static int g_count = 0;
static char g_names[OT_MAX_DEVICES][OT_NAME_LEN];
static int g_defaults[OT_MAX_DEVICES];
static ma_device_id g_ids[OT_MAX_DEVICES];

/*
 * Starts miniaudio once. [null_backend] picks its silent backend, which plays in
 * real time to nothing: for tests on machines with no sound card. 0 when ready.
 */
OT_API int ot_init(int null_backend) {
    if (g_ready) return 0;
    ma_context_config config = ma_context_config_init();
    ma_result r;
    if (null_backend) {
        ma_backend backends[] = { ma_backend_null };
        r = ma_context_init(backends, 1, &config, &g_context);
    } else {
        r = ma_context_init(NULL, 0, &config, &g_context);
    }
    if (r != MA_SUCCESS) return (int)r;
    ma_mutex_init(&g_list_lock);
    g_ready = 1;
    return 0;
}

/* The backend in use: "WASAPI", "Core Audio", "PulseAudio", "ALSA", "Null"... */
OT_API const char* ot_backend(void) {
    return g_ready ? ma_get_backend_name(g_context.backend) : "";
}

/* Reads the playback devices again; how many there are. */
OT_API int ot_refresh_devices(void) {
    if (!g_ready) return 0;
    ma_device_info* infos;
    ma_uint32 count;
    ma_mutex_lock(&g_list_lock);
    if (ma_context_get_devices(&g_context, &infos, &count, NULL, NULL) != MA_SUCCESS) count = 0;
    if (count > OT_MAX_DEVICES) count = OT_MAX_DEVICES;
    for (ma_uint32 i = 0; i < count; i++) {
        strncpy(g_names[i], infos[i].name, OT_NAME_LEN - 1);
        g_names[i][OT_NAME_LEN - 1] = '\0';
        g_defaults[i] = infos[i].isDefault ? 1 : 0;
        g_ids[i] = infos[i].id;
    }
    g_count = (int)count;
    ma_mutex_unlock(&g_list_lock);
    return g_count;
}

OT_API const char* ot_device_name(int i) {
    return (i >= 0 && i < g_count) ? g_names[i] : "";
}

OT_API int ot_device_is_default(int i) {
    return (i >= 0 && i < g_count) ? g_defaults[i] : 0;
}

/* ---- An output ------------------------------------------------------------------- */

typedef struct {
    ma_device device;
    ma_pcm_rb ring;
    ma_mutex lock;
    int frame_bytes;
    int channels;
    int rate;
    int format;
    int exclusive;
    volatile ma_int64 played;     /* frames taken from the ring and played */
    volatile ma_int64 underruns;  /* callbacks that found too little */
    volatile int discard;         /* a flush asked while the device runs */
    volatile int running;         /* started by the app */
    volatile int lost;            /* stopped by the system: unplugged, taken */
} ot_out;

static void on_data(ma_device* device, void* output, const void* input, ma_uint32 frames) {
    (void)input;
    ot_out* o = (ot_out*)device->pUserData;
    if (__atomic_load_n(&o->discard, __ATOMIC_ACQUIRE)) {
        ma_uint32 queued = ma_pcm_rb_available_read(&o->ring);
        ma_pcm_rb_seek_read(&o->ring, queued);
        __atomic_store_n(&o->discard, 0, __ATOMIC_RELEASE);
    }
    ma_uint32 done = 0;
    while (done < frames) {
        ma_uint32 want = frames - done;
        void* chunk;
        if (ma_pcm_rb_acquire_read(&o->ring, &want, &chunk) != MA_SUCCESS || want == 0) break;
        memcpy((char*)output + (size_t)done * o->frame_bytes, chunk, (size_t)want * o->frame_bytes);
        ma_pcm_rb_commit_read(&o->ring, want);
        done += want;
    }
    if (done < frames) {
        /* miniaudio hands over a zeroed buffer, so what's left is already silence. */
        if (__atomic_load_n(&o->running, __ATOMIC_ACQUIRE) && done > 0) __atomic_add_fetch(&o->underruns, 1, __ATOMIC_RELAXED);
    }
    __atomic_add_fetch(&o->played, (ma_int64)done, __ATOMIC_RELEASE);
}

static void on_notification(const ma_device_notification* n) {
    ot_out* o = (ot_out*)n->pDevice->pUserData;
    if (n->type == ma_device_notification_type_stopped && __atomic_load_n(&o->running, __ATOMIC_ACQUIRE)) {
        __atomic_store_n(&o->lost, 1, __ATOMIC_RELEASE);
    }
}

static void say(char* error, int len, const char* what, ma_result r) {
    if (error && len > 0) snprintf(error, (size_t)len, "%s (%s)", what, ma_result_description(r));
}

/*
 * Opens [device_name] (null or empty for the system's default) for [format] at
 * [rate] Hz and [channels]; 0 for any of them takes the device's own. With
 * [exclusive], nothing else plays to the device meanwhile and nothing converts
 * on the way: where the system offers that (WASAPI and ALSA) and the device
 * takes the format, else it fails. [ring_ms] of sound can wait in front of the
 * device. Null on failure, with why in [error].
 */
OT_API ot_out* ot_open(const char* device_name, int format, int rate, int channels, int exclusive, int ring_ms, char* error, int error_len) {
    if (!g_ready) { say(error, error_len, "not started", MA_INVALID_OPERATION); return NULL; }
    ot_out* o = (ot_out*)calloc(1, sizeof(ot_out));
    if (!o) { say(error, error_len, "out of memory", MA_OUT_OF_MEMORY); return NULL; }

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = to_ma(format);
    config.playback.channels = (ma_uint32)channels;
    config.sampleRate = (ma_uint32)rate;
    config.playback.shareMode = exclusive ? ma_share_mode_exclusive : ma_share_mode_shared;
    config.dataCallback = on_data;
    config.notificationCallback = on_notification;
    config.pUserData = o;
    /* Small periods for a quick start and seek; the ring in front carries the slack. */
    config.periodSizeInMilliseconds = 10;
    config.noPreSilencedOutputBuffer = MA_FALSE;
    config.noClip = MA_TRUE;

    ma_device_id id;
    int have_id = 0;
    if (device_name && device_name[0]) {
        ma_mutex_lock(&g_list_lock);
        if (g_count == 0) { ma_mutex_unlock(&g_list_lock); ot_refresh_devices(); ma_mutex_lock(&g_list_lock); }
        for (int i = 0; i < g_count; i++) {
            if (strcmp(g_names[i], device_name) == 0) { id = g_ids[i]; have_id = 1; break; }
        }
        ma_mutex_unlock(&g_list_lock);
    }
    config.playback.pDeviceID = have_id ? &id : NULL;

    ma_result r = ma_device_init(&g_context, &config, &o->device);
    if (r != MA_SUCCESS) {
        say(error, error_len, exclusive ? "the device won't open exclusively" : "the device won't open", r);
        free(o);
        return NULL;
    }
    o->format = from_ma(o->device.playback.format);
    o->channels = (int)o->device.playback.channels;
    o->rate = (int)o->device.sampleRate;
    o->exclusive = o->device.playback.shareMode == ma_share_mode_exclusive;
    o->frame_bytes = (int)ma_get_bytes_per_frame(o->device.playback.format, o->device.playback.channels);
    if (ring_ms < 20) ring_ms = 20;
    ma_uint32 ring_frames = (ma_uint32)((ma_int64)o->rate * ring_ms / 1000);
    r = ma_pcm_rb_init(o->device.playback.format, o->device.playback.channels, ring_frames, NULL, NULL, &o->ring);
    if (r != MA_SUCCESS) {
        say(error, error_len, "no memory for the ring", r);
        ma_device_uninit(&o->device);
        free(o);
        return NULL;
    }
    ma_mutex_init(&o->lock);
    return o;
}

/* What the output opened with: the format code, rate, channels, and 1 when exclusive. */
OT_API int ot_format(ot_out* o) { return o ? o->format : 0; }
OT_API int ot_rate(ot_out* o) { return o ? o->rate : 0; }
OT_API int ot_channels(ot_out* o) { return o ? o->channels : 0; }
OT_API int ot_exclusive(ot_out* o) { return o ? o->exclusive : 0; }

/* Copies as many of [frames] as fit into the ring; how many did. Never blocks. */
OT_API int ot_write(ot_out* o, const void* data, int frames) {
    if (!o || frames <= 0) return 0;
    int done = 0;
    while (done < frames) {
        ma_uint32 want = (ma_uint32)(frames - done);
        void* chunk;
        if (ma_pcm_rb_acquire_write(&o->ring, &want, &chunk) != MA_SUCCESS || want == 0) break;
        memcpy(chunk, (const char*)data + (size_t)done * o->frame_bytes, (size_t)want * o->frame_bytes);
        ma_pcm_rb_commit_write(&o->ring, want);
        done += (int)want;
    }
    return done;
}

/* Frames waiting in the ring, not yet handed to the device. */
OT_API int ot_queued(ot_out* o) { return o ? (int)ma_pcm_rb_available_read(&o->ring) : 0; }

/* Frames the ring has room for. */
OT_API int ot_room(ot_out* o) { return o ? (int)ma_pcm_rb_available_write(&o->ring) : 0; }

OT_API int ot_start(ot_out* o) {
    if (!o) return -1;
    ma_mutex_lock(&o->lock);
    __atomic_store_n(&o->running, 1, __ATOMIC_RELEASE);
    __atomic_store_n(&o->lost, 0, __ATOMIC_RELEASE);
    ma_result r = ma_device_is_started(&o->device) ? MA_SUCCESS : ma_device_start(&o->device);
    ma_mutex_unlock(&o->lock);
    return (int)r;
}

/* Stops the device; what's in the ring stays for the next start. */
OT_API int ot_stop(ot_out* o) {
    if (!o) return -1;
    ma_mutex_lock(&o->lock);
    __atomic_store_n(&o->running, 0, __ATOMIC_RELEASE);
    ma_result r = ma_device_is_started(&o->device) ? ma_device_stop(&o->device) : MA_SUCCESS;
    ma_mutex_unlock(&o->lock);
    return (int)r;
}

/* Drops what's waiting in the ring, at once when stopped, at the next callback when playing. */
OT_API void ot_flush(ot_out* o) {
    if (!o) return;
    ma_mutex_lock(&o->lock);
    if (ma_device_is_started(&o->device)) {
        __atomic_store_n(&o->discard, 1, __ATOMIC_RELEASE);
    } else {
        ma_uint32 queued = ma_pcm_rb_available_read(&o->ring);
        ma_pcm_rb_seek_read(&o->ring, queued);
    }
    ma_mutex_unlock(&o->lock);
}

/* 1 while a flush asked during play hasn't been done yet. */
OT_API int ot_flushing(ot_out* o) { return o ? __atomic_load_n(&o->discard, __ATOMIC_ACQUIRE) : 0; }

OT_API long long ot_played(ot_out* o) { return o ? (long long)__atomic_load_n(&o->played, __ATOMIC_ACQUIRE) : 0; }
OT_API long long ot_underruns(ot_out* o) { return o ? (long long)__atomic_load_n(&o->underruns, __ATOMIC_RELAXED) : 0; }

/* 1 when the system stopped the device on its own: unplugged, or taken by another app. */
OT_API int ot_lost(ot_out* o) { return o ? __atomic_load_n(&o->lost, __ATOMIC_ACQUIRE) : 0; }

/* The device's own volume, 0 to 1; samples at 1 go out untouched. */
OT_API void ot_set_volume(ot_out* o, float volume) {
    if (o) ma_device_set_master_volume(&o->device, volume);
}

OT_API void ot_close(ot_out* o) {
    if (!o) return;
    __atomic_store_n(&o->running, 0, __ATOMIC_RELEASE);
    ma_device_uninit(&o->device);
    ma_pcm_rb_uninit(&o->ring);
    ma_mutex_uninit(&o->lock);
    free(o);
}
