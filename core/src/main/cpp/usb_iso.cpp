// OpenTune's own USB Audio Class output: the app streams PCM to a USB DAC
// itself, through the device's usbfs file (from Android's UsbDeviceConnection),
// rather than through Android's audio system. The DAC gets the track's own
// sample rate and bit depth, nothing mixed or resampled, and plays it bit for
// bit.
//
// Audio goes out on the DAC's isochronous OUT endpoint, a few packets every
// millisecond, from a ring buffer the player fills. How many frames go in each
// packet follows the sample rate exactly, with the fraction carried over; an
// asynchronous DAC also says, on its feedback endpoint, how fast its own clock
// really runs, and the packets follow that, so the buffer neither overflows nor
// runs dry. When the player is late (or paused), silence goes out, never stale
// or partial frames.
//
// Kotlin has already claimed the streaming interface, chosen its alternate
// setting and set the DAC's sample rate; this file only moves the samples.

#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <thread>
#include <vector>

#define TAG "OpenTuneUsb"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

constexpr int kUrbs = 8;          // transfers kept queued on the endpoint
constexpr int kFeedbackUrbs = 2;  // feedback reads kept queued

/** A single-producer, single-consumer byte ring: the player writes, the USB thread reads. */
class Ring {
   public:
    explicit Ring(size_t size) : data_(size), size_(size) {}
    size_t readable() const { return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_relaxed); }
    size_t writable() const { return size_ - (head_.load(std::memory_order_relaxed) - tail_.load(std::memory_order_acquire)); }
    size_t write(const uint8_t* src, size_t n) {
        n = std::min(n, writable());
        size_t h = head_.load(std::memory_order_relaxed);
        for (size_t done = 0; done < n;) {
            size_t at = (h + done) % size_;
            size_t chunk = std::min(n - done, size_ - at);
            memcpy(&data_[at], src + done, chunk);
            done += chunk;
        }
        head_.store(h + n, std::memory_order_release);
        return n;
    }
    size_t read(uint8_t* dst, size_t n) {
        n = std::min(n, readable());
        size_t t = tail_.load(std::memory_order_relaxed);
        for (size_t done = 0; done < n;) {
            size_t at = (t + done) % size_;
            size_t chunk = std::min(n - done, size_ - at);
            memcpy(dst + done, &data_[at], chunk);
            done += chunk;
        }
        tail_.store(t + n, std::memory_order_release);
        return n;
    }
    void clear() { tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release); }
    size_t size() const { return size_; }

   private:
    std::vector<uint8_t> data_;
    size_t size_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

struct Urb {
    usbdevfs_urb* urb = nullptr;
    std::vector<uint8_t> buffer;
};

usbdevfs_urb* newUrb(int packets) {
    size_t bytes = sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc);
    auto* u = static_cast<usbdevfs_urb*>(calloc(1, bytes));
    return u;
}

struct Stream {
    int fd;
    unsigned char endpoint;
    unsigned char feedback;     // 0: none (adaptive or synchronous DAC)
    int maxPacket;              // bytes a packet may carry
    int feedbackPacket;         // bytes of a feedback report (3 at full speed, 4 at high speed)
    bool highSpeed;
    int packetsPerMs;           // packets a millisecond: 1 at full speed, up to 8 at high speed
    int frameBytes;
    int rate;
    int packetsPerUrb;

    Ring ring;
    std::atomic<bool> running{false};
    std::atomic<bool> playing{false};
    std::atomic<int64_t> framesPlayed{0};  // frames of the player's audio sent out
    std::atomic<int64_t> underruns{0};
    // Frames per packet: the nominal rate, then what the DAC's feedback says.
    std::atomic<double> framesPerPacket{0};
    double nominal = 0;
    double carry = 0;

    std::thread out;
    std::thread fb;
    std::vector<Urb> urbs;
    std::vector<Urb> fbUrbs;

    Stream(int fd_, int ep, int fbEp, int maxPacket_, int fbPacket, bool hs, int perMs, int frameBytes_, int rate_, size_t ringBytes)
        : fd(fd_), endpoint(static_cast<unsigned char>(ep)), feedback(static_cast<unsigned char>(fbEp)), maxPacket(maxPacket_),
          feedbackPacket(fbPacket), highSpeed(hs), packetsPerMs(perMs), frameBytes(frameBytes_), rate(rate_),
          packetsPerUrb(perMs * 2), ring(ringBytes) {
        nominal = static_cast<double>(rate) / (1000.0 * packetsPerMs);
        framesPerPacket.store(nominal);
    }

