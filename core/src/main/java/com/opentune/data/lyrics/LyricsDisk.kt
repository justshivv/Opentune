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

    /** When each song's kept lyrics were last asked about again, by video id (see [LyricsRepository.lyricsUpdates]). */
    private val checked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var checkedRead = false

    /** When [videoId]'s kept lyrics were last asked about again; 0 when never. */
    fun checkedAt(videoId: String): Long {
        readChecked()
        return checked[videoId] ?: 0L
    }

    /** [videoId]'s kept lyrics were asked about again [at] this time. */
    @Synchronized fun markChecked(videoId: String, at: Long = System.currentTimeMillis()) {
        readChecked()
        checked[videoId] = at
        if (checked.size > MAX_CHECKED) {
            checked.entries.sortedBy { it.value }.take(checked.size - MAX_CHECKED).forEach { checked.remove(it.key) }
        }
        val f = checkedFile() ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(checked.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    @Synchronized private fun readChecked() {
        if (checkedRead) return
        val f = checkedFile() ?: return
        checkedRead = true
        if (!f.exists()) return
        runCatching {
            f.readLines().forEach { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { checked.putIfAbsent(line.substring(0, tab), it) }
            }
        }
    }

    /** Not a .json file, so [trim] never takes it for a song's. */
    private fun checkedFile(): File? = dir?.let { File(it, "rechecked.tsv") }

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
    private const val MAX_CHECKED = 2_000
}
