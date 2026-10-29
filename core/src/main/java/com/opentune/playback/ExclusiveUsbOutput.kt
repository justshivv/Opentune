package com.opentune.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Opens each song's output for [BitPerfectUsb]: just before the AudioTrack is
 * made, the DAC is set to the song's rate and sample format, and the track is
 * opened in that same format, so Android's bit-perfect path takes it. What the
 * player hands over (16-bit, or float carrying 24 and 32-bit samples exactly)
 * is rewritten word for word into the DAC's format on the way in.
 *
 * Off, without a DAC, or for anything but plain PCM, outputs open as before.
 */
@OptIn(UnstableApi::class)
class ExclusiveUsbOutputProvider(delegate: AudioOutputProvider) : ForwardingAudioOutputProvider(delegate) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val carrier = config.encoding
        if (config.isOffload || config.isTunneling || carrier !in CARRIERS || !BitPerfectUsb.wanted) return super.getAudioOutput(config)
        val channels = Integer.bitCount(config.channelMask)
        val target = BitPerfectUsb.prepare(config.sampleRate, channels) ?: return super.getAudioOutput(config)
        if (target == carrier) return super.getAudioOutput(config)
        val opened = config.buildUpon()
            .setEncoding(target)
            .setBufferSize((config.bufferSize.toLong() * bytes(target) / bytes(carrier)).toInt())
            .build()
        return ConvertingAudioOutput(super.getAudioOutput(opened), carrier, target, channels)
    }

    companion object {
        private val CARRIERS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)

        internal fun bytes(encoding: Int) = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
    }
}

/**
 * [delegate], opened in [target], fed samples that arrive in [carrier]. Each
 * buffer is rewritten whole into [pending] and taken from the caller at once;
 * what the track hasn't room for yet is written first on the next call, and
 * until then the call says it isn't done, so the player waits as it would.
 */
@OptIn(UnstableApi::class)
internal class ConvertingAudioOutput(
    private val delegate: AudioOutput,
    private val carrier: Int,
    private val target: Int,
    private val channels: Int,
) : ForwardingAudioOutput(delegate) {
    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        pending?.let { p ->
            delegate.write(p, encodedAccessUnitCount, presentationTimeUs)
            if (p.hasRemaining()) return false
            pending = null
        }
        if (!buffer.hasRemaining()) return true
        val made = convert(buffer)
        delegate.write(made, encodedAccessUnitCount, presentationTimeUs)
        if (made.hasRemaining()) { pending = made; return false }
        return true
    }

    override fun flush() { pending = null; super.flush() }

    override fun release() { pending = null; super.release() }

    /** All of [input] in [target], in a buffer of its own; [input] is left empty. */
    private fun convert(input: ByteBuffer): ByteBuffer {
        val needed = PcmWords.size(input.remaining(), carrier, target, channels)
        if (scratch.capacity() < needed) scratch = ByteBuffer.allocateDirect(needed).order(ByteOrder.LITTLE_ENDIAN)
        return PcmWords.rewrite(input, carrier, target, channels, scratch.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { clear() })
    }
}

/**
 * PCM samples from one word format to another, value for value: 16-bit into
 * 24 or 32 bits is padded with zeros below, and float back into integers is
 * scaled and rounded, which gives back exactly the integer a 24-bit sample
 * started as (float holds 24 bits whole).
 */
internal object PcmWords {
    /** Bytes [inputBytes] of [carrier] take in [target], whole frames only. */
    fun size(inputBytes: Int, carrier: Int, target: Int, channels: Int): Int =
        (inputBytes / (ExclusiveUsbOutputProvider.bytes(carrier) * channels)) * channels * ExclusiveUsbOutputProvider.bytes(target)

    /** [input] rewritten into [into] (cleared, with room for it); [input] is left empty, [into] flipped. */
    fun rewrite(input: ByteBuffer, carrier: Int, target: Int, channels: Int, into: ByteBuffer): ByteBuffer {
        val src = input.order(ByteOrder.nativeOrder())
        val out = into.order(ByteOrder.LITTLE_ENDIAN)
        // Whole frames only: a stray partial one would shift every channel after it.
        val samples = (src.remaining() / (ExclusiveUsbOutputProvider.bytes(carrier) * channels)) * channels
        repeat(samples) {
            if (carrier == C.ENCODING_PCM_16BIT) {
                val s = src.getShort().toInt()
                when (target) {
                    C.ENCODING_PCM_24BIT -> put24(out, s shl 8)
                    C.ENCODING_PCM_32BIT -> out.putInt(s shl 16)
                    else -> out.putFloat(s / 32768f)
                }
            } else {
                val f = src.getFloat()
                when (target) {
                    C.ENCODING_PCM_24BIT -> put24(out, (f * 8_388_608.0).roundToLong().coerceIn(-8_388_608L, 8_388_607L).toInt())
                    C.ENCODING_PCM_32BIT -> out.putInt((f * 2_147_483_648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                    else -> out.putShort((f * 32_768.0).roundToLong().coerceIn(-32_768L, 32_767L).toInt().toShort())
                }
            }
        }
        input.position(input.limit())
        out.flip()
        return out
    }

    private fun put24(out: ByteBuffer, v: Int) {
        out.put(v.toByte())
        out.put((v shr 8).toByte())
        out.put((v shr 16).toByte())
    }
}
