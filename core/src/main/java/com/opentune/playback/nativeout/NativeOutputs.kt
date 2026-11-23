package com.opentune.playback.nativeout

import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.opentune.data.DebugLog as Log
import com.opentune.data.settings.BitPerfectOutput
import com.opentune.playback.BitPerfectUsb
import com.opentune.playback.PcmWords
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Opens each song's output on OpenTune's own bit-perfect paths when one is
 * chosen: the USB DAC through the app's own driver, or the phone's DAC through
 * an exclusive AAudio stream. Anything they can't take (offload, tunnelling,
 * a format the device doesn't offer, no DAC, no permission) opens on
 * Android's usual output instead, so music never stops for it.
 */
@OptIn(UnstableApi::class)
class NativeOutputProvider(delegate: AudioOutputProvider) : ForwardingAudioOutputProvider(delegate) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val mode = mode
        if (mode != BitPerfectOutput.USB_DIRECT && mode != BitPerfectOutput.BUILT_IN && mode != BitPerfectOutput.HEADPHONE_JACK) return super.getAudioOutput(config)
        // Any number of channels: a multichannel file (or decoded Dolby) goes out whole where the DAC takes that many.
        if (config.isOffload || config.isTunneling || config.encoding !in CARRIERS || !NativeAudio.loaded) return super.getAudioOutput(config)
        val channels = Integer.bitCount(config.channelMask)
        val bits = BitPerfectUsb.bitsOf(BitPerfectUsb.sourceEncoding).coerceIn(16, 32)
        // The phone's own DAC or jack, opened exclusively through AAudio, only from Android 10:
        // a crash in there can't be caught and takes the whole app down. Android's own output instead.
        if (mode != BitPerfectOutput.USB_DIRECT && !AAudioGuard.usable) return super.getAudioOutput(config).also { BitPerfectUsb.reportNative(null) }
        if (mode == BitPerfectOutput.HEADPHONE_JACK) {
            // No headphones in the jack: Android's own output, wherever it plays.
            val jack = BitPerfectUsb.wiredJack() ?: return super.getAudioOutput(config).also { BitPerfectUsb.reportNative(BitPerfectUsb.Status.NoDac) }
            // The jack is stereo: more channels than that go to Android's output, which folds them down (or spatializes them).
            if (channels > 2) return super.getAudioOutput(config).also { BitPerfectUsb.reportNative(null) }
            // Android's own bit-perfect mixer for the jack first, where the phone offers it (the
            // provider below sets it up); otherwise the jack itself, opened exclusively.
            if (BitPerfectUsb.jackMixerOffers(config.sampleRate, channels)) return super.getAudioOutput(config)
            val opened = runCatching { AAudioExclusiveOutput.open(config, channels, bits, deviceId = jack.id, name = "Headphone jack") }
                .onFailure { Log.w("NativeOutput", "Couldn't open the headphone jack exclusively", it) }.getOrNull()
            return opened ?: super.getAudioOutput(config).also { BitPerfectUsb.reportNative(null) }
        }
        val out = runCatching {
            when (mode) {
                BitPerfectOutput.USB_DIRECT -> UsbDirect.open(config.sampleRate, channels, bits)?.let { UsbDirectOutput(it, config, channels) }
                else -> AAudioExclusiveOutput.open(config, channels, bits)
            }
        }.onFailure { Log.w("NativeOutput", "Couldn't open the bit-perfect output", it) }.getOrNull()
        return out ?: super.getAudioOutput(config).also { BitPerfectUsb.reportNative(null) }
    }

    companion object {
        /** The bit-perfect path chosen in Settings. */
        @Volatile var mode: BitPerfectOutput = BitPerfectOutput.OFF
        private val CARRIERS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)

        internal fun encodingFor(bytes: Int) = when (bytes) {
            2 -> C.ENCODING_PCM_16BIT
            3 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_PCM_32BIT
        }
    }
}

/**
 * The parts every native output shares with Media3: the player's samples
 * rewritten into the device's word size as they arrive (value for value, see
 * [PcmWords]), held until the device takes them, and the position counted from
 * the frames the device has played.
 */
