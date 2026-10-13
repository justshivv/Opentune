package com.opentune.data.lossless

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Optional, user-verified session. App-private preferences are excluded from backup. */
object SpotiflacSession {
    internal data class Session(val id: String, val secret: String, val expires: Instant)
    private var prefs: SharedPreferences? = null
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _needsVerification = MutableStateFlow(false)
    val needsVerification = _needsVerification.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("spotiflac", Context.MODE_PRIVATE)
        _needsVerification.value = prefs?.getBoolean("needs_verification", false) == true
        _connected.value = current() != null
    }

    internal fun current(): Session? {
        val json = prefs?.getString("session", null) ?: return null
        if (prefs?.getInt("issuer_protocol", 0) != 2) {
            invalidate()
            return null
        }
        val session = parse(json)
        if (session == null) invalidate()
        return session
    }

    fun save(json: String, issuer: String): Boolean {
        if (parse(json) == null || prefs == null) return false
        if (httpsUrl(issuer) == null) return false
        prefs?.edit()?.putString("session", json)?.putString("issuer", issuer)
            ?.putInt("issuer_protocol", 2)?.putBoolean("needs_verification", false)?.apply()
        _needsVerification.value = false
        _connected.value = true
        ExternalStreams.retryLookups()
        _revision.value += 1
        return true
    }

    internal fun parse(json: String, now: Instant = Instant.now()): Session? = runCatching {
        val data = JSONObject(json)
        val id = data.getString("session_id").takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
        val secret = data.getString("session_secret").takeIf { it.isNotBlank() && it.length <= 4096 } ?: return null
        val expires = Instant.parse(data.getString("expires_at"))
        if (!expires.isAfter(now)) return null
        Session(id, secret, expires)
    }.getOrNull()

    internal fun installId(): String = prefs?.getString("install_id", null)?.takeIf { it.isNotBlank() }
        ?: java.util.UUID.randomUUID().toString().replace("-", "").also {
            prefs?.edit()?.putString("install_id", it)?.apply()
        }

    fun clear() {
        // Keep the install identity that the provider binds grants to.
        prefs?.edit()?.remove("session")?.remove("issuer")?.remove("issuer_protocol")
            ?.putBoolean("needs_verification", false)?.apply()
        _connected.value = false
        _needsVerification.value = false
    }

    internal fun invalidate(expected: Session? = null) {
        if (expected != null && prefs?.getString("session", null)?.let { parse(it) } != expected) return
        clear()
        prefs?.edit()?.putBoolean("needs_verification", true)?.apply()
        _needsVerification.value = true
    }
}

/** Community SPOTIFLAC-HMAC-V1 requests using a provider-issued session. */
internal object SpotiflacGated {
    const val APP_VERSION = "4.8.5"
    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private val cooldowns = CommunityCooldowns()
    class InvalidResponse : java.io.IOException("Community response was not a JSON object")

    suspend fun tidalUrl(endpoint: String, id: String, preferHiRes: Boolean = false): String? {
        val session = SpotiflacSession.current() ?: return null
        cooldowns.current(endpoint)?.let { throw it }
        // Community /api/dl has its own contract, distinct from public /track/.
        // See spotbye/SpotiFLAC backend/tidal_community.go.
        val body = JSONObject().put("id", id).put("quality", if (preferHiRes) "24" else "16")
            .toString().toByteArray(Charsets.UTF_8)
        val url = endpoint.toHttpUrl()
        // The signing protocol below assumes no query string.
        if (url.encodedQuery != null) return null
        val now = Instant.now()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }.hex()
        val headers = signHeaders(session.id, session.secret, url.encodedPath, body, now, nonce)
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "SpotiFLAC-Mobile/$APP_VERSION")
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return try {
            responseUrl(String(LosslessHttp.bytes(request, 1_048_576, timeoutMs = 12_000), Charsets.UTF_8))
        } catch (e: LosslessHttp.HttpFailure) {
            if (e.code == 401) SpotiflacSession.invalidate(session)
            cooldowns.record(endpoint, e)?.let { throw it }
            throw e
        }
    }

    internal fun responseUrl(payload: String): String? {
        val response = try { JSONObject(payload) } catch (_: org.json.JSONException) { throw InvalidResponse() }
        // Community replies with {quality, url, lyric}, not a base64 BTS manifest.
        // A returned quality label is not proof of FLAC: the caller probes its bytes.
        return LosslessSource.directUrl(response)
    }

    internal fun signHeaders(id: String, secret: String, path: String, body: ByteArray, now: Instant, nonce: String): Map<String, String> {
        val time = timestamp.format(now)
        val hash = MessageDigest.getInstance("SHA-256").digest(body).hex()
        // The community API uses the raw 32-byte HMAC as its rolling key.
        // The extension gateway's base64url-text key is a different protocol:
        // using it here rejects every request, even immediately after verification.
        // Reference: spotbye/SpotiFLAC backend/community_session.go, signCommunityRequest.
        val rolling = hmac(secret.toByteArray(Charsets.UTF_8), "${now.epochSecond / 300}:$id")
        val input = "SPOTIFLAC-HMAC-V1\nPOST\n$path\n\n$hash\n$time\n$nonce\n$id\n$APP_VERSION\nandroid"
        return mapOf(
            "X-Sig-Session" to id, "X-Sig-Timestamp" to time, "X-Sig-Nonce" to nonce,
            "X-Sig-Body-Sha256" to hash,
            "X-Sig-Signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(rolling, input)),
            "X-Sig-App-Version" to APP_VERSION, "X-Sig-Platform" to "android",
        )
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8))
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

