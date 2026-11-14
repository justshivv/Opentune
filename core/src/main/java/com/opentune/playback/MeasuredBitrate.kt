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
 * figure counts in. Null until it has settled (see [BitrateSettler]); the stream's own
 * figure is shown meanwhile.
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
 * Turns the running count of a song's audio into one figure to show. A running average
 * moves for a while (more so for variable-bitrate FLAC and Opus), so nothing is shown
 * until it has settled: three readings a second apart within 2% of each other once four
 * seconds have been read, or twelve seconds in whatever it says. That figure then stays
 * for the song, seeks included, until [reset] for the next stream.
 */
internal class BitrateSettler {
    private var bytes = 0L
    /** The latest frame's size: counted once the next frame shows where it ends. */
    private var pending = 0
    private var firstUs = Long.MIN_VALUE
    private var lastUs = Long.MIN_VALUE
    private var toldUs = Long.MIN_VALUE
    private val recent = ArrayDeque<Int>()
    /** The figure shown for this song once settled; null before. */
    var settled: Int? = null
        private set

    /** A new stream: nothing settled, counting from scratch. */
    fun reset() { settled = null; restartCount() }

    /** A seek: a settled figure stays; an unsettled count starts again from here. */
    fun seeked() { if (settled == null) restartCount() }

    private fun restartCount() {
        bytes = 0L; pending = 0; firstUs = Long.MIN_VALUE; lastUs = Long.MIN_VALUE; toldUs = Long.MIN_VALUE; recent.clear()
    }

    /** One compressed frame of [size] bytes at [timeUs]; the figure to show when it has just settled, else null. */
    fun frame(size: Int, timeUs: Long): Int? {
        if (settled != null || size <= 0) return null
        if (firstUs == Long.MIN_VALUE) { firstUs = timeUs; toldUs = timeUs }
        // Frames before the first counted one (a seek's preroll) don't stretch the span.
        if (timeUs < firstUs) return null
        // Each frame's bytes cover the time up to the next one, so the span from the first
        // frame to this one holds every frame before this one.
        if (timeUs > lastUs) { bytes += pending; pending = size; lastUs = timeUs } else pending += size
        if (lastUs - toldUs < 1_000_000L) return null
        toldUs = lastUs
        val span = lastUs - firstUs
        val now = MeasuredBitrate.kbpsOf(bytes, span) ?: return null
        recent.addLast(now); if (recent.size > 3) recent.removeFirst()
        val steady = span >= STEADY_AFTER_US && recent.size == 3 && recent.max() - recent.min() <= recent.max() * 0.02
        if (steady || span >= SETTLE_BY_US) settled = recent.average().toInt()
        return settled
    }

    /** The stream ended before it settled: what was read, if enough to say. */
    fun ended(): Int? {
        if (settled != null) return null
        settled = MeasuredBitrate.kbpsOf(bytes, lastUs - firstUs)
        return settled
    }

    companion object {
        const val STEADY_AFTER_US = 4_000_000L
        const val SETTLE_BY_US = 12_000_000L
    }
}

/**
 * The platform-decoder audio renderer, counting what it hands the decoder for
 * [MeasuredBitrate]. The figure is shown once it has settled and then kept for the song
 * (see [BitrateSettler]), so it doesn't keep changing while the song plays.
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
    private val settler = BitrateSettler()

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        if (buffer.isEndOfStream) { settler.ended()?.let(MeasuredBitrate::publish); return }
        val size = buffer.data?.limit() ?: return
        settler.frame(size, buffer.timeUs)?.let(MeasuredBitrate::publish)
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        // A new stream (the next song, or a better copy of this one): measured afresh.
        settler.reset()
        MeasuredBitrate.publish(null)
        return super.onInputFormatChanged(formatHolder)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamWasReset: Boolean) {
        super.onPositionReset(positionUs, joining, sampleStreamWasReset)
        settler.seeked()
    }
}
