package com.opentune.data.lossless

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotiflacGatedTest {
    private val now = Instant.parse("2026-10-09T00:00:00Z")

    @Test fun signingMatchesIndependentHmacVector() {
        val headers = SpotiflacGated.signHeaders("test-session", "test-secret", "/api/dl", """{"id":"123","quality":"LOSSLESS"}""".toByteArray(), now, "00112233445566778899aabb")
        assertEquals("MbML0aLmcnZRIzEVgNk4CDiz3B5ABAUzlgu6pxfL41A", headers["X-Sig-Signature"])
        assertEquals("2026-10-09T00:00:00.000Z", headers["X-Sig-Timestamp"])
        val next = SpotiflacGated.signHeaders("test-session", "test-secret", "/api/dl", byteArrayOf(), now.plusSeconds(300), "different")
        assertNotEquals(headers["X-Sig-Signature"], next["X-Sig-Signature"])
    }

    @Test fun invalidOrExpiredSessionsAreNotUsed() {
        assertNull(SpotiflacSession.parse("{}", now))
        assertNull(SpotiflacSession.parse("""{"session_id":"id","session_secret":"secret","expires_at":"2026-10-08T00:00:00Z"}""", now))
        assertNull(SpotiflacSession.parse("""{"session_id":"","session_secret":"secret","expires_at":"2026-10-10T00:00:00Z"}""", now))
        assertNotNull(SpotiflacSession.parse("""{"session_id":"id","session_secret":"secret","expires_at":"2026-10-10T00:00:00Z"}""", now))
    }
}
