package com.opentune.data.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One pace for every call to Apple's public search: about twenty a minute is what it
 * allows, and the track details, animated covers and artist genres all share it.
 */
internal object AppleSearchGate {
    private const val GAP_MS = 3_100L
    private val turn = Mutex()
    private var last = 0L

    /** Runs [block] once it's this caller's turn and the last call is far enough back. */
    suspend fun <T> paced(block: suspend () -> T): T = turn.withLock {
        val elapsed = System.nanoTime() / 1_000_000 - last
        if (last != 0L && elapsed < GAP_MS) delay(GAP_MS - elapsed)
        try { block() } finally { last = System.nanoTime() / 1_000_000 }
    }
}
