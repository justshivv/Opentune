package com.opentune.data.library

import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.history.PlayRecord
import kotlinx.serialization.Serializable
import java.util.Random

/** Where a smart playlist's songs come from before its rules narrow them down. */
@Serializable
enum class SmartSource {
    /** Everything this phone knows: songs played, liked, downloaded or in a playlist made here. */
    EVERYTHING,
    /** Songs played at least once. */
    HISTORY,
    LIKED,
    /** Finished downloads. */
    DOWNLOADS,
}

@Serializable
enum class SmartSort {
    MOST_PLAYED,
    RECENTLY_PLAYED,
    /** The longest since it was last played first; never played before any of them. */
    LONGEST_UNPLAYED,
    /** The songs that came to this phone most recently first. */
    NEWEST,
    TITLE,
    ARTIST,
    /** A new order every day, kept for the whole day. */
    SHUFFLE,
}

/**
 * What a smart playlist holds. Every rule that is set must hold for a song;
 * one left at its default doesn't narrow anything.
 */
@Serializable
data class SmartRules(
    val from: SmartSource = SmartSource.EVERYTHING,
    /** Played at least this many times: within the last [playedWithinDays] days, or ever when that is null. */
    val minPlays: Int = 0,
    val playedWithinDays: Int? = null,
    /** Not played at all for this many days (songs never played count as not played). */
    val notPlayedForDays: Int? = null,
    /** First played or downloaded within this many days. */
    val newWithinDays: Int? = null,
    val likedOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    /** Downloaded from a lossless source. */
    val losslessOnly: Boolean = false,
    /** Only songs whose artist contains this, ignoring case; blank for any artist. */
    val artist: String = "",
    val sort: SmartSort = SmartSort.MOST_PLAYED,
    /** At most this many songs; null for all of them. */
    val limit: Int? = 50,
)

@Serializable
data class SmartPlaylist(
    val id: String,
    val name: String,
    val rules: SmartRules = SmartRules(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Smart playlists: lists that fill themselves from the rules they were given,
 * worked out on this phone from its history, likes and downloads every time
 * they are shown, so they change as the listening does.
 */
object SmartPlaylists {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** What a smart playlist is worked out from. */
    data class Facts(
        /** Newest first, as [com.opentune.data.history.History.records] keeps it. */
        val history: List<PlayRecord>,
        val liked: List<SongRef>,
        val downloads: Map<String, DownloadEntry>,
        val playlists: List<LocalPlaylist> = emptyList(),
    )

    /** Ready-made rules to start from, with the name each is offered under. */
    val presets: List<Pair<String, SmartRules>> = listOf(
        "On repeat" to SmartRules(SmartSource.HISTORY, minPlays = 3, playedWithinDays = 30, sort = SmartSort.MOST_PLAYED, limit = 50),
        "Forgotten favourites" to SmartRules(SmartSource.LIKED, notPlayedForDays = 90, sort = SmartSort.LONGEST_UNPLAYED, limit = 50),
        "New to you" to SmartRules(SmartSource.HISTORY, newWithinDays = 30, sort = SmartSort.NEWEST, limit = 100),
        "All-time top 100" to SmartRules(SmartSource.HISTORY, sort = SmartSort.MOST_PLAYED, limit = 100),
        "Lossless on this phone" to SmartRules(SmartSource.DOWNLOADS, losslessOnly = true, sort = SmartSort.ARTIST, limit = null),
    )

    private class Known(val ref: SongRef) {
        var plays = 0
        var recentPlays = 0
        var lastPlayed = 0L
        var firstSeen = Long.MAX_VALUE
    }

    /** The songs [rules] pick out of [facts] at [now], in the order they ask for. */
    fun songs(rules: SmartRules, facts: Facts, now: Long = System.currentTimeMillis(), seed: Long = 0): List<SongRef> {
        val known = LinkedHashMap<String, Known>()
        fun know(ref: SongRef) = known.getOrPut(ref.videoId) { Known(ref.copy(setVideoId = null)) }
        val recentSince = rules.playedWithinDays?.let { now - it * DAY_MS }
        for (r in facts.history) {
            val k = know(SongRef(r.videoId, r.title, r.artist, r.thumbnailUrl, r.durationText, r.albumName, r.explicit))
            k.plays++
            if (recentSince == null || r.playedAt >= recentSince) k.recentPlays++
            if (r.playedAt > k.lastPlayed) k.lastPlayed = r.playedAt
            if (r.playedAt < k.firstSeen) k.firstSeen = r.playedAt
        }
        val likedIds = facts.liked.mapTo(HashSet()) { it.videoId }
        facts.liked.forEach { know(it) }
        val done = facts.downloads.filterValues { it.state == DownloadState.DONE }
        for ((id, entry) in done) {
            val k = known.getOrPut(id) { Known(entry.song.copy(setVideoId = null)) }
            if (entry.addedAt < k.firstSeen) k.firstSeen = entry.addedAt
        }
        facts.playlists.forEach { p -> p.songs.forEach { know(it) } }

        val pool = known.values.filter { k ->
            when (rules.from) {
                SmartSource.EVERYTHING -> true
                SmartSource.HISTORY -> k.plays > 0
                SmartSource.LIKED -> k.ref.videoId in likedIds
                SmartSource.DOWNLOADS -> k.ref.videoId in done
            }
        }
        val needle = rules.artist.trim().lowercase()
        val picked = pool.filter { k ->
            val id = k.ref.videoId
            (rules.minPlays <= 0 || k.recentPlays >= rules.minPlays) &&
                (rules.playedWithinDays == null || rules.minPlays > 0 || k.recentPlays > 0) &&
                (rules.notPlayedForDays == null || k.lastPlayed < now - rules.notPlayedForDays * DAY_MS) &&
                (rules.newWithinDays == null || (k.firstSeen != Long.MAX_VALUE && k.firstSeen >= now - rules.newWithinDays * DAY_MS)) &&
                (!rules.likedOnly || id in likedIds) &&
                (!rules.downloadedOnly || id in done) &&
                (!rules.losslessOnly || done[id]?.bits != null) &&
                (needle.isEmpty() || k.ref.artist.lowercase().contains(needle))
        }
        val sorted = when (rules.sort) {
            SmartSort.MOST_PLAYED -> picked.sortedWith(compareByDescending<Known> { it.recentPlays }.thenByDescending { it.lastPlayed })
            SmartSort.RECENTLY_PLAYED -> picked.sortedByDescending { it.lastPlayed }
            SmartSort.LONGEST_UNPLAYED -> picked.sortedBy { it.lastPlayed }
            SmartSort.NEWEST -> picked.sortedByDescending { if (it.firstSeen == Long.MAX_VALUE) Long.MIN_VALUE else it.firstSeen }
            SmartSort.TITLE -> picked.sortedBy { it.ref.title.lowercase() }
            SmartSort.ARTIST -> picked.sortedWith(compareBy<Known> { it.ref.artist.lowercase() }.thenBy { it.ref.title.lowercase() })
            SmartSort.SHUFFLE -> picked.sortedBy { it.ref.videoId }.shuffled(Random(seed xor (now / DAY_MS)))
        }
        val refs = sorted.map { it.ref }
        return rules.limit?.takeIf { it > 0 }?.let { refs.take(it) } ?: refs
    }
}
