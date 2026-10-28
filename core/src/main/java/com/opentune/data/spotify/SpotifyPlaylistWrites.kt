package com.opentune.data.spotify

import com.opentune.data.Pills
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.reco.Recommendations
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.IOException

/** Success means Spotify acknowledged the edit, never just a changed local copy. */
object SpotifyPlaylistWrites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val order = Mutex()
    private val pending = HashSet<Pair<String, String>>()

    fun add(localId: String, songs: List<Song>) {
        val cookie = SpotifyWebPlayer.account() ?: run { Pills.error("Connect Spotify to edit this playlist"); return }
        val accepted = synchronized(pending) {
            songs.distinctBy { it.videoId }.filter { pending.add(localId to it.videoId) }
        }
        if (accepted.isEmpty()) { Pills.info("These songs are already waiting to save to Spotify"); return }
        Pills.info("Saving to Spotify…")
        scope.launch {
            var confirmed = 0
            try {
                order.withLock {
                    val playlist = LibraryStore.playlist(localId) ?: return@withLock
                    val remote = playlist.spotifyId ?: return@withLock
                    val waiting: (Long) -> Unit = { ms -> Pills.info("Spotify is busy. Retrying your save in ${retryLabel(ms)}…") }
                    val remaining = accepted.filter { song -> playlist.songs.none { it.videoId == song.videoId } }
                    if (remaining.isEmpty()) { Pills.info("Already in ${playlist.name}"); return@withLock }
                    // Spotify accepts up to 100 URIs in a playlist edit. Search each recording,
                    // then submit the batch once instead of one write per track.
                    for (batch in remaining.chunked(100)) {
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
                    }
                    Pills.done("Saved to ${playlist.name} on Spotify")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = failure.message ?: "Couldn't update Spotify. Refresh before retrying."
                Pills.error(if (confirmed > 0) "$confirmed songs saved. $detail" else detail)
            } finally {
                synchronized(pending) { accepted.forEach { pending.remove(localId to it.videoId) } }
            }
        }
    }
}
