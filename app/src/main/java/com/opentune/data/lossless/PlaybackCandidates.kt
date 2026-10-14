package com.opentune.data.lossless

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Start the fallback immediately; keep a verified result when a slower route times out. */
internal object PlaybackCandidates {
    suspend fun <T, U> resolve(timeoutMs: Long, lossless: suspend () -> T?, fallback: suspend () -> U?): Pair<T?, U?> {
        val preferred = AtomicReference<T?>(null)
        val alternative = AtomicReference<U?>(null)
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val flac = async { lossless().also(preferred::set) }
                val other = async { fallback().also(alternative::set) }
                if (flac.await() != null) other.cancel() else other.await()
            }
        }
        return preferred.get() to alternative.get()
    }
}
