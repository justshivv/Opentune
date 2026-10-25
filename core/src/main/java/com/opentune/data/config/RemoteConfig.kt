package com.opentune.data.config

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

data class RemoteSection(val id: String, val enabled: Boolean = true, val title: String = "")
data class RemoteBanner(val id: String, val title: String, val body: String, val action: String, val url: String, val platforms: Set<String>)
data class RemotePlaylist(val id: String, val title: String, val browseId: String, val artwork: String?)
data class RemoteSource(val id: String, val name: String, val url: String, val enabled: Boolean)
data class AppConfig(
    val revision: Long = 0, val expiresAt: Long = Long.MAX_VALUE,
    val sections: List<RemoteSection> = RemoteConfigProtocol.sectionIds.map { RemoteSection(it) },
    val banners: List<RemoteBanner> = emptyList(), val playlists: List<RemotePlaylist> = emptyList(),
    val sources: List<RemoteSource>? = null,
    val github: String = "https://github.com/justshivv/Opentune", val discord: String = "https://discord.gg/6udrCVh5n",
    val web: String = "https://justshivv.github.io/Opentune/player/", val tv: String = "https://justshivv.github.io/Opentune/",
    val timeoutMs: Long = 8000, val retries: Int = 2, val communityPrompts: Boolean = true,
) {
    fun title(id: String, fallback: String) = sections.firstOrNull { it.id == id }?.title?.ifBlank { fallback } ?: fallback
}

object RemoteConfigProtocol {
    val sectionIds = listOf("speed-dial", "quick-picks", "mixes", "spotify-mixes", "featured-playlists", "recommendations")
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    private fun url(value: String): String = com.opentune.data.addons.AddonProtocol.url(value).toString()
    private fun string(r: JSONObject, key: String, max: Int, blank: Boolean = false): String = r.getString(key).also { require(it.length <= max && (blank || it.isNotBlank())) }
    /** ES256 uses 64-byte r||s on the wire; Android's JCA expects ASN.1 DER. */
    private fun der(signature: ByteArray): ByteArray {
        require(signature.size == 64)
        fun integer(from: Int): ByteArray {
            var bytes = signature.copyOfRange(from, from + 32).dropWhile { it == 0.toByte() }.toByteArray()
            if (bytes.isEmpty()) bytes = byteArrayOf(0)
            if (bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0) + bytes
            return byteArrayOf(2, bytes.size.toByte()) + bytes
        }
        val body = integer(0) + integer(32)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
    fun verify(envelope: String, publicKey: String, minimumRevision: Long = 0, now: Long = System.currentTimeMillis()): AppConfig {
        require(envelope.length <= 100_000 && publicKey.length <= 1024)
        val signed = JSONObject(envelope); val payload = signed.getString("payload")
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT)))
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload.toByteArray(Charsets.US_ASCII)); verify(der(decode(signed.getString("signature")))) })
        val document = JSONObject(String(decode(payload), Charsets.UTF_8))
        require(document.getInt("schemaVersion") == 1)
        val revision = document.getLong("revision"); val issued = document.getLong("issuedAt"); val expires = document.getLong("expiresAt")
        require(revision > 0 && revision >= minimumRevision && issued <= now + 600_000 && expires > now && expires > issued && expires - issued <= 7 * 86_400_000L)
        val data = document.getJSONObject("data")
        val array = data.getJSONArray("sections"); require(array.length() == sectionIds.size)
        val sections = (0 until array.length()).map { i -> array.getJSONObject(i).let { RemoteSection(it.getString("id"), it.getBoolean("enabled"), string(it, "title", 80, true)) } }
        require(sections.map { it.id }.toSet() == sectionIds.toSet() && sections.any { it.enabled })
        val bannersArray = data.getJSONArray("banners"); require(bannersArray.length() <= 3)
        val banners = (0 until bannersArray.length()).mapNotNull { i ->
            val b = bannersArray.getJSONObject(i); if (!b.getBoolean("enabled")) return@mapNotNull null
            val platforms = b.getJSONArray("platforms"); require(platforms.length() <= 3)
            val targets = (0 until platforms.length()).map { platforms.getString(it) }.toSet(); require(targets.all { it in setOf("android", "tv", "web") })
            RemoteBanner(string(b, "id", 64), string(b, "title", 100), string(b, "body", 300, true), string(b, "action", 40), url(b.getString("url")), targets)
        }
        val playlistsArray = data.getJSONArray("playlists"); require(playlistsArray.length() <= 12)
        val playlists = (0 until playlistsArray.length()).map { i -> playlistsArray.getJSONObject(i).let {
            val browseId = it.getString("browseId"); require(browseId.matches(Regex("VL[A-Za-z0-9_-]{8,150}")))
            RemotePlaylist(string(it, "id", 64), string(it, "title", 100), browseId, it.optString("artwork").takeIf(String::isNotBlank)?.let(::url))
        } }
        val sources = if (data.isNull("sources")) null else data.getJSONArray("sources").let { a ->
            require(a.length() <= 20)
            (0 until a.length()).map { i -> a.getJSONObject(i).let {
                val id = string(it, "id", 64); require(id.matches(Regex("[A-Za-z0-9_-]+")))
                RemoteSource(id, string(it, "name", 80), url(it.getString("url")), it.getBoolean("enabled"))
            } }.also { require(it.map(RemoteSource::id).distinct().size == it.size) }
        }
        require(banners.map { it.id }.distinct().size == banners.size && playlists.map { it.id }.distinct().size == playlists.size)
        val links = data.getJSONObject("links"); val network = data.getJSONObject("network")
        val timeout = network.getLong("timeoutMs"); val retries = network.getInt("retries")
        require(timeout in 4000..20000 && retries in 0..2)
        val github = url(links.getString("github")); val discord = url(links.getString("discord"))
        require(com.opentune.data.addons.AddonProtocol.url(github).host == "github.com")
        require(com.opentune.data.addons.AddonProtocol.url(discord).host in setOf("discord.gg", "discord.com"))
        return AppConfig(revision, expires, sections, banners, playlists, sources, github, discord, url(links.getString("web")), url(links.getString("tv")), timeout, retries, data.getBoolean("communityPrompts"))
    }
}

