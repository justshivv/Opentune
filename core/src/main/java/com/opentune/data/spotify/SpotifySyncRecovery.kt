package com.opentune.data.spotify

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun spotifyConnectionFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any {
        it is UnknownHostException || it is ConnectException || it is SocketTimeoutException
    }

internal fun spotifySyncError(error: Throwable): String = if (spotifyConnectionFailure(error))
    "Can't reach Spotify. Check your connection or DNS settings"
else error.message?.take(180) ?: "Couldn't refresh Spotify"

/** Sync only reads remote playlists. Unlike song writes, these reads can safely be retried. */
internal suspend fun <T> recoverSpotifyRead(
    online: () -> Boolean,
    connected: () -> Boolean,
    onRetry: (Throwable) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
    read: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        if (!connected()) throw CancellationException("Spotify account changed")
        try { return read() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!spotifyConnectionFailure(e) || retries >= 2 || !online()) throw e
            onRetry(e)
            wait(if (retries++ == 0) 5_000 else 15_000)
            if (!online()) throw e
        }
    }
}
