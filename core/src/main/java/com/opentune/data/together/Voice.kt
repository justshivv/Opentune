package com.opentune.data.together

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.opentune.data.DebugLog as Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AAC frames packed for a message: each a two-byte big-endian length, then
 * the frame. About a second of 24 kbps speech is some 3 KB.
 */
object VoiceFrames {
    fun pack(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        frames.forEach { f ->
            require(f.size <= 0xFFFF)
            out.write(f.size ushr 8); out.write(f.size and 0xFF); out.write(f)
        }
        return out.toByteArray()
    }

    /** The frames in [data], or null if it isn't a packing of them. */
    fun unpack(data: ByteArray): List<ByteArray>? {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            if (i + 2 > data.size) return null
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (n == 0 || i + n > data.size) return null
            frames += data.copyOfRange(i, i + n)
            i += n
        }
        return frames
    }
}

private const val RATE = 16_000
private const val BITRATE = 24_000
private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
/** Samples in one AAC-LC frame. */
private const val FRAME_SAMPLES = 1024
private const val TAG = "Voice"

/** ms of audio in [frames] AAC frames. */
internal fun framesMs(frames: Int): Long = frames * FRAME_SAMPLES * 1000L / RATE

/**
 * Says, frame by frame, whether someone is speaking: louder than the room's
 * own noise for two frames in a row opens it, and it stays open for
 * [hangFrames] after the voice stops, so the gaps between words don't cut
 * a sentence up. The noise floor follows the quiet parts, so a fan or
 * traffic doesn't hold it open.
 */
internal class SpeechGate(private val hangFrames: Int = 10) {
    private var floor = 200.0
    private var loudRun = 0
    private var hang = 0
    var open = false
        private set

    /** The next frame's loudness, as RMS of 16-bit samples; whether the gate is open after it. */
    fun next(rms: Double): Boolean {
        val loud = rms > maxOf(floor * 3.0, 350.0)
        // Down quickly, up slowly, and slower still while someone talks.
        floor = when {
            rms < floor -> floor * 0.8 + rms * 0.2
            open -> floor * 0.998 + rms * 0.002
            else -> floor * 0.98 + rms * 0.02
        }.coerceIn(30.0, 6_000.0)
        if (loud) {
            loudRun++
            if (loudRun >= 2) { open = true; hang = hangFrames }
        } else {
            loudRun = 0
            if (open && --hang <= 0) open = false
        }
        return open
    }

    companion object {
        fun rms(pcm: ByteArray, bytes: Int): Double {
            var sum = 0.0
            val n = bytes / 2
            if (n == 0) return 0.0
            for (i in 0 until n) {
                val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
                sum += v * v
            }
            return kotlin.math.sqrt(sum / n)
        }
    }
}

/**
 * Records the microphone as AAC, handing it on about once a second while it
 * records ([onPiece]), and stopping by itself at [maxMs]. The music ducks
 * while it records, unless [duck] is off.
 *
 * With [gate], it's an open mic for a call: only speech is sent. Each
 * stretch of speech is its own clip, its last piece marked last, and
 * [onSpeaking] says when one starts and ends. [muted] sends nothing.
 */
