package com.opentune.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One real listen, independent of the stream used to play it. Never derives time from seeks. */
internal class YouTubeHistorySession(
    private val transport: Transport,
    private val allowed: () -> Boolean,
) {
    interface Transport {
        suspend fun prepare(): Boolean
        suspend fun playback(): Boolean
        suspend fun atr(): Boolean
        suspend fun watchtime(heardSeconds: Long, final: Boolean): Boolean
    }

    private val mutex = Mutex()
    private var prepared = false
    private var started = false
    private var atrSent = false
    private var lastReport = -1L
    private var retryAt = 0L
    private var failures = 0
    private var closed = false

    suspend fun heard(milliseconds: Long, durationMs: Long?, final: Boolean = false) = mutex.withLock {
        if (closed || !allowed()) return@withLock
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        val threshold = minOf(30_000L, durationMs?.takeIf { it > 0 }?.div(2) ?: 30_000L)
        if (milliseconds < threshold || seconds < retryAt || failures >= 3) return@withLock
        try {
            if (!prepared) { check(transport.prepare()); prepared = true }
            if (!allowed()) return@withLock
            if (!started) { check(transport.playback()); started = true }
            if (!allowed()) return@withLock
            if (!atrSent) { check(transport.atr()); atrSent = true }
            if (!allowed()) return@withLock
            if (lastReport < 0 || seconds - lastReport >= 30 || final) {
                check(transport.watchtime(seconds, final))
                lastReport = seconds
                failures = 0
            }
            if (final) closed = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retry only after more listening, never in a tight network loop.
            failures++
            retryAt = seconds + 30
        }
    }
}
