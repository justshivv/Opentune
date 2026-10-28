package com.opentune.data.spotify

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpotifySyncRecoveryTest {
    @Test fun dnsFailureIsReadableAndDoesNotSuggestSigningOut() {
        val error = IOException("transport", UnknownHostException("api-partner.spotify.com: No address associated with hostname"))
        assertTrue(spotifyConnectionFailure(error))
        assertEquals("Can't reach Spotify. Check your connection or DNS settings", spotifySyncError(error))
    }

    @Test fun temporaryDnsFailureRetriesReadAndKeepsAccount() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        var notices = 0
        val answer = recoverSpotifyRead({ true }, { true }, { notices++ }, { delays += it }) {
            if (calls++ == 0) throw UnknownHostException("spotify")
            "playlists"
        }
        assertEquals("playlists", answer)
        assertEquals(2, calls)
        assertEquals(listOf(5_000L), delays)
        assertEquals(1, notices)
    }

    @Test fun permanentDnsFailuresAreBoundedAndOfflineReadsWaitForConnectivity() = runBlocking {
        for (online in listOf(true, false)) {
            var calls = 0
            val failure = runCatching {
                recoverSpotifyRead({ online }, { true }, {}, {}) { calls++; throw UnknownHostException("spotify") }
            }.exceptionOrNull()
            assertTrue(failure is UnknownHostException)
            assertEquals(if (online) 3 else 1, calls)
        }
    }

    @Test fun permissionErrorsAndAccountChangesAreNotRetried() = runBlocking {
        var calls = 0
        val denied = IOException("Spotify denied this request")
        assertSame(denied, runCatching {
            recoverSpotifyRead({ true }, { true }, {}, { fail("must not wait") }) { calls++; throw denied }
        }.exceptionOrNull())
        assertEquals(1, calls)
        assertTrue(runCatching {
            recoverSpotifyRead({ true }, { false }, {}, {}) { calls++; "unexpected" }
        }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, calls)
    }
}
