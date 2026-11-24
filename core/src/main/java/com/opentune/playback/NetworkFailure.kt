package com.opentune.playback

import androidx.media3.common.PlaybackException

/**
 * Whether a playback error is the connection going, not the song or its source failing: a lookup
 * or connection that couldn't be made, or one that broke off, however deep in the error it sits
 * (a lossless stream's comes wrapped as `IOException(ExecutionException(UnknownHostException))`).
 * The error can arrive before Android has noticed the network is gone, so this doesn't ask it.
 */
object NetworkFailure {
    private val CODES = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )

    fun of(error: Throwable): Boolean {
        if (error is PlaybackException && error.errorCode in CODES) return true
        return generateSequence(error) { it.cause }.take(12).any {
            // Socket errors cover a refused, reset or aborted connection; a timed-out read is
            // an InterruptedIOException. A secure connection cut mid-read comes as an SSLException,
            // but a refused certificate is no dropped connection.
            it is java.net.UnknownHostException || it is java.net.SocketException || it is java.io.InterruptedIOException ||
                (it is javax.net.ssl.SSLException && it !is javax.net.ssl.SSLHandshakeException && it !is javax.net.ssl.SSLPeerUnverifiedException)
        }
    }

    /** What the listener reads when the connection goes mid-song. */
    const val MESSAGE = "No internet connection. The song plays on when you're back online."
}
