package com.opentune.data.together

import com.opentune.data.model.Song
import com.opentune.playback.PlayerConnection

/** The app's player, as a room sees it. */
internal class ConnectionPlayer(private val c: PlayerConnection) : RoomPlayer {
    override val connected get() = c.connected
    override val currentSong get() = c.currentSong
    override val queue get() = c.queue
    override val upNext get() = c.upNext
    override val currentIndex get() = c.currentIndex
    override val playWhenReady get() = c.playWhenReady
    override val isBuffering get() = c.isBuffering
    override val seeks get() = c.seeks
    override fun currentPositionMs() = c.currentPositionMs()
    override fun setRate(rate: Float) = c.setSyncRate(rate)
    override fun play() = c.play()
    override fun pause() = c.pause()
    override fun seekTo(positionMs: Long) = c.seekTo(positionMs)
    override fun seekToItem(index: Int, positionMs: Long) = c.seekToItem(index, positionMs)
    override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) =
        // The room's queue, not a list of the listener's own: smart shuffle, and songs left
        // queued by hand here, stay out of it.
        c.playAll(songs, startIndex, shuffle, source, startPositionMs, fromList = false, keepQueued = false)
    override fun replaceUpcoming(songs: List<Song>) = c.replaceUpcoming(songs)
    override fun addToQueue(song: Song) = c.addToQueue(song)
    override fun playNow(song: Song) {
        c.playNext(song)
        c.skipNext()
    }
    override fun skipNext() = c.skipNext()
    override fun skipPrevious() = c.skipPrevious()
}
