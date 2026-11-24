package com.opentune.playback

import android.content.ComponentName
import android.content.Context
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.innertube.VideoQuality
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.QueueTier
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

/**
 * The app's one connection to [PlaybackService], wrapping a [MediaController]
 * behind Compose-friendly state.
 *
 * Everything here is read back from the controller rather than tracked on the
 * side, because the queue also changes from places this class doesn't drive:
 * the service appends autoplay radio and skips tracks that fail, and the
 * notification has its own next/previous buttons.
 */
class PlayerConnection(private val context: Context) {

    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var smartJob: Job? = null

    /** True once the controller is connected and commands will be heard. */
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    /** Queue indices still to play after the current one, in play order. */
    private val _upNext = MutableStateFlow<List<Int>>(emptyList())
    val upNext = _upNext.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    val hasNext = _hasNext.asStateFlow()
    private val _hasPrevious = MutableStateFlow(false)
    /** A song before this one in the queue (going back otherwise only starts this one over). */
    val hasPrevious = _hasPrevious.asStateFlow()

    private val _videoMode = MutableStateFlow(false)
    /** True while the playing song is on its music video instead of its audio stream. */
    val videoMode = _videoMode.asStateFlow()

    private val _videoAvailable = MutableStateFlow(false)
    /** Whether the playing song has a music video it can switch to. */
    val videoAvailable = _videoAvailable.asStateFlow()

    /** The song a video-availability walk is currently running for, if any. */
    private var videoCheckId: String? = null

    init {
        // The switch is a setting (on by default): turning it off takes the
        // switch away at once, and a song playing its video comes back to
        // its audio stream.
        scope.launch {
            AppSettings.playback.collect { playback ->
                if (!playback.videoModeEnabled) {
                    _videoAvailable.value = false
                    if (_videoMode.value) setVideoMode(false)
                }
            }
        }
    }

    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    /** The playing stream's picture size, so the video surface can fit itself. */
    val videoSize = _videoSize.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled = _shuffleEnabled.asStateFlow()

    private val _shuffleMode = MutableStateFlow(ShuffleMode.OFF)
    /** What the player's shuffle button shows; see [cycleShuffle]. */
    val shuffleMode = _shuffleMode.asStateFlow()

    private val _smartShuffleAvailable = MutableStateFlow(false)
    /** Whether the queue came from a playlist or album, the only kind smart shuffle applies to. */
    val smartShuffleAvailable = _smartShuffleAvailable.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()
    private val _durationOf = MutableStateFlow<Pair<String?, Long>>(null to 0L)
    /**
     * The duration together with the item it belongs to. Just after a song
     * changes, [durationMs] can still hold the last song's for a moment;
     * this says whose it is.
     */
    val durationOf = _durationOf.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    /** The app player's own volume, 0 to 1, apart from the device's. */
    val volume = _volume.asStateFlow()

    private val _isSounding = MutableStateFlow(false)
    /** Really sounding right now — playing and ready, not merely meant to play while a stream loads. The Canvas follows this, so it waits frozen until the song itself starts. */
    val isSounding = _isSounding.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()
    private val _loadingMessage = MutableStateFlow<String?>(null)
    val loadingMessage = _loadingMessage.asStateFlow()
    private var bufferingSince = 0L
    private var connectionWatch: Job? = null

    private fun refreshLoading(player: Player) {
        val stalled = player.playbackState == Player.STATE_BUFFERING && PlaybackRequests.soundingPosition == null
        val online = AppSettings.isOnline()
        val now = android.os.SystemClock.elapsedRealtime()
        if (stalled && player.playWhenReady && online) {
            if (bufferingSince == 0L) bufferingSince = now
        } else bufferingSince = 0L
        // Only a stall that lasts shows: a moment's rebuffer never flashes the spinner. Around a
        // quality upgrade it has to last longer, as the switch rebuffers the silent player and
        // this side hears of the player's state a moment late, so the spinner came and went.
        val switching = PlaybackRequests.switchingStream || now - PlaybackRequests.switchEndedAt < SWITCH_SETTLE_MS
        val buffering = stalled && bufferingSince != 0L && now - bufferingSince >= if (switching) SWITCH_STALL_MS else STALL_MS
        val waiting = player.playWhenReady && (buffering || player.playerError != null)
        _isBuffering.value = waiting && buffering && online
        _loadingMessage.value = when {
            waiting && !online -> "Offline · waiting for connection"
            bufferingSince != 0L && now - bufferingSince >= 20_000 -> "Taking longer than usual · tap Pause to stop"
            _isBuffering.value -> "Buffering"
            player.playerError != null -> "Playback stopped · tap Play to retry"
            else -> null
        }
    }

    /** Whether playback is meant to be running, buffering or not. */
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady = _playWhenReady.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Where the queue came from, for the player's "Playing from" line. */
    private val _source = MutableStateFlow<String?>(null)
    val source = _source.asStateFlow()

    /** The selected audio stream, for stats for nerds. */
    private val _audioFormat = MutableStateFlow<AudioFormatInfo?>(null)
    val audioFormat = _audioFormat.asStateFlow()

    /** Fires each time the position is moved by hand, here or from the notification. */
    private val _seeks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val seeks = _seeks.asSharedFlow()