    /** How many frames the next packet carries, the fraction carried over to the one after. */
    int nextFrames() {
        double want = framesPerPacket.load(std::memory_order_relaxed) + carry;
        int n = static_cast<int>(want);
        n = std::min(n, maxPacket / frameBytes);
        carry = want - n;
        if (carry > 1.0) carry = 1.0;  // never let a long stretch of short packets bank up
        return n;
    }

    /** Fills [u] with the next packets: the player's audio, or silence where there's none yet. */
    void fill(Urb& u) {
        auto* urb = u.urb;
        size_t at = 0;
        for (int i = 0; i < packetsPerUrb; i++) {
            int frames = nextFrames();
            size_t bytes = static_cast<size_t>(frames) * frameBytes;
            uint8_t* dst = u.buffer.data() + at;
            size_t got = 0;
            if (playing.load(std::memory_order_acquire)) {
                // Whole frames only, so the channels never slip against each other.
                size_t avail = ring.readable() / frameBytes * frameBytes;
                got = ring.read(dst, std::min(bytes, avail));
                if (got < bytes) underruns.fetch_add(1);
                framesPlayed.fetch_add(static_cast<int64_t>(got / frameBytes));
            }
            if (got < bytes) memset(dst + got, 0, bytes - got);
            urb->iso_frame_desc[i].length = static_cast<unsigned int>(bytes);
            urb->iso_frame_desc[i].actual_length = 0;
            urb->iso_frame_desc[i].status = 0;
            at += bytes;
        }
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = u.buffer.data();
        urb->buffer_length = static_cast<int>(at);
        urb->number_of_packets = packetsPerUrb;
        urb->usercontext = &u;
    }

    bool start() {
        urbs.resize(kUrbs);
        for (auto& u : urbs) {
            u.urb = newUrb(packetsPerUrb);
            u.buffer.assign(static_cast<size_t>(maxPacket) * packetsPerUrb, 0);
        }
        running = true;
        for (auto& u : urbs) {
            fill(u);
            if (ioctl(fd, USBDEVFS_SUBMITURB, u.urb) < 0) {
                LOGW("submit failed: %s", strerror(errno));
                running = false;
                return false;
            }
        }
        out = std::thread([this] { pump(); });
        if (feedback != 0) startFeedback();
        return true;
    }

