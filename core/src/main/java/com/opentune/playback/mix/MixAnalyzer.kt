package com.opentune.playback.mix

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.opentune.data.DebugLog as Log
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reads the end of a song and the start of the next, decodes them, and
 * works out [Ending] and [Opening] for a transition. The audio comes from the
 * song cache when it's there (the playing song always is, by its end); only
 * the incoming song's first seconds may be fetched, and only when [reader]
 * offers a source for it. Results are kept for the last few songs.
 */
@OptIn(UnstableApi::class)
internal class MixAnalyzer(
    /** How hard to work: how many songs are read at once, and how much of each. */
    private val performance: () -> com.opentune.data.settings.AutomixPerformance = { com.opentune.data.settings.AutomixPerformance.BALANCED },
    /** Sources to try for a song's audio, in order: the cache first, then the network. */
    private val reader: (uri: Uri, network: Boolean) -> List<DataSource.Factory>,
) {
    private val workers = HashMap<Int, kotlinx.coroutines.CoroutineDispatcher>()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun worker(): kotlinx.coroutines.CoroutineDispatcher = synchronized(workers) {
        val n = performance().threads
        workers.getOrPut(n) { Dispatchers.Default.limitedParallelism(n) }
    }

    private val reads get() = performance() == com.opentune.data.settings.AutomixPerformance.PERFORMANCE

    private val endings = object : LinkedHashMap<String, Ending?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ending?>?) = size > KEEP
    }
    private val openings = object : LinkedHashMap<String, Opening?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Opening?>?) = size > KEEP
    }

    suspend fun ending(uri: Uri, durationMs: Long): Ending? {
        val key = "$uri@$durationMs"
        synchronized(endings) { if (endings.containsKey(key)) return endings[key] }
        val from = max(0L, durationMs - if (reads) LONG_ENDING_SPAN_MS else ENDING_SPAN_MS)
        // From the cache when the song is all there, else its last stretch from the network:
        // read from the cache alone, a song only part cached got no reading at all.
        val d = features(uri, from, durationMs, network = true)
        val result = d?.let { analyzeEnding(it.features, durationMs, it.chroma) }
        Log.d(TAG, "Ending of $uri: $result")
        synchronized(endings) { endings[key] = result }
        return result
    }

    suspend fun opening(uri: Uri): Opening? {
        val key = uri.toString()
        synchronized(openings) { if (openings.containsKey(key)) return openings[key] }
        val d = features(uri, 0L, if (reads) LONG_OPENING_SPAN_MS else OPENING_SPAN_MS, network = true)
        val result = d?.let { analyzeOpening(it.features, it.chroma) }
        Log.d(TAG, "Opening of $uri: $result")
        synchronized(openings) { openings[key] = result }
        return result
    }

    /**
     * Whether [uri] is silent from [fromMs] to its end at [durationMs], read from the file
     * itself: true when nothing in the rest rises above a whisper, false when there's sound
     * still to come (a pause, then a last chord), null when it couldn't be read.
     */
    suspend fun restIsSilent(uri: Uri, fromMs: Long, durationMs: Long): Boolean? {
        if (durationMs - fromMs < 300) return true
        val d = features(uri, fromMs, durationMs, network = true) ?: return null
        val loudest = d.features.powerDb.maxOrNull() ?: return null
        return loudest < SILENT_BELOW_DBFS
    }

    /** A stretch of a song read: its loudness and onsets, and how much of each note it holds (for its key). */
    private class Decoded(val features: Features, val chroma: FloatArray?)

    private suspend fun features(uri: Uri, fromMs: Long, toMs: Long, network: Boolean): Decoded? = withContext(worker()) {
        for (factory in reader(uri, network)) {
            val f = runCatching { decode(factory, uri, fromMs, toMs) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (f != null) return@withContext f
        }
        null
    }

    /** Decodes [fromMs]..[toMs] of [uri] to one loudness and bass loudness per ~10 ms. */
    private suspend fun decode(factory: DataSource.Factory, uri: Uri, fromMs: Long, toMs: Long): Decoded? {
        val media = DataSourceMedia(factory, uri)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(media)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            if (fromMs > 0) extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var isFloat = false
            val fromUs = fromMs * 1000
            val toUs = toMs * 1000
            val energy = FloatArrayList()
            val low = FloatArrayList()
            // The band voices sit in, for where the singing ends (see vocalsEnd).
            val mid = FloatArrayList()
            var voice = VoiceBand(rate)
            var midSq = 0.0
            var hop = 0
            var alpha = 0f
            // A low-rate copy of the sound (about 11 kHz), for the notes it holds.
            var decimate = 1
            val slow = FloatArrayList()
            var slowSum = 0f; var slowCount = 0
            fun tune() {
                hop = max(1, (rate * HOP_MS / 1000.0).roundToInt())
                alpha = (1 - exp(-2 * PI * LOW_HZ / rate)).toFloat()
                decimate = max(1, (rate / KEY_RATE).roundToInt())
            }
            tune()
            var lp1 = 0f; var lp2 = 0f
            var inHop = 0; var sumSq = 0.0; var lowSq = 0.0
            var firstUs = -1L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val started = SystemClock.elapsedRealtime()
            while (true) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - started > DECODE_LIMIT_MS) {
                    Log.w(TAG, "Gave up decoding $uri after ${DECODE_LIMIT_MS} ms")
                    break
                }
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(WAIT_US)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || t > toUs) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, WAIT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    rate = out.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                    channels = out.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                    isFloat = out.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    tune()
                    voice = VoiceBand(rate)
                    continue
                }
                if (o < 0) continue
                val buf = decoder.getOutputBuffer(o)
                if (buf != null && info.size > 0 && channels > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = buf.slice().order(ByteOrder.nativeOrder())
                    val width = if (isFloat) 4 else 2
                    val frames = info.size / (width * channels)
                    for (k in 0 until frames) {
                        val t = info.presentationTimeUs + k * 1_000_000L / rate
                        if (t < fromUs) continue
                        if (t > toUs) break
                        if (firstUs < 0) firstUs = t
                        var x = 0f
                        for (c in 0 until channels) {
                            val at = (k * channels + c) * width
                            x += if (isFloat) data.getFloat(at) else data.getShort(at) / 32768f
                        }
                        x /= channels
                        // Averaged down: a plain low-pass, enough for notes below 2 kHz.
                        slowSum += x
                        if (++slowCount >= decimate) { slow.add(slowSum / slowCount); slowSum = 0f; slowCount = 0 }
                        lp1 += alpha * (x - lp1)
                        lp2 += alpha * (lp1 - lp2)
                        sumSq += x * x
                        lowSq += lp2 * lp2
                        val v = voice.next(x)
                        midSq += v * v
                        if (++inHop >= hop) {
                            energy.add((sumSq / inHop).toFloat())
                            low.add((lowSq / inHop).toFloat())
                            mid.add((midSq / inHop).toFloat())
                            inHop = 0; sumSq = 0.0; lowSq = 0.0; midSq = 0.0
                        }
                    }
                }
                decoder.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (info.presentationTimeUs > toUs) break
            }
            // A short stretch (the silent end of a song) has few hops; the reading of a whole span needs more.
            if (energy.size < minOf(MIN_HOPS.toLong(), (toMs - fromMs) / 20).toInt().coerceAtLeast(10) || firstUs < 0) return null
            val notes = runCatching { chroma(slow.raw(), slow.size, rate / decimate) }.getOrNull()
            return Decoded(Features.of(firstUs / 1000, hop * 1000.0 / rate, energy.toArray(), low.toArray(), mid.toArray()), notes)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { media.close() }
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private class FloatArrayList {
        private var data = FloatArray(4096)
        var size = 0
            private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
        /** The backing array, valid up to [size]; no copy. */
        fun raw(): FloatArray = data
    }

    /** A Media3 [DataSource] as the platform's [MediaDataSource], reading on from where it left off. */
    private class DataSourceMedia(private val factory: DataSource.Factory, private val uri: Uri) : MediaDataSource() {
        private var source: DataSource? = null
        private var at = -1L
        private var length = -2L
        private var read = 0L

        @Synchronized
        override fun getSize(): Long {
            if (length == -2L) {
                val s = factory.createDataSource()
                length = try {
                    val n = s.open(DataSpec(uri))
                    if (n == C.LENGTH_UNSET.toLong()) -1L else n
                } finally {
                    runCatching { s.close() }
                }
            }
            return length
        }

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (length >= 0 && position >= length) return -1
            if (read > MAX_BYTES) throw java.io.IOException("Read too much for an analysis")
            if (source == null || position != at) {
                runCatching { source?.close() }
                val s = factory.createDataSource()
                s.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                source = s
                at = position
            }
            val n = source!!.read(buffer, offset, size)
            if (n == C.RESULT_END_OF_INPUT) return -1
            at += n
            read += n
            return n
        }

        @Synchronized
        override fun close() {
            runCatching { source?.close() }
            source = null
        }
    }

    private companion object {
        const val TAG = "Automix"
        /** Below this, a stretch counts as silent (about the hiss of a quiet recording). */
        const val SILENT_BELOW_DBFS = -50f
        const val KEEP = 12
        const val HOP_MS = 10.0
        const val LOW_HZ = 150.0
        const val KEY_RATE = 11_025.0
        const val WAIT_US = 5_000L
        const val MIN_HOPS = 300
        /** The last stretch of a song looked at: room for a long fade-out and the beat before it. */
        const val ENDING_SPAN_MS = 45_000L
        const val OPENING_SPAN_MS = 30_000L
        /** On Performance, more of each: a longer fade or intro and more bars to find the beat in. */
        const val LONG_ENDING_SPAN_MS = 60_000L
        const val LONG_OPENING_SPAN_MS = 40_000L
        /** A reading that takes longer than this is given up (a slow network, a busy phone). */
        const val DECODE_LIMIT_MS = 25_000L
        const val MAX_BYTES = 40L * 1024 * 1024
    }
}
