package com.opentune.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playing song's bitrate as measured from its audio: the bytes of every compressed
 * frame the decoder is given, over the stretch of the song they cover. Exact for any
 * codec and any source (a FLAC file's 900 kbps, a stream's 160), and leaves out what a
 * file holds besides the audio (its container, its cover art), which a size-over-length
 * figure counts in. Null until a couple of seconds have been read.
 */
object MeasuredBitrate {
    private val _kbps = MutableStateFlow<Int?>(null)
    val kbps: StateFlow<Int?> = _kbps.asStateFlow()

    internal fun publish(kbps: Int?) { _kbps.value = kbps }

    /** kbps for [bytes] of audio spanning [spanUs] of the song; null for too short a stretch to say. */
    internal fun kbpsOf(bytes: Long, spanUs: Long): Int? =
        if (spanUs < MIN_SPAN_US || bytes <= 0) null else (bytes * 8_000L / spanUs).toInt()

    internal const val MIN_SPAN_US = 2_000_000L
}

/**
 * The platform-decoder audio renderer, counting what it hands the decoder for
 * [MeasuredBitrate]. Counting starts again at each seek and each new stream, so the
 * figure is always this song's.
 */
@OptIn(UnstableApi::class)
internal class MeasuringAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    fallback: Boolean,
    handler: Handler?,
    listener: AudioRendererEventListener?,
    sink: AudioSink,
) : MediaCodecAudioRenderer(context, codecAdapterFactory, selector, fallback, handler, listener, sink) {
    private var bytes = 0L
    private var firstUs = Long.MIN_VALUE
    private var lastUs = Long.MIN_VALUE
    private var told = 0L

    private fun restart() {
        bytes = 0L
        firstUs = Long.MIN_VALUE
        lastUs = Long.MIN_VALUE
        told = 0L
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        if (buffer.isEndOfStream) return
        val size = buffer.data?.limit() ?: return
        if (size <= 0) return
        val t = buffer.timeUs
        if (firstUs == Long.MIN_VALUE) firstUs = t
        // Frames before the first counted one (a seek's preroll) don't stretch the span.
        if (t < firstUs) return
        lastUs = maxOf(lastUs, t)
        bytes += size
        // Told every second or so of the song; the figure settles as more is read.
        if (lastUs - told >= 1_000_000L) {
            told = lastUs
            MeasuredBitrate.kbpsOf(bytes, lastUs - firstUs)?.let { MeasuredBitrate.publish(it) }
        }
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        restart()
        MeasuredBitrate.publish(null)
        return super.onInputFormatChanged(formatHolder)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamWasReset: Boolean) {
        super.onPositionReset(positionUs, joining, sampleStreamWasReset)
        // The figure so far stays shown; counting starts over from here.
        restart()
    }
}
