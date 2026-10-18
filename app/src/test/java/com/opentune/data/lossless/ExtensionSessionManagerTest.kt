package com.opentune.data.lossless

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtensionSessionManagerTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private var stored = JSONObject()
    private var changes = 0
    private var failSessionWrite = false
    private val session get() = JSONObject().put("session_id", "test-session").put("session_secret", "test-secret")
        .put("expires_at", Instant.now().plusSeconds(7200).toString()).toString()
    private fun manager(transport: suspend (Request) -> String): ExtensionSessionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return ExtensionSessionManager(
            { JSONObject(stored.toString()) },
            {
                if (failSessionWrite && it.has("session")) throw IOException("storage unavailable")
                stored = JSONObject(it.toString())
            }, transport, { changes++ }, scope,
        ).also { it.init() }
    }
    private fun callback() = Uri.parse(ExtensionProtocol.CALLBACK).buildUpon()
        .appendQueryParameter("state", stored.getJSONObject("pending").getString("state"))
        .appendQueryParameter("grant", "test-grant").build().toString()
    @After fun cleanup() { scopes.forEach { it.cancel() } }

    @Test fun runtimeRequestsCarryUpstreamHeadersAndKeepProviderDenialConnected() = runBlocking {
        stored = JSONObject().put("install_id", "fixture").put("enabled", true).put("scope", ExtensionProtocol.VERSION).put("session", JSONObject(session))
        val manager = manager { request ->
            assertEquals("SpotiFLAC-Mobile/${ExtensionProtocol.VERSION}", request.header("User-Agent"))
            assertEquals("application/json", request.header("Accept"))
            assertEquals("fixture-ticket", request.header("X-Zarz-Ticket"))
            assertNotNull(request.header("X-Zarz-Signature"))
            throw LosslessHttp.HttpFailure(403, """{"origin":"provider","code":"PROVIDER_AUTH_FAILED"}""")
        }
        try { manager.signed("/dl/amazeamazeamaze", JSONObject().put("asin", "B012345678"), mapOf("X-Zarz-Ticket" to "fixture-ticket")); fail() }
        catch (e: LosslessHttp.HttpFailure) { assertEquals("PROVIDER_AUTH_FAILED", ProviderDiagnostic.from(e).code) }
        assertEquals("Connected", manager.status.value)
    }

    @Test fun reopeningBrowserKeepsTheOriginalChallenge() = runBlocking {
        var requests = 0
        val manager = manager { requests++; """{"challenge_id":"test-challenge"}""" }
        val first = manager.connect()
        assertNotNull(first)
        assertEquals(first, manager.connect())
        assertEquals(1, requests)
        assertNull(manager.current())
        assertEquals("Waiting for the browser result", manager.status.value)
    }

    @Test fun callbackIsPersistedAndDuplicateDeliveryExchangesOnlyOnce() = runBlocking {
        val response = CompletableDeferred<String>()
        var exchanges = 0
        val manager = manager { request ->
            if (request.url.encodedPath.endsWith("bootstrap")) """{"challenge_id":"test-challenge"}"""
            else {
                exchanges++
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertEquals(stored.getString("install_id"), body.getString("install_id"))
                assertEquals("test-grant", body.getString("grant"))
                assertEquals("extension", body.getString("platform"))
                assertEquals(ExtensionProtocol.VERSION, body.getString("app_version"))
                response.await()
            }
        }
        manager.connect()
        val result = callback()
        assertTrue(manager.receiveCallback(result))
        assertEquals("test-grant", stored.getJSONObject("pending").getString("grant"))
        assertEquals("Saving verified session…", manager.status.value)
        assertTrue(manager.receiveCallback(result))
        assertEquals(1, exchanges)
        response.complete(session)
        withTimeout(3000) { manager.status.first { it == "Connected" } }
        assertNotNull(manager.current())
        assertFalse(stored.has("pending"))
        assertEquals(1, changes)
    }

    @Test fun failedExchangeSurvivesProcessRestartAndRetriesStoredGrant() = runBlocking {
        val first = manager { request ->
            if (request.url.encodedPath.endsWith("bootstrap")) """{"challenge_id":"test-challenge"}"""
            else throw IOException("offline")
        }
        first.connect()
        assertTrue(first.receiveCallback(callback()))
        withTimeout(3000) { first.canRetry.first { it } }
        assertNull(first.current())
        assertTrue(first.status.value.contains("could not be saved"))
        scopes.first().cancel()
        var exchanges = 0
        val restarted = manager { exchanges++; session }
        withTimeout(3000) { restarted.status.first { it == "Connected" } }
        assertEquals(1, exchanges)
        assertEquals(1, changes)
        assertFalse(stored.has("pending"))
    }

    @Test fun disconnectWhileExchangeRunsCannotReconnectFromItsLateResponse() = runBlocking {
        val response = CompletableDeferred<String>()
        val manager = manager { request ->
            if (request.url.encodedPath.endsWith("bootstrap")) """{"challenge_id":"test-challenge"}"""
            else response.await()
        }
        manager.connect()
        manager.receiveCallback(callback())
        manager.disconnect()
        response.complete(session)
        withTimeout(3000) { manager.status.first { it == "Not connected" } }
        assertNull(manager.current())
        assertFalse(stored.has("session"))
        assertFalse(stored.has("pending"))
    }

    @Test fun failingStorageDoesNotClaimConnectedOrLoseTheRetry() = runBlocking {
        val manager = manager { request ->
            if (request.url.encodedPath.endsWith("bootstrap")) """{"challenge_id":"test-challenge"}""" else session
        }
        manager.connect()
        failSessionWrite = true
        manager.receiveCallback(callback())
        withTimeout(3000) { manager.canRetry.first { it } }
        assertNull(manager.current())
        assertFalse(stored.has("session"))
        assertEquals(0, changes)
        failSessionWrite = false
        manager.retryCompletion()
        withTimeout(3000) { manager.status.first { it == "Connected" } }
        assertEquals(1, changes)
    }

    @Test fun mismatchedCallbackDoesNotExchangeOrOverwriteThePendingVerification() = runBlocking {
        var requests = 0
        val manager = manager { requests++; """{"challenge_id":"test-challenge"}""" }
        manager.connect()
        val before = stored.toString()
        val wrong = Uri.parse(callback()).buildUpon().clearQuery()
            .appendQueryParameter("state", "wrong-state").appendQueryParameter("grant", "test-grant").build()
        assertFalse(manager.receiveCallback(wrong.toString()))
        assertFalse(manager.receiveCallback(wrong.toString(), pasted = true))
        assertEquals(before, stored.toString())
        assertEquals(1, requests)
    }

    @Test fun grantOnlyCodeNeedsExplicitSubmissionAndProviderAcceptance() = runBlocking {
        val response = CompletableDeferred<String>()
        val code = "gr_test_code_0123456789"
        var exchanges = 0
        val manager = manager { request ->
            if (request.url.encodedPath.endsWith("bootstrap")) """{"challenge_id":"test-challenge"}"""
            else {
                exchanges++
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertEquals(code, body.getString("grant"))
                assertEquals(stored.getString("install_id"), body.getString("install_id"))
                response.await()
            }
        }
        assertFalse(manager.receiveCallback("grant=$code", pasted = true))
        manager.connect()
        assertFalse(manager.receiveCallback("grant=$code"))
        assertFalse(manager.receiveCallback("${ExtensionProtocol.CALLBACK}?grant=$code", pasted = true))
        assertFalse(manager.receiveCallback("grant=$code&state=wrong", pasted = true))
        assertEquals(0, exchanges)
        assertTrue(manager.receiveCallback("grant=$code", pasted = true))
        assertEquals(1, exchanges)
        assertNull(manager.current())
        assertEquals("Saving verified session…", manager.status.value)
        assertTrue(manager.receiveCallback("grant=$code", pasted = true))
        assertFalse(manager.receiveCallback("grant=gr_another_code_0123456789", pasted = true))
        assertEquals(1, exchanges)
        response.complete(session)
        withTimeout(3000) { manager.status.first { it == "Connected" } }
        assertNotNull(manager.current())
    }

    @Test fun expiredOrCancelledChallengeCannotAcceptManualCode() = runBlocking {
        var requests = 0
        val first = manager { requests++; """{"challenge_id":"test-challenge"}""" }
        first.connect()
        stored.getJSONObject("pending").put("created", 0)
        val expired = manager { requests++; error("No exchange expected") }
        assertFalse(expired.receiveCallback("grant=gr_test_code_0123456789", pasted = true))
        first.disconnect()
        assertFalse(first.receiveCallback("grant=gr_test_code_0123456789", pasted = true))
        assertEquals(1, requests)
        assertNull(expired.current())
    }

    @Test fun rejectedManualCodeNeverBecomesConnected() = runBlocking {
        val manager = manager { request ->
            if (request.url.encodedPath.endsWith("bootstrap")) """{"challenge_id":"test-challenge"}"""
            else throw LosslessHttp.HttpFailure(400, "invalid grant")
        }
        manager.connect()
        assertTrue(manager.receiveCallback("grant=gr_test_code_0123456789", pasted = true))
        withTimeout(3000) { manager.canRetry.first { it } }
        assertNull(manager.current())
        assertTrue(manager.status.value.contains("HTTP 400"))
        assertFalse(stored.has("session"))
    }
}