    /** Each finished transfer is filled again and sent straight back. */
    void pump() {
        while (running.load()) {
            usbdevfs_urb* done = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURB, &done) < 0) {
                if (errno == EINTR) continue;
                LOGW("reap failed: %s", strerror(errno));
                break;
            }
            if (done == nullptr) continue;
            auto* u = static_cast<Urb*>(done->usercontext);
            if (u == nullptr) continue;
            if (done->endpoint == feedback && feedback != 0) {
                onFeedback(*u);
                if (running.load()) ioctl(fd, USBDEVFS_SUBMITURB, u->urb);
                continue;
            }
            if (!running.load()) continue;
            fill(*u);
            if (ioctl(fd, USBDEVFS_SUBMITURB, u->urb) < 0) {
                LOGW("resubmit failed: %s", strerror(errno));
                break;
            }
        }
        running = false;
    }

    void startFeedback() {
        fbUrbs.resize(kFeedbackUrbs);
        for (auto& u : fbUrbs) {
            u.urb = newUrb(1);
            u.buffer.assign(static_cast<size_t>(std::max(feedbackPacket, 4)), 0);
            u.urb->type = USBDEVFS_URB_TYPE_ISO;
            u.urb->endpoint = feedback;
            u.urb->flags = USBDEVFS_URB_ISO_ASAP;
            u.urb->buffer = u.buffer.data();
            u.urb->buffer_length = feedbackPacket;
            u.urb->number_of_packets = 1;
            u.urb->iso_frame_desc[0].length = static_cast<unsigned int>(feedbackPacket);
            u.urb->usercontext = &u;
            if (ioctl(fd, USBDEVFS_SUBMITURB, u.urb) < 0) LOGW("feedback submit failed: %s", strerror(errno));
        }
    }

    /**
     * The DAC's report of its real rate, in frames per (micro)frame: 10.14 fixed
     * point at full speed, 16.16 at high speed. Kept within a few percent of
     * nominal, since a garbled report must never change the pitch.
     */
    void onFeedback(Urb& u) {
        int len = static_cast<int>(u.urb->iso_frame_desc[0].actual_length);
        if (len < 3) return;
        const uint8_t* b = u.buffer.data();
        double perUsbFrame;
        if (!highSpeed || len == 3) {
            uint32_t v = b[0] | (b[1] << 8) | (b[2] << 16);
            perUsbFrame = v / 16384.0;  // frames per 1 ms frame
            perUsbFrame /= packetsPerMs;
        } else {
            uint32_t v = b[0] | (b[1] << 8) | (b[2] << 16) | (static_cast<uint32_t>(b[3]) << 24);
            perUsbFrame = v / 65536.0;  // frames per 125 µs microframe
            perUsbFrame *= 8.0 / packetsPerMs;
        }
        if (perUsbFrame > nominal * 0.95 && perUsbFrame < nominal * 1.05) framesPerPacket.store(perUsbFrame);
    }

    void stop() {
        running = false;
        for (auto& u : urbs) if (u.urb) ioctl(fd, USBDEVFS_DISCARDURB, u.urb);
        for (auto& u : fbUrbs) if (u.urb) ioctl(fd, USBDEVFS_DISCARDURB, u.urb);
        // The pump may be blocked reaping; discarded transfers come back and wake it.
        if (out.joinable()) out.join();
        // Collect what's left so the memory isn't handed back while the kernel holds it.
        for (int i = 0; i < kUrbs + kFeedbackUrbs; i++) {
            usbdevfs_urb* done = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &done) < 0) break;
        }
        for (auto& u : urbs) free(u.urb);
        for (auto& u : fbUrbs) free(u.urb);
        urbs.clear();
        fbUrbs.clear();
    }
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_usbOpen(JNIEnv*, jclass, jint fd, jint endpoint, jint feedbackEndpoint, jint maxPacket,
                                                         jint feedbackPacket, jboolean highSpeed, jint packetsPerMs, jint frameBytes,
                                                         jint rate, jint ringMs) {
    if (fd < 0 || frameBytes <= 0 || rate <= 0 || packetsPerMs <= 0) return 0;
    size_t ringBytes = static_cast<size_t>(rate) * frameBytes * std::max(ringMs, 50) / 1000;
    auto* s = new Stream(fd, endpoint, feedbackEndpoint, maxPacket, feedbackPacket, highSpeed, packetsPerMs, frameBytes, rate, ringBytes);
    if (!s->start()) {
        s->stop();
        delete s;
        return 0;
    }
    LOGI("streaming %d Hz, %d bytes a frame, %d packets/ms, feedback %s", rate, frameBytes, packetsPerMs, feedbackEndpoint ? "on" : "off");
    return reinterpret_cast<jlong>(s);
}

/** Copies what fits of [frames] frames from [buffer]; returns the frames taken. */
JNIEXPORT jint JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_usbWrite(JNIEnv* env, jclass, jlong handle, jobject buffer, jint offset, jint frames) {
    auto* s = reinterpret_cast<Stream*>(handle);
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr || !s->running.load()) return -1;
    size_t room = s->ring.writable() / s->frameBytes;
    size_t n = std::min(static_cast<size_t>(frames), room);
    s->ring.write(data + offset, n * s->frameBytes);
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_usbPlay(JNIEnv*, jclass, jlong handle, jboolean on) {
    reinterpret_cast<Stream*>(handle)->playing.store(on, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_usbFlush(JNIEnv*, jclass, jlong handle) {
    reinterpret_cast<Stream*>(handle)->ring.clear();
}

/** Frames played, frames waiting, underruns, and whether it's still streaming (1) or failed (0). */
JNIEXPORT jlongArray JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_usbState(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Stream*>(handle);
    jlong v[4] = {s->framesPlayed.load(), static_cast<jlong>(s->ring.readable() / s->frameBytes), s->underruns.load(), s->running.load() ? 1 : 0};
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, v);
    return out;
}

JNIEXPORT void JNICALL
Java_com_opentune_playback_nativeout_NativeAudio_usbClose(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Stream*>(handle);
    s->stop();
    delete s;
}

}  // extern "C"
