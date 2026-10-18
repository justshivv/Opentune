package com.opentune.data.lossless

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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

/** Process-owned connection. Activity recreation never owns/cancels a grant exchange. */
internal object ExtensionSessions {
    private lateinit var context: Context
    private val managers = java.util.concurrent.ConcurrentHashMap<String, ExtensionSessionManager>()
    val changes = MutableStateFlow(0L)
    private val manager get() = forProvider("amazon")
    val status get() = manager.status
    val pending get() = manager.pending
    val canRetry get() = manager.canRetry
    val revision get() = manager.revision
    fun init(context: Context) { this.context = context.applicationContext; forProvider("amazon") }
    @Synchronized fun forProvider(id: String): ExtensionSessionManager {
        require(id in ProviderPackages.supported)
        val version = ProviderPackages.get(id)?.sessionVersion ?: if (id == "amazon") ExtensionProtocol.VERSION else error("Install the provider first")
        return managers.getOrPut("$id:$version") {
            val vault = ExtensionVault(context, if (id == "amazon" && version == ExtensionProtocol.VERSION) "data" else ExtensionProtocol.hash("$id:$version"))
            ExtensionSessionManager(vault::read, vault::write,
                { request -> LosslessHttp.bytes(request, 1_048_576, timeoutMs = 20_000, followRedirects = false).toString(Charsets.UTF_8) },
                { synchronized(this) { changes.value++ } }, version = version).also { it.init() }
        }
    }
    fun enabled() = manager.enabled()
    fun current() = manager.current()
    fun disconnect() = manager.disconnect()
    fun refreshStatus() = manager.refreshStatus()
    fun pendingUrl() = manager.pendingUrl()
    fun receiveCallback(text: String, pasted: Boolean = false): Boolean {
        ProviderPackages.installed.value.keys.forEach { forProvider(it) }
        return managers.values.any { it.receiveCallback(text, pasted) }
    }
    fun retryCompletion() = manager.retryCompletion()
    suspend fun connect() = manager.connect()
    suspend fun signed(path: String, body: JSONObject, extraHeaders: Map<String, String> = emptyMap()) = manager.signed(path, body, extraHeaders)
}

