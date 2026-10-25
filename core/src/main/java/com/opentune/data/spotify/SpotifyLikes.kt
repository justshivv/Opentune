package com.opentune.data.spotify

import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import com.opentune.data.reco.Recommendations
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A like in OpenTune saved to the listener's Spotify Liked Songs too, and an
 * unlike taken back out, while Spotify is connected and the setting is on.
 * Spotify's suggestions (its song radio, Daily Mixes, Discover Weekly) learn
 * from what's saved there. Plays aren't sent: a song heard here streamed from
 * YouTube Music, not from Spotify.
 */
object SpotifyLikes {
    private const val TAG = "SpotifyLikes"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One at a time, in the order they were made, so a quick like-unlike ends unliked. */
    private val order = Mutex()

    fun liked(song: Song, liked: Boolean) {
        if (!AppSettings.ui.value.spotifySaveLikes) return
        val cookie = SpotifyWebPlayer.account() ?: return
        if (song.videoId.startsWith("local:") || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch {
            order.withLock {
                runCatching {
                    val artist = Recommendations.mainArtist(song.artist)
                    val hit = SpotifyWebPlayer.findTrack(song.title, artist, song.albumName, Recommendations.durationMs(song.durationText), cookie)
                        ?: return@runCatching Log.d(TAG, "${song.title} isn't on Spotify for certain; not saved there")
                    SpotifyWebPlayer.query(if (liked) "addToLibrary" else "removeFromLibrary", cookie, buildJsonObject {
                        putJsonArray("libraryItemUris") { add(JsonPrimitive(hit.uri)) }
                        if (liked) put("interactionId", null as String?)
                    })
                }.onFailure { Log.w(TAG, "couldn't ${if (liked) "save" else "remove"} ${song.title} on Spotify", it) }
            }
        }
    }
}
