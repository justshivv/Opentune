package com.opentune.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * YouTube's loudness figure for every track this device has resolved, kept
 * across launches.
 *
 * A track that plays from the song cache never asks YouTube for anything, so
 * without this it would play at full level after a restart while freshly
 * resolved tracks are turned down to YouTube's reference: one song loud, the
 * next quiet. With the figure kept, every track gets the same treatment.
 */
object LoudnessStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Double.serializer())
    private val map = ConcurrentHashMap<String, Double>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private var pending: Job? = null

    fun init(context: Context) {
        val f = File(context.filesDir, "loudness.json")
        file = f
        runCatching { map.putAll(json.decodeFromString(serializer, f.readText())) }
    }

    fun get(videoId: String): Double? = map[videoId]

    fun put(videoId: String, db: Double) {
        if (map.put(videoId, db) == db) return
        if (map.size > MAX_ENTRIES) map.keys.take(map.size - MAX_ENTRIES).forEach(map::remove)
        // Written a few seconds after the last change, not once per track.
        pending?.cancel()
        pending = scope.launch {
            delay(3_000)
            val f = file ?: return@launch
            runCatching {
                val tmp = File(f.parentFile, "loudness.json.tmp")
                tmp.writeText(json.encodeToString(serializer, HashMap(map)))
                tmp.renameTo(f)
            }
        }
    }

    private const val MAX_ENTRIES = 20_000
}
