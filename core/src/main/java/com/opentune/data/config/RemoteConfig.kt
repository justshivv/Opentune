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
    val github: String = "https://github.com/justshivv/Opentune", val discord: String = "https://discord.gg/aqGA9ekG2H",
    val web: String = "https://justshivv.github.io/Opentune/player/", val tv: String = "https://justshivv.github.io/Opentune/",
    val timeoutMs: Long = 8000, val retries: Int = 2, val communityPrompts: Boolean = true,
    /** The optional links the dashboard set (website, privacy, donate and social accounts), by key; unset ones are absent. */
    val extra: Map<String, String> = emptyMap(),
) {
    val website: String? get() = extra["website"]
    val privacy: String? get() = extra["privacy"]
    val donate: String? get() = extra["donate"]

    /** The project's social accounts the dashboard set, in a fixed order, as (key, url). */
    val socials: List<Pair<String, String>> get() = RemoteConfigProtocol.socialKeys.mapNotNull { k -> extra[k]?.let { k to it } }

    /** A page in the GitHub repository ([path] such as "releases/latest"), so it follows the repository link. */
    fun repo(path: String): String = github.trimEnd('/') + "/" + path.trimStart('/')

    fun title(id: String, fallback: String) = sections.firstOrNull { it.id == id }?.title?.ifBlank { fallback } ?: fallback
}

object RemoteConfigProtocol {
    val sectionIds = listOf("speed-dial", "quick-picks", "mixes", "spotify-mixes", "featured-playlists", "recommendations")

    /**
     * Links shown only when set, each on the sites it may point to (null: any public HTTPS
     * page). The same table as the dashboard's, so a social link can't lead off its own site.
     */
    val optionalLinks: Map<String, Set<String>?> = linkedMapOf(
        "website" to null, "privacy" to null,
        "donate" to setOf("ko-fi.com", "buymeacoffee.com", "paypal.me", "paypal.com", "github.com", "liberapay.com", "patreon.com", "opencollective.com"),
        "x" to setOf("x.com", "twitter.com"), "instagram" to setOf("instagram.com"), "youtube" to setOf("youtube.com", "youtu.be"),
        "telegram" to setOf("t.me", "telegram.me"), "reddit" to setOf("reddit.com"),
    )
    val socialKeys = listOf("x", "instagram", "youtube", "telegram", "reddit")

    /** [links]' optional entries that are set and on their allowed sites; a bad one is dropped, not the whole configuration. */
    internal fun optional(links: JSONObject, url: (String) -> String, host: (String) -> String): Map<String, String> =
        optionalLinks.mapNotNull { (key, hosts) ->
            val raw = links.optString(key).trim().takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return@mapNotNull null
            val clean = runCatching { url(raw) }.getOrNull()?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val h = runCatching { host(clean) }.getOrNull() ?: return@mapNotNull null
            if (hosts != null && hosts.none { h == it || h == "www.$it" }) return@mapNotNull null
            key to clean
        }.toMap()
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
        val extra = optional(links, ::url) { com.opentune.data.addons.AddonProtocol.url(it).host }
        return AppConfig(revision, expires, sections, banners, playlists, sources, github, discord, url(links.getString("web")), url(links.getString("tv")), timeout, retries, data.getBoolean("communityPrompts"), extra)
    }
}

/** Configuration is data, never executable code. Device verification has its own fixed trust policy. */
object RemoteConfig {
    const val ORIGIN = "https://addon-opentune.rethinknow.in"

    /**
     * The dashboard's signing keys this build trusts outright (SPKI, base64). A phone that pinned
     * an earlier key, from before the dashboard's database was set up again, would otherwise
     * refuse every new configuration for good; one of these takes over the pin, and the revision
     * count starts again with it. A key the server offers that isn't one of these is still pinned
     * on first use, as before.
     */
    internal val BUNDLED_KEYS = listOf(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa/ugSt0oayRYS9XNc33hZiGqARuCaxeqNZI3GC57as+6eSrYxfV0fTOfVYrC8jyKVGGrIgqTM820cCgA9WDwJQ==",
    )

    /** How soon after one check the app may check again: a minute, so a banner published shows up when the app is next opened. */
    private const val MIN_GAP_MS = 60_000L

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
        require(response.isSuccessful) { "HTTP ${response.code} for $path" }
        val source = requireNotNull(response.body).source(); source.request(100_001); require(source.buffer.size <= 100_000)
        source.readUtf8()
    }

    /** The key [envelope] verifies under and what it says, by the rules in [RemoteConfig]'s keys; null if none. */
    internal fun accept(envelope: String, pinned: String?, revision: Long, offered: () -> String?, now: Long = System.currentTimeMillis()): Pair<String, AppConfig>? {
        fun attempt(key: String, floor: Long) = runCatching { key to RemoteConfigProtocol.verify(envelope, key, floor, now) }
        // The pinned key, held to the last revision seen.
        pinned?.let { key -> attempt(key, revision).getOrNull()?.let { return it } }
        // A key this build trusts: from a fresh count when it replaces another.
        BUNDLED_KEYS.forEach { key -> attempt(key, if (key == pinned) revision else 0).getOrNull()?.let { return it } }
        // Nothing pinned yet: the key the server offers, pinned on first use.
        if (pinned == null) offered()?.let { key -> attempt(key, 0).getOrNull()?.let { return it } }
        return null
    }

    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val p = prefs ?: return@withLock false
        if (mutable.value.expiresAt <= System.currentTimeMillis()) mutable.value = AppConfig()
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastAttempt != 0L && now - lastAttempt < MIN_GAP_MS) return@withLock current.revision > 0
        lastAttempt = now
        withContext(Dispatchers.IO) {
            try {
                val pinned = p.getString("key", null)
                val revision = p.getLong("revision", 0)
                val envelope = get("/v1/app-config")
                val (key, config) = accept(envelope, pinned, revision, offered = {
                    runCatching { JSONObject(get("/v1/app-config/key")).getString("publicKey") }.getOrNull()
                }) ?: run {
                    // Kept for Export diagnostics: a configuration refused leaves the last one up.
                    com.opentune.data.DebugLog.w("RemoteConfig", "Refused the dashboard's configuration: " +
                        (if (pinned == null) "no trusted key verifies it" else "it doesn't verify under the pinned key, or its revision is under $revision"))
                    return@withContext false
                }
                if (key != pinned) com.opentune.data.DebugLog.i("RemoteConfig", "Now trusting the dashboard key ${key.takeLast(12)}")
                p.edit().putString("key", key).putString("envelope", envelope).putLong("revision", config.revision).apply()
                mutable.value = config; true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                com.opentune.data.DebugLog.w("RemoteConfig", "Couldn't load the dashboard's configuration", e)
                false
            }
        }
    }
}
