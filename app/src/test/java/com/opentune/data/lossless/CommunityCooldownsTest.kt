package com.opentune.data.lossless

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CommunityCooldownsTest {
    @Test fun honoursRetryAfterAndReopensOnlyAfterProviderPause() {
        var time = 1_000L
        val pauses = CommunityCooldowns { time }
        val error = LosslessHttp.HttpFailure(503, 120_000)
        assertEquals(120_000L, pauses.record("provider", error)!!.remainingMs)
        assertNull(pauses.current("other-provider"))
        time += 30_000
        assertEquals(90_000L, pauses.current("provider")!!.remainingMs)
        // A late concurrent response cannot shorten an already announced pause.
        pauses.record("provider", LosslessHttp.HttpFailure(503, 1_000))
        assertEquals(90_000L, pauses.current("provider")!!.remainingMs)
        time += 90_000
        assertNull(pauses.current("provider"))
    }

    @Test fun onlyAvailabilityAndRateLimitsPauseRequests() {
        val pauses = CommunityCooldowns { 0L }
        assertNull(pauses.record("auth", LosslessHttp.HttpFailure(401)))
        assertNull(pauses.record("track", LosslessHttp.HttpFailure(404)))
        assertEquals(30_000L, pauses.record("busy", LosslessHttp.HttpFailure(503))!!.remainingMs)
        assertEquals(45_000L, pauses.record("rate", LosslessHttp.HttpFailure(429, 45_000))!!.remainingMs)
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDatesWithoutOverflow() {
        val now = Instant.parse("2026-10-13T21:00:00Z")
        assertEquals(120_000L, LosslessHttp.retryAfterMillis("120", now))
        assertEquals(120_000L, LosslessHttp.retryAfterMillis("Tue, 13 Oct 2026 21:02:00 GMT", now))
        assertEquals(0L, LosslessHttp.retryAfterMillis("Tue, 13 Oct 2026 20:00:00 GMT", now))
        assertEquals(86_400_000L, LosslessHttp.retryAfterMillis(Long.MAX_VALUE.toString(), now))
        assertNull(LosslessHttp.retryAfterMillis("-1", now))
        assertNull(LosslessHttp.retryAfterMillis("invalid", now))
    }
}
