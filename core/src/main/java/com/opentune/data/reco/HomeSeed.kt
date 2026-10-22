package com.opentune.data.reco

import com.opentune.data.ExploreFeed
import com.opentune.data.history.DailyMixes
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.releases.NewReleases

/**
 * A start for Home before there's much listening to go on: Quick picks and
 * Daily Mixes from the artists you follow, the welcome's picks among them.
 * Once history is long enough, Home's own picks and mixes carry it and this
 * adds nothing.
 */
object HomeSeed {
    /** Below this many plays, history is short and followed artists seed Home. */
    const val SHORT_HISTORY = 30
    /** Each seed artist costs two requests, so only the most recent few. */
    const val MAX_ARTISTS = 4
    /** Home's Quick picks shelf leads with a shelf titled like this. */
    const val TITLE_PREFIX = "Because you like "
    private const val MAX_PICKS = 20
    private const val MIX_MIN = 6
    private const val MIX_MAX = 30

    /** One seed artist's songs: their top songs, and YouTube Music's radio off the first. */
    data class Artist(val browseId: String, val name: String, val top: List<Song>, val radio: List<Song>)

    fun isShort(plays: Int) = plays < SHORT_HISTORY

    /** The artists to seed from, the most recently followed first; none once history is long enough. */
    fun artists(followed: List<NewReleases.Followed>, plays: Int): List<NewReleases.Followed> =
        if (!isShort(plays)) emptyList() else followed.sortedByDescending { it.followedAt }.take(MAX_ARTISTS)

    /**
     * Quick picks from the seeds: each artist's top songs in turn, so no
     * one artist fills the first page, then their radios in turn. Null with
     * fewer than four songs, which Home wouldn't lay out as picks.
     */
    fun quickPicks(seeds: List<Artist>, turn: Int = 0): HomeShelf? {
        val woven = (ExploreFeed.weave(seeds.map { it.top }) + ExploreFeed.weave(seeds.map { it.radio })).distinctBy { it.videoId }
        // A refresh draws a different handful from everything the seeds give, in a different order.
        val songs = (if (turn == 0) woven else woven.shuffled(kotlin.random.Random(turn))).take(MAX_PICKS)
        if (songs.size < 4) return null
        val items = songs.map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null, explicit = it.isExplicit == true) }
        return HomeShelf(TITLE_PREFIX + names(seeds.map { it.name }), items, "From artists you follow")
    }

    /**
     * Daily Mixes while history is short: the ones made from listening
     * first, then one for each seed artist (their songs and the radio around
     * them) that no listening mix already leads with.
     */
    fun mixes(fromHistory: List<DailyMixes.Mix>, seeds: List<Artist>, plays: Int): List<DailyMixes.Mix> {
        if (!isShort(plays)) return fromHistory
        val led = fromHistory.mapTo(HashSet()) { it.subtitle.substringBefore(",").trim().lowercase() }
        val seeded = seeds.filter { it.name.lowercase() !in led }.mapNotNull { a ->
            val songs = ExploreFeed.weave(listOf(a.top, a.radio)).distinctBy { it.videoId }.take(MIX_MAX)
            if (songs.size < MIX_MIN) null
            else DailyMixes.Mix("seed:${a.browseId}", "${a.name} mix", "${a.name} and artists like them", songs)
        }
        return fromHistory + seeded
    }

    /** "A", "A and B", "A, B and C", "A, B and 2 more". */
    fun names(names: List<String>): String = when (names.size) {
        0 -> "these artists"
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        3 -> "${names[0]}, ${names[1]} and ${names[2]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
    }
}
