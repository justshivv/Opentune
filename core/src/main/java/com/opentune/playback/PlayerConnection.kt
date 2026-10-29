package com.opentune.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()
    private val _loadingMessage = MutableStateFlow<String?>(null)
    val loadingMessage = _loadingMessage.asStateFlow()
    private var bufferingSince = 0L
    private var connectionWatch: Job? = null

    private fun refreshLoading(player: Player) {
        val buffering = player.playbackState == Player.STATE_BUFFERING && PlaybackRequests.soundingPosition == null
        val waiting = player.playWhenReady && (buffering || player.playerError != null)
        val online = AppSettings.isOnline()
        val now = android.os.SystemClock.elapsedRealtime()
        if (buffering && player.playWhenReady && online) {
            if (bufferingSince == 0L) bufferingSince = now
        } else bufferingSince = 0L
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
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            _audioFormat.value = null
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
            _error.value = when (error.errorCode) {
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
        refresh(newController)
        listener.onTracksChanged(newController.currentTracks)
        _connected.value = true
        connectionWatch?.cancel()
        connectionWatch = scope.launch {
            while (controller === newController) {
                refreshLoading(newController)
                kotlinx.coroutines.delay(1_000)
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
        _currentIndex.value = player.currentMediaItemIndex
        _currentSong.value = player.currentMediaItem?.toSong()
        _upNext.value = player.upcomingPlayOrder().drop(1)
        _hasNext.value = player.hasNextMediaItem()
        _shuffleEnabled.value = player.shuffleModeEnabled
        publishShuffle(player)
        _repeatMode.value = player.repeatMode
        // The same song swapped onto another stream (a quality switch, a recovery) has no
        // length until the new one loads; it keeps the one it had, so the seek bar and
        // times don't empty to "--:--" while it plays on.
        val id = player.currentMediaItem?.mediaId
        val duration = player.duration.takeIf { it > 0 }
            ?: _durationOf.value.takeIf { id != null && it.first == id }?.second
            ?: 0L
        _durationMs.value = duration
        _durationOf.value = id to duration
        // Buffering on the way, with playback meant to go on (a quality switch
        // loading its stream, a stall), still counts as playing: the button
        // flipped to play and back for that moment at every upgrade.
        _isPlaying.value = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
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
    fun play(song: Song, source: String? = null) {
        val controller = controller ?: return
        _error.value = null
        _source.value = source
        endSmartShuffle()
        SmartShuffle.clear()
        val kept = handQueued(controller)
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
            val kept = if (mixing) emptyList() else handQueued(controller)
            if (kept.isEmpty()) o else {
                val at = (if (shuffled) 0 else startIndex.coerceIn(o.indices)) + 1
                o.take(at) + kept + o.drop(at)
            }
        }
        controller.shuffleModeEnabled = false
        controller.setMediaItems(
            ordered.map { it.toMediaItem() },
            if (shuffled) 0 else startIndex.coerceIn(songs.indices),
            startPositionMs.coerceAtLeast(0L),
        )
        controller.prepare()
        controller.play()
        if (mixing) startSmartShuffle(origin, songs, seed)
    }

    fun toggleShuffle() {
        val controller = controller ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    /**
     * The player's shuffle button: off, then shuffle, then — for a queue
     * from a list — smart shuffle, then off again. Returns the new mode.
     */
    fun cycleShuffle(): ShuffleMode {
        val controller = controller ?: return _shuffleMode.value
        val from = _shuffleMode.value
        when (from.next(_smartShuffleAvailable.value)) {
            ShuffleMode.ON -> controller.shuffleModeEnabled = true
            ShuffleMode.SMART -> smartShuffleOn(controller)
            ShuffleMode.OFF -> if (from == ShuffleMode.SMART) smartShuffleOff(controller) else controller.shuffleModeEnabled = false
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
            player.shuffleModeEnabled -> ShuffleMode.ON
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
    private fun startSmartShuffle(origin: SmartShuffle.Origin, list: List<Song>, seed: Long) {
        SmartShuffle.setOn(true)
        controller?.let(::publishShuffle)
        smartJob?.cancel()
        smartJob = scope.launch {
            val found = SmartShuffle.recommendationsFor(list, seed)
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
            val plan = SmartShuffle.insertions(upcoming.map { it.videoId }, after)
                // The queue was changed meanwhile: slot the picks into it as it is now.
                ?: upcoming.takeIf { u -> u.none { it.smartShuffle } }?.let { u ->
                    SmartShuffle.insertions(u.map { it.videoId }, SmartShuffle.interleave(u, after.filter { it.smartShuffle }))
                }
                ?: return@launch
            plan.forEach { (at, song) -> controller.addMediaItem(from + at, song.toMediaItem()) }
        }
    }

    /** Takes out added songs, not played yet, that the user has just hidden. */
    fun dropHiddenSmartShuffleSongs() {
        val controller = controller ?: return
        val from = controller.currentMediaItemIndex + 1
        val upcoming = (from until controller.mediaItemCount).map { controller.getMediaItemAt(it).toSong() }
        SmartShuffle.toRemove(upcoming, emptySet(), LibraryStore.hidden.value).forEach { controller.removeMediaItem(from + it) }
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
        val at = QueueTimeline.userInsertIndex(tiers(controller), controller.currentMediaItemIndex, next)
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
        controller?.seekToNext()
    }

    /** Restarts the current track, or goes back one if it has only just begun. */
    fun skipPrevious() {
        controller?.seekToPrevious()
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
        controller?.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

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
}

data class AudioFormatInfo(val codec: String?, val bitrateKbps: Int?, val sampleRateHz: Int?, val channels: Int?, val bitDepth: Int? = null)
