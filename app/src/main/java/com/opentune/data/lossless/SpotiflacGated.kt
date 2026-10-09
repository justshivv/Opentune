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

    fun init(context: Context) {
        prefs = context.getSharedPreferences("spotiflac", Context.MODE_PRIVATE)
        _connected.value = current() != null
    }

    internal fun current(): Session? {
        val json = prefs?.getString("session", null) ?: return null
        val session = parse(json)
        if (session == null) clear()
        return session
    }

    fun save(json: String): Boolean {
        if (parse(json) == null || prefs == null) return false
        prefs?.edit()?.putString("session", json)?.apply()
        _connected.value = true
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

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        _connected.value = false
    }
}

/** SPOTIFLAC-HMAC-V1 signing adapted from Spotui (GPLv3). No session is fabricated. */
internal object SpotiflacGated {
    const val APP_VERSION = "4.8.5"
    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    suspend fun tidalUrl(endpoint: String, id: String): String? {
        val session = SpotiflacSession.current() ?: return null
        val body = JSONObject().put("id", id).put("quality", "LOSSLESS").toString().toByteArray()
        val url = endpoint.toHttpUrl()
        // The signing protocol below assumes no query string.
        if (url.encodedQuery != null) return null
        val now = Instant.now()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }.hex()
        val headers = signHeaders(session.id, session.secret, url.encodedPath, body, now, nonce)
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "SpotiFLAC-Mobile/$APP_VERSION")
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        return LosslessSource.tidalUrl(JSONObject(String(LosslessHttp.bytes(request, 1_048_576), Charsets.UTF_8)))
    }

    internal fun signHeaders(id: String, secret: String, path: String, body: ByteArray, now: Instant, nonce: String): Map<String, String> {
        val time = timestamp.format(now)
        val hash = MessageDigest.getInstance("SHA-256").digest(body).hex()
        val rolling = hmac(secret, "${now.epochSecond / 300}:$id")
        val input = "SPOTIFLAC-HMAC-V1\nPOST\n$path\n\n$hash\n$time\n$nonce\n$id\n$APP_VERSION\nandroid"
        return mapOf(
            "X-Sig-Session" to id, "X-Sig-Timestamp" to time, "X-Sig-Nonce" to nonce,
            "X-Sig-Body-Sha256" to hash, "X-Sig-Signature" to hmac(rolling, input),
            "X-Sig-App-Version" to APP_VERSION, "X-Sig-Platform" to "android",
        )
    }

    private fun hmac(key: String, data: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }.doFinal(data.toByteArray()),
    )
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
