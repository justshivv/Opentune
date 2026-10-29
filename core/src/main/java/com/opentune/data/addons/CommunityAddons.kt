package com.opentune.data.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Public owner-curated catalog. URLs are hidden in UI, not confidential network credentials.
 * Read each time the app comes to the front (at most every [EVERY_MS]), so a source the
 * owner turns off stops being asked within minutes on every phone that's in use.
 */
object CommunityAddons {
    private const val CONFIG = "https://justshivv.github.io/Opentune/usage-config.json"
    /** The owner's server, and the Cloudflare Worker it took over from. */
    private val CATALOG_HOSTS = setOf("addon-opentune.rethinknow.in", "opentune-usage.create-a-new-site-with-sites.workers.dev")
    private val mutex = Mutex()
    private var lastCheck = 0L
    private const val EVERY_MS = 10 * 60_000L
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastCheck < EVERY_MS && lastCheck != 0L) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                com.opentune.data.config.RemoteConfig.refresh(force)
                val managed = com.opentune.data.config.RemoteConfig.current.sources
                val document = if (managed != null) JSONObject().put("sources", org.json.JSONArray().apply {
                    managed.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("enabled", it.enabled)) }
                }) else {
                    val config = JSONObject(AddonHttp.bytes(CONFIG, 8192, false).toString(Charsets.UTF_8))
                    val base = AddonProtocol.url(config.getString("apiBase"))
                    require(base.host in CATALOG_HOSTS)
                    JSONObject(AddonHttp.bytes(base.newBuilder().encodedPath("/v1/addons").build().toString(), 65536, false).toString(Charsets.UTF_8))
                }
                val sources = document.getJSONArray("sources")
                require(sources.length() <= 20)
                val incoming = mutableListOf<AudioAddon>()
                for (i in 0 until sources.length()) {
                    val item = sources.getJSONObject(i)
                    val id = item.getString("id"); require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                    val name = item.getString("name").take(80)
                    val url = AddonProtocol.normalize(item.getString("url"))
                    // Missing on catalogs published before the switch existed: on.
                    val off = !item.optBoolean("enabled", true)
                    val existing = AddonStore.addons.value.firstOrNull { it.key == "admin:$id" && it.base == url }
                    // One the owner turned off isn't even asked for its manifest.
                    val manifest = existing?.manifest ?: if (off) AddonManifest("admin:$id", name, "") else try { AddonClient.forBase(url).manifest() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { AddonManifest("admin:$id", name, "") }
                    incoming += AudioAddon("admin:$id", url, manifest.copy(name = name), admin = true, ownerOff = off)
                }
                require(incoming.map { it.key }.distinct().size == incoming.size)
                AddonStore.syncCommunity(incoming)
                lastCheck = now
                true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { false } // Offline never clears the last working catalog.
        }
    }
}
