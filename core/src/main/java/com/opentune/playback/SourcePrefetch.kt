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

/** Neither provider's latency or failure delays the other's preparation. */
internal suspend fun prefetchExternalSources(lossless: suspend () -> Unit, lossy: suspend () -> Unit) = supervisorScope {
    suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    launch { attempt(lossless) }
    launch { attempt(lossy) }
    Unit
}
