package com.opentune.playback

import kotlinx.coroutines.delay

/**
 * Waits up to [timeoutMs] for a song's startup source to be chosen, [choosing] being true until it is,
 * including before the choice has begun. False when it never came.
 */
internal suspend fun awaitStartupChoice(choosing: () -> Boolean, timeoutMs: Long = 30_000, pollMs: Long = 100): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (choosing()) delay(pollMs)
        true
    } == true