/** Injectable storage/transport allow the actual connection lifecycle to be regression tested. */
internal class ExtensionSessionManager(
    private val read: () -> JSONObject,
    private val write: (JSONObject) -> Unit,
    private val request: suspend (Request) -> String,
    private val onChanged: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val version: String = ExtensionProtocol.VERSION,
) {
    private var data = JSONObject()
    private val connectionLock = Mutex()
    // The browser result belongs to the application, not an Activity or settings dialog.
    private var exchanging = false
    val pending = MutableStateFlow(false)
    val canRetry = MutableStateFlow(false)
    val status = MutableStateFlow("Not connected")
    val revision = MutableStateFlow(0L)
    @Synchronized fun init() {
        data = read()
        if (!data.has("install_id")) data.put("install_id", ExtensionProtocol.randomHex(16))
        updateStatus()
        retryCompletion()
    }
    @Synchronized private fun persist() { write(data) }
    @Synchronized private fun updateStatus() {
        val request = data.optJSONObject("pending")
        val active = ExtensionProtocol.pendingActive(request, version = version)
        pending.value = active
        canRetry.value = active && !exchanging && !request?.optString("grant").isNullOrEmpty()
        status.value = when {
            current() != null -> "Connected"
            exchanging -> "Saving verified session…"
            request != null && !active -> "Verification expired. Connect again."
            !request?.optString("error").isNullOrEmpty() -> request!!.optString("error")
            active -> "Waiting for the browser result"
            data.optBoolean("enabled") -> "Verification required"
            else -> "Not connected"
        }
    }
    @Synchronized fun enabled() = data.optBoolean("enabled")
    @Synchronized fun current(): ExtensionProtocol.Session? = if (data.optBoolean("enabled") && data.optString("scope") == version)
        data.optJSONObject("session")?.let { ExtensionProtocol.session(it) } else null
    @Synchronized private fun installId() = data.getString("install_id")
    @Synchronized private fun save(session: ExtensionProtocol.Session) {
        val saved = JSONObject(data.toString()).put("enabled", true).put("scope", version).put("session", session.json())
        saved.remove("pending")
        write(saved)
        data = saved
        updateStatus(); onChanged(); revision.value++
    }
    @Synchronized fun disconnect() {
        data.remove("session"); data.remove("pending"); data.put("enabled", false)
        persist(); updateStatus(); onChanged(); revision.value++
    }
    @Synchronized private fun invalidate(old: ExtensionProtocol.Session) {
        if (current() != old) return
        data.remove("session"); persist(); updateStatus()
    }
    @Synchronized fun refreshStatus() = updateStatus()
    @Synchronized fun pendingUrl(): String? = data.optJSONObject("pending")
        ?.takeIf { ExtensionProtocol.pendingActive(it, version = version) }?.optString("url")?.takeIf { it.isNotEmpty() }

    /** Call only from an explicit user action. Background playback never opens Chrome. */
    suspend fun connect(): String? = withContext(Dispatchers.IO) { connectionLock.withLock {
        current()?.let { return@withLock null }
        // Reopen the same challenge; another click must not invalidate the browser's state.
        pendingUrl()?.let { return@withLock it }
        val generation = revision.value
        val url = "${ExtensionProtocol.BASE}/bootstrap".toHttpUrl().newBuilder()
            .addQueryParameter("install_id", installId()).addQueryParameter("app_version", version).build()
        val root = JSONObject(request(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "SpotiFLAC-Mobile/$version").build()))
        synchronized(this@ExtensionSessionManager) {
            check(generation == revision.value) { "Connection was cancelled" }
            ExtensionProtocol.session(root)?.let { save(it); return@withLock null }
            val challenge = ExtensionProtocol.challenge(root, UUID.randomUUID().toString())
                ?: error("Provider returned an unsupported verification page")
            val saved = JSONObject(data.toString()).put("enabled", true).put("pending", JSONObject().put("state", challenge.second)
                .put("url", challenge.first).put("created", System.currentTimeMillis()).put("version", version))
            write(saved); data = saved; updateStatus()
            challenge.first
        }
    } }

    /** Persist a callback or explicitly entered code before HTTP so recreation cannot lose it. */
    @Synchronized fun receiveCallback(text: String, pasted: Boolean = false): Boolean {
        val saved = JSONObject(data.toString())
        val request = saved.optJSONObject("pending") ?: return false
        if (!ExtensionProtocol.pendingActive(request, version = version)) { updateStatus(); return false }
        val state = request.optString("state")
        // Manual codes cannot prove callback-state correlation. Accept only the exact
        // grant=gr_… form after explicit user submission during an active challenge;
        // automatic intents and malformed/state-mismatched URLs never take this path.
        val grant = if (pasted) ExtensionProtocol.pastedGrant(text, state) ?: ExtensionProtocol.manualGrantCode(text)
            else runCatching { ExtensionProtocol.grant(Uri.parse(text), state) }.getOrNull()
        if (grant == null) return false
        // A duplicate callback never starts a second exchange or overwrites an in-flight grant.
        if (request.optString("grant").isNotEmpty() && request.optString("grant") != grant) return false
        if (exchanging) return true
        request.put("grant", grant).remove("error")
        write(saved); data = saved; retryCompletion()
        return true
    }

    @Synchronized fun retryCompletion() {
        val request = data.optJSONObject("pending")
        if (exchanging || !ExtensionProtocol.pendingActive(request, version = version) || request?.optString("grant").isNullOrEmpty()) {
            updateStatus(); return
        }
        val snapshot = JSONObject(request!!.toString())
        val generation = revision.value
        exchanging = true; updateStatus()
        scope.launch {
            try {
                connectionLock.withLock {
                    if (!stillPending(snapshot, generation)) return@withLock
                    val body = JSONObject().put("grant", snapshot.getString("grant")).put("install_id", installId())
                        .put("app_version", version).put("platform", "extension").toString()
                    val response = exchange(body)
                    val session = ExtensionProtocol.session(JSONObject(response)) ?: error("Invalid provider session")
                    synchronized(this@ExtensionSessionManager) {
                        if (stillPending(snapshot, generation)) save(session)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                synchronized(this@ExtensionSessionManager) {
                    if (stillPending(snapshot, generation)) {
                        val error = when (e) {
                            is LosslessHttp.HttpFailure -> "Session could not be saved (HTTP ${e.code}). Retry or reconnect."
                            else -> "Session could not be saved. Check your connection and retry."
                        }
                        data.optJSONObject("pending")?.put("error", error)
                        // No response body, callback or credential is displayed or logged.
                        runCatching { persist() }
                    }
                }
            } finally {
                synchronized(this@ExtensionSessionManager) { exchanging = false; updateStatus() }
            }
        }
    }
    @Synchronized private fun stillPending(snapshot: JSONObject, generation: Long): Boolean =
        revision.value == generation && data.optJSONObject("pending")?.optString("state") == snapshot.optString("state") &&
            data.optJSONObject("pending")?.optString("grant") == snapshot.optString("grant")

    private suspend fun exchange(body: String): String {
        for (attempt in 0..2) {
            try { return request(post("/session/exchange", body)) }
            catch (e: LosslessHttp.HttpFailure) {
                // Match the upstream exchange contract, with a bounded foreground wait.
                val seconds = e.retryAfterSeconds ?: 1
                if (e.code != 429 || attempt == 2 || seconds !in 0..5) throw e
                kotlinx.coroutines.delay(seconds.coerceAtLeast(1) * 1000)
            }
        }
        error("Session exchange retry exhausted")
    }
    suspend fun signed(path: String, body: JSONObject, extraHeaders: Map<String, String> = emptyMap()): String {
        return signedRaw("POST", path, body.toString(), extraHeaders)
    }
    suspend fun signedRaw(method: String, path: String, body: String, extraHeaders: Map<String, String> = emptyMap()): String {
        require(method in setOf("GET", "POST"))
        require(path == "/tickets" || Regex("/dl/[A-Za-z0-9_-]+").matches(path))
        require(extraHeaders.keys.all { it.equals("X-Zarz-Ticket", true) || it.equals("Accept", true) })
        val session = refreshedSession() ?: throw LosslessFailure("Verify this provider in Lossless → Manage connection.")
        try { return signedRequest(path, body, session, extraHeaders, method) }
        catch (e: LosslessHttp.HttpFailure) {
            val latest = current()
            if (ProviderDiagnostic.from(e).code == "REQUEST_AUTH_INVALID" && latest != null && latest != session)
                return signedRequest(path, body, latest, extraHeaders, method)
            throw e
        }
    }
    private suspend fun refreshedSession(): ExtensionProtocol.Session? = connectionLock.withLock {
        val old = current() ?: return@withLock null
        if (old.expires.isAfter(Instant.now().plusSeconds(3600))) return@withLock old
        try {
            val response = JSONObject(signedRequest("/session/refresh", JSONObject().put("install_id", installId()).toString(), old))
            val merged = old.json().apply { response.keys().forEach { put(it, response.get(it)) } }
            val updated = ExtensionProtocol.session(merged) ?: return@withLock old
            synchronized(this@ExtensionSessionManager) {
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
    private suspend fun signedRequest(path: String, body: String, session: ExtensionProtocol.Session, extra: Map<String, String> = emptyMap(), method: String = "POST"): String {
        for (attempt in 0..1) {
            val signed = post(path, body).newBuilder().apply {
                if (method == "GET") get()
                ExtensionProtocol.headers(session, method, "/v2$path", body, ExtensionProtocol.randomHex(12), version = version).forEach { (k, v) -> header(k, v) }
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
        .header("Accept", "application/json").header("User-Agent", "SpotiFLAC-Mobile/$version")
        .post(body.toRequestBody("application/json".toMediaType())).build()
}
