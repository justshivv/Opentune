package com.opentune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.data.together.Together
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.opentune.data.model.artworkAt

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
}

/** Playback state and actions shared by every screen, plus the current track's lyrics. */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    val player = PlayerConnection(application)

    private val artwork = com.opentune.data.covers.PlaybackArtwork()
    /** A sharper catalogue cover found for the playing track, by its id. */
    private val betterCover = MutableStateFlow<Pair<String, String>?>(null)
    val currentSong = combine(player.currentSong, betterCover) { song, better ->
        song?.copy(thumbnailUrl = artwork.choose(song, better?.takeIf { it.first == song.videoId }?.second)) ?: run { artwork.clear(); null }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Apple's animated cover and Spotify's Canvas found for the playing track, by its id. */
    private val motionFound = MutableStateFlow<Pair<String, com.opentune.data.covers.AppleMotionArt.Motion>?>(null)
    private val canvasFound = MutableStateFlow<Pair<String, String>?>(null)
    /** The animated cover to loop in the player's cover; null when none, or turned off since. */
    val coverVideo = combine(player.currentSong, motionFound, AppSettings.ui) { song, found, ui ->
        found?.takeIf { song != null && it.first == song.videoId && ui.animatedCovers && !ui.reduceAnimation }?.second
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    /** The Canvas to loop behind the player; null when none, or turned off or disconnected since. */
    val canvasVideo = combine(player.currentSong, canvasFound, AppSettings.ui, com.opentune.ui.spotify.SpotifyConnection.connected) { song, found, ui, connected ->
        found?.takeIf { song != null && it.first == song.videoId && ui.spotifyCanvas && connected && !ui.reduceAnimation }?.second
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    val queue = player.queue
    val currentIndex = player.currentIndex
    val upNext = player.upNext
    val hasNext = player.hasNext
    val isPlaying = player.isPlaying
    val isBuffering = player.isBuffering
    val loadingMessage = player.loadingMessage
    val shuffleEnabled = player.shuffleEnabled
    val shuffleMode = player.shuffleMode
    val smartShuffleAvailable = player.smartShuffleAvailable
    val repeatMode = player.repeatMode
    val durationMs = player.durationMs
    val playbackError = player.error
    val source = player.source
    val audioFormat = player.audioFormat
    /** Fires on each seek and each silence skipped, for the lyrics clock to take a jump cleanly. */
    val seeks = player.jumps

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    val lyrics = _lyrics.asStateFlow()

    init {
        Together.attach(player)
        viewModelScope.launch { player.connect() }
        viewModelScope.launch { watchLyrics() }
        viewModelScope.launch {
            combine(player.currentSong, player.queue, player.upNext) { current, queue, next ->
                listOfNotNull(current) + next.take(2).mapNotNull { queue.getOrNull(it) }
            }.distinctUntilChanged().collectLatest { songs ->
                kotlinx.coroutines.coroutineScope {
                    songs.forEach { song -> launch { warmArtwork(song.thumbnailUrl) } }
                }
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Reading the cookie starts the WebView engine: off the main thread, and not at app start.
            com.opentune.ui.spotify.SpotifyConnection.refresh()
            com.opentune.ui.spotify.SpotifyConnection.syncPlaylists()
            player.currentSong.distinctUntilChangedBy { it?.videoId }.collectLatest { song ->
                if (song == null || !com.opentune.data.covers.AlbumCovers.wants(song)) return@collectLatest
                val ui = AppSettings.ui.value
                // Clips are only fetched where they may play: motion allowed, and off mobile data when asked.
                val moving = !ui.reduceAnimation && !(ui.animatedCoversWifiOnly && AppSettings.onMeteredNetwork())
                val motion = moving && ui.animatedCovers
                val cookie = if (moving && ui.spotifyCanvas) com.opentune.ui.spotify.SpotifyConnection.cookie() else null
                if (!ui.albumCovers && !motion && cookie == null) return@collectLatest
                // Apple, Deezer and Spotify are matched on the exact length, so wait for the player's.
                val duration = withTimeoutOrNull(20_000) {
                    player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second
                } ?: 0L
                kotlinx.coroutines.coroutineScope {
                    if (ui.albumCovers) launch {
                        com.opentune.data.covers.AlbumCovers.coverFor(song, duration)?.let {
                            // Keep the visible cover until its replacement is decoded and cached.
                            if (warmArtwork(it)) betterCover.value = song.videoId to it
                        }
                    }
                    if (motion && duration > 0) launch {
                        com.opentune.data.covers.AppleMotionArt.forSong(song, duration)?.let { motionFound.value = song.videoId to it }
                    }
                    if (cookie != null && duration > 0) launch {
                        try {
                            com.opentune.data.spotify.SpotifyWebPlayer.canvasFor(song, duration, cookie)?.let { canvasFound.value = song.videoId to it }
                        } catch (e: com.opentune.data.spotify.SpotifyWebPlayer.SignedOut) {
                            com.opentune.ui.spotify.SpotifyConnection.lost(e.message)
                        } catch (e: java.io.IOException) {
                            com.opentune.data.DebugLog.w("Canvas", "no canvas for ${song.videoId}", e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun warmArtwork(url: String?): Boolean {
        if (url == null) return false
        val app = getApplication<Application>()
        val loader = coil3.SingletonImageLoader.get(app)
        return withTimeoutOrNull(5_000) {
            kotlinx.coroutines.coroutineScope {
                val small = launch {
                    loader.execute(coil3.request.ImageRequest.Builder(app)
                        .data(url.artworkAt(com.opentune.data.model.ROW_ART_PX)).size(com.opentune.data.model.ROW_ART_PX).build())
                }
                val result = loader.execute(coil3.request.ImageRequest.Builder(app)
                    .data(url.artworkAt(com.opentune.data.model.PLAYER_ART_PX)).size(com.opentune.data.model.PLAYER_ART_PX).build())
                small.join()
                result is coil3.request.SuccessResult
            }
        } ?: false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchLyrics() {
        combine(
            // A new title on the same id (a song from an add-on matched to an upload) is a new song too.
            currentSong.distinctUntilChangedBy { it?.let { s -> Triple(s.videoId, s.title, s.artist) } },
            AppSettings.lyrics.map { Triple(it.ordered, it.preferWordSynced, it.chosen) }.distinctUntilChanged(),
        ) { song, _ -> song }.collectLatest { song ->
            if (song == null) return@collectLatest
            // A station has no fixed song to look lyrics up for.
            if (com.opentune.data.radio.Radio.isRadio(song.videoId)) {
                _lyrics.value = LyricsState.NotFound
                return@collectLatest
            }
            _lyrics.value = LyricsState.Loading
            // LRCLIB matches on duration, and the player's figure is exact
            // where the row's "3:45" is rounded. Without it any version's
            // lyrics can match, so wait for it while the stream starts. It
            // has to be this song's: right after a change the player can
            // still report the last one's, and lyrics found with that are
            // another version's, out of step from the first line.
            val duration = withTimeoutOrNull(20_000) {
                player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second
            } ?: 0L
            // Plain text often means a synced source was busy just then; the
            // repository asks again a little later and sends better lyrics on.
            LyricsRepository.lyricsUpdates(song.videoId, song.title, song.artist, duration).collect { found ->
                _lyrics.value = found?.let(LyricsState::Found) ?: LyricsState.NotFound
            }
        }
    }

    /** In someone else's room, picking a song sends it to the room rather than playing it here. */
    private fun toRoom(song: Song?): Boolean = song != null && Together.room.value?.following == true && Together.suggest(song)

    fun play(song: Song, source: String? = null) { if (!toRoom(song)) player.play(song, source) }
    fun playAll(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null, fromList: Boolean = true) {
        if (!toRoom(songs.getOrNull(if (shuffle) 0 else startIndex))) player.playAll(songs, startIndex, shuffle, source, fromList = fromList)
    }
    /** Shuffles [songs] with similar songs mixed in; in a room, the first song is only suggested, as with shuffle. */
    fun smartShuffle(songs: List<Song>, source: String? = null) {
        if (!toRoom(songs.firstOrNull())) player.playAll(songs, 0, shuffle = true, source = source, smart = true)
    }
    fun playNext(song: Song) { if (!toRoom(song)) player.playNext(song) }
    fun addToQueue(song: Song) { if (!toRoom(song)) player.addToQueue(song) }
    // In someone else's room these go to the host instead (see Together.intercept).
    fun togglePlayPause() { if (!Together.intercept(Together.Control.PlayPause)) player.togglePlayPause() }
    fun skipNext() { if (!Together.intercept(Together.Control.Next)) player.skipNext() }
    fun skipPrevious() { if (!Together.intercept(Together.Control.Previous)) player.skipPrevious() }
    fun toggleShuffle() = player.toggleShuffle()
    /** Off, shuffle, then smart shuffle for a queue from a list; returns the new mode. */
    fun cycleShuffle(): com.opentune.playback.ShuffleMode = player.cycleShuffle()
    fun cycleRepeatMode() = player.cycleRepeatMode()
    fun playQueueItem(index: Int) = player.playQueueItem(index)
    fun removeQueueItem(index: Int) = player.removeQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)
    /** A song on its own; the service follows it with radio. */
    fun startRadio(song: Song) { if (!toRoom(song)) player.play(song, "${song.title} radio") }
    fun seekTo(positionMs: Long) { if (!Together.intercept(Together.Control.Seek(positionMs))) player.seekTo(positionMs) }
    fun positionMs(): Long = player.currentPositionMs()
    fun bufferedPositionMs(): Long = player.bufferedPositionMs()

    override fun onCleared() {
        Together.detach()
        player.disconnect()
        super.onCleared()
    }
}
