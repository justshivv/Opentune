package com.opentune.data.spotify

import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Brings a Spotify playlist (or the liked songs) over as a playlist on the
 * phone: each track is looked up on YouTube Music by title and artist and
 * the closest catalogue song is kept. Runs on its own scope so leaving the
 * screen doesn't stop it.
 */
object SpotifyImport {
    private const val TAG = "SpotifyImport"
    private const val PARALLEL = 4

    data class Progress(
        val name: String,
        val total: Int,
        val done: Int = 0,
        val matched: Int = 0,
        val missed: List<String> = emptyList(),
        val playlistId: String? = null,
        val finished: Boolean = false,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    val running get() = job?.isActive == true

    /** Imports [name]'s tracks, fetched by [tracks], into a new phone playlist called [name]. */
    fun start(name: String, tracks: suspend () -> List<Spotify.Track>) {
        if (running) return
        _progress.value = Progress(name, total = 0)
        job = scope.launch {
            try {
                val list = tracks()
                _progress.value = Progress(name, total = list.size)
                val gate = Semaphore(PARALLEL)
                val found = list.map { t ->
                    async {
                        val song = gate.withPermit { runCatching { match(t) }.getOrNull() }
                        _progress.update { p ->
                            p?.copy(
                                done = p.done + 1,
                                matched = p.matched + if (song != null) 1 else 0,
                                missed = if (song == null) p.missed + "${t.artists.firstOrNull().orEmpty()} – ${t.title}" else p.missed,
                            )
                        }
                        song
                    }
                }.awaitAll().filterNotNull()
                val id = LibraryStore.createPlaylist(name)
                LibraryStore.addAllToPlaylist(id, found)
                _progress.update { it?.copy(playlistId = id, finished = true) }
            } catch (e: CancellationException) {
                _progress.update { it?.copy(finished = true, error = "Cancelled") }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "import of $name failed", e)
                _progress.update { it?.copy(finished = true, error = e.message ?: "Import failed") }
            }
        }
    }

    fun cancel() = job?.cancel()

    fun dismiss() {
        if (!running) _progress.value = null
    }

    /** The YouTube Music song for [track], or null when nothing is close enough. */
    suspend fun match(track: Spotify.Track): Song? {
        val artist = track.artists.firstOrNull().orEmpty()
        val rows = MusicRepository.search("${track.title} $artist".trim(), SearchFilter.SONGS)
        val candidates = rows.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
        return best(track, candidates)
    }

    /**
     * The candidate whose title and artist match [track], preferring one
     * whose length is within a few seconds. Null when no title matches.
     */
    internal fun best(track: Spotify.Track, candidates: List<Song>): Song? {
        val wantTitle = key(track.title)
        val wantArtists = track.artists.map(::key).filter { it.isNotEmpty() }
        return candidates
            .map { song ->
                val title = key(song.title)
                val artist = key(song.artist)
                var score = 0
                if (title == wantTitle) score += 4 else if (title.contains(wantTitle) || wantTitle.contains(title)) score += 2 else return@map song to -1
                if (wantArtists.any { artist.contains(it) || it.contains(artist) }) score += 3
                val seconds = durationSeconds(song.durationText)
                if (seconds != null && track.durationMs > 0 && kotlin.math.abs(seconds * 1000 - track.durationMs) <= 7_000) score += 2
                song to score
            }
            .filter { it.second >= 5 } // a title and the artist, or a title and the length
            .maxByOrNull { it.second }?.first
    }

    /** Lower-case letters and digits, with "(feat. …)", "- Remastered 2011" and similar taken off. */
    internal fun key(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("""\s*[(\[](?:feat|ft|with|from|remaster|live|radio edit|mono|stereo)[^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+.*(?:remaster|version|edit|mix|live|mono|stereo).*$"""), "")
            .filter { it.isLetterOrDigit() }

    private fun durationSeconds(text: String?): Long? =
        text?.split(':')?.map { it.trim().toLongOrNull() ?: return null }?.fold(0L) { acc, n -> acc * 60 + n }
}
