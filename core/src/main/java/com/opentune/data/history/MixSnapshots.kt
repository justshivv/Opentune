package com.opentune.data.history

import android.content.Context
import android.content.SharedPreferences
import com.opentune.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Home's genre and Discover mixes as they were last made, kept for [KEEP_MS]: opening Home,
 * or playing another song, shows the same mixes in the same order instead of making them again.
 * They're made afresh once they're older than that, or when a genre turns up that has no mix
 * yet (the first hours after an install, while artists' genres are still being looked up).
 */
object MixSnapshots {
    const val KEEP_MS = 12 * 60 * 60 * 1000L
    const val GENRES = "genres"
    const val DISCOVER = "discover"

    @Serializable
    internal data class Saved(val madeAt: Long, val mixes: List<SavedMix>)
    @Serializable
    internal data class SavedMix(val id: String, val title: String, val subtitle: String, val songs: List<Song>)

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null
    private val saved = mapOf(GENRES to MutableStateFlow<Saved?>(null), DISCOVER to MutableStateFlow<Saved?>(null))
    private val shown = mapOf(GENRES to MutableStateFlow<List<DailyMixes.Mix>>(emptyList()), DISCOVER to MutableStateFlow<List<DailyMixes.Mix>>(emptyList()))

    fun init(context: Context) {
        val p = context.getSharedPreferences("home_mixes", Context.MODE_PRIVATE)
        prefs = p
        saved.keys.forEach { key ->
            val s = p.getString(key, null)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
            publish(key, s)
        }
    }

    /** The mixes last made for [key]. */
    fun mixes(key: String): StateFlow<List<DailyMixes.Mix>> = shown.getValue(key).asStateFlow()

    /** When the mixes for [key] were made, or null if never. */
    fun madeAt(key: String): Long? = saved.getValue(key).value?.madeAt

    /**
     * Whether [key]'s mixes should be made again, given the ids a new making would have: they're
     * older than [KEEP_MS], there are none, or a mix would be added that isn't there.
     */
    fun due(key: String, ids: Collection<String>, nowMs: Long = System.currentTimeMillis()): Boolean =
        due(saved.getValue(key).value, ids, nowMs)

    internal fun due(s: Saved?, ids: Collection<String>, nowMs: Long): Boolean {
        if (s == null || s.mixes.isEmpty()) return true
        if (nowMs - s.madeAt >= KEEP_MS || nowMs < s.madeAt) return true
        val have = s.mixes.mapTo(HashSet()) { it.id }
        return ids.any { it !in have }
    }

    fun save(key: String, mixes: List<DailyMixes.Mix>, nowMs: Long = System.currentTimeMillis()) {
        val s = Saved(nowMs, mixes.map { SavedMix(it.id, it.title, it.subtitle, it.songs) })
        publish(key, s)
        prefs?.edit()?.putString(key, json.encodeToString(Saved.serializer(), s))?.apply()
    }

    private fun publish(key: String, s: Saved?) {
        saved.getValue(key).value = s
        shown.getValue(key).value = s?.mixes.orEmpty().map { DailyMixes.Mix(it.id, it.title, it.subtitle, it.songs) }
    }
}
