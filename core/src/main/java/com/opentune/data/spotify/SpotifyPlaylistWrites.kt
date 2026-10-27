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

/** Explicit playlist edits live beyond the picker; success means Spotify acknowledged them. */
object SpotifyPlaylistWrites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val order = Mutex()

    fun add(localId: String, songs: List<Song>) {
        scope.launch {
            order.withLock {
                val playlist = LibraryStore.playlist(localId) ?: return@withLock
                val remote = playlist.spotifyId ?: return@withLock
                try {
                    val cookie = SpotifyWebPlayer.account() ?: throw IOException("Connect Spotify to edit this playlist")
                    for (song in songs.distinctBy { it.videoId }) {
                        if (LibraryStore.playlist(localId)?.songs?.any { it.videoId == song.videoId } == true) continue
                        val hit = SpotifyWebPlayer.findTrack(song.title, Recommendations.mainArtist(song.artist),
                            song.albumName, Recommendations.durationMs(song.durationText), cookie)
                            ?: throw IOException("Couldn't find the same recording of ${song.title} on Spotify")
                        if (remote == SpotifyLibrary.LIKED_ID) {
                            val answer = SpotifyWebPlayer.query("addToLibrary", cookie, buildJsonObject {
                                putJsonArray("libraryItemUris") { add(JsonPrimitive(hit.uri)) }
                                put("interactionId", JsonNull)
                            })
                            if ((answer["errors"] as? JsonArray)?.isNotEmpty() == true ||
                                (answer["data"] as? JsonObject)?.values?.any { it != JsonNull } != true)
                                throw IOException("Spotify did not confirm saving this song")
                        } else {
                            SpotifyWebPlayer.appendPlaylist(cookie, remote, listOf(hit.uri))
                        }
                        LibraryStore.confirmSpotifyAddition(localId, remote, song)
                    }
                    Pills.done("Saved to ${playlist.name} on Spotify")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Pills.error(failure.message ?: "Couldn't update Spotify. Refresh the playlist before retrying.")
                }
            }
        }
    }
}
