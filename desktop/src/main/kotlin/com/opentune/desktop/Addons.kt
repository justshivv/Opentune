package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.addons.AddonProtocol
import com.opentune.data.addons.AddonTrack
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Where a song can play from besides YouTube, as on the phone and the web
 * player: HTTP audio add-ons, the ones OpenTune's owner publishes and any
 * the listener adds, for FLAC, and JioSaavn for 320 kbps AAC. Kept in
 * ~/.opentune/sources.json beside the library.
 */
internal object Addons {
    @Serializable
    data class Source(
        val id: String,
        val name: String,
        /** The manifest's address. */
        val url: String,
        val enabled: Boolean = true,
        /** Published by OpenTune's owner: it can be turned off here, not removed. */
        val admin: Boolean = false,
        /** Turned off by the owner for everyone. */
        val ownerOff: Boolean = false,
    )

    @Serializable
    private data class Saved(val sources: List<Source> = emptyList(), val jioSaavn: Boolean = true, val lossless: Boolean = true)

    /** A verified FLAC file for a song: who has it and what it measured. */
    data class Flac(val url: String, val provider: String, val bits: Int, val rate: Int, val durationSec: Double, val headers: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var file = File(System.getProperty("user.home"), ".opentune/sources.json")
    private val saved = MutableStateFlow(load())

    private val _sources = MutableStateFlow(saved.value.sources)
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()
    private val _jioSaavn = MutableStateFlow(saved.value.jioSaavn)
    val jioSaavn: StateFlow<Boolean> = _jioSaavn.asStateFlow()
    private val _lossless = MutableStateFlow(saved.value.lossless)
    /** Whether add-ons are asked for FLAC at all. */
    val lossless: StateFlow<Boolean> = _lossless.asStateFlow()

    /** What the last search found and why each other source had nothing, for Settings. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private fun load(): Saved = runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrDefault(Saved())

    private fun save(change: (Saved) -> Saved) {
        val next = change(saved.value)
        saved.value = next
        _sources.value = next.sources
        _jioSaavn.value = next.jioSaavn
        _lossless.value = next.lossless
        runCatching {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(Saved.serializer(), next))
        }
    }

    /** For tests: keeps the list in [at] instead. */
    internal fun useFile(at: File) {
        file = at
        save { load() }
    }

    fun setJioSaavn(on: Boolean) = save { it.copy(jioSaavn = on) }
    fun setLossless(on: Boolean) = save { it.copy(lossless = on) }
    fun setEnabled(id: String, on: Boolean) = save { s -> s.copy(sources = s.sources.map { if (it.id == id) it.copy(enabled = on) else it }) }
    fun remove(id: String) = save { s -> s.copy(sources = s.sources.filterNot { it.id == id && !it.admin }) }
    fun move(id: String, by: Int) = save { s ->
        val list = s.sources.toMutableList()
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@save s
        list.add((i + by).coerceIn(list.indices), list.removeAt(i))
        s.copy(sources = list)
    }

    /** Adds the listener's own add-on, after reading its manifest. */
    suspend fun add(raw: String) {
        val url = manifestUrl(raw)
        val manifest = AddonProtocol.manifest(text(url))
        val now = saved.value.sources
        require(now.count { !it.admin } < 20) { "Up to 20 sources can be added" }
        require(now.none { it.url == url }) { "This source is already added" }
        save { it.copy(sources = it.sources + Source(UUID.randomUUID().toString(), manifest.name, url)) }
    }

    /** An add-on's manifest address from whatever the listener pasted: its base, or the manifest itself. */
    fun manifestUrl(raw: String): String = AddonProtocol.normalize(raw.trim()).toHttpUrl().newBuilder().addPathSegment("manifest.json").build().toString()

    // ---- The published catalog ---------------------------------------------------

    /** Where the website says the owner's server is; the catalog is only ever read from one of these. */
    private const val CONFIG_URL = "https://justshivv.github.io/Opentune/usage-config.json"
    private val CATALOG_HOSTS = setOf("addon-opentune.rethinknow.in", "opentune-usage.create-a-new-site-with-sites.workers.dev")
    private const val REFRESH_MS = 15 * 60_000L
    @Volatile private var lastCheck = 0L

    data class Published(val id: String, val name: String, val url: String, val enabled: Boolean?)

    /** The published list, checked: at most 20 sources, each with a plain id, a name and a public HTTPS manifest. */
    fun publishedOf(text: String): List<Published> {
        val list = JSONObject(text).optJSONArray("sources") ?: error("Invalid source catalog")
        require(list.length() <= 20) { "Invalid source catalog" }
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || !item.has("name") || !item.has("url")) return@mapNotNull null
            val url = runCatching { manifestUrl(item.getString("url")) }.getOrNull() ?: return@mapNotNull null
            Published(id, item.getString("name").trim().take(80).ifBlank { "Source" }, url, item.opt("enabled") as? Boolean)
        }
    }

    /**
     * The listener's sources with the published ones folded in, as the phone's
     * syncCommunity does: their own stay as they are; a published one keeps its
     * place and on/off here, takes the catalog's name and address, and goes when
     * it's no longer published; a new one joins at the end, on.
     */
    fun merge(local: List<Source>, published: List<Published>): List<Source> {
        val incoming = LinkedHashMap(published.associateBy { "admin:${it.id}" })
        val kept = local.mapNotNull { source ->
            if (!source.admin) return@mapNotNull source
            val update = incoming.remove(source.id) ?: return@mapNotNull null
            source.copy(name = update.name, url = update.url, ownerOff = update.enabled == false)
        }
        return kept + incoming.map { (id, p) -> Source(id, p.name, p.url, enabled = true, admin = true, ownerOff = p.enabled == false) }
    }

    /**
     * Folds in what the owner publishes; quiet on failure, the list as it was
     * keeps working. As on the phone, the signed configuration from the
     * owner's server (its /control/ page) decides; only when it carries no
     * list is the older catalog behind the website's usage address asked.
     */
    suspend fun syncPublished(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - lastCheck < REFRESH_MS) return@withContext true
        runCatching {
            runCatching { com.opentune.data.config.RemoteConfig.refresh(force) }
            val managed = com.opentune.data.config.RemoteConfig.current.sources
            val published = if (managed != null) {
                managed.mapNotNull { s ->
                    val url = runCatching { manifestUrl(s.url) }.getOrNull() ?: return@mapNotNull null
                    Published(s.id, s.name.take(80), url, s.enabled)
                }
            } else {
                val base = JSONObject(text(CONFIG_URL)).getString("apiBase").toHttpUrl()
                require(base.isHttps && base.host in CATALOG_HOSTS) { "Unexpected source catalog address" }
                publishedOf(text(base.newBuilder().encodedPath("/v1/addons").build().toString()))
            }
            lastCheck = System.currentTimeMillis()
            val merged = merge(saved.value.sources, published)
            if (merged != saved.value.sources) save { it.copy(sources = merged) }
        }.onFailure { System.err.println("Addons: catalog: ${it.message}") }.isSuccess
    }

    // ---- Finding a FLAC ------------------------------------------------------------

    private val client = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()

    private class NoMatch(message: String) : Exception(message)

    private fun text(url: String): String {
        val request = Request.Builder().url(url).apply { AddonPairing.headersFor(url).forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { r ->
            if (r.code == 429) throw Exception("is busy (rate limited)")
            if (!r.isSuccessful) {
                val reason = runCatching { JSONObject(r.body!!.string()).optString("error") }.getOrNull().orEmpty().take(140)
                throw HttpStatus(r.code, reason)
            }
            val body = r.body ?: error("empty answer")
            require(body.contentLength() <= 2_097_152) { "answer too large" }
            return body.string()
        }
    }

    class HttpStatus(val code: Int, reason: String) : Exception("returned HTTP $code${if (reason.isNotBlank()) ": $reason" else ""}")

    private fun normal(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
        .replace(Regex("\\b(official|audio|video|lyrics)\\b"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun artists(s: String) = s.split(Regex(",| & | feat\\.? | featuring | ft\\.? ", RegexOption.IGNORE_CASE)).map(::normal).filter(String::isNotEmpty)

    /** The web player's rule (web/shared/src/addons/protocol.ts): the same title, every asked artist credited, within three seconds. */
    fun matches(wanted: JioSaavn.Wanted, track: AddonTrack): Boolean {
        val length = track.durationMs / 1000.0
        if (wanted.durationSec <= 0 || length <= 0 || abs(wanted.durationSec - length) > 3) return false
        val credit = artists(track.artist)
        val asked = artists(wanted.artist)
        return normal(wanted.title) == normal(track.title) && asked.isNotEmpty() && asked.all { it in credit }
    }

    /** Bits, sample rate and length from a FLAC file's STREAMINFO, or null when it isn't one. */
    fun flacInfo(b: ByteArray): Triple<Int, Int, Double>? {
        if (b.size < 42 || String(b, 0, 4, Charsets.US_ASCII) != "fLaC" || (b[4].toInt() and 127) != 0) return null
        fun u(i: Int) = b[i].toInt() and 0xff
        val rate = (u(18) shl 12) or (u(19) shl 4) or (u(20) shr 4)
        val bits = (((u(20) and 1) shl 4) or (u(21) shr 4)) + 1
        val samples = ((u(21) and 15).toLong() shl 32) or (u(22).toLong() shl 24) or (u(23).toLong() shl 16) or (u(24).toLong() shl 8) or u(25).toLong()
        if (rate == 0 || samples == 0L) return null
        return Triple(bits, rate, samples.toDouble() / rate)
    }

    private fun probeFlac(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-4095").build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("its file server returned HTTP ${r.code}")
            r.body!!.byteStream().readNBytes(4096)
        }

    private fun check(source: Source, wanted: JioSaavn.Wanted): Flac {
        val manifest = try {
            AddonProtocol.manifest(text(source.url))
        } catch (e: HttpStatus) {
            throw Exception("is offline right now (HTTP ${e.code})")
        }
        // The phone checks the output is wired or USB first; a computer can't say.
        if (manifest.outputRequired) throw NoMatch("plays in the Android app only: it needs a verified wired or USB output")
        val base = AddonProtocol.normalize(source.url)
        val params = manifest.parameters("HI_RES_LOSSLESS")
        val found = AddonProtocol.search(text(AddonProtocol.endpoint(base, listOf("search"), params + ("q" to "${wanted.title} ${wanted.artist}"))))
        val match = found.firstOrNull { matches(wanted, it) } ?: throw NoMatch("no matching recording")
        val stream = try {
            AddonProtocol.stream(text(AddonProtocol.endpoint(base, listOf("stream", match.id), params)))
        } catch (e: HttpStatus) {
            if (e.code == 404) throw NoMatch("no stream for this song") else throw e
        }
        if (stream.codec != "flac") throw NoMatch("no FLAC for this song (${stream.codec})")
        if (stream.transport != null) throw NoMatch("only a segmented stream, which plays on Android")
        val (bits, rate, length) = flacInfo(probeFlac(stream.url)) ?: throw NoMatch("its file isn't FLAC")
        if (abs(length - wanted.durationSec) > 3) throw NoMatch("its file is a different length")
        return Flac(stream.url, manifest.name, bits, rate, length)
    }

    /**
     * Every enabled source asked at once; the verified FLAC files ranked by
     * measured quality, equal quality keeping the listener's order.
     */
    suspend fun findFlac(wanted: JioSaavn.Wanted): Flac? = withContext(Dispatchers.IO) {
        if (!_lossless.value) return@withContext null
        val asked = saved.value.sources.filter { it.enabled && !it.ownerOff }
        if (asked.isEmpty()) return@withContext null
        _status.value = "Checking ${asked.joinToString { it.name }}…"
        val results = coroutineScope {
            asked.map { source ->
                async {
                    try {
                        Result.success(withTimeout(25_000) { runInterruptible(Dispatchers.IO) { check(source, wanted) } })
                    } catch (e: CancellationException) {
                        if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(Exception("timed out")) else throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val ranked = results.mapNotNull { it.getOrNull() }.sortedByDescending { it.bits * it.rate }
        val notes = results.mapIndexedNotNull { i, r -> r.exceptionOrNull()?.let { "${asked[i].name}: ${it.message}" } }
        val best = ranked.firstOrNull()
        _status.value = (listOf(best?.let { "${it.provider} · verified FLAC ${it.bits}-bit / ${khz(it.rate)}" } ?: "No matching FLAC found") + notes).joinToString("\n")
        best
    }

    fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000.0)
}

/**
 * Verifying this computer for OpenTune's own add-on, as the web player does
 * (web/app/src/addons/verification.ts): a key made here, approved once from
 * the official app on the listener's phone, then every request to that server
 * signed with it. Access lasts up to 12 hours; the key never leaves this computer.
 */
internal object AddonPairing {
    const val OFFICIAL = "https://addon-opentune.rethinknow.in"
    /** The Origin this app pairs under; the server and the phone know it (services/lossless-addon/auth.mjs). */
    private const val ORIGIN = "opentune-desktop://app"

    @Serializable
    private data class Stored(val key: String, val session: String, val expires: Long, val offset: Long)

    data class Pairing(val id: String, val poll: String, val code: String, val link: String, val expires: Long, internal val key: PrivateKey)

    private val json = Json { ignoreUnknownKeys = true }
    private var file = File(System.getProperty("user.home"), ".opentune/addon-device.json")
    private val http = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()

    private val _until = MutableStateFlow(stored()?.takeIf { it.expires > now(it) }?.expires ?: 0L)
    /** When this computer's access ends, or 0 when it isn't verified. */
    val until: StateFlow<Long> = _until.asStateFlow()

    private fun now(s: Stored) = System.currentTimeMillis() + s.offset

    private fun stored(): Stored? = runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrNull()

    private fun store(value: Stored?) {
        if (value == null) {
            file.delete()
            _until.value = 0
            return
        }
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(Stored.serializer(), value))
        // The key signs as this computer: readable by this user only.
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        _until.value = value.expires
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun call(path: String, body: JSONObject, origin: Boolean = false): JSONObject {
        val request = Request.Builder().url(OFFICIAL + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (origin) header("Origin", ORIGIN) }.build()
        http.newCall(request).execute().use { r ->
            val result = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw Exception(result.optString("error").ifBlank { "Verification failed (HTTP ${r.code})" })
            return result
        }
    }

    /** A 32-byte coordinate, as a JWK wants it. */
    private fun coordinate(v: BigInteger): String {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return b64(ByteArray(32 - raw.size) + raw)
    }

    /** Starts a request the phone approves; the code shows on both screens. */
    suspend fun start(): Pairing = withContext(Dispatchers.IO) {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val point = (keys.public as ECPublicKey).w
        val jwk = JSONObject().put("kty", "EC").put("crv", "P-256").put("x", coordinate(point.affineX)).put("y", coordinate(point.affineY))
        val r = call("/auth/web/start", JSONObject().put("key", jwk), origin = true)
        val id = r.optString("id")
        require(id.matches(TOKEN) && r.optString("poll").isNotBlank() && r.optString("code").isNotBlank()) { "Invalid verification response" }
        Pairing(id, r.getString("poll"), r.getString("code"), "opentune://addon-approve?id=$id", r.optLong("expires"), keys.private)
    }

    /** Whether the phone has approved [pair]; once it has, the session is kept. */
    suspend fun poll(pair: Pairing): Boolean = withContext(Dispatchers.IO) {
        check(System.currentTimeMillis() < pair.expires) { "Request expired. Start verification again." }
        val r = call("/auth/web/status", JSONObject().put("id", pair.id).put("poll", pair.poll))
        val session = r.opt("session") as? String ?: return@withContext false
        require(session.matches(TOKEN)) { "Invalid verification response" }
        store(Stored(Base64.getEncoder().encodeToString(pair.key.encoded), session, r.getLong("expires"), r.getLong("serverTime") - System.currentTimeMillis()))
        true
    }

    fun disconnect() = store(null)

    /** For tests: keeps the session in [at] instead. */
    internal fun useFile(at: File) {
        file = at
        _until.value = stored()?.takeIf { it.expires > now(it) }?.expires ?: 0L
    }

    /** Whether [url] is one of the official server's protected addresses (not its manifests). */
    fun protects(url: String): Boolean {
        val u = url.toHttpUrl()
        return u.isHttps && "https://${u.host}" == OFFICIAL && u.port == 443 &&
            !u.encodedPath.endsWith("/manifest.json") && !u.encodedPath.endsWith("/health") && u.encodedPath != "/"
    }

    /** The signed headers [url] needs, none for any other server; throws when this computer isn't verified. */
    fun headersFor(url: String): Map<String, String> {
        if (!protects(url)) return emptyMap()
        val s = stored()?.takeIf { it.expires > now(it) }
            ?: throw Exception("needs this computer verified: Settings › Audio sources › Verify this computer")
        val u = url.toHttpUrl()
        val time = now(s).toString()
        val nonce = b64(ByteArray(24).also(SecureRandom()::nextBytes))
        val path = u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
        val message = listOf("OpenTune-Addon-v1", s.session, time, nonce, "GET", path, sha256("")).joinToString("\n")
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(s.key)))
        // The server checks a browser-style session's signature as raw r‖s, which is what P1363 gives.
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run { initSign(key); update(message.toByteArray()); sign() }
        return mapOf("Authorization" to "Bearer ${s.session}", "X-OpenTune-Time" to time, "X-OpenTune-Nonce" to nonce, "X-OpenTune-Signature" to b64(signature))
    }

    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
}
