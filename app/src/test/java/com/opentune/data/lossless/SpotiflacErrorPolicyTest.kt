package com.opentune.data.lossless

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotiflacErrorPolicyTest {
    @Test fun genericHttpErrorsDoNotEraseVerification() {
        for (code in listOf(401, 403, 428, 429, 503)) {
            assertFalse(SpotiflacErrorPolicy.requiresVerification(LosslessHttp.HttpFailure(code)))
            assertFalse(SpotiflacErrorPolicy.requiresVerification(LosslessHttp.HttpFailure(code, "<html>unavailable</html>")))
        }
        assertFalse(SpotiflacErrorPolicy.requiresVerification(LosslessHttp.HttpFailure(401,
            """{"origin":"provider","code":"SESSION_INVALID","action":"bootstrap_session"}""")))
        assertTrue(SpotiflacErrorPolicy.requiresVerification(LosslessHttp.HttpFailure(401,
            """{"origin":"gateway","code":"SESSION_INVALID","action":"bootstrap_session"}""")))
        assertTrue(SpotiflacErrorPolicy.requiresVerification(LosslessHttp.HttpFailure(428,
            """{"origin":"gateway","code":"VERIFY_REQUIRED","action":"verify"}""")))
    }
    @Test fun retriesOnlyExplicitTransientProviderErrorsOnceAndHonorsServerDelay() {
        val body = """{"origin":"provider","code":"PROVIDER_UNAVAILABLE","retryable":true,"retry_mode":"same_operation","retry_after_seconds":1}"""
        assertEquals(1000L, SpotiflacErrorPolicy.retryDelayMs(LosslessHttp.HttpFailure(503, body), 0))
        assertNull(SpotiflacErrorPolicy.retryDelayMs(LosslessHttp.HttpFailure(503, body), 1))
        assertNull(SpotiflacErrorPolicy.retryDelayMs(LosslessHttp.HttpFailure(503, body, 60), 0))
        assertNull(SpotiflacErrorPolicy.retryDelayMs(LosslessHttp.HttpFailure(503, body.replace("true", "false")), 0))
        assertNull(SpotiflacErrorPolicy.retryDelayMs(LosslessHttp.HttpFailure(503, "{}"), 0))
        assertNull(SpotiflacErrorPolicy.retryDelayMs(LosslessHttp.HttpFailure(401, body), 0))
    }
    @Test fun parsesRetryAfterDatesAndDoesNotIgnoreUnknownDelayHeaders() {
        val now = Instant.parse("2026-10-15T00:00:00Z")
        assertEquals(60L, LosslessHttp.retryAfterSeconds("Thu, 15 Oct 2026 00:01:00 GMT", now))
        assertEquals(2L, LosslessHttp.retryAfterSeconds("2", now))
        assertEquals(Long.MAX_VALUE, LosslessHttp.retryAfterSeconds("wait", now))
        assertNull(LosslessHttp.retryAfterSeconds(null, now))
    }
}
