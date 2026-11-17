package com.opentune.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay

/** Upcoming songs yield catalogue capacity until the playing song has its first answer. */
internal suspend fun awaitForegroundLookup(target: String, current: () -> String?, pending: (String) -> Boolean) {
    while (true) {
        val playing = current() ?: return
        if (playing == target || !pending(playing)) return
        delay(100)
    }
}

/** How a song ahead was opened: not yet, on YouTube, or already on an external stream. */
internal enum class AheadOpened { NOT_YET, YOUTUBE, EXTERNAL }

/**
 * The positions among [ahead] (the next songs in play order: id and how each was opened) to point
 * at a FLAC just found for [id]. Only songs already opened on YouTube: one not opened yet picks the
 * FLAC by itself when it opens, and one on an external stream has its source.
 */
internal fun aheadToStartOnFlac(id: String, ahead: List<Pair<String, AheadOpened>>): List<Int> =
    ahead.withIndex().filter { (_, song) -> song.first == id && song.second == AheadOpened.YOUTUBE }.map { it.index }

/**
 * Whether a song just opened on YouTube is searched for its FLAC again: not the song playing (its
 * upgrade does that), only with lossless allowed, once per opening, and not while a search is running.
 */
internal fun lookAgainForFlac(playing: Boolean, losslessAllowed: Boolean, firstForThisOpening: Boolean, searching: Boolean): Boolean =
    !playing && losslessAllowed && firstForThisOpening && !searching

/**
 * Waits up to [timeoutMs] for a song's startup source to be chosen, [choosing] being true until it is,
 * including before the choice has begun. False when it never came.
 */
internal suspend fun awaitStartupChoice(choosing: () -> Boolean, timeoutMs: Long = 30_000, pollMs: Long = 100): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (choosing()) delay(pollMs)
        true
    } == true

/** Neither provider's latency or failure delays the other's preparation. */
internal suspend fun prefetchExternalSources(lossless: suspend () -> Unit, lossy: suspend () -> Unit) = supervisorScope {
    suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    launch { attempt(lossless) }
    launch { attempt(lossy) }
    Unit
}
