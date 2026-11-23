package com.opentune.lyrics

import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lyrics the player found for the playing song, by its id, for the places outside the
 * player that show a line (widgets, floating lyrics). Set by the player's own lookup, so
 * they never look lyrics up themselves.
 */
object NowPlayingLyrics {
    data class Loaded(val videoId: String, val lyrics: Lyrics?)

    private val _loaded = MutableStateFlow<Loaded?>(null)
    val loaded: StateFlow<Loaded?> = _loaded.asStateFlow()

    /** [lyrics] are [videoId]'s, or null while they're looked up or when there are none. */
    fun set(videoId: String, lyrics: Lyrics?) {
        _loaded.value = Loaded(videoId, lyrics)
    }

    /** [videoId]'s timed lines, when they're in and synced. */
    fun syncedFor(videoId: String?): Lyrics.Synced? =
        _loaded.value?.takeIf { videoId != null && it.videoId == videoId }?.lyrics as? Lyrics.Synced
}

/** The line being sung in [videoId] and the next, as the widgets and floating lyrics show them. */
data class LyricsNow(val videoId: String, val current: LyricLine?, val next: LyricLine?) {
    /** Changes only when what's shown does. */
    val key: String get() = "$videoId@${current?.startMs}:${next?.startMs}"
}
