package com.opentune.data.lossless

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Zarz v2 extension protocol, independent of the older community SpotiFLAC issuer. */
internal object ExtensionProtocol {
    const val BASE = "https://api.zarz.moe/v2"
    const val VERSION = "amzn@2.3.11"
    const val CALLBACK = "opentune://provider-session"
    const val VERIFICATION_TIMEOUT_MS = 180_000L
    fun pendingActive(pending: JSONObject?, now: Long = System.currentTimeMillis(), version: String = VERSION): Boolean =
        pending != null && pending.optString("version") == version &&
            now - pending.optLong("created") in 0 until VERIFICATION_TIMEOUT_MS
    fun randomHex(bytes: Int): String = ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    data class Session(val id: String, val secret: String, val expires: Instant) {
        fun json() = JSONObject().put("session_id", id).put("session_secret", secret).put("expires_at", expires.toString())
    }
    fun session(json: JSONObject, now: Instant = Instant.now()): Session? = runCatching {
        val id = json.getString("session_id")
        val secret = json.getString("session_secret")
        val expires = Instant.parse(json.getString("expires_at"))
        require(id.length in 1..4096 && secret.length in 1..4096 && expires.isAfter(now))
        require(id.none(Char::isISOControl) && secret.none(Char::isISOControl))
        Session(id, secret, expires)
    }.getOrNull()
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun hmac(key: String, value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.toByteArray(), "HmacSHA256")); doFinal(value.toByteArray())
        })
    fun headers(session: Session, method: String, path: String, body: String, nonce: String, now: Instant = Instant.now(), version: String = VERSION): Map<String, String> {
        require(path.startsWith("/v2/") && !path.contains('?') && !path.contains('#'))
        val time = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(now)
        val digest = hash(body)
        val rolling = hmac(session.secret, "${now.epochSecond / 300}:${session.id}")
        val canonical = listOf("ZARZ-HMAC-V1", method.uppercase(), path, "", digest, time, nonce, session.id, version, "extension").joinToString("\n")
        return mapOf("Session" to session.id, "Timestamp" to time, "Nonce" to nonce, "Body-SHA256" to digest,
            "Signature" to hmac(rolling, canonical), "App-Version" to version, "Platform" to "extension")
            .mapKeys { "X-Zarz-${it.key}" }
    }
    /** Only the reviewed issuer can receive a callback or signed session. */
    fun challenge(root: JSONObject, randomState: String): Pair<String, String>? {
        val raw = root.optString("auth_url").ifBlank { root.optString("challenge_url") }
        val url = if (raw.isNotBlank()) raw.toHttpUrlOrNull() else root.optString("challenge_id").takeIf { it.isNotBlank() }
            ?.let { "$BASE/challenge".toHttpUrl().newBuilder().addQueryParameter("id", it).build() }
        if (url == null || !url.isHttps || url.host != "api.zarz.moe" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.encodedPath != "/v2/challenge") return null
        if (url.queryParameterValues("state").size > 1) return null
        val state = url.queryParameter("state")?.takeIf { it.isNotBlank() } ?: randomState
        if (state.length !in 16..512 || state.any(Char::isISOControl)) return null
        val callback = Uri.parse(CALLBACK).buildUpon().appendQueryParameter("cb_version", "v2grant").appendQueryParameter("state", state).build()
        return url.newBuilder().setQueryParameter("state", state).setQueryParameter("cb", callback.toString()).build().toString() to state
    }
    fun isCallback(uri: Uri): Boolean = uri.scheme == "opentune" && uri.host == "provider-session"
    /**
     * The provider's Copy link control can return just grant=gr_… . Treat that
     * as a manually entered code, not a state-validated redirect. Only explicit
     * submission against an active verification may use this parser. Never
     * recover a missing/wrong state from a URL or a multi-parameter callback.
     */
    fun manualGrantCode(text: String): String? =
        Regex("grant=(gr_[A-Za-z0-9_-]{16,256})").matchEntire(text.trim())?.groupValues?.get(1)

    fun grant(uri: Uri, state: String): String? {
        if (!isCallback(uri) || uri.port != -1 || uri.userInfo != null || !uri.path.isNullOrEmpty() || uri.fragment != null) return null
        if (state.isBlank() || uri.getQueryParameters("state") != listOf(state)) return null
        val grants = uri.getQueryParameters("grant") + uri.getQueryParameters("code")
        return grants.singleOrNull()?.takeIf { it.length in 1..8192 && it.none(Char::isISOControl) }
    }
    /** Explicit paste recovery, like SpotiFLAC's launcher. Never register its scheme with Android. */
    fun pastedGrant(text: String, state: String, depth: Int = 0): String? {
        if (text.length !in 1..32768 || depth > 3 || text.any(Char::isISOControl)) return null
        val input = text.trim()
        val parameters = input.removePrefix("?")
        // The provider may copy only query parameters. Upstream's manual recovery accepts
        // these too; still require the exact active state and a single grant/code.
        if (listOf("grant=", "code=", "state=").any(parameters::startsWith)) {
            return runCatching { grant(Uri.parse("$CALLBACK?$parameters"), state) }.getOrNull()
        }
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
        if (uri.fragment != null || uri.userInfo != null) return null
        val upstream = uri.scheme == "spotiflac" && uri.host == "session-grant" && uri.port == -1 && uri.path.isNullOrEmpty()
        if (isCallback(uri) || upstream) {
            return grant(if (upstream) uri.buildUpon().scheme("opentune").authority("provider-session").build() else uri, state)
        }
        // A copied verification URL may wrap the result in cb. Restrict wrappers to the issuer.
        if (uri.scheme != "https" || uri.host != "api.zarz.moe" || uri.port !in listOf(-1, 443) || uri.path != "/v2/challenge") return null
        val states = uri.getQueryParameters("state")
        if (states.isNotEmpty() && states != listOf(state)) return null
        if (uri.getQueryParameters("grant").isNotEmpty() || uri.getQueryParameters("code").isNotEmpty()) return null
        val nested = uri.getQueryParameters("cb").singleOrNull() ?: return null
        return pastedGrant(nested, state, depth + 1)
    }
    fun verificationRequired(status: Int, body: String): Boolean =
        SpotiflacErrorPolicy.requiresVerification(LosslessHttp.HttpFailure(status, body))
}
