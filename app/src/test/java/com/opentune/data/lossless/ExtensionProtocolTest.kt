package com.opentune.data.lossless

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtensionProtocolTest {
    private val state = "a-long-random-browser-state"
    @Test fun authTabReturnsTheManifestCallbackOnlyThroughItsBoundResult() {
        val original = ExtensionProtocol.challenge(JSONObject().put("challenge_id", "fixture"), state)!!.first
        val tab = ExtensionProtocol.authTabUrl(original, state)!!
        val callback = Uri.parse(tab.toHttpUrl().queryParameter("cb"))
        assertEquals("spotiflac", callback.scheme)
        assertEquals("session-grant", callback.host)
        assertEquals(state, callback.getQueryParameter("state"))
        val result = callback.buildUpon().appendQueryParameter("grant", "fixture-grant").build()
        assertEquals("fixture-grant", ExtensionProtocol.browserGrant(result, state))
        assertFalse(ExtensionProtocol.isCallback(result))
        assertNull(ExtensionProtocol.grant(result, state))
        assertNull(ExtensionProtocol.browserGrant(result, "wrong-state"))
        assertNull(ExtensionProtocol.browserGrant(Uri.parse("spotiflac://session-grant?grant=fixture-grant"), state))
        assertNull(ExtensionProtocol.browserGrant(result.buildUpon().appendQueryParameter("grant", "duplicate").build(), state))
        assertNull(ExtensionProtocol.authTabUrl("https://evil.example/v2/challenge", state))
        assertNull(ExtensionProtocol.authTabUrl(original, "wrong-state"))
    }
    @Test fun signingUsesBase64RollingKeyAndEscapedFullPath() {
        val headers = ExtensionProtocol.headers(ExtensionProtocol.Session("session", "secret", Instant.MAX), "POST",
            "/v2/dl/amazeamazeamaze", """{"asin":"B012345678","codec":"flac"}""", "nonce", Instant.parse("2026-10-15T00:00:00Z"))
        // Independently calculated with Python hashlib/hmac, not the implementation under test.
        assertEquals("LYcRr6xNJIjM0eX4JaEmhbnTmx7624FXdZpl6o_zLxc", headers["X-Zarz-Signature"])
        assertEquals("2026-10-15T00:00:00.000Z", headers["X-Zarz-Timestamp"])
        assertEquals("extension", headers["X-Zarz-Platform"])
        assertFalse(headers.keys.any { it.startsWith("X-Sig-") })
    }
    @Test fun browserChallengeReturnsToOpenTuneAndKeepsIssuedState() {
        val (page, actualState) = ExtensionProtocol.challenge(JSONObject().put("auth_url",
            "${ExtensionProtocol.BASE}/challenge?id=issued&state=$state"), "different-random-state")!!
        assertEquals(state, actualState)
        val callback = Uri.parse(page.toHttpUrl().queryParameter("cb"))
        assertEquals("opentune", callback.scheme)
        assertEquals("provider-session", callback.host)
        assertEquals("v2grant", callback.getQueryParameter("cb_version"))
        assertEquals(state, callback.getQueryParameter("state"))
        assertEquals("issued", page.toHttpUrl().queryParameter("id"))
        assertNotNull(ExtensionProtocol.challenge(JSONObject().put("challenge_id", "issued"), state))
        for (url in listOf("https://evil.example/v2/challenge", "http://api.zarz.moe/v2/challenge",
            "https://api.zarz.moe/v2/other", "https://api.zarz.moe:444/v2/challenge", "https://user@api.zarz.moe/v2/challenge")) {
            assertNull(url, ExtensionProtocol.challenge(JSONObject().put("auth_url", url), state))
        }
    }
    @Test fun callbacksRejectDuplicatesWrongAppAndStateConfusion() {
        val url = "opentune://provider-session?state=$state&grant=issued"
        assertEquals("issued", ExtensionProtocol.grant(Uri.parse(url), state))
        for (candidate in listOf("$url&grant=another", "$url&code=another", "$url&state=$state", "$url#fragment",
            url.replace(state, "wrong"), url.replace("opentune", "spotiflac"), url.replace("session?", "session/path?"),
            url.replace("issued", "line%0Abreak"), url.replace("issued", ""))) {
            assertNull(candidate, ExtensionProtocol.grant(Uri.parse(candidate), state))
        }
    }
    @Test fun outagesAndBadRequestSignaturesDoNotBecomeExpiredSessions() {
        assertTrue(ExtensionProtocol.verificationRequired(428, """{"origin":"gateway","code":"VERIFY_REQUIRED","action":"verify"}"""))
        assertTrue(ExtensionProtocol.verificationRequired(401, """{"origin":"gateway","code":"SESSION_INVALID","action":"bootstrap_session"}"""))
        assertFalse(ExtensionProtocol.verificationRequired(401, """{"origin":"gateway","code":"SESSION_INVALID"}"""))
        assertFalse(ExtensionProtocol.verificationRequired(503, """{"origin":"provider","code":"PROVIDER_UNAVAILABLE"}"""))
        assertFalse(ExtensionProtocol.verificationRequired(403, """{"origin":"gateway","code":"REQUEST_AUTH_INVALID"}"""))
        assertFalse(ExtensionProtocol.verificationRequired(401, "<html>unavailable</html>"))
        assertFalse(ExtensionProtocol.verificationRequired(401, """{"origin":"provider","code":"SESSION_INVALID"}"""))
    }
    @Test fun explicitPasteAcceptsUpstreamAndNestedResultsButStillBindsState() {
        val direct = "opentune://provider-session?state=$state&grant=issued"
        val upstream = "spotiflac://session-grant?state=$state&code=issued"
        val wrapped = "${ExtensionProtocol.BASE}/challenge?cb=${Uri.encode(upstream)}"
        for (value in listOf(direct, upstream, wrapped)) assertEquals("issued", ExtensionProtocol.pastedGrant(value, state))
        for (value in listOf(upstream.replace(state, "wrong"), "$upstream&code=another", "$upstream&state=$state",
            wrapped.replace("api.zarz.moe", "evil.example"), "$wrapped&state=wrong", "$wrapped&cb=${Uri.encode(direct)}",
            upstream.replace("session-grant", "session-grant/path"), upstream.replace("issued", "line%0Abreak"),
            "${ExtensionProtocol.BASE}/challenge?id=original-verification&cb=${Uri.encode(ExtensionProtocol.CALLBACK)}")) {
            assertNull(value, ExtensionProtocol.pastedGrant(value, state))
        }
        assertFalse(ExtensionProtocol.isCallback(Uri.parse(upstream)))
    }
    @Test fun pendingVerificationExpiresAndRejectsClockRollbackOrChangedAdapter() {
        val pending = JSONObject().put("created", 1_000L).put("version", ExtensionProtocol.VERSION)
        assertTrue(ExtensionProtocol.pendingActive(pending, 1_000L))
        assertTrue(ExtensionProtocol.pendingActive(pending, 180_999L))
        assertFalse(ExtensionProtocol.pendingActive(pending, 181_000L))
        assertFalse(ExtensionProtocol.pendingActive(pending, 999L))
        assertFalse(ExtensionProtocol.pendingActive(pending.put("version", "old"), 1_000L))
    }
    @Test fun copiedGrantParametersNeedNoUrlButStillRequireTheActiveState() {
        for (text in listOf("grant=sample-grant&state=$state", "state=$state&grant=sample-grant",
            "?code=sample-grant&state=$state")) {
            assertEquals("sample-grant", ExtensionProtocol.pastedGrant(text, state))
        }
        for (text in listOf("grant=sample-grant", "grant=sample-grant&state=wrong",
            "grant=sample-grant&state=$state&state=$state", "grant=sample-grant&code=another&state=$state",
            "grant=sample-grant&grant=another&state=$state", "grant=sample-grant&state=$state#fragment")) {
            assertNull(text, ExtensionProtocol.pastedGrant(text, state))
        }
    }
    @Test fun requiresUnexpiredWellFormedCredentials() {
        val json = JSONObject().put("session_id", "id").put("session_secret", "secret").put("expires_at", "2026-10-15T01:00:00Z")
        assertNotNull(ExtensionProtocol.session(json, Instant.parse("2026-10-15T00:00:00Z")))
        assertNull(ExtensionProtocol.session(json, Instant.parse("2026-10-15T01:00:00Z")))
        assertNull(ExtensionProtocol.session(json.put("expires_at", "invalid")))
        assertNull(ExtensionProtocol.session(JSONObject().put("session_id", "id")))
    }
    @Test fun manualCodeRecoveryAcceptsOnlyTheProvidersExactCodeFormat() {
        val code = "gr_test_code_0123456789"
        assertEquals(code, ExtensionProtocol.manualGrantCode("grant=$code"))
        assertEquals(code, ExtensionProtocol.manualGrantCode("  grant=$code  "))
        for (text in listOf(code, "code=$code", "grant=short", "grant=", "?grant=$code",
            "grant=$code&state=wrong", "grant=$code&state=", "grant=$code&grant=$code",
            "grant=$code#fragment", "grant=gr_test%5fcode_0123456789", "grant=gr_test\ncode_0123456789",
            "${ExtensionProtocol.CALLBACK}?grant=$code", "https://api.zarz.moe/v2/challenge?grant=$code",
            "grant=gr_${"x".repeat(257)}")) {
            assertNull(text, ExtensionProtocol.manualGrantCode(text))
        }
        assertNull(ExtensionProtocol.grant(Uri.parse("${ExtensionProtocol.CALLBACK}?grant=$code"), state))
        assertNull(ExtensionProtocol.pastedGrant("grant=$code", state))
    }
}
