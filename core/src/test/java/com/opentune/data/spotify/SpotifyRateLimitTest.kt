package com.opentune.data.spotify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SpotifyRateLimitTest {
    @Test fun honorsSecondsAndHttpDateWithBackoffForMissingHeader() {
        assertEquals(3000L, SpotifyRateLimit.retryAfterMs("3", 0))
        assertEquals(60_000L, SpotifyRateLimit.retryAfterMs("Wed, 28 Oct 2026 10:01:00 GMT", Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()))
        assertEquals(15_000L, SpotifyRateLimit.retryAfterMs(null, 0))
        assertEquals(30_000L, SpotifyRateLimit.retryAfterMs("invalid", 0, 1))
    }

    @Test fun retryWaitsForSpotifyAndOnlyThenConfirms() = runBlocking {
        var time = 0L
        val waits = mutableListOf<Long>()
        val gate = SpotifyRateLimit({ time }, { 0L }, { waits += it; time += it })
        var requests = 0
        val result = gate.execute {
            if (++requests == 1) SpotifyRateLimit.Reply(429, "", "3") else SpotifyRateLimit.Reply(201, "saved")
        }
        assertEquals(201, result.code)
        assertEquals(listOf(3000L), waits)
        assertEquals(2, requests)
    }

    @Test fun longCooldownIsSharedWithoutRetryingOrPretendingSuccess() = runBlocking {
        var requests = 0
        val gate = SpotifyRateLimit({ 0L }, { 0L }, { error("Must not wait for hours") })
        repeat(2) {
            try {
                gate.execute { requests++; SpotifyRateLimit.Reply(429, "", "3600") }
                fail("Expected rate limit")
            } catch (e: SpotifyRateLimit.Limited) { assertEquals(3_600_000L, e.waitMs) }
        }
        assertEquals("The second call must not send another request during cooldown", 1, requests)
    }

    @Test fun ambiguousWriteFailuresAreNeverRetried() = runBlocking {
        var requests = 0
        val gate = SpotifyRateLimit({ 0L }, { 0L }, {})
        try { gate.execute { requests++; throw IOException("Connection lost after upload") }; fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(1, requests)
    }

    @Test fun repeated429sStopAfterTwoRetries() = runBlocking {
        var time = 0L
        var requests = 0
        val gate = SpotifyRateLimit({ time }, { 0L }, { time += it })
        try { gate.execute { requests++; SpotifyRateLimit.Reply(429, "", "1") }; fail("Expected rate limit") }
        catch (_: SpotifyRateLimit.Limited) { }
        assertEquals(3, requests)
    }
    @Test fun negativeMonotonicOriginAndRequestSpacingAreSafe() = runBlocking {
        var time = -9000L
        val waits = mutableListOf<Long>()
        val gate = SpotifyRateLimit({ time }, { 0L }, { waits += it; time += it })
        repeat(2) { gate.execute { SpotifyRateLimit.Reply(200, "ok") } }
        assertEquals(listOf(250L), waits)
    }

    @Test fun permissionsAndServerFailuresAreNotRetried() = runBlocking {
        for (code in listOf(401, 403, 500)) {
            var requests = 0
            val gate = SpotifyRateLimit({ 0L }, { 0L }, {})
            assertEquals(code, gate.execute { requests++; SpotifyRateLimit.Reply(code, "") }.code)
            assertEquals(1, requests)
        }
    }

}
