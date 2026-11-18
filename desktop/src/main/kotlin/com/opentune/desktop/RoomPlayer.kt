package com.opentune.desktop

import com.opentune.data.model.Song
import com.opentune.data.together.RoomPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The desktop's player as a Listen together room sees it ([RoomPlayer]), so a room runs here
 * exactly as on the phone. The room keeps guests in step by seeking; the desktop's engines
 * don't play a touch faster or slower, so [setRate] leaves the speed alone.
 */
internal object DesktopRoomPlayer : RoomPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
    override val currentSong: StateFlow<Song?> =
        combine(Player.queue, Player.index) { q, i -> q.getOrNull(i) }.stateIn(scope, SharingStarted.Eagerly, Player.current)
    override val queue: StateFlow<List<Song>> = Player.queue
    /** The places of the songs still to play, in the order they'll play. */
    override val upNext: StateFlow<List<Int>> =
        combine(Player.queue, Player.index, ::after)
            // Right from the start, not a moment later: a room reads it as soon as it opens.
            .stateIn(scope, SharingStarted.Eagerly, after(Player.queue.value, Player.index.value))

    private fun after(q: List<Song>, i: Int): List<Int> = ((i + 1).coerceAtLeast(0) until q.size).toList()
    override val currentIndex: StateFlow<Int> = Player.index
    override val playWhenReady: StateFlow<Boolean> = Player.wantsPlay
    override val isBuffering: StateFlow<Boolean> = Player.loading
    override val seeks = Player.seeks

    override fun currentPositionMs(): Long = Player.positionMs()
    override fun setRate(rate: Float) = Unit
    override fun play() = Player.play()
    override fun pause() = Player.pause()
    override fun seekTo(positionMs: Long) = Player.seekTo(positionMs, byHand = false)
    override fun seekToItem(index: Int, positionMs: Long) = Player.playAt(index, positionMs)
    override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) {
        val order = if (shuffle) songs.shuffled() else songs
        Player.playAll(order, if (shuffle) 0 else startIndex, startPositionMs)
    }
    override fun replaceUpcoming(songs: List<Song>) = Player.replaceUpcoming(songs)
    override fun addToQueue(song: Song) = Player.addToQueue(listOf(song))
    override fun playNow(song: Song) {
        Player.playNext(listOf(song))
        Player.skipNext()
    }
    override fun skipNext() = Player.skipNext()
    override fun skipPrevious() = Player.skipPrevious()
}
