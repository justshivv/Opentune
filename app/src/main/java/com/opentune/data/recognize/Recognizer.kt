package com.opentune.data.recognize

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.opentune.data.DebugLog as Log
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names the song playing nearby: listens through the microphone, builds a
 * [Signature] as the sound comes in, and asks [Shazam] a few times as it
 * grows (after 4, 7, 10 and 13 seconds), stopping at the first answer.
 * The recording itself never leaves the phone.
 */
object Recognizer {
    private const val TAG = "Recognizer"
    private const val RATE = 16_000
    /** When to ask, by seconds heard. */
    private val ASK_AT = intArrayOf(4, 7, 10, 13)

    sealed interface State {
        data object Idle : State
        /** [level] is how loud it is right now, 0 to 1, for the animation. */
        data class Listening(val level: Float, val seconds: Float) : State
        data class Found(val song: Recognized) : State
        data object NotFound : State
        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    private val _history = MutableStateFlow<List<Recognized>>(emptyList())
    /** Songs found lately, newest first. */
    val history: StateFlow<List<Recognized>> = _history
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("recognized", Context.MODE_PRIVATE)
        prefs = p
        _history.value = runCatching { fromJson(p.getString("history", null)) }.getOrDefault(emptyList())
    }

    /** Starts listening; needs the microphone permission already granted. */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        init(context)
        if (job?.isActive == true) return
        _state.value = State.Listening(0f, 0f)
        job = scope.launch {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                _state.value = State.Failed("Couldn't use the microphone")
                return@launch
            }
            val signature = Signature()
            val buffer = ShortArray(RATE / 10)
            var asked = 0
            var lookup: kotlinx.coroutines.Deferred<Recognized?>? = null
            try {
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    signature.add(buffer, n)
                    var sum = 0.0
                    for (i in 0 until n) sum += buffer[i] * buffer[i].toDouble()
                    val level = (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
                    val seconds = signature.durationMs / 1000f
                    _state.value = State.Listening(level, seconds)
                    // An answer that's come back ends it.
                    lookup?.takeIf { it.isCompleted }?.let { done ->
                        val found = runCatching { done.await() }.getOrNull()
                        if (found != null) {
                            remember(found)
                            _state.value = State.Found(found)
                            return@launch
                        }
                        lookup = null
                    }
                    if (lookup == null && asked < ASK_AT.size && seconds >= ASK_AT[asked]) {
                        asked++
                        val copy = signature.encode()
                        val ms = signature.durationMs
                        lookup = async(Dispatchers.IO) { runCatching { Shazam.lookup(copy, ms) }.onFailure { Log.w(TAG, "lookup failed", it) }.getOrNull() }
                    }
                    if (asked == ASK_AT.size && lookup == null) break
                }
                // The last ask's answer, waited for.
                val last = lookup?.let { runCatching { it.await() }.getOrNull() }
                if (last != null) {
                    remember(last)
                    _state.value = State.Found(last)
                } else {
                    _state.value = State.NotFound
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listening failed", e)
                _state.value = State.Failed("Couldn't listen: ${e.message ?: "unknown error"}")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    /** Puts the page in [state] without listening, for screenshots. */
    @androidx.annotation.VisibleForTesting
    internal fun show(state: State) {
        _state.value = state
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    /** Back to the start, after a result's been seen. */
    fun reset() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    fun forget(song: Recognized) {
        _history.value = _history.value.filterNot { it.foundAt == song.foundAt }
        save()
    }

    private fun remember(song: Recognized) {
        _history.value = (listOf(song) + _history.value.filterNot { it.title == song.title && it.artist == song.artist }).take(30)
        save()
    }

    private fun save() {
        prefs?.edit()?.putString("history", toJson(_history.value))?.apply()
    }

    internal fun toJson(list: List<Recognized>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().put("title", s.title).put("artist", s.artist).put("album", s.album).put("cover", s.coverUrl).put("url", s.shazamUrl).put("at", s.foundAt))
        }
    }.toString()

    internal fun fromJson(text: String?): List<Recognized> {
        val array = JSONArray(text ?: return emptyList())
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Recognized(
                o.optString("title"),
                o.optString("artist"),
                o.optString("album").takeIf { it.isNotBlank() && it != "null" },
                o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                o.optLong("at"),
            )
        }
    }
}
