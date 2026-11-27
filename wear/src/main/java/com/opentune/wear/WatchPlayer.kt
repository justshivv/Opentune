package com.opentune.wear

import android.app.Application
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opentune.data.MusicRepository
import com.opentune.data.connect.CTrack
import com.opentune.data.connect.Connect
import com.opentune.data.connect.Device
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The watch's connection to the player service, and the songs a search
 * found. While another of the person's devices plays (Connect), what's
 * shown and every control are that device's; otherwise the watch's own.
 */
class WatchPlayer @JvmOverloads constructor(
    application: Application,
    /** False only in tests, which have no player service to reach. */
    connectNow: Boolean = true,
) : AndroidViewModel(application) {
    /** The watch's shared connection (see [WatchNow.player]); tests get one of their own, never connected. */
    val player = if (connectNow) WatchNow.player(application) else PlayerConnection(application)
    private val shared = connectNow
    val isBuffering = player.isBuffering

    /** The other device the watch controls now, if any. */
    val controlling: StateFlow<Device?> = Connect.controlling

    /** What the player screen shows: the watch's song, or the controlled device's. */
    val shown: StateFlow<Shown?> = combine(player.currentSong, player.isPlaying, controlling) { song, playing, remote ->
        WatchRemote.shown(song, playing, remote)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How long the shown song is, in ms; 0 when not known yet. */
    val durationMs: StateFlow<Long> = combine(player.durationMs, controlling) { local, remote ->
        if (remote != null) remote.state?.track?.lengthMs ?: 0L else local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val hasNext: StateFlow<Boolean> = combine(player.hasNext, controlling) { local, remote ->
        if (remote != null) remote.state?.queue?.isNotEmpty() == true else local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Up next as shown: the remote device's (its song first) or the watch's queue, and the playing row. */
    val upNext: StateFlow<Pair<List<Song>, Int>> = combine(player.queue, player.currentIndex, controlling) { queue, index, remote ->
        if (remote != null) WatchRemote.remoteQueue(remote) else queue to index
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<Song>() to -1)

    sealed interface Found {
        data object Idle : Found
        data object Searching : Found
        data class Songs(val query: String, val songs: List<Song>) : Found
        data class Failed(val query: String) : Found
    }

    private val _found = MutableStateFlow<Found>(Found.Idle)
    val found = _found.asStateFlow()

    init {
        if (connectNow) viewModelScope.launch {
            player.connected.first { it }
            // In the person's devices while the watch app is open; the service keeps it while music plays.
            Connect.attach(player)
        }
    }

    fun search(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        _found.value = Found.Searching
        viewModelScope.launch {
            _found.value = runCatching { MusicRepository.search(q, SearchFilter.SONGS) }
                .map { rows -> Found.Songs(q, songsIn(rows)) }
                .getOrElse { Found.Failed(q) }
        }
    }

    fun playAll(songs: List<Song>, index: Int) { if (!Connect.playOnRemote(songs, index)) player.playAll(songs, index) }
    fun togglePlayPause() { if (!Connect.togglePlayPause()) player.togglePlayPause() }
    fun skipNext() { if (!Connect.skipNext()) player.skipNext() }
    fun skipPrevious() { if (!Connect.skipPrevious()) player.skipPrevious() }
    /** Moves the shown song to [positionMs]: on the controlled device, or here. */
    fun seekTo(positionMs: Long) { if (!Connect.seekTo(positionMs)) player.seekTo(positionMs) }

    /** Row [index] of [upNext]. */
    fun playQueueItem(index: Int) {
        val remote = controlling.value
        if (remote != null) {
            WatchRemote.remoteEntry(remote, index)?.let { (at, t) -> Connect.playQueued(at, t) }
            return
        }
        player.playQueueItem(index)
    }

    /**
     * True when a press sent to the controlled device has gone unanswered for a while. The press
     * shows at once (Connect guesses its outcome), so a quick answer needs no spinner; a slow one
     * gets it, so it's clear the other device hasn't heard yet.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val waiting: StateFlow<Boolean> = combine(Connect.view, controlling) { v, remote -> remote != null && v?.pending?.isNotEmpty() == true }
        .distinctUntilChanged()
        .transformLatest { pending ->
            if (pending) { kotlinx.coroutines.delay(WatchRemote.SLOW_ANSWER_MS); emit(true) } else emit(false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _volume = MutableStateFlow<Float?>(null)
    /** The volume just set with the crown, 0 to 1, for the small bar at the bottom; null once it has faded. */
    val volume: StateFlow<Float?> = _volume.asStateFlow()
    private var volumeFade: kotlinx.coroutines.Job? = null

    /** The volume last sent to a device, so quick turns add up instead of each starting from its last report. */
    private var sentVolume: Triple<String, Float, Long>? = null

    /**
     * One crown step ([AudioManager.ADJUST_RAISE] or [AudioManager.ADJUST_LOWER]): the controlled
     * device's volume, or the watch's (headphones included). Shown in a small bar, not the system's
     * full-screen one.
     */
    fun crown(step: Int, audio: AudioManager?) {
        val remote = controlling.value
        val now = System.currentTimeMillis()
        val level = if (remote != null) {
            val from = sentVolume?.takeIf { it.first == remote.id && now - it.third < WatchRemote.VOLUME_HOLD_MS }?.second ?: remote.state?.volume ?: 1f
            val v = WatchRemote.volumeAfter(from, step)
            sentVolume = Triple(remote.id, v, now)
            Connect.setVolume(v)
            v
        } else {
            audio ?: return
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }
        _volume.value = level
        volumeFade?.cancel()
        volumeFade = viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            _volume.value = null
        }
    }

    /** Where the shown song is now, in ms. */
    fun positionMs(): Long = controlling.value?.positionAt(System.currentTimeMillis()) ?: player.currentPositionMs()

    override fun onCleared() {
        Connect.detach()
        if (!shared) player.disconnect()
        super.onCleared()
    }

    companion object {
        /** The songs in a search's rows, in order, without repeats. */
        fun songsIn(rows: List<SearchResult>): List<Song> = rows.mapNotNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        }.distinctBy { it.videoId }
    }
}

/** A song as the player, tile and complication show it, and where it plays: null [on] is here. */
data class Shown(val song: Song, val playing: Boolean, val on: String? = null)

/** The parts of controlling another device that need no Android, so tests can reach them. */
object WatchRemote {
    /** How much one crown step moves another device's volume. */
    const val VOLUME_STEP = 0.05f
    /** How long the volume sent last counts over the device's own reports, which come a moment later. */
    const val VOLUME_HOLD_MS = 3_000L
    /** How long a press may go unanswered before the player shows it's still waiting. */
    const val SLOW_ANSWER_MS = 1_500L

    fun shown(local: Song?, localPlaying: Boolean, remote: Device?): Shown? {
        if (remote != null) {
            val st = remote.state ?: return null
            val t = st.track ?: return null
            return Shown(t.toSong(), st.playing, remote.name)
        }
        return local?.let { Shown(it, localPlaying) }
    }

    fun volumeAfter(current: Float, step: Int): Float = when (step) {
        AudioManager.ADJUST_RAISE -> current + VOLUME_STEP
        AudioManager.ADJUST_LOWER -> current - VOLUME_STEP
        else -> current
    }.coerceIn(0f, 1f)

    /** The remote device's song and Up Next as one list, its song (if any) the playing row. */
    fun remoteQueue(remote: Device): Pair<List<Song>, Int> {
        val st = remote.state ?: return emptyList<Song>() to -1
        val rows = listOfNotNull(st.track) + st.queue
        return rows.map(CTrack::toSong) to (if (st.track != null) 0 else -1)
    }

    /** Row [index] of [remoteQueue] as the remote's Up Next: its place there and its track. */
    fun remoteEntry(remote: Device, index: Int): Pair<Int, CTrack>? {
        val st = remote.state ?: return null
        val at = index - (if (st.track != null) 1 else 0)
        return st.queue.getOrNull(at)?.let { at to it }
    }
}
