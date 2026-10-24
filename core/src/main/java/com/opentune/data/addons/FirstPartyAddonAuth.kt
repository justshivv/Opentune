package com.opentune.data.addons

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/** Only the first-party origin receives proofs. No APK-wide shared secret. */
object FirstPartyAddonAuth {
    class VerificationFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
    /** The add-on on OpenTune's own server (services/lossless-addon/vps); it was a Cloudflare Worker before. */
    const val ORIGIN = "https://addon-opentune.rethinknow.in"
    private const val HOST = "addon-opentune.rethinknow.in"
    private const val ALIAS = "opentune.addon.attestation.v1"
    private val mutex = Mutex()
    private lateinit var file: File
    private val http by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build() }
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var blockedUntil = 0L
    private var blockedReason = "OpenTune device verification failed"
    fun init(context: Context) { file = File(context.noBackupFilesDir, "addon-device-session.json") }
    fun protects(url: okhttp3.HttpUrl): Boolean = url.scheme == "https" && url.host == HOST && url.port == 443 &&
        !url.encodedPath.endsWith("/manifest.json") && !url.encodedPath.endsWith("/health") && url.encodedPath != "/"
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun sign(value: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(ALIAS, null) as java.security.PrivateKey); update(value.toByteArray(Charsets.UTF_8)); b64(sign())
    }
    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        val source = response.body?.source() ?: throw IOException("Empty verification response")
        source.request(80_001); if (source.buffer.size > 80_000) throw IOException("Invalid verification response")
        val data = runCatching { JSONObject(source.readUtf8()) }.getOrElse { throw IOException("Device verification is unavailable") }
        if (!response.isSuccessful) {
            val stage = when (request.url.encodedPath) {
                "/auth/challenge" -> "Verification challenge"
                "/auth/enroll" -> "Device enrollment"
                else -> "OpenTune verification"
            }
            val code = data.optString("code").takeIf { it.matches(Regex("[A-Z0-9_]{3,64}")) }
            throw VerificationFailure("$stage failed (HTTP ${response.code}${code?.let { "; $it" }.orEmpty()}); retry on a supported, locked device")
        }
        data
    }
    private fun post(path: String, body: String) = Request.Builder().url(ORIGIN + path).post(body.toRequestBody("application/json".toMediaType())).build()
    private fun session(): JSONObject {
        check(::file.isInitialized) { "OpenTune verification has not initialized" }
        val old = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (old != null && old.optLong("expires") > System.currentTimeMillis() + old.optLong("offset") + 60_000 && store.containsAlias(ALIAS)) return old
        if (System.currentTimeMillis() < blockedUntil) throw VerificationFailure("$blockedReason; retry in a minute")
        try {
            val challenge = call(Request.Builder().url("$ORIGIN/auth/challenge").build())
            val offset = challenge.getLong("serverTime") - System.currentTimeMillis()
            val value = challenge.getString("challenge")
            store.deleteEntry(ALIAS)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                    .build())
            }.generateKeyPair()
            val chain = JSONArray(store.getCertificateChain(ALIAS).map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) })
            val input = JSONObject().put("challenge", value).put("chain", chain).put("proof", sign("OpenTune-Enrollment-v1\n$value"))
            val result = call(post("/auth/enroll", input.toString())).put("offset", offset)
            // Tokens are useless without the non-exportable keystore key and are never backed up.
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(result.toString()); check(temporary.renameTo(file))
            return result
        } catch (e: Exception) {
            blockedUntil = System.currentTimeMillis() + 60_000
            val reason = when (e) {
                is VerificationFailure -> e.message.orEmpty()
                is java.net.SocketTimeoutException -> "OpenTune verification timed out; retry on a working connection"
                is java.net.UnknownHostException -> "Cannot reach OpenTune verification; check your connection or DNS"
                is java.security.GeneralSecurityException -> "Android could not create the hardware verification key; a supported, locked device is required"
                else -> "OpenTune device verification failed; use the official build on a supported, locked device"
            }
            blockedReason = reason
            throw VerificationFailure(reason, e)
        }
    }
    suspend fun authorize(request: Request, body: String = ""): Request = withContext(Dispatchers.IO) {
        require(request.url.toString().startsWith("$ORIGIN/")) { "Device proofs cannot leave OpenTune's source" }
        mutex.withLock {
            val session = session()
            val token = session.getString("session")
            val time = (System.currentTimeMillis() + session.optLong("offset")).toString()
            val nonce = b64(ByteArray(24).also { SecureRandom().nextBytes(it) })
            val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            val canonical = listOf("OpenTune-Addon-v1", token, time, nonce, request.method, path, sha(body)).joinToString("\n")
            request.newBuilder().header("User-Agent", "OpenTune/verified-addon-v1")
                .header("Authorization", "Bearer $token").header("X-OpenTune-Time", time)
                .header("X-OpenTune-Nonce", nonce).header("X-OpenTune-Signature", sign(canonical)).build()
        }
    }
    suspend fun pairingInfo(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        val request = authorize(Request.Builder().url("$ORIGIN/auth/web/info?id=$id").build())
        return withContext(Dispatchers.IO) { call(request).also { check(it.optBoolean("ok")) { "Browser request has expired" } } }
    }
    suspend fun approveBrowser(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        val body = JSONObject().put("id", id).toString()
        val request = authorize(post("/auth/web/approve", body), body)
        withContext(Dispatchers.IO) { call(request).also { check(it.optBoolean("ok")) { "Browser approval failed" } } }
    }
}
