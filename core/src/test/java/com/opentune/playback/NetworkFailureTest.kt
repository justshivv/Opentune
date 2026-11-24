package com.opentune.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A dropped connection is told from a song or source that fails, however the error is wrapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NetworkFailureTest {
    private fun error(cause: Throwable?, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) = PlaybackException("failed", cause, code)

    @Test fun aLosslessStreamLosingItsConnection() {
        // As the phone showed it: IOException(ExecutionException(UnknownHostException)).
        val wrapped = java.io.IOException(java.util.concurrent.ExecutionException(java.net.UnknownHostException("tracks.example")))
        assertTrue(NetworkFailure.of(error(wrapped)))
        assertTrue(NetworkFailure.of(error(java.net.SocketException("Connection reset"))))
        assertTrue(NetworkFailure.of(error(java.net.SocketTimeoutException("timeout"))))
        assertTrue(NetworkFailure.of(error(javax.net.ssl.SSLException("Read error: connection abort"))))
        assertTrue(NetworkFailure.of(error(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
    }

    @Test fun aSongOrSourceThatFails() {
        assertFalse(NetworkFailure.of(error(null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertFalse(NetworkFailure.of(error(javax.net.ssl.SSLHandshakeException("bad certificate"))))
        assertFalse(NetworkFailure.of(error(IllegalStateException("no stream"), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
    }
}
