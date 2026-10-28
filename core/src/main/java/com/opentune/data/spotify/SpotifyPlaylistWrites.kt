package com.opentune.data.spotify

import com.opentune.data.Pills
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.reco.Recommendations
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.IOException

/** Success means Spotify acknowledged the edit, never just a changed local copy. */
object SpotifyPlaylistWrites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val order = Mutex()
    private val pending = HashSet<Pair<String, String>>()
    data class SaveKey(val playlistId: String, val videoId: String)
    data class SaveProgress(val message: String, val busy: Boolean = false, val failed: Boolean = false)
    private val _progress = MutableStateFlow<Map<SaveKey, SaveProgress>>(emptyMap())
    val progress = _progress.asStateFlow()

    private fun report(id: String, songs: List<Song>, state: SaveProgress) {
        _progress.update { old ->
            val kept = old.filterValues { !it.busy }.entries.toList().takeLast(256).associate { it.toPair() } + old.filterValues { it.busy }
            kept + songs.associate { SaveKey(id, it.videoId) to state }
        }
    }

    fun add(localId: String, songs: List<Song>) {
        val cookie = SpotifyWebPlayer.account() ?: run {
            report(localId, songs, SaveProgress("Connect Spotify to edit this playlist", failed = true))
            Pills.error("Connect Spotify to edit this playlist")
            return
        }
        val accepted = synchronized(pending) {
            songs.distinctBy { it.videoId }.filter { pending.add(localId to it.videoId) }
        }
        if (accepted.isEmpty()) { Pills.info("These songs are already waiting to save to Spotify"); return }
        report(localId, accepted, SaveProgress("Waiting to save…", busy = true))
        Pills.info("Saving to Spotify…")
        scope.launch {
            var confirmed = 0
            val confirmedIds = HashSet<String>()
            try {
                order.withLock {
                    val playlist = LibraryStore.playlist(localId) ?: throw IOException("This playlist is no longer in your library")
                    val remote = playlist.spotifyId ?: throw IOException("This playlist is no longer linked to Spotify")
                    val remaining = accepted.filter { song -> playlist.songs.none { it.videoId == song.videoId } }
                    confirmedIds += accepted.filter { it !in remaining }.map { it.videoId }
                    report(localId, accepted.filter { it !in remaining }, SaveProgress("Added"))
                    if (remaining.isEmpty()) { Pills.info("Already in ${playlist.name}"); return@withLock }
                    // Spotify accepts up to 100 URIs in a playlist edit. Search each recording,
                    // then submit the batch once instead of one write per track.
                    for (batch in remaining.chunked(100)) {
                        report(localId, batch, SaveProgress("Saving to Spotify…", busy = true))
                        val waiting: (Long) -> Unit = { ms ->
                            val message = "Spotify is busy · retrying in ${retryLabel(ms)}"
                            report(localId, batch, SaveProgress(message, busy = true))
                            Pills.info(message)
                        }
                        if (SpotifyWebPlayer.account() != cookie) throw SpotifyWebPlayer.SignedOut("Spotify account changed. Try saving again.")
                        val matches = batch.map { song ->
                            val hit = SpotifyWebPlayer.findTrack(song.title, Recommendations.mainArtist(song.artist),
                                song.albumName, Recommendations.durationMs(song.durationText), cookie, waiting)
                                ?: throw IOException("Couldn't find the same recording of ${song.title} on Spotify")
                            song to hit.uri
                        }
                        val uris = matches.map { it.second }.distinct()
                        if (remote == SpotifyLibrary.LIKED_ID) {
                            val answer = SpotifyWebPlayer.query("addToLibrary", cookie, buildJsonObject {
                                putJsonArray("libraryItemUris") { uris.forEach { add(JsonPrimitive(it)) } }
                                put("interactionId", JsonNull)
                            }, onRateWait = waiting)
                            if ((answer["errors"] as? JsonArray)?.isNotEmpty() == true ||
                                (answer["data"] as? JsonObject)?.values?.any { it != JsonNull } != true)
                                throw IOException("Spotify did not confirm saving these songs")
                        } else {
                            SpotifyWebPlayer.appendPlaylist(cookie, remote, uris, onRateWait = waiting)
                        }
                        matches.forEach { (song, _) -> LibraryStore.confirmSpotifyAddition(localId, remote, song) }
                        confirmed += matches.size
                        confirmedIds += matches.map { it.first.videoId }
                        report(localId, batch, SaveProgress("Added on Spotify"))
                    }
                    Pills.done("Saved to ${playlist.name} on Spotify")
                }
            } catch (cancelled: CancellationException) {
                report(localId, accepted.filter { it.videoId !in confirmedIds }, SaveProgress("Save interrupted. Refresh the playlist before retrying.", failed = true))
                throw cancelled
            } catch (failure: Exception) {
                val detail = failure.message ?: "Couldn't update Spotify. Refresh before retrying."
                report(localId, accepted.filter { it.videoId !in confirmedIds }, SaveProgress(detail, failed = true))
                Pills.error(if (confirmed > 0) "$confirmed songs saved. $detail" else detail)
            } finally {
                synchronized(pending) { accepted.forEach { pending.remove(localId to it.videoId) } }
            }
        }
    }
}
