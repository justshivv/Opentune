package com.opentune.data.reco

import com.opentune.data.ContentFilter
import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
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
 * engine knows nothing about the song, YouTube Music's radio steps in, so
 * the music never stops for want of a match.
 */
object Recommendations {
    private const val TAG = "Recommendations"
    /** How many picks are looked up on YouTube Music per batch; each is one search. */
    private const val MATCHES = 15
    private const val PARALLEL = 4
    /** Fewer found songs than this and YouTube Music's radio is used instead. */
    private const val ENOUGH = 3

    data class Result(val songs: List<Song>, val engine: Recommender)

    /** Up next after [seed], from [engine], falling back to YouTube Music. */
    suspend fun after(seed: Song, engine: Recommender): Result {
        if (engine != Recommender.YOUTUBE) {
            val songs = try {
                matched(seed, picks(seed, engine))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${engine.label} picks for ${seed.title} failed", e)
                emptyList()
            }
            if (songs.size >= ENOUGH) return Result(songs, engine)
            Log.d(TAG, "${engine.label} found ${songs.size} for ${seed.title}; using YouTube Music")
        }
        return Result(youtube(seed.videoId), Recommender.YOUTUBE)
    }

    suspend fun youtube(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        ContentFilter.songs(InnertubeParser.parseWatchQueue(Innertube.next(videoId)))
    }

    private suspend fun picks(seed: Song, engine: Recommender): List<Spotify.Track> {
        val artist = mainArtist(seed.artist)
        return when (engine) {
            Recommender.SPOTIFY -> SpotifyRadio.radio(seed.title, artist, seed.albumName)
            Recommender.JIOSAAVN -> JioSaavnRadio.radio(seed.title, artist)
            Recommender.YOUTUBE -> emptyList()
        }
    }

    private suspend fun matched(seed: Song, picks: List<Spotify.Track>): List<Song> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        picks.take(MATCHES).map { t ->
            async { gate.withPermit { runCatching { PlaylistImport.match(t) }.getOrNull() } }
        }.awaitAll()
            .filterNotNull()
            .filter { it.videoId != seed.videoId }
            .distinctBy { it.videoId }
            .let { ContentFilter.songs(it) }
    }

    /** "Arijit Singh, Shreya Ghoshal" and "A & B" as their first name, which lookups match best. */
    internal fun mainArtist(artist: String): String =
        artist.substringBefore(",").substringBefore(" & ").substringBefore(" feat").substringBefore(" x ").trim()
}
