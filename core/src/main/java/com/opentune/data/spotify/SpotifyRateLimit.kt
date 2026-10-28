package com.opentune.data.spotify

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared request lane prevents simultaneous library, Canvas and save calls from bursting.
 * Only explicit 429 responses are retried. An ambiguous network failure during a write is not.
 */
internal class SpotifyRateLimit(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    data class Reply(val code: Int, val text: String, val retryAfter: String? = null)
    class Limited(val waitMs: Long) : IOException("Spotify is temporarily limiting requests. Try again in ${retryLabel(waitMs)}.")
    private val lane = Mutex()
    private var nextRequestAt: Long? = null
    private var limitedUntil = Long.MIN_VALUE

    suspend fun execute(onWaiting: (Long) -> Unit = {}, request: suspend () -> Reply): Reply {
        val started = now()
        var retries = 0
        return lane.withLock {
            while (true) {
                val pause = nextRequestAt?.let { until ->
                    val current = now()
                    if (until <= current) 0L else (until - current).takeIf { it >= 0 } ?: Long.MAX_VALUE
                } ?: 0L
                if (pause > 0) {
                    // Leave long server cooldowns intact for all following requests, but don't
                    // hold a save open for hours. The user gets the real wait instead.
                    if (pause > MAX_WAIT_MS - (now() - started).coerceAtLeast(0)) throw Limited(pause)
                    if (limitedUntil > now()) onWaiting(pause)
                    wait(pause)
                }
                val reply = request()
                nextRequestAt = now() + 250
                if (reply.code != 429) return@withLock reply
                val pauseMs = retryAfterMs(reply.retryAfter, wallTime(), retries)
                limitedUntil = runCatching { Math.addExact(now(), pauseMs) }.getOrDefault(Long.MAX_VALUE)
                nextRequestAt = limitedUntil
                if (retries++ >= 2) throw Limited(pauseMs)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    companion object {
        private const val MAX_WAIT_MS = 90_000L
        internal fun retryAfterMs(header: String?, nowMs: Long, retry: Int = 0): Long {
            val seconds = header?.trim()?.toLongOrNull()
            if (seconds != null && seconds >= 0) return seconds.coerceAtMost(Long.MAX_VALUE / 1000).times(1000).coerceAtLeast(1000)
            val date = header?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            if (date != null) return (date - nowMs).coerceAtLeast(1000)
            return 15_000L * (1L shl retry.coerceIn(0, 4))
        }
    }
}

internal fun retryLabel(ms: Long): String = if (ms < 60_000) "${(ms + 999) / 1000} seconds"
    else "${ms / 60_000 + if (ms % 60_000 > 0) 1 else 0} minutes"