class VoiceRecorder(
    private val context: Context,
    private val maxMs: Long,
    private val gate: Boolean = false,
    private val duck: Boolean = true,
    private val onSpeaking: (Boolean) -> Unit = {},
    private val onPiece: (cfg: ByteArray?, frames: List<ByteArray>, msSoFar: Long, last: Boolean) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var muted = false
    private var worker: Thread? = null
    private val focus = Focus(context)

    val isRecording get() = running

    @SuppressLint("MissingPermission") // asked for by the screen before it starts one
    fun start(): Boolean {
        if (running) return false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        val codec = runCatching {
            MediaCodec.createEncoderByType(MIME).apply {
                configure(
                    MediaFormat.createAudioFormat(MIME, RATE, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 4)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                start()
            }
        }.getOrNull() ?: run { record.release(); return false }
        running = true
        if (duck) focus.take()
        record.startRecording()
        worker = thread(name = "voice-record") {
            try {
                encode(record, codec)
            } catch (e: Exception) {
                Log.w(TAG, "Recording failed", e)
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { codec.stop() }
                codec.release()
                if (duck) focus.drop()
                running = false
            }
        }
        return true
    }

    /** Ends the recording; the last piece goes out from the recording thread. */
    fun stop() {
        running = false
    }

    private fun encode(record: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(FRAME_SAMPLES * 2)
        var cfg: ByteArray? = null
        var cfgSent = false
        var pending = mutableListOf<ByteArray>()
        var total = 0
        var samplesIn = 0L
        var eosQueued = false
        var done = false
        val framesPerPiece = RATE / FRAME_SAMPLES // about a second
        // For a call: which frames are speech, in the order they went in.
        val speech = SpeechGate()
        val voiced = ArrayDeque<Boolean>()
        // The frames just before speech starts, so its first sound isn't clipped.
        val preroll = ArrayDeque<ByteArray>()
        var spurt = false
        var spurtCfgSent = false
        var spurtFrames = 0
        // The microphone erring (taken by a call) ends the clip, rather than feeding empty buffers until let go.
        var micGone = false
        while (!done) {
            if (!eosQueued) {
                val stopNow = !running || micGone || framesMs((samplesIn / FRAME_SAMPLES).toInt()) >= maxMs
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    if (stopNow) {
                        codec.queueInputBuffer(inIndex, 0, 0, samplesIn * 1_000_000 / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        val n = record.read(pcm, 0, minOf(pcm.size, buf.capacity()))
                        if (n > 0) {
                            buf.clear(); buf.put(pcm, 0, n)
                            codec.queueInputBuffer(inIndex, 0, n, samplesIn * 1_000_000 / RATE, 0)
                            samplesIn += n / 2
                            if (gate) voiced.addLast(speech.next(SpeechGate.rms(pcm, n)) && !muted)
                        } else {
                            if (n < 0) micGone = true
                            codec.queueInputBuffer(inIndex, 0, 0, samplesIn * 1_000_000 / RATE, 0)
                        }
                    }
                }
            }
            var outIndex = codec.dequeueOutputBuffer(info, 10_000)
            while (outIndex >= 0) {
                val out = codec.getOutputBuffer(outIndex)!!
                val bytes = ByteArray(info.size).also { out.position(info.offset); out.get(it) }
                val isCfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                codec.releaseOutputBuffer(outIndex, false)
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) done = true
                if (gate) {
                    if (isCfg) cfg = bytes
                    else if (cfg != null) {
                        val talk = bytes.isNotEmpty() && (voiced.removeFirstOrNull() ?: spurt)
                        if (talk) {
                            if (!spurt) {
                                spurt = true
                                spurtCfgSent = false
                                pending = preroll.toMutableList()
                                preroll.clear()
                                spurtFrames = pending.size
                                onSpeaking(true)
                            }
                            pending += bytes
                            spurtFrames++
                        } else if (bytes.isNotEmpty()) {
                            preroll.addLast(bytes)
                            while (preroll.size > 3) preroll.removeFirst()
                        }
                        val end = spurt && (!talk || eos)
                        if (spurt && (pending.size >= framesPerPiece || end)) {
                            onPiece(if (spurtCfgSent) null else cfg, pending, framesMs(spurtFrames), end)
                            spurtCfgSent = true
                            pending = mutableListOf()
                        }
                        if (end) { spurt = false; onSpeaking(false) }
                    }
                    if (eos) break
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                    continue
                }
                when {
                    isCfg -> cfg = bytes
                    bytes.isNotEmpty() -> { pending += bytes; total++ }
                }
                if ((pending.size >= framesPerPiece || eos) && cfg != null) {
                    onPiece(if (cfgSent) null else cfg, pending, framesMs(total), eos)
                    cfgSent = true
                    pending = mutableListOf()
                }
                if (eos) break
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
        // A recording too short to make a single frame still ends, with nothing in it.
        if (!gate && !cfgSent && cfg != null) onPiece(cfg, pending, framesMs(total), true)
    }
}

/**
 * Plays AAC voice clips: live talk as its pieces arrive ([talk]), each clip
 * on its own track so several people talking at once are heard together,
 * and saved notes from the chat ([play]), one after another. The music
 * ducks under them.
 */
class VoicePlayer(context: Context) {
    private class Job(
        val id: String,
        val cfg: ByteArray,
        val pieces: LinkedBlockingQueue<List<ByteArray>> = LinkedBlockingQueue(),
        @Volatile var ended: Boolean = false,
        @Volatile var stopped: Boolean = false,
    )

    private val focus = Focus(context)
    private val jobs = LinkedBlockingQueue<Job>()
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var current: Job? = null
    @Volatile var playing: String? = null
        private set
    /** Called with the clip now playing, or null when it's quiet again. */
    var onPlaying: (String?) -> Unit = {}

    init {
        thread(name = "voice-play", isDaemon = true) {
            while (true) {
                val job = jobs.take()
                if (job.stopped) { byId.remove(job.id); continue }
                current = job
                playing = job.id
                onPlaying(job.id)
                runCatching { decode(job) }.onFailure { Log.w(TAG, "Playback failed", it) }
                byId.remove(job.id)
                current = null
                if (jobs.isEmpty()) { playing = null; onPlaying(null) }
            }
        }
    }

    /** More of live clip [id]; the first call starts it playing, alongside any other talk. */
    fun talk(id: String, cfg: ByteArray, frames: List<ByteArray>, done: Boolean) {
        val job = byId.getOrPut(id) {
            Job(id, cfg).also { j ->
                thread(name = "voice-talk", isDaemon = true) {
                    runCatching { decode(j) }.onFailure { Log.w(TAG, "Talk playback failed", it) }
                    byId.remove(j.id)
                }
            }
        }
        job.pieces.put(frames)
        if (done) job.ended = true
    }

    /** A whole clip, from the chat. Playing it again while it plays stops it. */
    fun play(id: String, cfg: ByteArray, frames: List<ByteArray>) {
        if (playing == id) { current?.stopped = true; return }
        val job = Job(id, cfg).apply { pieces.put(frames); ended = true }
        byId[id] = job
        jobs.put(job)
    }

    fun stop() {
        jobs.clear()
        byId.values.forEach { it.stopped = true }
        current?.stopped = true
    }

    private fun decode(job: Job) {
        focus.take()
        val format = MediaFormat.createAudioFormat(MIME, RATE, 1).apply { setByteBuffer("csd-0", ByteBuffer.wrap(job.cfg)) }
        val codec = MediaCodec.createDecoderByType(MIME).apply { configure(format, null, null, 0); start() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        val frames = ArrayDeque<ByteArray>()
        var inputDone = false
        var waitedMs = 0L
        try {
            while (!job.stopped) {
                if (frames.isEmpty() && !inputDone) {
                    val piece = job.pieces.poll(100, TimeUnit.MILLISECONDS)
                    if (piece != null) { frames.addAll(piece); waitedMs = 0 } else {
                        waitedMs += 100
                        // A piece that never comes: give up on the rest after a few seconds.
                        if (job.ended && job.pieces.isEmpty() || waitedMs > 4_000) inputDone = true
                    }
                }
                if (frames.isNotEmpty() || inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (frames.isEmpty()) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val f = frames.removeFirst()
                            codec.getInputBuffer(inIndex)!!.apply { clear(); put(f) }
                            codec.queueInputBuffer(inIndex, 0, f.size, pts, 0)
                            pts += FRAME_SAMPLES * 1_000_000L / RATE
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!
                    val pcm = ByteArray(info.size).also { out.position(info.offset); out.get(it) }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (pcm.isNotEmpty()) track.write(pcm, 0, pcm.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { codec.stop() }
            codec.release()
            focus.drop()
        }
    }
}

/**
 * Ducks other audio (the music) while a voice records or plays. Counted, so
 * the music comes back only when the last voice playing has finished.
 */
private class Focus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()
    private var users = 0

    @Synchronized fun take() {
        if (users++ == 0) am?.requestAudioFocus(request)
    }

    @Synchronized fun drop() {
        if (users == 0) return
        if (--users == 0) am?.abandonAudioFocusRequest(request)
    }
}
