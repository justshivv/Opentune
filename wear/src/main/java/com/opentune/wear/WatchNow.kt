package com.opentune.wear

import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.opentune.data.connect.Connect
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * What plays, for the tile and the complication, which live without the
 * app's screens: a player connection of their own, the controlled device
 * when there is one, and a refresh of both whenever the song or its playing
 * state changes.
 */
object WatchNow {
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var player: PlayerConnection? = null
    private val _shown = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = _shown.asStateFlow()

    /**
     * The watch's one connection to the player, shared by its screens, the tile, the
     * complication and the playback service's Connect: each holding its own, four of them
     * redid the whole queue on every change, more than the watch keeps up with.
     */
    fun player(context: Context): PlayerConnection {
        start(context)
        return player!!
    }

    /** Starts following the player (once per process). */
    fun start(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        val p = PlayerConnection(app).also { player = it }
        scope.launch {
            p.connect()
            combine(p.currentSong, p.isPlaying, Connect.controlling) { song, playing, remote -> WatchRemote.shown(song, playing, remote) }
                .distinctUntilChanged { a, b -> a?.song?.videoId == b?.song?.videoId && a?.playing == b?.playing && a?.on == b?.on }
                .collect { now ->
                    _shown.value = now
                    runCatching { TileService.getUpdater(app).requestUpdate(NowPlayingTile::class.java) }
                    runCatching { NowPlayingComplication.update(app) }
                }
        }
    }

    fun togglePlayPause() { if (!Connect.togglePlayPause()) player?.togglePlayPause() }
    fun skipNext() { if (!Connect.skipNext()) player?.skipNext() }
    fun skipPrevious() { if (!Connect.skipPrevious()) player?.skipPrevious() }

    /** How far into the shown song, 0 to 1: the controlled device's, or the watch's. */
    fun progress(): Float {
        val remote = Connect.controlling.value
        if (remote != null) return SongTime.fraction(remote.positionAt(System.currentTimeMillis()), remote.state?.track?.lengthMs ?: 0L)
        val p = player ?: return 0f
        return SongTime.fraction(p.currentPositionMs(), p.durationMs.value)
    }
}