/** Configuration is data, never executable code. Device verification has its own fixed trust policy. */
object RemoteConfig {
    const val ORIGIN = "https://addon-opentune.rethinknow.in"
    private val mutable = MutableStateFlow(AppConfig())
    val state = mutable.asStateFlow()
    val current: AppConfig get() = mutable.value.takeIf { it.expiresAt > System.currentTimeMillis() } ?: AppConfig()
    private var prefs: android.content.SharedPreferences? = null
    private val mutex = Mutex()
    private var lastAttempt = 0L
    private val client by lazy { OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build() }
    fun init(context: Context) {
        prefs = context.getSharedPreferences("remote_config", 0)
        val p = prefs!!
        runCatching { RemoteConfigProtocol.verify(p.getString("envelope", "")!!, p.getString("key", "")!!, p.getLong("revision", 0)) }.getOrNull()?.let { mutable.value = it }
    }
    private fun get(path: String): String = client.newCall(Request.Builder().url(ORIGIN + path).build()).execute().use { response ->
        require(response.isSuccessful)
        val source = requireNotNull(response.body).source(); source.request(100_001); require(source.buffer.size <= 100_000)
        source.readUtf8()
    }
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val p = prefs ?: return@withLock false
        if (mutable.value.expiresAt <= System.currentTimeMillis()) mutable.value = AppConfig()
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastAttempt != 0L && now - lastAttempt < 5 * 60_000) return@withLock current.revision > 0
        lastAttempt = now
        withContext(Dispatchers.IO) {
            try {
                // Bootstrap over the fixed HTTPS origin, then pin the signing key in app storage.
                // A later key change is refused; restore control.sqlite when moving servers.
                val key = p.getString("key", null) ?: JSONObject(get("/v1/app-config/key")).getString("publicKey")
                val envelope = get("/v1/app-config")
                val config = RemoteConfigProtocol.verify(envelope, key, p.getLong("revision", 0))
                p.edit().putString("key", key).putString("envelope", envelope).putLong("revision", config.revision).apply()
                mutable.value = config; true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false }
        }
    }
}
