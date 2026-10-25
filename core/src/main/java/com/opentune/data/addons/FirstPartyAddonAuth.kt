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

/**
 * Only OpenTune's own add-on servers receive proofs. No APK-wide shared secret.
 * Each server keeps its own sign-ins, so the device verifies with each one apart,
 * with its own hardware key and session.
 */
object FirstPartyAddonAuth {
    class VerificationFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
    /** The add-on on OpenTune's own server (services/lossless-addon/vps); browsers pair through it. */
    const val ORIGIN = "https://addon-opentune.rethinknow.in"
    /** The same add-on as a Cloudflare Worker, where it ran first. */
    const val WORKER_ORIGIN = "https://opentune-lossless-addon.create-a-new-site-with-sites.workers.dev"
    val ORIGINS = listOf(ORIGIN, WORKER_ORIGIN)
    private val HOSTS = ORIGINS.map { it.removePrefix("https://") }.toSet()
    private const val ALIAS = "opentune.addon.attestation.v1"
    /** The Worker keeps the key and session made before there were two servers. */
    private fun aliasFor(origin: String) = if (origin == WORKER_ORIGIN) ALIAS else "$ALIAS.${origin.removePrefix("https://")}"
    private val mutex = Mutex()
    private lateinit var dir: File
    private val http by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build() }
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val blockedUntil = HashMap<String, Long>()
    private val blockedReason = HashMap<String, String>()
    fun init(context: Context) { dir = context.noBackupFilesDir }
    private fun fileFor(origin: String) = File(dir, if (origin == WORKER_ORIGIN) "addon-device-session.json" else "addon-device-session-${origin.removePrefix("https://")}.json")
    fun protects(url: okhttp3.HttpUrl): Boolean = url.scheme == "https" && url.host in HOSTS && url.port == 443 &&
        !url.encodedPath.endsWith("/manifest.json") && !url.encodedPath.endsWith("/health") && url.encodedPath != "/"
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun sign(alias: String, value: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(alias, null) as java.security.PrivateKey); update(value.toByteArray(Charsets.UTF_8)); b64(sign())
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
    private fun post(origin: String, path: String, body: String) = Request.Builder().url(origin + path).post(body.toRequestBody("application/json".toMediaType())).build()
    private fun session(origin: String): JSONObject {
        check(::dir.isInitialized) { "OpenTune verification has not initialized" }
        val file = fileFor(origin)
        val alias = aliasFor(origin)
        val old = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (old != null && old.optLong("expires") > System.currentTimeMillis() + old.optLong("offset") + 60_000 && store.containsAlias(alias)) return old
        if (System.currentTimeMillis() < (blockedUntil[origin] ?: 0L)) throw VerificationFailure("${blockedReason[origin] ?: "OpenTune device verification failed"}; retry in a minute")
        try {
            val challenge = call(Request.Builder().url("$origin/auth/challenge").build())
            val offset = challenge.getLong("serverTime") - System.currentTimeMillis()
            val value = challenge.getString("challenge")
            store.deleteEntry(alias)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                    .build())
            }.generateKeyPair()
            val chain = JSONArray(store.getCertificateChain(alias).map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) })
            val input = JSONObject().put("challenge", value).put("chain", chain).put("proof", sign(alias, "OpenTune-Enrollment-v1\n$value"))
            val result = call(post(origin, "/auth/enroll", input.toString())).put("offset", offset)
            // Tokens are useless without the non-exportable keystore key and are never backed up.
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(result.toString()); check(temporary.renameTo(file))
            return result
        } catch (e: Exception) {
            blockedUntil[origin] = System.currentTimeMillis() + 60_000
            val reason = when (e) {
                is VerificationFailure -> e.message.orEmpty()
                is java.net.SocketTimeoutException -> "OpenTune verification timed out; retry on a working connection"
                is java.net.UnknownHostException -> "Cannot reach OpenTune verification; check your connection or DNS"
                is java.security.GeneralSecurityException -> "Android could not create the hardware verification key; a supported, locked device is required"
                else -> "OpenTune device verification failed; use the official build on a supported, locked device"
            }
            blockedReason[origin] = reason
            throw VerificationFailure(reason, e)
        }
    }
    suspend fun authorize(request: Request, body: String = ""): Request = withContext(Dispatchers.IO) {
        val origin = "https://${request.url.host}"
        require(request.url.scheme == "https" && request.url.port == 443 && origin in ORIGINS) { "Device proofs cannot leave OpenTune's source" }
        mutex.withLock {
            val alias = aliasFor(origin)
            val session = session(origin)
            val token = session.getString("session")
            val time = (System.currentTimeMillis() + session.optLong("offset")).toString()
            val nonce = b64(ByteArray(24).also { SecureRandom().nextBytes(it) })
            val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            val canonical = listOf("OpenTune-Addon-v1", token, time, nonce, request.method, path, sha(body)).joinToString("\n")
            request.newBuilder().header("User-Agent", "OpenTune/verified-addon-v1")
                .header("Authorization", "Bearer $token").header("X-OpenTune-Time", time)
                .header("X-OpenTune-Nonce", nonce).header("X-OpenTune-Signature", sign(alias, canonical)).build()
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
        val request = authorize(post(ORIGIN, "/auth/web/approve", body), body)
        withContext(Dispatchers.IO) { call(request).also { check(it.optBoolean("ok")) { "Browser approval failed" } } }
    }
}
