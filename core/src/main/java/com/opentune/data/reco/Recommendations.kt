package com.opentune.data.reco

import com.opentune.data.ContentFilter
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.Recommender
import com.opentune.data.spotify.PlaylistImport
import com.opentune.data.spotify.Spotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Songs to follow a song, from the chosen [Recommender]. Spotify's and
 * JioSaavn's picks are names, so each is found on YouTube Music the way an
 * imported playlist is; whatever can't be found is left out. When the
 * engine knows nothing about the song, JioSaavn can fall back to YouTube
 * Music. Selecting Spotify keeps recommendations on Spotify.
 *
 * Whatever the user hid ("Not interested", "Don't play [artist]") is left
 * out here, so every caller — autoplay, smart shuffle, Home's "Because you
 * played" — gets the same filtered picks. So are songs played in the last
 * few hours, as long as enough are left.
 *
 * With Spotify connected, Spotify's picks are its song radio for the
 * listener ([SpotifyRadio.personal]), each matched on YouTube Music to the
 * same length, in Spotify's order. Likes can be synced to Spotify separately;
 * external audio playback is not submitted as Spotify listening history.
 */
object Recommendations {
    private const val TAG = "Recommendations"
    /** How many picks are looked up on YouTube Music per batch; each is one search. */
    private const val MATCHES = 15
    private const val PARALLEL = 4
    /** Fewer found songs than this and YouTube Music's radio is used instead. */
    private const val ENOUGH = 3
    /** Songs played this recently aren't suggested again. */
    private const val RECENT_MS = 3 * 60 * 60_000L
    /** How many places a pick by an artist the listener keeps moves up. */
    private const val LEAN = 4

    data class Result(val songs: List<Song>, val engine: Recommender)

    /** Spotify stays the selected source even when it has no playable matches. */
    suspend fun after(seed: Song, engine: Recommender): Result {
        val result = resolve(engine, preferred = {
            val hidden = LibraryStore.hidden.value
            val picks = picks(seed, engine)
            val tracks = picks.tracks.filterNot { hidden.hidesArtist(it.artists.joinToString(", ")) }
            // Preserve Spotify's ranking instead of moving local favourite artists ahead.
            matched(seed, if (engine == Recommender.SPOTIFY) tracks else lean(tracks), picks.exact)
        }, fallback = { youtube(seed.videoId) })
        return result.copy(songs = notRecent(result.songs))
    }

    internal suspend fun resolve(
        engine: Recommender,
        preferred: suspend () -> List<Song>,
        fallback: suspend () -> List<Song>,
    ): Result {
        if (engine != Recommender.YOUTUBE) {
            val songs = try {
                preferred()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${engine.label} recommendations failed", e)
                emptyList()
            }
            if (engine == Recommender.SPOTIFY || songs.size >= ENOUGH) return Result(songs, engine)
        }
        return Result(fallback(), Recommender.YOUTUBE)
    }

    /** [songs] without those played in the last few hours, unless that leaves too few. */
    internal fun notRecent(
        songs: List<Song>,
        played: List<com.opentune.data.history.PlayRecord> = com.opentune.data.history.History.records.value,
        now: Long = System.currentTimeMillis(),
    ): List<Song> {
        // Newest listen first.
        val recent = played
            .asSequence().takeWhile { now - it.playedAt < RECENT_MS }.mapTo(HashSet()) { it.videoId }
        if (recent.isEmpty()) return songs
        return songs.filterNot { it.videoId in recent }.takeIf { it.size >= ENOUGH } ?: songs
    }

    /**
     * Picks by artists the listener keeps (in their playlists or likes)
     * moved up to [LEAN] places, otherwise in the engine's order.
     */
    private fun lean(picks: List<Spotify.Track>): List<Spotify.Track> {
        val kept = HashSet<String>()
        LibraryStore.liked.value.forEach { kept += artistKey(it.artist) }
        LibraryStore.playlists.value.forEach { p -> p.songs.forEach { kept += artistKey(it.artist) } }
        return leanTowards(picks, kept)
    }

    internal fun leanTowards(picks: List<Spotify.Track>, kept: Set<String>): List<Spotify.Track> {
        if (kept.isEmpty()) return picks
        return picks.withIndex()
            .sortedBy { (i, t) -> if (t.artists.any { artistKey(it) in kept }) i - LEAN - 0.5 else i.toDouble() }
            .map { it.value }
    }

    private fun artistKey(artist: String) = mainArtist(artist).lowercase().filter { it.isLetterOrDigit() }

    suspend fun youtube(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        LibraryStore.hidden.value.songs(ContentFilter.songs(InnertubeParser.parseWatchQueue(Innertube.next(videoId))))
    }

    /** An engine's picks; [exact] when each comes with its length, to match on YouTube Music. */
    private class Picks(val tracks: List<Spotify.Track>, val exact: Boolean)

    private suspend fun picks(seed: Song, engine: Recommender): Picks {
        val artist = mainArtist(seed.artist)
        return when (engine) {
            Recommender.SPOTIFY -> SpotifyRadio.personal(seed.title, artist, seed.albumName, durationMs(seed.durationText))
                ?.takeIf { it.isNotEmpty() }?.let { Picks(it, exact = true) }
                ?: Picks(SpotifyRadio.radio(seed.title, artist, seed.albumName), exact = false)
            Recommender.JIOSAAVN -> Picks(JioSaavnRadio.radio(seed.title, artist), exact = false)
            Recommender.YOUTUBE -> Picks(emptyList(), exact = false)
        }
    }

    /** "3:25" or "1:02:03" in milliseconds; 0 when unknown. */
    internal fun durationMs(text: String?): Long =
        text?.split(':')?.map { it.trim().toLongOrNull() ?: return 0L }?.fold(0L) { acc, n -> acc * 60 + n }?.times(1000) ?: 0L

    private suspend fun matched(seed: Song, picks: List<Spotify.Track>, exact: Boolean): List<Song> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        // Exact matching turns some down, so a few more are looked up.
        picks.take(if (exact) MATCHES + 5 else MATCHES).map { t ->
            async { gate.withPermit {
                try { PlaylistImport.match(t, exactLength = exact) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { null }
            } }
        }.awaitAll()
            .filterNotNull()
            .filter { it.videoId != seed.videoId }
            .distinctBy { it.videoId }
            .let { LibraryStore.hidden.value.songs(ContentFilter.songs(it)) }
    }

    /** "Arijit Singh, Shreya Ghoshal" and "A & B" as their first name, which lookups match best. */
    fun mainArtist(artist: String): String =
        artist.substringBefore(",").substringBefore(" & ").substringBefore(" feat").substringBefore(" x ").trim()
}