    /**
     * Every jump of the position the lyrics have to take at once: the seeks
     * above, and the gaps Skip silence cuts out. Without the second, a skipped
     * silence reads as the song speeding up, and the words run ahead after it.
     */
    private val _jumps = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val jumps = _jumps.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _videoSize.value = videoSize
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            _audioFormat.value = null
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && beforeShuffle != null) controller?.let(::shuffleArrivals)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TRACKS_CHANGED)) onTracksChanged(player.currentTracks)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) _seeks.tryEmit(Unit)
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) _jumps.tryEmit(Unit)
        }

        override fun onPlayerError(error: PlaybackException) {
            val mime = controller?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)?.sampleMimeType
            val dolby = mime != null && mime in DOLBY_MIMES
            _error.value = when (error.errorCode) {
                // A Dolby stream (Atmos, Digital Plus) on a phone with no Dolby decoder, and no receiver to pass it to.
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                    -> if (dolby) dolbyUnplayable(mime)
                else error.cause?.message ?: error.message
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "This audio copy could not be read. Saved downloads will be repaired when connected."
                else -> error.cause?.message ?: error.message
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val format = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat) }
            _audioFormat.value = format?.let {
                val flac = LosslessAudioPolicy.streamInfo(it)
                AudioFormatInfo(
                    codec = (it.codecs ?: it.sampleMimeType?.substringAfter('/'))?.uppercase(),
                    mime = it.sampleMimeType,
                    bitrateKbps = it.bitrate.takeIf { b -> b > 0 }?.div(1000),
                    sampleRateHz = flac?.rate ?: it.sampleRate.takeIf { r -> r > 0 },
                    channels = flac?.channels ?: it.channelCount.takeIf { c -> c > 0 },
                    bitDepth = flac?.bits ?: when (it.pcmEncoding) {
                        C.ENCODING_PCM_8BIT -> 8
                        C.ENCODING_PCM_16BIT -> 16
                        C.ENCODING_PCM_24BIT -> 24
                        C.ENCODING_PCM_32BIT -> 32
                        C.ENCODING_PCM_FLOAT -> null
                        else -> null
                    },
                )
            }
        }
    }

    suspend fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        // A session that turns the app away (or can't be started) is logged, not a crash.
        val newController = try {
            MediaController.Builder(context, sessionToken).buildAsync().await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.opentune.data.DebugLog.w("PlayerConnection", "Couldn't connect to the player", e)
            return
        }
        newController.addListener(listener)
        controller = newController
        when (val out = videoView) {
            is TextureView -> newController.setVideoTextureView(out)
            is Surface -> newController.setVideoSurface(out)
        }
        refresh(newController)
        listener.onTracksChanged(newController.currentTracks)
        _connected.value = true
        connectionWatch?.cancel()
        connectionWatch = scope.launch {
            // A mix starting or ending changes the song shown.
            launch { MixState.showing.collect { if (controller === newController) refresh(newController) } }
            while (controller === newController) {
                // Only while the app is on screen: this drives the buffering spinner, and with
                // the screen off it kept the phone working four times a second all listen long.
                if (!AppOnScreen.shown.value) { AppOnScreen.shown.first { it }; continue }
                refreshLoading(newController)
                kotlinx.coroutines.delay(LOADING_TICK_MS)
            }
        }
    }

    fun disconnect() {
        scope.coroutineContext.cancelChildren()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _connected.value = false
    }

    private fun refresh(player: Player) {
        _queue.value = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
        // A mix under way shows the song coming in, from the moment it starts.
        val mixing = MixState.showing.value?.takeIf { it.index in 0 until player.mediaItemCount }
        _currentIndex.value = mixing?.index ?: player.currentMediaItemIndex
        _currentSong.value = (mixing?.item ?: player.currentMediaItem)?.toSong()
        _upNext.value = player.upcomingPlayOrder().drop(1).let { up -> if (mixing != null) up.dropWhile { it != mixing.index }.drop(1) else up }
        _hasNext.value = player.hasNextMediaItem()
        _hasPrevious.value = player.hasPreviousMediaItem()
        _shuffleEnabled.value = player.shuffleModeEnabled
        publishShuffle(player)
        _repeatMode.value = player.repeatMode
        _volume.value = player.volume
        // The same song swapped onto another stream (a quality switch, a recovery) has no
        // length until the new one loads; it keeps the one it had, so the seek bar and
        // times don't empty to "--:--" while it plays on.
        val id = mixing?.item?.mediaId ?: player.currentMediaItem?.mediaId
        val duration = (if (mixing != null) mixing.durationMs().takeIf { it > 0 } else player.duration.takeIf { it > 0 })
            ?: _durationOf.value.takeIf { id != null && it.first == id }?.second
            ?: 0L
        _durationMs.value = duration
        _durationOf.value = id to duration
        // Video mode follows the item's own address, so a swap the service makes
        // (an upgrade, a recovery) settles the toggle on what is actually playing.
        // A merged video source publishes its first side's item, whose address
        // is the picture-only one, so both video addresses count.
        _videoMode.value = (mixing?.item ?: player.currentMediaItem)?.localConfiguration?.uri
            ?.let { isVideoUri(it) || isVideoOnlyUri(it) } == true
        _videoAvailable.value = AppSettings.playback.value.videoModeEnabled && id?.let { StreamResolver.hasVideo(it) } == true
        // A song served without its response ever being parsed (from the cache,
        // a download) has no answer yet: ask, and light the toggle if it has one.
        if (id != null && !_videoAvailable.value && videoCheckId != id && AppSettings.playback.value.videoModeEnabled) {
            videoCheckId = id
            scope.launch {
                // A song restored paused after a relaunch can be asked about
                // before the probe's engine has warmed up: that ask comes
                // back unanswered, which is not a no. Keep asking, a few
                // seconds apart, while the song stays current and the answer
                // stays unknown, so the switch still arrives on its own.
                var tries = 0
                while (_currentSong.value?.videoId == id && !StreamResolver.videoKnown(id) && tries < 6) {
                    if (StreamResolver.videoAvailable(id)) {
                        if (_currentSong.value?.videoId == id && AppSettings.playback.value.videoModeEnabled) _videoAvailable.value = true
                        break
                    }
                    tries++
                    if (!StreamResolver.videoKnown(id)) kotlinx.coroutines.delay(5_000)
                }
                if (videoCheckId == id) videoCheckId = null
            }
        }
        _videoSize.value = player.videoSize
        // Buffering on the way, with playback meant to go on (a quality switch
        // loading its stream, a stall), still counts as playing: the button
        // flipped to play and back for that moment at every upgrade.
        _isPlaying.value = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        _isSounding.value = player.isPlaying
        // A quality switch rebuffers the main player while the song plays on (another player
        // sounding it, or a moment's silence between streams): no spinner on the button for that.
        refreshLoading(player)
        _playWhenReady.value = player.playWhenReady
        // A track that started, or a recovery the service made on its own,
        // makes the last error stale.
        if (player.isPlaying) _error.value = null
    }

    /**
     * Starts [song] on its own, replacing the queue but for the songs queued
     * by hand, which come next. The service fills in radio after them.
     */
    /**
     * Plays [song] as its music video from the start (a video card): the service puts it onto its
     * picture as it begins. As a plain [play] when music videos are turned off in Settings.
     */
    fun playVideo(song: Song, source: String? = null) {
        if (AppSettings.playback.value.videoModeEnabled) PlaybackRequests.startAsVideo(song.videoId)
        play(song, source)
    }

    fun play(song: Song, source: String? = null) {
        val controller = controller ?: return
        _error.value = null
        _source.value = source
        endSmartShuffle()
        SmartShuffle.clear()
        val kept = handQueued(controller)
        beforeShuffle = null
        beforeSmart = null
        controller.setMediaItems((listOf(song.copy(queueTier = QueueTier.CONTEXT)) + kept).map { it.toMediaItem() }, 0, 0L)
        controller.prepare()
        controller.play()
    }

    private fun tiers(controller: MediaController) = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).queueTier }

    /** The songs queued by hand still to come, which starting something else keeps. */
    private fun handQueued(controller: MediaController): List<Song> =
        if (controller.mediaItemCount == 0) emptyList()
        else QueueTimeline.upcomingUser(tiers(controller), controller.currentMediaItemIndex).map { controller.getMediaItemAt(it).toSong() }

    /**
     * Replaces the queue with [songs], starting at [startIndex]. With
     * [shuffle], the list is shuffled up front (so turning shuffle off later
     * keeps the shuffled order) and playback starts from its first track.
     *
     * [fromList] says the songs are a playlist, album or other list the user
     * opened, which smart shuffle can be turned on for; false for a radio
     * that only arrives as a list. [smart] shuffles with similar songs
     * mixed in, slotted into the queue as soon as they are found.
     */
    fun playAll(
        songs: List<Song>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        source: String? = null,
        startPositionMs: Long = 0L,
        fromList: Boolean = true,
        smart: Boolean = false,
        /**
         * Whether the songs queued by hand here come next, as they do for anything started
         * here. Not for a queue that is another's whole session (a device handing its music
         * over, a room): its own Up Next is already in [songs].
         */
        keepQueued: Boolean = true,
    ) {
        val controller = controller ?: return
        if (songs.isEmpty()) return
        _error.value = null
        _source.value = source
        endSmartShuffle()
        val origin = if (fromList) SmartShuffle.startList(songs) else { SmartShuffle.clear(); null }
        val mixing = smart && origin != null && origin.canMix
        val shuffled = shuffle || smart
        val seed = Random.nextLong()
        // Smart shuffle starts on the list alone, in the order the mix will
        // have, so the first song plays at once; the picks are slotted in later.
        val list = songs.map { it.copy(queueTier = QueueTier.CONTEXT) }
        val ordered = when {
            mixing -> SmartShuffle.mix(list, emptyList(), LibraryStore.hidden.value, seed)
            shuffled -> list.shuffled()
            else -> list
        }.let { o ->
            // Songs queued by hand come right after the first song. Smart shuffle
            // slots its picks by position, so it starts on the list alone.
            val kept = if (mixing || !keepQueued) emptyList() else handQueued(controller)
            if (kept.isEmpty()) o else {
                val at = (if (shuffled) 0 else startIndex.coerceIn(o.indices)) + 1
                o.take(at) + kept + o.drop(at)
            }
        }
        controller.shuffleModeEnabled = false
        val startAt = if (shuffled) 0 else startIndex.coerceIn(songs.indices)
        // Each song keeps its place in the list, so an album played in its order is told apart
        // from one shuffled (see albumRunsOn).
        val places = if (fromList) songs.withIndex().associate { it.value.videoId to it.index } else emptyMap()
        val landing = setQueue(controller, ordered, startAt, startPositionMs.coerceAtLeast(0L), places)
        controller.prepare()
        controller.play()
        if (mixing) startSmartShuffle(origin, songs, seed, landing)
    }

    /** Stamps each queue hand-over; a newer playAll supersedes one still landing. */
    private var queueStamp = 0

    /**
     * Hands [ordered] to the player in chunks: the head — with the start
     * song in it — goes up at once, so playback begins without waiting for
     * a long list to cross to the playback service, and the rest follows
     * behind it. However long the list, the first song starts the same.
     */
    private fun setQueue(controller: MediaController, ordered: List<Song>, startIndex: Int, startPositionMs: Long, places: Map<String, Int> = emptyMap()): Job? {
        fun item(song: Song) = song.toMediaItem().let { m -> places[song.videoId]?.let(m::atListPosition) ?: m }
        val stamp = ++queueStamp
        // A new queue: a shuffle turned on for the last one has nothing to put back.
        beforeShuffle = null
        beforeSmart = null
        val headSize = maxOf(QUEUE_CHUNK, startIndex + 1)
        val head = ordered.take(headSize)
        controller.setMediaItems(head.map(::item), startIndex.coerceIn(head.indices), startPositionMs)
        if (ordered.size <= head.size) return null
        return scope.launch {
            var at = head.size
            while (at < ordered.size) {
                if (stamp != queueStamp) return@launch
                // The queue was cleared or replaced meanwhile (a dismiss, a
                // radio): stop landing instead of refilling it.
                if (controller.mediaItemCount < at) return@launch
                val chunk = ordered.subList(at, minOf(at + QUEUE_CHUNK, ordered.size))
                controller.addMediaItems(at, chunk.map(::item))
                at += chunk.size
                kotlinx.coroutines.delay(QUEUE_CHUNK_MS)
            }
        }
    }

    /** Switches the playing song between its audio stream and its music video, in place. */
    fun setVideoMode(on: Boolean) {
        if (_videoMode.value == on) return
        // The setting is the feature's master switch: while it is off, no
        // song is swapped onto its video.
        if (on && !AppSettings.playback.value.videoModeEnabled) return
        val controller = controller ?: return
        val item = controller.currentMediaItem ?: return
        // The service makes the swap on its own player: its rendition swaps
        // (the quality switch's) are the ones that hold, where a controller's
        // replace of the playing item settles back onto the queued item.
        PlaybackRequests.setVideoMode(item.mediaId, on)
        _videoMode.value = on
    }

    /** Gives the player a view to draw the playing song's video picture into. */
    fun setVideoTextureView(view: TextureView) {
        videoView = view
        controller?.setVideoTextureView(view)
    }

    /**
     * Takes [view] back from the player, if it is still the one drawn into. Media3 ignores a
     * clear with no view named, so the view leaving has to say which it is; and a newer view
     * already set is left alone.
     */
    fun clearVideoTextureView(view: TextureView) {
        if (videoView === view) videoView = null
        controller?.clearVideoTextureView(view)
    }

    /**
     * Gives the player a surface to draw the picture into. The player screen keeps one
     * surface for as long as it shows video, wherever the picture moves (the player, full
     * screen and back), so the decoder never has to be told of a new one.
     */
    fun setVideoSurface(surface: Surface) {
        videoView = surface
        controller?.setVideoSurface(surface)
    }

    /** Takes [surface] back from the player, if it is still the one drawn into. */
    fun clearVideoSurface(surface: Surface) {
        if (videoView === surface) videoView = null
        controller?.clearVideoSurface(surface)
    }

    /**
     * The view or surface the picture should be drawn into, kept so a controller
     * that connects (or reconnects) after it appeared is handed it too;
     * otherwise the picture stayed blank until the player was reopened.
     */
    private var videoView: Any? = null

    /** The sizes the playing song's video can be chosen at: Auto first, then what it's offered at and this phone decodes. */
    fun videoChoices(): List<Int> = _currentSong.value?.videoId?.let(StreamResolver::videoChoices) ?: listOf(VideoQuality.AUTO)

    /** Chooses the video size for the network in use, and moves the playing video onto it where it stands. */
    fun setVideoQuality(rung: Int) {
        VideoQuality.chooseNow(rung)
        val id = controller?.currentMediaItem?.mediaId ?: return
        PlaybackRequests.setVideoQuality(id)
    }

    fun toggleShuffle() {
        val controller = controller ?: return
        if (_shuffleMode.value == ShuffleMode.OFF) shuffleUpcoming(controller) else unshuffleUpcoming(controller)
        publishShuffle(controller)
    }

    /**
     * The list's songs after the current one, in the order they had before shuffle took them,
     * so turning it off puts them back. Null when shuffle isn't on this way.
     */
    private var beforeShuffle: List<MediaItem>? = null

    /** Which songs that shuffle moved (see [QueueShuffle.movable]), to put the same ones back. */
    private var shuffled: (MediaItem) -> Boolean = { it.queueTier == QueueTier.CONTEXT }

    /**
     * The songs [beforeShuffle] holds and turning shuffle off puts back: those shuffle moved,
     * and AutoPlay's, which it mixes in as they arrive (see [shuffleArrivals]).
     */
    private fun restorable(item: MediaItem) = shuffled(item) || item.queueTier == QueueTier.AUTOPLAY

    /** The queue's songs while shuffle is on, so the ones AutoPlay adds after can be told apart. */
    private val shuffleKnown = HashSet<String>()

    /**
     * Shuffle, as a new order for the songs still to come rather than the player's shuffle
     * mode: the song playing is never touched, and the queue's order is the play order, so
     * rows can still be dragged. Songs queued by hand stay first and AutoPlay's stay last;
     * only the list's own songs (the album, the playlist) are mixed.
     */
    private fun shuffleUpcoming(controller: MediaController) {
        controller.shuffleModeEnabled = false
        beforeSmart = null
        val from = controller.currentMediaItemIndex + 1
        val items = (from until controller.mediaItemCount).map(controller::getMediaItemAt)
        shuffled = QueueShuffle.movable(items) { it.queueTier }
        val (mixed, _) = QueueShuffle.mix(items, shuffled)
        beforeShuffle = items.filter(::restorable)
        shuffleKnown.clear()
        (0 until controller.mediaItemCount).mapTo(shuffleKnown) { controller.getMediaItemAt(it).mediaId }
        if (mixed != items) replaceAfter(controller, from, mixed)
    }

    /**
     * Songs AutoPlay adds while shuffle is on are shuffled in too, rather than all played
     * last in the order they came: each goes to a random place among AutoPlay's songs still
     * to come (or, with no list left to shuffle, among all the songs shuffle mixed), never
     * ahead of the next song or of songs queued by hand. Turning shuffle off puts them back
     * in the order they came.
     */
    private fun shuffleArrivals(controller: MediaController) {
        val before = beforeShuffle ?: return
        val count = controller.mediaItemCount
        val queue = (0 until count).map(controller::getMediaItemAt)
        val arrived = QueueShuffle.arrivals(queue, shuffleKnown, { it.queueTier == QueueTier.AUTOPLAY }) { it.mediaId }
        if (arrived == 0) return
        val added = queue.takeLast(arrived)
        added.mapTo(shuffleKnown) { it.mediaId }
        beforeShuffle = before + added
        val from = controller.currentMediaItemIndex + 1
        // Only those still to come move: one already playing (the queue had run out) stays put.
        val movable = minOf(arrived, count - from)
        if (movable < 1) return
        val upcoming = queue.subList(from, count)
        val mixesWithAll = shuffled(added.first())
        val placed = QueueShuffle.placeAdded(upcoming, movable,
            staysAhead = { if (mixesWithAll) !shuffled(it) else it.queueTier != QueueTier.AUTOPLAY })
        val firstMoved = placed.indices.firstOrNull { placed[it] !== upcoming[it] } ?: return
        controller.removeMediaItems(from + firstMoved, count)
        placed.drop(firstMoved).chunked(QUEUE_CHUNK).forEach { controller.addMediaItems(it) }
    }

    /** Puts the list's songs still to come back in their order; songs added since keep their places. */
    private fun unshuffleUpcoming(controller: MediaController) {
        controller.shuffleModeEnabled = false
        val before = beforeShuffle ?: return
        beforeShuffle = null
        shuffleKnown.clear()
        val from = controller.currentMediaItemIndex + 1
        val items = (from until controller.mediaItemCount).map(controller::getMediaItemAt)
        val restored = QueueShuffle.restore(items, ::restorable, before) { it.mediaId }
        if (restored != items) replaceAfter(controller, from, restored)
    }

    /**
     * Hands the player [items] as everything after the current song, in chunks for a long queue.
     * The song playing stays where it is and keeps playing.
     */
    private fun replaceAfter(controller: MediaController, from: Int, items: List<MediaItem>) {
        controller.removeMediaItems(from, controller.mediaItemCount)
        items.chunked(QUEUE_CHUNK).forEach { controller.addMediaItems(it) }
    }

    /**
     * The player's shuffle button: off, then shuffle, then — for a queue
     * from a list — smart shuffle, then off again. Returns the new mode.
     */
    fun cycleShuffle(): ShuffleMode {
        val controller = controller ?: return _shuffleMode.value
        val from = _shuffleMode.value
        when (from.next(_smartShuffleAvailable.value)) {
            ShuffleMode.ON -> shuffleUpcoming(controller)
            // Smart shuffle makes its own order from the songs to come, whatever order they're in.
            // The order from before shuffle is kept through it, for off to put back.
            ShuffleMode.SMART -> { keepOrderForSmart(controller); smartShuffleOn(controller) }
            ShuffleMode.OFF -> if (from == ShuffleMode.SMART) { smartShuffleOff(controller); restoreAfterSmart(controller) } else unshuffleUpcoming(controller)
        }
        publishShuffle(controller)
        return _shuffleMode.value
    }

    private fun publishShuffle(player: Player) {
        val origin = SmartShuffle.origin.value
        val current = player.currentMediaItem
        // Anything else may have replaced the queue since (Android Auto, a
        // shared link); the list still counts while one of its songs plays.
        val fromList = origin != null && origin.canMix && current != null &&
                (current.mediaId in origin.songIds || current.toSong().smartShuffle)
        // With the setting off the button only offers smart shuffle to turn
        // off one already running.
        _smartShuffleAvailable.value = fromList && AppSettings.playback.value.smartShuffle
        _shuffleMode.value = when {
            fromList && SmartShuffle.on.value -> ShuffleMode.SMART
            player.shuffleModeEnabled || beforeShuffle != null -> ShuffleMode.ON
            else -> ShuffleMode.OFF
        }
    }

    /**
     * Smart shuffle for the queue that's playing: the songs not played yet
     * are shuffled into a fixed order after the current one, and the picks
     * are slotted in when they arrive. Shuffle mode itself goes off, as
     * the order is now the queue's own.
     */
    private fun smartShuffleOn(controller: MediaController) {
        val origin = SmartShuffle.origin.value ?: return
        val upcoming = controller.upcomingPlayOrder().drop(1)
        val items = upcoming.map(controller::getMediaItemAt).filterNot { it.toSong().smartShuffle }
        val list = items.map { it.toSong() }
        val seed = Random.nextLong()
        // The queue's own items go back in the new order, rather than items
        // rebuilt from songs, so nothing the item carries is lost.
        val byId = items.groupByTo(HashMap()) { it.mediaId }.mapValues { ArrayDeque(it.value) }
        val mixed = SmartShuffle.mix(list, emptyList(), LibraryStore.hidden.value, seed).mapNotNull { byId[it.videoId]?.removeFirstOrNull() }
        if (controller.shuffleModeEnabled) {
            // The songs played so far stay behind the current one.
            val current = controller.currentMediaItemIndex
            val unplayed = upcoming.toHashSet()
            val played = (0 until controller.mediaItemCount).filter { it != current && it !in unplayed }.map(controller::getMediaItemAt)
            controller.shuffleModeEnabled = false
            controller.removeMediaItems(current + 1, controller.mediaItemCount)
            controller.removeMediaItems(0, current)
            if (played.isNotEmpty()) controller.addMediaItems(0, played)
        } else {
            replaceUpcoming(emptyList())
        }
        if (mixed.isNotEmpty()) controller.addMediaItems(mixed)
        startSmartShuffle(origin, list, seed)
    }

    /**
     * The songs to come in the order they had before shuffle, while smart shuffle that followed
     * it is on: smart shuffle mixes them all again, so the order shuffle kept is taken as the
     * whole queue to come with its shuffled songs put back. Null when there's none to put back.
     */
    private var beforeSmart: List<MediaItem>? = null

    private fun keepOrderForSmart(controller: MediaController) {
        val before = beforeShuffle
        beforeShuffle = null
        shuffleKnown.clear()
        beforeSmart = before?.let { b ->
            val from = controller.currentMediaItemIndex + 1
            val items = (from until controller.mediaItemCount).map(controller::getMediaItemAt)
            QueueShuffle.restore(items, ::restorable, b) { it.mediaId }
        }
    }

    /** Shuffle, then smart shuffle, then off: the songs to come go back to the order they had before shuffle. */
    private fun restoreAfterSmart(controller: MediaController) {
        val order = beforeSmart ?: return
        beforeSmart = null
        val from = controller.currentMediaItemIndex + 1
        val items = (from until controller.mediaItemCount).map(controller::getMediaItemAt)
        val restored = QueueShuffle.putBack(items, order, { it.toSong().smartShuffle }) { it.mediaId }
        if (restored != items) replaceAfter(controller, from, restored)
    }

    /** Takes out the added songs not played yet; the list's own songs keep their order. */
    private fun smartShuffleOff(controller: MediaController) {
        endSmartShuffle()
        val from = controller.currentMediaItemIndex + 1
        val upcoming = (from until controller.mediaItemCount).map { controller.getMediaItemAt(it).toSong() }
        SmartShuffle.toRemove(upcoming, SmartShuffle.kept()).forEach { controller.removeMediaItem(from + it) }
        controller.shuffleModeEnabled = false
    }

    private fun endSmartShuffle() {
        smartJob?.cancel()
        smartJob = null
        SmartShuffle.setOn(false)
    }

    /** Finds the picks for [list] and slots them into the queue where [SmartShuffle.mix] places them. */
    private fun startSmartShuffle(origin: SmartShuffle.Origin, list: List<Song>, seed: Long, landed: Job? = null) {
        SmartShuffle.setOn(true)
        controller?.let(::publishShuffle)
        smartJob?.cancel()
        smartJob = scope.launch {
            val found = SmartShuffle.recommendationsFor(list, seed)
            landed?.join()
            val controller = controller ?: return@launch
            if (SmartShuffle.origin.value?.token != origin.token || !SmartShuffle.on.value) return@launch
            val queued = (0 until controller.mediaItemCount).mapTo(HashSet()) { controller.getMediaItemAt(it).mediaId }
            val mixed = SmartShuffle.mix(list, found.filter { it.videoId !in queued }, LibraryStore.hidden.value, seed)
            if (mixed.none { it.smartShuffle }) {
                SmartShuffle.notice("Couldn't find similar songs for this list")
                return@launch
            }
            val from = controller.currentMediaItemIndex + 1
            val upcoming = (from until controller.mediaItemCount).map { controller.getMediaItemAt(it).toSong() }
            // Counted from the song playing now, which may have moved on while the picks were found.
            val currentId = controller.currentMediaItem?.mediaId
            val after = mixed.drop(mixed.indexOfFirst { !it.smartShuffle && it.videoId == currentId } + 1)
            if (SmartShuffle.insertions(upcoming.map { it.videoId }, after) != null) {
                // The queue is still the list as mixed: swap everything after
                // the playing song for the mixed tail in two calls, instead of
                // a call - and a queue rebuild - per added song.
                controller.removeMediaItems(from, controller.mediaItemCount)
                controller.addMediaItems(from, after.map { it.toMediaItem() })
            } else {
                // The queue was changed meanwhile: slot the picks into it as it is now.
                val plan = upcoming.takeIf { u -> u.none { it.smartShuffle } }?.let { u ->
                    SmartShuffle.insertions(u.map { it.videoId }, SmartShuffle.interleave(u, after.filter { it.smartShuffle }))
                } ?: return@launch
                plan.forEach { (at, song) -> controller.addMediaItem(from + at, song.toMediaItem()) }
            }
        }
    }

    /** Takes out added songs, not played yet, that the user has just hidden. */
    fun dropHiddenSmartShuffleSongs() {
        val controller = controller ?: return
        val from = controller.currentMediaItemIndex + 1
        val upcoming = (from until controller.mediaItemCount).map { controller.getMediaItemAt(it).toSong() }
        SmartShuffle.toRemove(upcoming, emptySet(), LibraryStore.hidden.value).forEach { controller.removeMediaItem(from + it) }
    }

    /** Sets repeat straight to [mode] (a Player.REPEAT_MODE_ value). */
    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    /** Off, then repeat the whole queue, then repeat the current track. */
    fun cycleRepeatMode() {
        val controller = controller ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Queues [song] right after the current track. */
    fun playNext(song: Song) = queueByHand(song, next = true)

    /** Queues [song] after the other songs queued by hand, ahead of the rest of the list and autoplay. */
    fun addToQueue(song: Song) = queueByHand(song, next = false)

    private fun queueByHand(song: Song, next: Boolean) {
        val controller = controller ?: return
        if (controller.mediaItemCount == 0) return play(song)
        // Mixing: the song coming in is the one playing, as the player shows it. Counted from the
        // one going out, Play next went in between the two, which called the mix off (the song
        // coming in faded back out) and a song played at once from a room or another device
        // played the song that had been coming in instead.
        val current = MixState.showing.value?.index?.takeIf { it in 0 until controller.mediaItemCount } ?: controller.currentMediaItemIndex
        val at = QueueTimeline.userInsertIndex(tiers(controller), current, next)
        controller.addMediaItem(at, song.copy(queueTier = QueueTier.USER).toMediaItem())
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.playWhenReady && (controller.playerError == null || !AppSettings.isOnline())) {
            controller.pause()
        } else {
            // After an error the player sits idle; play() alone won't restart it.
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun play() {
        val controller = controller ?: return
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        controller.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun setVolume(volume: Float) {
        val controller = controller ?: return
        controller.volume = volume.coerceIn(0f, 1f)
        _volume.value = controller.volume
    }

    /** Stops playback and clears the queue entirely: nothing is left playing. */
    fun dismiss() {
        QueueStore.clear()
        beforeShuffle = null
        beforeSmart = null
        val controller = controller ?: return
        controller.stop()
        controller.clearMediaItems()
    }

    /** Swaps everything after the current track for [songs], leaving the current one playing. */
    fun replaceUpcoming(songs: List<Song>) {
        val controller = controller ?: return
        val from = controller.currentMediaItemIndex + 1
        if (from < controller.mediaItemCount) controller.removeMediaItems(from, controller.mediaItemCount)
        if (songs.isNotEmpty()) controller.addMediaItems(songs.map { it.toMediaItem() })
    }

    /** Jumps to queue entry [index] at [positionMs]. */
    fun seekToItem(index: Int, positionMs: Long) {
        val controller = controller ?: return
        if (index !in 0 until controller.mediaItemCount) return
        controller.seekTo(index, positionMs.coerceAtLeast(0L))
    }

    fun skipNext() {
        val controller = controller ?: return
        // Mixing: the song shown is the one coming in, so next is the one after it.
        MixState.showing.value?.let { controller.seekTo(it.index, 0); controller.seekToNext(); return }
        controller.seekToNext()
    }

    /** Restarts the current track, or goes back one if it has only just begun. */
    fun skipPrevious() {
        val controller = controller ?: return
        MixState.showing.value?.let { controller.seekTo(it.index, 0); return }
        controller.seekToPrevious()
    }

    /**
     * Plays queue row [index]. Further down the queue, the songs queued by
     * hand are kept coming next, as [QueueTimeline.jump] says; with shuffle on
     * the rows aren't in play order, so it's a plain jump.
     */
    fun playQueueItem(index: Int) {
        val controller = controller ?: return
        val jump = if (controller.shuffleModeEnabled) null else QueueTimeline.jump(tiers(controller), controller.currentMediaItemIndex, index)
        if (jump == null) {
            controller.seekToDefaultPosition(index)
            controller.play()
            return
        }
        val (removals, moves) = QueueTimeline.edits(controller.mediaItemCount, jump.order)
        removals.forEach { controller.removeMediaItem(it) }
        moves.forEach { (from, to) -> controller.moveMediaItem(from, to) }
        if (jump.promote) {
            controller.replaceMediaItem(jump.target, controller.getMediaItemAt(jump.target).toSong().copy(queueTier = QueueTier.CONTEXT).toMediaItem())
        }
        controller.seekToDefaultPosition(jump.target)
        controller.play()
    }

    fun removeQueueItem(index: Int) {
        val controller = controller ?: return
        if (index != controller.currentMediaItemIndex) controller.removeMediaItem(index)
    }

    /** Moves a queue entry, for drag-to-reorder in the queue. */
    fun moveQueueItem(from: Int, to: Int) {
        val controller = controller ?: return
        if (from == to || from !in 0 until controller.mediaItemCount || to !in 0 until controller.mediaItemCount) return
        controller.moveMediaItem(from, to)
    }

    fun seekTo(positionMs: Long) {
        val controller = controller ?: return
        // Mixing: a seek is in the song shown, the one coming in.
        MixState.showing.value?.let { controller.seekTo(it.index, positionMs.coerceAtLeast(0L)); return }
        controller.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = MixState.showing.value?.positionMs?.invoke() ?: controller?.currentPosition ?: 0L

    /**
     * Plays at [rate] times the Remix speed, pitch unchanged. Listening
     * together nudges this a few percent either way to close a small gap
     * with the host without the jump of a seek; 1 puts it back.
     */
    fun setSyncRate(rate: Float) {
        val c = controller ?: return
        val sound = com.opentune.data.settings.AppSettings.sound.value
        val speed = sound.speed * rate
        if (kotlin.math.abs(c.playbackParameters.speed - speed) > 0.001f) c.playbackParameters = PlaybackParameters(speed, sound.pitch)
    }

    fun bufferedPositionMs(): Long = controller?.bufferedPosition ?: 0L

    private companion object {
        /** How long a stall lasts before the spinner shows. */
        const val STALL_MS = 600L
        /** The same during a quality switch and just after it. */
        const val SWITCH_STALL_MS = 2_500L
        const val SWITCH_SETTLE_MS = 3_000L
        const val LOADING_TICK_MS = 250L

        /** Songs per chunk when a queue is handed to the player; see setQueue. */
        const val QUEUE_CHUNK = 150
        const val QUEUE_CHUNK_MS = 50L
        val DOLBY_MIMES = setOf("audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4")
    }
}

data class AudioFormatInfo(
    val codec: String?, val bitrateKbps: Int?, val sampleRateHz: Int?, val channels: Int?, val bitDepth: Int? = null,
    /** The stream's own MIME type, e.g. "audio/eac3-joc" for Dolby Atmos in E-AC-3. */
    val mime: String? = null,
)

/**
 * The playing song's bitrate in kbps, best figure first: measured from the audio as it's
 * decoded ([measured], see MeasuredBitrate), exact for uncompressed PCM (rate × depth ×
 * channels), then what the stream or file says, then what the source said.
 */
fun bestKbps(info: AudioFormatInfo?, measured: Int?, sourceKbps: Int?): Int? {
    val pcm = info?.mime == "audio/raw" || info?.codec?.equals("RAW", ignoreCase = true) == true
    if (pcm) {
        val bits = info?.bitDepth; val rate = info?.sampleRateHz; val ch = info?.channels
        if (bits != null && rate != null && ch != null) return (bits.toLong() * rate * ch / 1000).toInt()
    }
    return measured?.takeIf { it > 0 } ?: info?.bitrateKbps?.takeIf { it > 0 } ?: sourceKbps?.takeIf { it > 0 }
}

/**
 * Why a Dolby stream didn't play. AC-3 and E-AC-3 have OpenTune's own decoder to fall back on
 * (third_party/ffmpeg-dolby); AC-4 has none, as FFmpeg can't decode it.
 */
fun dolbyUnplayable(mime: String?): String =
    if (mime == "audio/ac4") "This phone has no Dolby AC-4 decoder. AC-4 plays on phones with Dolby's own decoder, or through a Dolby-capable receiver or TV over HDMI or eARC."
    else "This Dolby audio couldn't be decoded. It also plays through a Dolby-capable receiver or TV over HDMI or eARC."

/**
 * What Dolby format [info] is, as the player names it, or null when it isn't Dolby:
 * Atmos for E-AC-3 with objects (JOC) and for AC-4, Dolby Digital Plus for plain
 * E-AC-3, Dolby Digital for AC-3.
 */
fun dolbyName(info: AudioFormatInfo?): String? {
    val mime = info?.mime?.lowercase().orEmpty()
    val codec = info?.codec?.lowercase().orEmpty()
    return when {
        mime == "audio/eac3-joc" || codec.startsWith("ec+3") || codec.contains("joc") -> "Dolby Atmos"
        mime == "audio/ac4" || codec.startsWith("ac-4") -> "Dolby Atmos"
        mime == "audio/eac3" || codec.startsWith("ec-3") || codec == "eac3" -> "Dolby Digital Plus"
        mime == "audio/ac3" || codec.startsWith("ac-3") || codec == "ac3" -> "Dolby Digital"
        else -> null
    }
}

/** Whether the app is on screen, set by the activity that shows it: off it, the UI's polling waits. */
object AppOnScreen {
    val shown = MutableStateFlow(true)
}
