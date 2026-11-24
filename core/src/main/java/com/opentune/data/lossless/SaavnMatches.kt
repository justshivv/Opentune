package com.opentune.data.lossless

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Which JioSaavn track each song is, or that it has none, as a search found: a song played
 * again skips the search (the slow part) and only asks for the track's current stream. The
 * stream address itself isn't kept; it's asked for each time.
 */
internal class SaavnMatchBook(private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    data class Entry(val id: String? = null, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The JioSaavn track [videoId] matched, if that's still recent enough to trust. */
    fun idFor(videoId: String): String? =
        entries[videoId]?.takeIf { it.id != null && now() - it.at < MATCH_TTL_MS }?.id

    /** Whether a search lately found no JioSaavn track for [videoId]. */
    fun missed(videoId: String): Boolean =
        entries[videoId]?.let { it.id == null && now() - it.at < MISS_TTL_MS } == true

    fun matched(videoId: String, id: String) = put(videoId, Entry(id, now()))
    fun noMatch(videoId: String) = put(videoId, Entry(null, now()))
    fun forget(videoId: String) { entries.remove(videoId) }

    fun snapshot(): Map<String, Entry> = HashMap(entries)

    /** What a file held; anything newer already here stays. */
    fun load(saved: Map<String, Entry>) { saved.forEach { (k, v) -> entries.putIfAbsent(k, v) } }

    private fun put(videoId: String, entry: Entry) {
        entries[videoId] = entry
        if (entries.size > MAX) {
            // The oldest tenth goes: a song not heard for that long is the least likely next.
            entries.entries.sortedBy { it.value.at }.take(MAX / 10).forEach { entries.remove(it.key, it.value) }
        }
    }

    companion object {
        /** A catalog's track ids don't change; this only lets a re-release be found in time. */
        const val MATCH_TTL_MS = 60L * 24 * 3600_000
        /** The catalog gains songs: one missing is looked for again after a few days. */
        const val MISS_TTL_MS = 3L * 24 * 3600_000
        const val MAX = 3000
    }
}

/** [SaavnMatchBook] kept in a file across launches; nothing is kept before [init]. */
internal object SaavnMatches {
    @Volatile private var book: SaavnMatchBook? = null
    @Volatile private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), SaavnMatchBook.Entry.serializer())

    fun init(context: Context) {
        val f = File(context.filesDir, "saavn-matches.json")
        val b = SaavnMatchBook()
        file = f
        book = b
        scope.launch { runCatching { if (f.exists()) b.load(json.decodeFromString(serializer, f.readText())) } }
    }

    /** The book, or null before [init] (and in tests, which then search every time). */
    fun book(): SaavnMatchBook? = book

    /** Writes the book a moment after a change, so a run of changes is written once. */
    fun changed() {
        val f = file ?: return
        val b = book ?: return
        synchronized(this) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                runCatching {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(json.encodeToString(serializer, b.snapshot()))
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                }
            }
        }
    }

    fun forget(videoId: String) { book?.forget(videoId); changed() }

    private const val SAVE_DELAY_MS = 3_000L
}
