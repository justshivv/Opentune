package com.opentune.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.opentune.data.DebugLog as Log
import com.opentune.data.download.Downloads
import com.opentune.data.local.LocalMusic
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A song's loudness along its length, [BINS] steps from start to end, for
 * the waveform seek bar. A song on the phone (downloaded, or in its own
 * library) is decoded in the background and has its whole waveform at
 * once. A streamed song's is filled in from the app's own audio as it
 * plays ([observe]), and kept, so the next time it's complete from the
 * start. Values are 0 to 1, or [UNKNOWN] for a step not heard yet.
 */
object Waveforms {
    const val BINS = 96
    const val UNKNOWN = -1f
    private const val TAG = "Waveforms"
    private const val KEEP = 600

    /** A waveform being shown or built; [version] moves on whenever its steps change. */
    class Wave(val id: String, val bins: FloatArray) {
        @Volatile var version = 0
            internal set
        val complete: Boolean get() = bins.none { it == UNKNOWN }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val waves = ConcurrentHashMap<String, Wave>()
    private val decoding = ConcurrentHashMap.newKeySet<String>()
    private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "waveforms").apply { mkdirs() }
    }

    /** The waveform for [id]: from memory, from the phone, decoded, or empty to be filled while it plays. */
    fun forSong(context: Context, id: String): Wave {
        init(context)
        waves[id]?.let { return it }
        val wave = load(id) ?: Wave(id, FloatArray(BINS) { UNKNOWN })
        waves[id] = wave
        if (wave.bins.any { it == UNKNOWN }) decodeIfOnPhone(context, wave)
        if (waves.size > 40) waves.keys.take(waves.size - 40).forEach { if (it != id) waves.remove(it) }
        return wave
    }

    /** One moment of a streamed song as it's heard: [level] at [positionMs] of [durationMs]. */
    fun observe(wave: Wave, positionMs: Long, durationMs: Long, level: Float) {
        if (durationMs <= 0 || positionMs < 0) return
        val i = ((positionMs.toDouble() / durationMs) * BINS).toInt().coerceIn(0, BINS - 1)
        val old = wave.bins[i]
        val v = level.coerceIn(0f, 1f)
        if (old == UNKNOWN || v > old) {
            wave.bins[i] = if (old == UNKNOWN) v else max(old, v)
            wave.version++
        }
    }

    /** Keeps what's been heard of [wave], once enough of it has. */
    fun save(wave: Wave) {
        val known = wave.bins.count { it != UNKNOWN }
        if (known < BINS / 4) return
        scope.launch { write(wave) }
    }

    internal fun write(wave: Wave) {
        val d = dir ?: return
        run {
            runCatching {
                File(d, name(wave.id)).writeBytes(ByteArray(BINS) { i -> if (wave.bins[i] == UNKNOWN) 0xFF.toByte() else (wave.bins[i] * 254).toInt().coerceIn(0, 254).toByte() })
                // Only the most recent few hundred are kept.
                d.listFiles()?.takeIf { it.size > KEEP }?.let { files -> files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach(File::delete) }
            }
        }
    }

    /** Forgets what's in memory, so the next ask reads the phone; for tests. */
    internal fun forgetLoaded() = waves.clear()

    private fun load(id: String): Wave? = runCatching {
        val f = File(dir ?: return null, name(id)).takeIf { it.exists() } ?: return null
        val bytes = f.readBytes()
        if (bytes.size != BINS) return null
        Wave(id, FloatArray(BINS) { i -> (bytes[i].toInt() and 0xFF).let { if (it == 0xFF) UNKNOWN else it / 254f } })
    }.getOrNull()

    private fun name(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80) + ".wave"

    private fun decodeIfOnPhone(context: Context, wave: Wave) {
        val source: Pair<File?, Uri?> = when {
            LocalMusic.isLocal(wave.id) -> null to LocalMusic.contentUri(wave.id)
            else -> (Downloads.fileFor(wave.id) ?: return) to null
        }
        if (!decoding.add(wave.id)) return
        scope.launch {
            try {
                val bins = decode(context, source.first, source.second) ?: return@launch
                bins.copyInto(wave.bins)
                wave.version++
                save(wave)
            } catch (e: Exception) {
                Log.d(TAG, "couldn't read ${wave.id}: ${e.message}")
            } finally {
                decoding.remove(wave.id)
            }
        }
    }

    /** Decodes the whole file and takes the loudest sample in each step, scaled so the loudest step is 1. */
    private fun decode(context: Context, file: File?, uri: Uri?): FloatArray? {
        val extractor = MediaExtractor()
        try {
            if (file != null) extractor.setDataSource(file.path) else extractor.setDataSource(context, uri!!, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } ?: return null
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val peaks = FloatArray(BINS)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buffer, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            val bin = ((info.presentationTimeUs.toDouble() / durationUs) * BINS).toInt().coerceIn(0, BINS - 1)
                            // 16-bit PCM; every eighth sample is plenty to find the peaks.
                            var p = peaks[bin]
                            var k = info.offset
                            while (k + 1 < info.offset + info.size) {
                                p = max(p, abs(out.getShort(k).toFloat()) / 32768f)
                                k += 16
                            }
                            peaks[bin] = p
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val top = peaks.maxOrNull()?.takeIf { it > 0f } ?: return null
            return FloatArray(BINS) { peaks[it] / top }
        } finally {
            extractor.release()
        }
    }
}
