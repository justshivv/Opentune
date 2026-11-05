package com.opentune.playback.nativeout

import java.nio.ByteBuffer

/** The native side of the bit-perfect outputs (src/main/cpp). */
internal object NativeAudio {
    /** Whether the library is there and loaded; without it the outputs fall back to Android's. */
    val loaded: Boolean = runCatching { System.loadLibrary("opentune_audio") }.isSuccess

    // AAudio formats.
    const val FORMAT_I16 = 1
    const val FORMAT_FLOAT = 2
    const val FORMAT_I24_PACKED = 3
    const val FORMAT_I32 = 4
    const val SHARING_EXCLUSIVE = 0

    @JvmStatic external fun aaudioOpen(rate: Int, channels: Int, format: Int, deviceId: Int, exclusive: Boolean): Long
    /** Sharing mode, format, rate, channels, buffer frames, device id. */
    @JvmStatic external fun aaudioInfo(handle: Long): IntArray
    @JvmStatic external fun aaudioWrite(handle: Long, buffer: ByteBuffer, offset: Int, frames: Int, timeoutNanos: Long): Int
    @JvmStatic external fun aaudioStart(handle: Long): Int
    @JvmStatic external fun aaudioPause(handle: Long): Int
    @JvmStatic external fun aaudioFlush(handle: Long): Int
    @JvmStatic external fun aaudioStop(handle: Long): Int
    @JvmStatic external fun aaudioClose(handle: Long)
    @JvmStatic external fun aaudioFramesPlayed(handle: Long): Long
    @JvmStatic external fun aaudioXRuns(handle: Long): Int

    @JvmStatic external fun usbOpen(
        fd: Int, endpoint: Int, feedbackEndpoint: Int, maxPacket: Int, feedbackPacket: Int,
        highSpeed: Boolean, packetsPerMs: Int, frameBytes: Int, rate: Int, ringMs: Int,
    ): Long
    @JvmStatic external fun usbWrite(handle: Long, buffer: ByteBuffer, offset: Int, frames: Int): Int
    @JvmStatic external fun usbPlay(handle: Long, on: Boolean)
    @JvmStatic external fun usbFlush(handle: Long)
    /** Frames played, frames waiting, underruns, streaming (1) or failed (0). */
    @JvmStatic external fun usbState(handle: Long): LongArray
    @JvmStatic external fun usbClose(handle: Long)
}
