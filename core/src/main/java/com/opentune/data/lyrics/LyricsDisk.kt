package com.opentune.data.lyrics

import android.content.Context
import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The lyrics found for each song, kept across launches, so a song heard before shows its words
 * at once and without the network, even after the app was closed. One small file per song, the
 * least lately used dropped past [MAX_FILES]. Nothing is kept before [init] (nor in tests).
 * Downloads keep their own copy (see [LyricsRepository.saveOffline]); this is only a cache.
 */
internal object LyricsDisk {
    const val MAX_FILES = 1000

    @Volatile private var dir: File? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Lyrics.serializer())

    fun init(context: Context) {
        dir = File(context.filesDir, "lyrics-cache").apply { mkdirs() }
    }

    /** What's kept for [videoId], by [LyricsRepository]'s key; marked as just used. */
    fun read(videoId: String): Map<String, Lyrics> {
        val f = fileFor(videoId) ?: return emptyMap()
        if (!f.exists()) return emptyMap()
        return runCatching { json.decodeFromString(serializer, f.readText()) }
            .onSuccess { f.setLastModified(System.currentTimeMillis()) }
            .getOrElse { f.delete(); emptyMap() }
    }

    /** Whether anything is kept for [videoId]. */
    fun has(videoId: String): Boolean = fileFor(videoId)?.exists() == true

    /** Keeps [lyrics] for [videoId] under [key], beside what was kept for its other keys. */
    @Synchronized fun write(videoId: String, key: String, lyrics: Lyrics) {
        val d = dir ?: return
        val f = fileFor(videoId) ?: return
        val kept = read(videoId)
        if (kept[key] == lyrics) return
        // Few keys per song: one per sources setting and length; only the latest few are useful.
        val next = LinkedHashMap(kept).apply { remove(key); put(key, lyrics) }
        while (next.size > KEYS_PER_SONG) next.remove(next.keys.first())
        runCatching {
            val tmp = File(d, f.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        trim(d)
    }

    private fun trim(d: File) {
        val files = d.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES + MAX_FILES / 10).forEach { it.delete() }
    }

    private fun fileFor(videoId: String): File? {
        val d = dir ?: return null
        val safe = videoId.replace(UNSAFE, "_")
        val name = if (safe == videoId) safe else "${safe.take(40)}-${Integer.toHexString(videoId.hashCode())}"
        return File(d, "$name.json")
    }

    private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    private const val KEYS_PER_SONG = 4
}
