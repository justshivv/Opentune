package com.opentune.playback

import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song

/**
 * When to extend the queue with radio, and with what. Kept free of Android
 * types so the rules can be tested without a device.
 */
object Autoplay {

    /**
     * How close to the end of the queue playback gets before more radio is
     * fetched. One track of slack means the fetch normally finishes while the
     * second-to-last track is still playing, so the hand-off is gapless.
     */
    const val TRACKS_BEFORE_END = 1

    /**
     * @param remaining tracks still to play after the current one, in play
     *   order (which differs from queue order while shuffle is on).
     */
    fun shouldExtend(remaining: Int, queueSize: Int): Boolean =
        queueSize > 0 && remaining <= TRACKS_BEFORE_END

    /**
     * The radio tracks worth appending: anything not already queued, once
     * each. The radio for a track always lists that track first, and
     * neighbouring seeds overlap heavily, so most of a second fetch is
     * already in the queue.
     */
    fun newTracks(queuedIds: Set<String>, radio: List<Song>): List<Song> =
        radio.filter { it.videoId !in queuedIds }.distinctBy { it.videoId }

    /** How many songs the next radio is based on, the last queued one included. */
    const val MAX_SEEDS = 3

    /** How far back through the queue the other seeds can come from. */
    const val SEED_WINDOW = 12

    /**
     * The songs to base more radio on: the last one queued, then earlier ones
     * walking back through the end of the queue, one per artist, so a queue
     * that moved between artists carries on from all of them, not only the
     * last. [usable] says which songs have radio at all.
     */
    fun seeds(queue: List<Song>, usable: (Song) -> Boolean): List<Song> {
        val last = queue.lastOrNull()?.takeIf(usable) ?: return emptyList()
        val picked = mutableListOf(last)
        val artists = mutableSetOf(artistKey(last))
        for (song in queue.dropLast(1).takeLast(SEED_WINDOW).asReversed()) {
            if (picked.size >= MAX_SEEDS) break
            if (!usable(song) || picked.any { it.videoId == song.videoId }) continue
            if (artists.add(artistKey(song))) picked += song
        }
        return picked
    }

    /**
     * One list from the radios of [seeds]' songs, [radios] in the same order,
     * the last song's first. A song scores for how high it sits in each radio
     * (the last song's radio counts most), more when several radios agree on
     * it, and a little more when its artist is already in [queue]. Then no
     * artist plays twice in a row where another can go between.
     */
    fun blend(radios: List<List<Song>>, queue: List<Song>): List<Song> {
        if (radios.size <= 1) return radios.firstOrNull().orEmpty()
        val known = queue.mapTo(HashSet()) { artistKey(it) }
        val scores = LinkedHashMap<String, Double>()
        val seen = HashMap<String, Int>()
        val songs = HashMap<String, Song>()
        radios.forEachIndexed { r, radio ->
            val weight = if (r == 0) 1.0 else OTHER_SEED_WEIGHT
            val list = radio.distinctBy { it.videoId }
            list.forEachIndexed { i, song ->
                songs.putIfAbsent(song.videoId, song)
                val agreeing = seen.merge(song.videoId, 1, Int::plus)!! - 1
                scores[song.videoId] = (scores[song.videoId] ?: 0.0) + weight * (1.0 - i.toDouble() / list.size) + agreeing * AGREE_BONUS
            }
        }
        val ranked = scores.entries
            .sortedByDescending { (id, score) -> score + if (artistKey(songs.getValue(id)) in known) FAMILIAR_BONUS else 0.0 }
            .map { songs.getValue(it.key) }
        return spreadArtists(ranked)
    }

    /** [ranked] in order, except a song by the artist just placed waits for the next song by someone else. */
    internal fun spreadArtists(ranked: List<Song>): List<Song> {
        val out = ArrayList<Song>(ranked.size)
        val waiting = ArrayDeque<Song>()
        for (song in ranked) {
            if (out.isNotEmpty() && artistKey(out.last()) == artistKey(song)) {
                waiting += song
                continue
            }
            out += song
            val next = waiting.firstOrNull { artistKey(it) != artistKey(song) }
            if (next != null) {
                waiting.remove(next)
                out += next
            }
        }
        out += waiting
        return out
    }

    // A song with no artist named is nobody else's.
    private fun artistKey(song: Song) = HiddenList.normalize(HiddenList.mainArtist(song.artist)).ifEmpty { "#" + song.videoId }

    private const val OTHER_SEED_WEIGHT = 0.6
    private const val AGREE_BONUS = 0.25
    private const val FAMILIAR_BONUS = 0.15
}
