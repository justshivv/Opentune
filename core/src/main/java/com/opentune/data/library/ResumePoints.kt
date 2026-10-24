package com.opentune.data.library

import android.content.Context
import com.opentune.data.DebugLog as Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where you stopped in anything much longer than a song: a mix, a talk, a
 * full album in one video, a podcast episode played from outside the
 * podcasts pages. It plays on from there next time, and lists show how far
 * in it is. Podcast episodes started from their own pages keep their place
 * in [com.opentune.data.podcasts.Podcasts] instead.
 */
object ResumePoints {
    private const val TAG = "ResumePoints"
    /** Anything this long or longer keeps its place. */
    const val LONG_MS = 20 * 60_000L
    /** Resume only past the first half minute, and not in the last. */
    private const val EDGE_MS = 30_000L
    private const val MAX = 300

    @Serializable
    data class Point(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
        val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val _points = MutableStateFlow<Map<String, Point>>(emptyMap())
    /** Each long track's place, by video id. */
    val points: StateFlow<Map<String, Point>> = _points.asStateFlow()

    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, "resume-points.json")
        file = f
        runCatching { json.decodeFromString<Map<String, Point>>(f.readText()) }.getOrNull()?.let { _points.value = it }
    }

    /** Where to pick [videoId] up again, or null to start from the top. */
    fun resumeAt(videoId: String): Long? = _points.value[videoId]?.positionMs

    /**
     * Keeps [positionMs] for [videoId] when it's [LONG_MS] or longer. Near
     * the end it's counted as finished and forgotten, so it starts over next
     * time; in the first half minute nothing changes.
     */
    fun save(videoId: String, positionMs: Long, durationMs: Long, now: Long = System.currentTimeMillis()) {
        if (durationMs < LONG_MS) return
        val before = _points.value
        val next = when {
            positionMs >= durationMs - EDGE_MS -> before - videoId
            positionMs < EDGE_MS -> return
            else -> (before + (videoId to Point(positionMs, durationMs, now)))
                .entries.sortedByDescending { it.value.updatedAt }.take(MAX).associate { it.toPair() }
        }
        if (next == before) return
        _points.value = next
        write()
    }

    fun forget(videoId: String) {
        val before = _points.value
        if (videoId !in before) return
        _points.value = before - videoId
        write()
    }

    /** Writes whatever is newest when its turn comes, so an older copy never lands last. */
    private fun write() {
        val f = file ?: return
        scope.launch {
            lock.withLock {
                runCatching { f.writeText(json.encodeToString(_points.value)) }.onFailure { Log.w(TAG, "couldn't save", it) }
            }
        }
    }
}
