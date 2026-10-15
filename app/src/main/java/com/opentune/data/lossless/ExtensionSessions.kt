package com.opentune.data.lossless

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** One reviewed adapter today. Session material and pending browser state are encrypted at rest. */
internal object ExtensionSessions {
    private lateinit var vault: ExtensionVault
    private var data = JSONObject()
    private val connectionLock = Mutex()
    val status = MutableStateFlow("Not connected")
    val revision = MutableStateFlow(0L)
    @Synchronized fun init(context: Context) {
        vault = ExtensionVault(context)
        data = vault.read()
        if (!data.has("install_id")) data.put("install_id", ExtensionProtocol.randomHex(16))
        updateStatus()
        AmazonAudio.init(context)
    }
    @Synchronized private fun persist() { vault.write(data) }
    @Synchronized private fun updateStatus() {
        status.value = when {
            current() != null -> "Connected"
            data.has("pending") -> "Finish verification in your browser"
            data.optBoolean("enabled") -> "Verification required"
            else -> "Not connected"
        }
    }
    @Synchronized fun enabled() = data.optBoolean("enabled")
    @Synchronized fun current(): ExtensionProtocol.Session? = if (data.optBoolean("enabled") && data.optString("scope") == ExtensionProtocol.VERSION)
        data.optJSONObject("session")?.let { ExtensionProtocol.session(it) } else null
    @Synchronized private fun installId() = data.getString("install_id")
    @Synchronized private fun save(session: ExtensionProtocol.Session) {
        data.put("enabled", true).put("scope", ExtensionProtocol.VERSION).put("session", session.json()).remove("pending")
        persist(); updateStatus(); ExternalStreams.retryLookups(); revision.value++
    }
    @Synchronized fun disconnect() {
        data.remove("session"); data.remove("pending"); data.put("enabled", false)
        persist(); updateStatus(); ExternalStreams.retryLookups(); revision.value++
    }
    @Synchronized private fun invalidate(old: ExtensionProtocol.Session) {
        if (current() != old) return
        data.remove("session"); persist(); updateStatus()
    }
    /** Call only from an explicit user action. Background playback never opens Chrome. */
    suspend fun connect(): String? = withContext(Dispatchers.IO) { connectionLock.withLock {
        val generation = revision.value
        val url = "${ExtensionProtocol.BASE}/bootstrap".toHttpUrl().newBuilder()
            .addQueryParameter("install_id", installId()).addQueryParameter("app_version", ExtensionProtocol.VERSION).build()
        val root = JSONObject(request(Request.Builder().url(url).build()))
        synchronized(this@ExtensionSessions) {
            check(generation == revision.value) { "Connection was cancelled" }
            ExtensionProtocol.session(root)?.let { save(it); return@withLock null }
            val challenge = ExtensionProtocol.challenge(root, UUID.randomUUID().toString())
                ?: error("Provider returned an unsupported verification page")
            data.put("enabled", true).put("pending", JSONObject().put("state", challenge.second)
                .put("created", System.currentTimeMillis()).put("version", ExtensionProtocol.VERSION))
            persist(); updateStatus()
            challenge.first
        }
    } }
    suspend fun complete(uri: Uri): Boolean = withContext(Dispatchers.IO) { connectionLock.withLock {
        val generation = revision.value
        val pending = synchronized(this@ExtensionSessions) { data.optJSONObject("pending")?.toString()?.let(::JSONObject) } ?: return@withLock false
        val age = System.currentTimeMillis() - pending.optLong("created")
        if (age !in 0..600_000 || pending.optString("version") != ExtensionProtocol.VERSION) return@withLock false
        val grant = ExtensionProtocol.grant(uri, pending.optString("state")) ?: return@withLock false
        val body = JSONObject().put("grant", grant).put("install_id", installId())
            .put("app_version", ExtensionProtocol.VERSION).put("platform", "extension").toString()
        val response = JSONObject(request(post("/session/exchange", body)))
        val session = ExtensionProtocol.session(response) ?: error("Provider did not return a valid session")
        synchronized(this@ExtensionSessions) {
            if (generation != revision.value || data.optJSONObject("pending")?.optString("state") != pending.optString("state")) return@withLock false
            save(session)
        }
        true
    } }
    suspend fun signed(path: String, body: JSONObject, extraHeaders: Map<String, String> = emptyMap()): String {
        require(path in setOf("/tickets", "/dl/amazeamazeamaze"))
        val session = refreshedSession() ?: error("Verify Amazon Music in Audio settings")
        return signedRequest(path, body.toString(), session, extraHeaders)
    }
    private suspend fun refreshedSession(): ExtensionProtocol.Session? = connectionLock.withLock {
        val old = current() ?: return@withLock null
        if (old.expires.isAfter(Instant.now().plusSeconds(3600))) return@withLock old
        try {
            val response = JSONObject(signedRequest("/session/refresh", JSONObject().put("install_id", installId()).toString(), old))
            val merged = old.json().apply { response.keys().forEach { put(it, response.get(it)) } }
            val updated = ExtensionProtocol.session(merged) ?: return@withLock old
            synchronized(this@ExtensionSessions) {
                if (current() == old) {
                    // A routine refresh must not cancel/restart the playback lookup that requested it,
                    // nor consume a browser challenge opened while the old session is still usable.
                    data.put("session", updated.json()); persist(); updateStatus()
                }
            }
            current()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { current() } // An outage must not log a listener out.
    }
    private suspend fun signedRequest(path: String, body: String, session: ExtensionProtocol.Session, extra: Map<String, String> = emptyMap()): String {
        for (attempt in 0..1) {
            val signed = post(path, body).newBuilder().apply {
                ExtensionProtocol.headers(session, "POST", "/v2$path", body, ExtensionProtocol.randomHex(12)).forEach { (k, v) -> header(k, v) }
                extra.forEach { (k, v) -> header(k, v) }
            }.build()
            try { return request(signed) }
            catch (e: LosslessHttp.HttpFailure) {
                if (ExtensionProtocol.verificationRequired(e.code, e.responseBody)) invalidate(session)
                val wait = SpotiflacErrorPolicy.retryDelayMs(e, attempt) ?: throw e
                kotlinx.coroutines.delay(wait)
                if (current() != session) throw e
            }
        }
        error("Provider retry exhausted")
    }
    private fun post(path: String, body: String) = Request.Builder().url(ExtensionProtocol.BASE + path)
        .post(body.toRequestBody("application/json".toMediaType())).build()
    private suspend fun request(request: Request) = LosslessHttp.bytes(request, 1_048_576,
        timeoutMs = 12_000, followRedirects = false).toString(Charsets.UTF_8)
}