@OptIn(UnstableApi::class)
internal abstract class NativeOutput(
    protected val config: AudioOutputProvider.OutputConfig,
    protected val channels: Int,
    /** The [C] encoding the device takes. */
    private val target: Int,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    protected val frameBytes = channels * when (target) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
    /** Frames played when the output was last flushed: the position counts from there. */
    private var base = 0L
    private var advancing = false

    /** Frames the device has played since it opened. */
    protected abstract fun framesPlayed(): Long
    /** Takes what it can of [frames] frames from [buffer] at [offset]; the frames taken, or negative on failure. */
    protected abstract fun take(buffer: ByteBuffer, offset: Int, frames: Int): Int
    protected abstract fun start()
    protected abstract fun hold()
    protected abstract fun drop()
    protected abstract fun close()

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        pending?.let { p -> if (!send(p)) return false; pending = null }
        if (!buffer.hasRemaining()) return true
        val made = convert(buffer)
        if (!send(made)) { pending = made; return false }
        return true
    }

    private fun send(p: ByteBuffer): Boolean {
        val frames = p.remaining() / frameBytes
        if (frames == 0) { p.position(p.limit()); return true }
        val taken = take(p, p.position(), frames)
        if (taken < 0) throw AudioOutput.WriteException(taken, false)
        p.position(p.position() + taken * frameBytes)
        return !p.hasRemaining()
    }

    private fun convert(input: ByteBuffer): ByteBuffer {
        val carrier = config.encoding
        val needed = PcmWords.size(input.remaining(), carrier, target, channels)
        if (scratch.capacity() < needed) scratch = ByteBuffer.allocateDirect(needed).order(ByteOrder.LITTLE_ENDIAN)
        val out = scratch.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { clear() }
        if (carrier == target) {
            out.put(input)
            out.flip()
            return out
        }
        return PcmWords.rewrite(input, carrier, target, channels, out)
    }

    override fun play() {
        start()
        if (!advancing) {
            advancing = true
            val now = System.currentTimeMillis()
            listeners.forEach { it.onPositionAdvancing(now) }
        }
    }

    override fun pause() { hold(); advancing = false }

    override fun flush() {
        pending = null
        drop()
        base = framesPlayed()
        advancing = false
    }

    override fun stop() = Unit // the device plays out what it has; the position shows when it's done

    override fun release() {
        close()
        listeners.forEach { it.onReleased() }
    }

    // Bit-perfect: the volume is the app's own software gain, set before the samples arrive.
    override fun setVolume(volume: Float) = Unit
    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = config.audioSessionId
    override fun getSampleRate() = config.sampleRate
    override fun getPositionUs(): Long = ((framesPlayed() - base).coerceAtLeast(0) * 1_000_000L) / config.sampleRate
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) = Unit
}

/** A song on the USB DAC, through OpenTune's own driver. */
@OptIn(UnstableApi::class)
internal class UsbDirectOutput(
    private val session: UsbDirect.Session,
    config: AudioOutputProvider.OutputConfig,
    channels: Int,
) : NativeOutput(config, channels, NativeOutputProvider.encodingFor(session.setting.subslotBytes)) {
    private val handle: Long

    init {
        val s = session.setting
        handle = NativeAudio.usbOpen(
            session.fd, s.endpoint, s.feedbackEndpoint, s.maxPacket, s.feedbackPacket,
            session.highSpeed, UsbAudioDescriptors.packetsPerMs(s, session.highSpeed), s.frameBytes, session.rate, RING_MS,
        )
        check(handle != 0L) { "The DAC wouldn't stream" }
        BitPerfectUsb.reportNative(BitPerfectUsb.Status.Active(session.deviceName, session.rate, s.bits, "${s.bits}-bit, direct driver"))
    }

    override fun framesPlayed(): Long = NativeAudio.usbState(handle)[0]
    override fun take(buffer: ByteBuffer, offset: Int, frames: Int): Int {
        val state = NativeAudio.usbState(handle)
        if (state[3] == 0L) return -1
        return NativeAudio.usbWrite(handle, buffer, offset, frames)
    }
    override fun start() = NativeAudio.usbPlay(handle, true)
    override fun hold() = NativeAudio.usbPlay(handle, false)
    override fun drop() = NativeAudio.usbFlush(handle)
    override fun close() {
        NativeAudio.usbClose(handle)
        UsbDirect.idle(session)
    }
    override fun getBufferSizeInFrames(): Long = session.rate.toLong() * RING_MS / 1000

    private companion object { const val RING_MS = 300 }
}

