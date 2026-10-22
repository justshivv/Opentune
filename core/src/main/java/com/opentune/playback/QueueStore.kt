package com.opentune.playback

import android.content.Context
import com.opentune.data.library.SongRef
import com.opentune.data.model.QueueTier
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The queue as it was, so the app reopens on the song you left off at,
 * paused at the same point, with each song's part of the queue (the list,
 * queued by hand, autoplay) and shuffle and repeat as they were. Written by
 * [PlaybackService].
 */
object QueueStore {
    @Serializable
    data class Saved(
        val songs: List<SongRef>,
        val index: Int,
        val positionMs: Long,
        val source: String? = null,
        /** Each song's [QueueTier], in [songs]' order; null when all are the list's. */
        val tiers: List<QueueTier>? = null,
        val shuffle: Boolean = false,
        val repeat: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "queue.json")
    }

    fun load(): Saved? = file?.takeIf { it.exists() }?.let { f ->
        runCatching { json.decodeFromString(Saved.serializer(), f.readText()) }.getOrNull()
    }?.takeIf { it.songs.isNotEmpty() }

    fun save(saved: Saved) {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "queue.json.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), saved.copy(songs = saved.songs.take(MAX_SONGS), tiers = saved.tiers?.take(MAX_SONGS))))
            tmp.renameTo(f)
        }
    }

    private const val MAX_SONGS = 200
}
