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
            tmp.writeText(json.encodeToString(Saved.serializer(), trimmed(saved)))
            tmp.renameTo(f)
        }
    }

    /**
     * [saved] cut to [max] songs around the one playing, its index moved with them. Cut from
     * the front, a long playlist left past its 200th song reopened on the 200th, at the
     * place reached in another song.
     */
    internal fun trimmed(saved: Saved, max: Int = MAX_SONGS): Saved {
        if (saved.songs.size <= max) return saved
        val index = saved.index.coerceIn(saved.songs.indices)
        // A few of the songs already played stay before it, for Previous; the rest are the ones to come.
        val start = (index - PLAYED_KEPT).coerceIn(0, saved.songs.size - max)
        return saved.copy(
            songs = saved.songs.subList(start, start + max).toList(),
            index = index - start,
            tiers = saved.tiers?.drop(start)?.take(max),
        )
    }

    /** Forgets the saved queue: the next start opens with no player. */
    fun clear() {
        runCatching { file?.delete() }
    }

    private const val MAX_SONGS = 200
    /** Songs already played kept before the current one when a long queue is cut. */
    private const val PLAYED_KEPT = 20
}
