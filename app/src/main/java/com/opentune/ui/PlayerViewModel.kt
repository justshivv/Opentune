package com.opentune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.data.connect.Connect
import com.opentune.data.connect.meant
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.opentune.data.model.artworkAt

/** How long lyrics wait for a music video's SponsorBlock stretches before going on without them, until they come. */
private const val GAPS_WAIT_MS = 3_000L

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
    /** The fuller artist credit the catalogue bills for the playing track, by its id. */
    private val fullerArtist = MutableStateFlow<Pair<String, String>?>(null)
    /**
     * Another of the person's devices playing (Connect): the player shows its
     * song, play state and position, and its buttons act there.
     */
    private val remote = Connect.controlling
    val currentSong = combine(player.currentSong, betterCover, fullerArtist, remote) { song, better, fuller, far ->
        far?.state?.let { s -> s.track?.toSong() ?: Song("", com.opentune.i18n.Tr.t("Something of its own"), "", null) }
            ?: song?.copy(
                thumbnailUrl = artwork.choose(song, better?.takeIf { it.first == song.videoId }?.second),
                artist = fuller?.takeIf { it.first == song.videoId }?.second ?: song.artist,
            ) ?: run { artwork.clear(); null }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Apple's animated cover and Spotify's Canvas found for the playing track, by its id. */
    private val motionFound = MutableStateFlow<Pair<String, com.opentune.data.covers.AppleMotionArt.Motion>?>(null)
    private val canvasFound = MutableStateFlow<Pair<String, String>?>(null)
    /** Whether the full player is on screen; AppRoot keeps it current, the activity reads it. */
    private val _playerOpen = MutableStateFlow(false)
    val playerOpen = _playerOpen.asStateFlow()
    fun setPlayerOpen(open: Boolean) { _playerOpen.value = open }
    /** The animated cover to loop in the player's cover; null when none, or turned off since. */
    val coverVideo = combine(player.currentSong, motionFound, AppSettings.ui) { song, found, ui ->
        found?.takeIf { song != null && it.first == song.videoId && ui.animatedCovers && !ui.reduceAnimation }?.second
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    /** The Canvas to loop behind the player; null when none, or turned off or disconnected since. */
    val canvasVideo = combine(player.currentSong, canvasFound, AppSettings.ui, com.opentune.ui.spotify.SpotifyConnection.connected) { song, found, ui, connected ->
        found?.takeIf { song != null && it.first == song.videoId && ui.spotifyCanvas && connected && !ui.reduceAnimation }?.second
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    // With another device playing: its song first, then its Up Next.
    val queue = combine(player.queue, remote) { here, far ->
        far?.state?.let { st -> listOfNotNull(st.track?.toSong()) + st.queue.map { it.toSong() } } ?: here
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, player.queue.value)
    val currentIndex = combine(player.currentIndex, remote) { here, far -> if (far?.state != null) 0 else here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, player.currentIndex.value)
    val upNext = combine(player.upNext, remote) { here, far ->
        far?.state?.let { st -> val first = if (st.track != null) 1 else 0; st.queue.indices.map { it + first } } ?: here
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, player.upNext.value)
    val hasNext = combine(player.hasNext, remote) { here, far -> far != null || here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    /** A song before this one to go back to (else going back starts this one over). */
    val hasPrevious = player.hasPrevious
    // Meant to play there, stalled to load or not, as this phone's own button shows it: by
    // moving, a stall there showed Play, and pressing it sent "play" to a device already playing.
    val isPlaying = combine(player.isPlaying, remote) { here, far -> far?.state?.meant ?: here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val isSounding = player.isSounding
    val isBuffering = combine(player.isBuffering, remote) { here, far -> far?.state?.let { it.meant && !it.playing } ?: (far == null && here) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val loadingMessage = player.loadingMessage
    val shuffleEnabled = combine(player.shuffleEnabled, remote) { here, far -> far?.state?.shuffle ?: here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    /** The playing song is showing its music video (this device only, never a remote one). */
    val videoMode = combine(player.videoMode, remote) { here, far -> far == null && here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    /** The playing song has a music video to switch to (this device only). */
    val videoAvailable = combine(player.videoAvailable, remote) { here, far -> far == null && here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val shuffleMode = combine(player.shuffleMode, remote) { here, far ->
        far?.state?.let { if (it.shuffle) com.opentune.playback.ShuffleMode.ON else com.opentune.playback.ShuffleMode.OFF } ?: here
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.opentune.playback.ShuffleMode.OFF)
    val smartShuffleAvailable = combine(player.smartShuffleAvailable, remote) { here, far -> far == null && here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    val repeatMode = combine(player.repeatMode, remote) { here, far ->
        when (far?.state?.repeat) {
            null -> here
            "one" -> androidx.media3.common.Player.REPEAT_MODE_ONE
            "all" -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, androidx.media3.common.Player.REPEAT_MODE_OFF)
    // The other device's song's length, never this phone's own song's: that one's showed under the other song.
    val durationMs = combine(player.durationMs, remote) { here, far -> far?.state?.let { it.track?.lengthMs ?: 0L } ?: here }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0L)
    val playbackError = player.error
    val source = player.source
    val audioFormat = player.audioFormat
    /** Fires on each seek and each silence skipped, for the lyrics clock to take a jump cleanly. */
    val seeks = player.jumps

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    val lyrics = _lyrics.asStateFlow()

    init {
        Together.attach(player)
        com.opentune.data.connect.Connect.qualityLabel = {
            com.opentune.data.NerdStats.playbackSource.value
                ?.takeIf { it.mediaId == player.currentSong.value?.videoId }
                ?.let { com.opentune.ui.player.qualityStatusLabel(it, player.audioFormat.value, false) }
        }
        com.opentune.data.connect.Connect.attach(player)
        viewModelScope.launch { player.connect() }
        viewModelScope.launch { watchLyrics() }
        viewModelScope.launch {
            combine(player.currentSong, player.queue, player.upNext) { current, queue, next ->
                listOfNotNull(current) + next.take(2).mapNotNull { queue.getOrNull(it) }
            }.distinctUntilChanged().collectLatest { songs ->
                kotlinx.coroutines.coroutineScope {
                    songs.forEach { song -> launch {
                        val saved = com.opentune.data.download.Downloads.artFor(song.videoId)?.toURI()?.toString()
                        val cached = if (AppSettings.ui.value.albumCovers) com.opentune.data.covers.AlbumCovers.cached(song) else null
                        warmArtwork(saved ?: cached ?: song.thumbnailUrl)
                    } }
                }
            }
        }
        viewModelScope.launch {
            // The catalogue's fuller credit can be on record already (the song
            // played before) or land while it plays; both paths land here.
            player.currentSong.distinctUntilChangedBy { it?.videoId }.collectLatest { song ->
                fullerArtist.value = song?.videoId?.let { id ->
                    com.opentune.data.lossless.ExternalStreams.artistLine(id)?.let { id to it }
                }
            }
        }
        viewModelScope.launch {
            com.opentune.data.lossless.ExternalStreams.artistsReady.collect { id ->
                if (player.currentSong.value?.videoId == id) {
                    com.opentune.data.lossless.ExternalStreams.artistLine(id)?.let { fullerArtist.value = id to it }
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
                if (!ui.albumCovers && !motion && !(moving && ui.spotifyCanvas)) return@collectLatest
                // A sharper cover found for this song before: straight away once it's shown to load,
                // rather than after waiting for the song's length.
                if (ui.albumCovers) com.opentune.data.covers.AlbumCovers.cached(song)?.let {
                    if (warmArtwork(it) && player.currentSong.value?.videoId == song.videoId) betterCover.value = song.videoId to it
                }
                // Apple, Deezer and Spotify are matched on the exact length, so wait for the player's.
                val duration = withTimeoutOrNull(20_000) {
                    player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second
                } ?: 0L
                kotlinx.coroutines.coroutineScope {
                    if (ui.albumCovers && betterCover.value?.first != song.videoId) launch {
                        com.opentune.data.covers.AlbumCovers.coverFor(song, duration)?.let {
                            // Keep the visible cover until its replacement is decoded and cached.
                            if (warmArtwork(it)) betterCover.value = song.videoId to it
                        }
                    }
                    if (motion && duration > 0) launch {
                        com.opentune.data.covers.AppleMotionArt.forSong(song, duration)?.let { motionFound.value = song.videoId to it }
                    }
                    if (moving && ui.spotifyCanvas) launch {
                        // Canvas waits for the length without the cap above: on a
                        // slow connection the player may take far longer than
                        // 20s to prepare the song, and a capped wait meant the
                        // Canvas was skipped for good. collectLatest cancels
                        // this wait the moment the song changes, so waiting is
                        // safe. The cookie is read after the wait, and a failed
                        // fetch is retried instead of given up on.
                        val d = player.durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second
                        val cookie = withTimeoutOrNull(30_000) {
                            var found = com.opentune.ui.spotify.SpotifyConnection.cookie()
                            while (found == null) {
                                kotlinx.coroutines.delay(500)
                                found = com.opentune.ui.spotify.SpotifyConnection.cookie()
                            }
                            found
                        } ?: return@launch
                        var attempt = 0
                        while (true) {
                            try {
                                com.opentune.data.spotify.SpotifyWebPlayer.canvasFor(song, d, cookie)?.let { canvasFound.value = song.videoId to it }
                                break
                            } catch (e: com.opentune.data.spotify.SpotifyWebPlayer.SignedOut) {
                                com.opentune.ui.spotify.SpotifyConnection.lost(e.message)
                                break
                            } catch (e: java.io.IOException) {
                                attempt++
                                if (attempt >= 3) {
                                    com.opentune.data.DebugLog.w("Canvas", "no canvas for ${song.videoId}", e)
                                    break
                                }
                                kotlinx.coroutines.delay(3_000)
                            }
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
                com.opentune.lyrics.NowPlayingLyrics.set(song.videoId, null)
                return@collectLatest
            }
            _lyrics.value = LyricsState.Loading
            com.opentune.lyrics.NowPlayingLyrics.set(song.videoId, null)
            // LRCLIB matches on duration, and the player's figure is exact
            // where the row's "3:45" is rounded. Without it any version's
            // lyrics can match, so wait for it while the stream starts. It
            // has to be this song's: right after a change the player can
            // still report the last one's, and lyrics found with that are
            // another version's, out of step from the first line.
            // Apple, Deezer and Spotify are matched on the exact length, so wait for the player's.
            // No giving up after a while: a song restored at app start may not be
            // played until long after it became current, and its length only
            // arrives once it is; a timed-out wait skipped the clips for good,
            // until the song was left and returned to. collectLatest cancels
            // this wait when the song changes, so waiting is safe.
            // A song another device plays (Connect) never plays here, so this player never says
            // its length: that device's own figure for it is taken, or its lyrics stayed loading.
            // This player's exact figure still comes first when it has the song too.
            val duration = combine(player.durationOf, remote) { (id, ms), far ->
                ms.takeIf { id == song.videoId && it > 0 }
                    ?: far?.state?.track?.takeIf { it.id == song.videoId }?.lengthMs?.takeIf { it > 0 } ?: 0L
            }.first { it > 0 }
            // A music video's stretches without the song (a talking intro, a scene with no
            // music) aren't in the lyrics' timing: they're looked up for the song without them
            // and moved past each. Whether "Skip non-music parts" is on or not: on, the player
            // jumps the stretch; off, it plays, and the lyrics wait through it. Only the lookup
            // waits, and only so long; stretches that arrive later place the lyrics again.
            if (!com.opentune.data.isYouTubeId(song.videoId)) {
                showLyrics(song, duration, emptyList())
                return@collectLatest
            }
            kotlinx.coroutines.coroutineScope {
                val lookup = async {
                    com.opentune.data.sponsorblock.SponsorBlock.segments(song.videoId, setOf(com.opentune.data.lyrics.LyricsGaps.CATEGORY))
                        .let { com.opentune.data.lyrics.LyricsGaps.of(it, duration) }
                }
                val early = withTimeoutOrNull(GAPS_WAIT_MS) { lookup.await() }
                if (early != null) {
                    showLyrics(song, duration, early)
                    return@coroutineScope
                }
                val without = launch { showLyrics(song, duration, emptyList()) }
                val late = lookup.await()
                if (late.isNotEmpty()) {
                    without.cancelAndJoin()
                    showLyrics(song, duration, late)
                }
            }
        }
    }

    /** Looks [song]'s lyrics up for the song without [gaps] and shows them placed on the video's clock. */
    private suspend fun showLyrics(song: Song, duration: Long, gaps: List<com.opentune.data.lyrics.LyricsGaps.Gap>) {
        val songMs = com.opentune.data.lyrics.LyricsGaps.songMs(duration, gaps)
        // Plain text often means a synced source was busy just then; the
        // repository asks again a little later and sends better lyrics on.
        // The video's own captions are timed to it, gaps and all: taken onto the song's clock first.
        LyricsRepository.lyricsUpdates(song.videoId, song.title, song.artist, songMs,
            videoTimed = { com.opentune.data.lyrics.LyricsGaps.fromVideo(it, gaps) }).collect { found ->
            val placed = found?.let { com.opentune.data.lyrics.LyricsGaps.onto(it, gaps) }
            _lyrics.value = placed?.let(LyricsState::Found) ?: LyricsState.NotFound
            // The widgets and floating lyrics show these too, rather than looking them up again.
            com.opentune.lyrics.NowPlayingLyrics.set(song.videoId, placed)
        }
    }

    /** In someone else's room, picking a song sends it to the room rather than playing it here. */
    private fun toRoom(song: Song?): Boolean = song != null && Together.room.value?.following == true && Together.suggest(song)

    fun play(song: Song, source: String? = null) { if (!toRoom(song)) player.play(song, source) }
    /** Plays [song] as its music video from the start; in a room, as a song suggested to the host. */
    fun playVideo(song: Song, source: String? = null): Boolean = !toRoom(song) && player.playVideo(song, source)
    fun playAll(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null, fromList: Boolean = true) {
        if (!toRoom(songs.getOrNull(if (shuffle) 0 else startIndex))) player.playAll(songs, startIndex, shuffle, source, fromList = fromList)
    }
    /** Shuffles [songs] with similar songs mixed in; in a room, the first song is only suggested, as with shuffle. */
    fun smartShuffle(songs: List<Song>, source: String? = null) {
        if (!toRoom(songs.firstOrNull())) player.playAll(songs, 0, shuffle = true, source = source, smart = true)
    }
    fun playNext(song: Song) { if (!toRoom(song)) player.playNext(song) }
    fun addToQueue(song: Song) { if (!toRoom(song)) player.addToQueue(song) }
    // While another of the person's devices plays, these act on it (see Connect);
    // in someone else's room they go to the host instead (see Together.intercept).
    fun togglePlayPause() { if (!Connect.togglePlayPause() && !Together.intercept(Together.Control.PlayPause)) player.togglePlayPause() }

    /** Switches the playing song between its audio stream and its music video. */
    fun setVideoMode(on: Boolean) { player.setVideoMode(on) }
    fun dismiss() = player.dismiss()
    fun skipNext() { if (!Connect.skipNext() && !Together.intercept(Together.Control.Next)) player.skipNext() }
    fun skipPrevious() { if (!Connect.skipPrevious() && !Together.intercept(Together.Control.Previous)) player.skipPrevious() }
    fun toggleShuffle() { if (!Connect.toggleShuffle()) player.toggleShuffle() }
    /** Off, shuffle, then smart shuffle for a queue from a list; returns the new mode. */
    fun cycleShuffle(): com.opentune.playback.ShuffleMode {
        val far = remote.value ?: return player.cycleShuffle()
        Connect.toggleShuffle()
        return if (far.state?.shuffle == true) com.opentune.playback.ShuffleMode.OFF else com.opentune.playback.ShuffleMode.ON
    }
    fun cycleRepeatMode() { if (!Connect.cycleRepeat()) player.cycleRepeatMode() }
    /** Entry [index] of [queue] as the other device's Up Next, when one is playing: its place there and its track. */
    private fun remoteEntry(index: Int): Pair<Int, com.opentune.data.connect.CTrack>? {
        val st = remote.value?.state ?: return null
        val at = index - (if (st.track != null) 1 else 0)
        return st.queue.getOrNull(at)?.let { at to it }
    }
    fun playQueueItem(index: Int) {
        if (remote.value != null) { remoteEntry(index)?.let { (at, t) -> Connect.playQueued(at, t) }; return }
        player.playQueueItem(index)
    }
    fun removeQueueItem(index: Int) {
        if (remote.value != null) { remoteEntry(index)?.let { (at, t) -> Connect.removeQueued(at, t) }; return }
        player.removeQueueItem(index)
    }
    // Reordering stays on this device's own queue.
    fun moveQueueItem(from: Int, to: Int) { if (remote.value == null) player.moveQueueItem(from, to) }
    /** A song on its own; the service follows it with radio. */
    fun startRadio(song: Song) { if (!toRoom(song)) player.play(song, com.opentune.i18n.Tr.f("{0} radio", song.title)) }
    fun seekTo(positionMs: Long) { if (!Connect.seekTo(positionMs) && !Together.intercept(Together.Control.Seek(positionMs))) player.seekTo(positionMs) }
    fun positionMs(): Long = remote.value?.let { far ->
        // Counted on from its last report, never past the song's end while the next report is on its way.
        val at = far.positionAt(System.currentTimeMillis())
        far.state?.track?.lengthMs?.takeIf { it > 0 }?.let { at.coerceAtMost(it) } ?: at
    } ?: player.currentPositionMs()
    fun bufferedPositionMs(): Long = player.bufferedPositionMs()

    override fun onCleared() {
        Together.detach()
        Connect.detach()
        player.disconnect()
        super.onCleared()
    }
}