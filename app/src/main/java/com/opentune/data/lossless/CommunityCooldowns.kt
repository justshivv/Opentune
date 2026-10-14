package com.opentune.data.lossless

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A provider-wide pause does not invalidate verification or block other sources. */
internal class CommunityCooldowns(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    class Paused(val code: Int, val remainingMs: Long) : IOException("Community provider paused (HTTP $code)")
    private data class Pause(val code: Int, val until: Long)
    private val pauses = ConcurrentHashMap<String, Pause>()

    fun current(endpoint: String): Paused? {
        val pause = pauses[endpoint] ?: return null
        val remaining = pause.until - now()
        if (remaining > 0) return Paused(pause.code, remaining)
        pauses.remove(endpoint, pause)
        return null
    }

    fun record(endpoint: String, error: LosslessHttp.HttpFailure): Paused? {
        if (error.code != 429 && error.code != 503) return null
        val wait = (error.retryAfterMs ?: 30_000).coerceIn(1_000, 86_400_000)
        val until = now() + wait
        pauses.compute(endpoint) { _, old -> if (old != null && old.until > until) old else Pause(error.code, until) }
        return current(endpoint)
    }
}
