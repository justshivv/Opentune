package com.opentune.data.lossless

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotiflacVerificationApiTest {
    private val page = "https://api.zarz.moe/v2/challenge?id=chl_example"

    @Test fun loadsTheProviderPageForTheIssuedChallengeInsteadOfConstructingAWidget() {
        assertEquals(page, SpotiflacVerificationApi.challengeUrl(JSONObject("""{"challenge_id":"chl_example","server_nonce":"nonce","turnstile_site_key":"key"}""")))
        assertNull(SpotiflacVerificationApi.challengeUrl(JSONObject("{}")))
        assertNull(SpotiflacVerificationApi.challengeUrl(JSONObject().put("challenge_id", "a&cb=https://elsewhere.example")))
    }

    @Test fun grantPollingIsRestrictedToTheExactChallengePage() {
        assertTrue(SpotiflacVerificationApi.isChallengePage(page, page))
        assertTrue(SpotiflacVerificationApi.isChallengePage("$page#help", page))
        for (other in listOf(null, "about:blank", "http://api.zarz.moe/v2/challenge?id=chl_example",
            "https://api.zarz.moe.evil.example/v2/challenge?id=chl_example",
            "https://api.zarz.moe/v2/challenge?id=another", "https://api.zarz.moe/")) {
            assertFalse(SpotiflacVerificationApi.isChallengePage(other, page))
        }
    }

    @Test fun javascriptResultIsDecodedOnceAndRejectsNonStringOrOversizedResults() {
        assertEquals("grant_abc-123", SpotiflacVerificationApi.grantFromJavascript("\"grant_abc-123\""))
        for (invalid in listOf(null, "null", "{}", "42", "\"\"", "unquoted", "\"a\\nb\"", "\"" + "x".repeat(8_193) + "\"")) {
            assertNull(SpotiflacVerificationApi.grantFromJavascript(invalid))
        }
    }

    @Test fun retriesAndRestartsKeepTheSameInstallIdentity() {
        val context = RuntimeEnvironment.getApplication()
        SpotiflacSession.init(context)
        SpotiflacSession.clear()
        val id = SpotiflacSession.installId()
        assertTrue(Regex("[a-f0-9]{32}").matches(id))
        assertEquals(id, SpotiflacSession.installId())
        SpotiflacSession.init(context)
        assertEquals(id, SpotiflacSession.installId())
        assertFalse(SpotiflacSession.connected.value)
    }
}