/** A song on the phone's own DAC, through an AAudio stream OpenTune has to itself. */
@OptIn(UnstableApi::class)
internal class AAudioExclusiveOutput private constructor(
    private val handle: Long,
    config: AudioOutputProvider.OutputConfig,
    channels: Int,
    target: Int,
    private val bufferFrames: Long,
) : NativeOutput(config, channels, target) {
    override fun framesPlayed(): Long = NativeAudio.aaudioFramesPlayed(handle).also {
        // A few seconds played on it: this phone takes the stream, and the mark comes off.
        if (it >= config.sampleRate.toLong() * AAudioGuard.TRUSTED_SECONDS) AAudioGuard.settled()
    }
    override fun take(buffer: ByteBuffer, offset: Int, frames: Int): Int = NativeAudio.aaudioWrite(handle, buffer, offset, frames, 0)
    override fun start() { NativeAudio.aaudioStart(handle) }
    override fun hold() { NativeAudio.aaudioPause(handle) }
    override fun drop() { NativeAudio.aaudioPause(handle); NativeAudio.aaudioFlush(handle) }
    override fun close() { NativeAudio.aaudioStop(handle); NativeAudio.aaudioClose(handle); AAudioGuard.settled() }
    override fun getBufferSizeInFrames(): Long = bufferFrames

    companion object {
        /**
         * Opens the phone's DAC exclusively at the song's rate, in the narrowest
         * format that holds its bits (24 and 32-bit integer formats need Android 12).
         * Null when the device won't give it exclusively at that rate and format.
         */
        fun open(
            config: AudioOutputProvider.OutputConfig,
            channels: Int,
            bits: Int,
            /** A particular output (the headphone jack), or 0 for wherever Android routes music. */
            deviceId: Int = 0,
            name: String = "Built-in DAC",
        ): AAudioExclusiveOutput? {
            val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val tries = buildList {
                if (bits <= 16) add(NativeAudio.FORMAT_I16 to C.ENCODING_PCM_16BIT)
                if (modern && bits <= 24) add(NativeAudio.FORMAT_I24_PACKED to C.ENCODING_PCM_24BIT)
                if (modern) add(NativeAudio.FORMAT_I32 to C.ENCODING_PCM_32BIT)
            }
            // Marked as opening until the stream has played a few seconds: if the app dies in
            // there, the next start finds the mark and turns the exclusive output off.
            AAudioGuard.opening()
            for ((format, encoding) in tries) {
                val handle = NativeAudio.aaudioOpen(config.sampleRate, channels, format, deviceId, true)
                if (handle == 0L) continue
                val info = NativeAudio.aaudioInfo(handle)
                // Only what was asked for, exactly: shared, resampled or routed elsewhere isn't bit-perfect.
                if (info[0] != NativeAudio.SHARING_EXCLUSIVE || info[1] != format || info[2] != config.sampleRate || (deviceId > 0 && info[5] != deviceId)) {
                    NativeAudio.aaudioClose(handle)
                    continue
                }
                BitPerfectUsb.reportNative(BitPerfectUsb.Status.Active(name, config.sampleRate, BitPerfectUsb.bitsOf(encoding), "exclusive"))
                return AAudioExclusiveOutput(handle, config, channels, encoding, info[4].toLong())
            }
            AAudioGuard.settled()
            Log.w("NativeOutput", "The phone's DAC wouldn't open exclusively at ${config.sampleRate} Hz")
            BitPerfectUsb.reportNative(BitPerfectUsb.Status.NoMatch(config.sampleRate, bits))
            return null
        }
    }
}

/**
 * Keeps the exclusive AAudio outputs (the built-in DAC, the headphone jack) from taking the app
 * down again and again. A crash inside AAudio can't be caught, and on some phones (an EMUI one,
 * #72) the app closed as soon as music started. So a mark is left on disk while such a stream
 * opens and plays its first seconds; finding it at the next start means the app died there,
 * and the output goes back to Android's own. They're never tried before Android 10.
 */
internal object AAudioGuard {
    /** Seconds a stream plays before it counts as safe on this phone. */
    const val TRUSTED_SECONDS = 3L

    @Volatile private var mark: java.io.File? = null
    @Volatile private var marked = false

    /** Whether the exclusive AAudio outputs may be tried on this phone. */
    val usable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** At the service's start: a mark left from last time means the app died in AAudio; [onCrashFound] then runs. */
    fun start(dir: java.io.File, onCrashFound: () -> Unit) {
        val file = java.io.File(dir, MARK)
        mark = file
        if (marked) return
        if (runCatching { file.exists() }.getOrDefault(false)) {
            Log.w("NativeOutput", "The app closed while an exclusive output was opening; back to Android's output")
            runCatching { file.delete() }
            onCrashFound()
        }
    }

    fun opening() {
        if (marked) return
        marked = true
        runCatching { mark?.createNewFile() }
    }

    fun settled() {
        if (!marked) return
        marked = false
        runCatching { mark?.delete() }
    }

    private const val MARK = "aaudio-exclusive-opening"
}
