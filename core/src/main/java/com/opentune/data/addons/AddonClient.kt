package com.opentune.data.addons

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Each source coalesces identical requests and caches successful responses, never failures.
 * Different requests go out side by side: one song's search no longer waits behind another's.
 * A rate limit is held per song by [AddonResolver], not here for every song at once.
 */
class AddonClient(val base: String, private val fetch: suspend (String) -> String = AddonHttp::text,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Cached(val at: Long, val text: String)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private suspend fun get(path: List<String>, params: Map<String, String>, ttl: Long, fresh: Boolean = false, validate: (String) -> Unit): String {
        val url = AddonProtocol.endpoint(base, path, params)
        if (locks.size > 512) locks.clear()
        val started = now()
        return locks.computeIfAbsent(url) { Mutex() }.withLock {
            // A fresh ask still takes an answer that came in while it waited: that one is new too.
            cache[url]?.takeIf { now() - it.at < ttl && (!fresh || it.at >= started) }?.let { return@withLock it.text }
            val text = fetch(url)
            validate(text)
            if (cache.size > 128) cache.clear()
            cache[url] = Cached(now(), text); text
        }
    }
    suspend fun manifest(): AddonManifest = AddonProtocol.manifest(get(listOf("manifest.json"), emptyMap(), 600_000) { AddonProtocol.manifest(it) })
    suspend fun search(query: String, manifest: AddonManifest, tier: String): List<AddonTrack> =
        AddonProtocol.search(get(listOf("search"), manifest.parameters(tier) + ("q" to query), 600_000) { AddonProtocol.search(it) })
    /** [fresh]: a new link, not the one handed out before (which may have expired). */
    suspend fun stream(id: String, manifest: AddonManifest, tier: String, fresh: Boolean = false): AddonStream =
        AddonProtocol.stream(get(listOf("stream", id), manifest.parameters(tier), 300_000, fresh) { AddonProtocol.stream(it) })
    companion object {
        private val clients = ConcurrentHashMap<String, AddonClient>()
        fun forBase(base: String): AddonClient = clients.getOrPut(base) { AddonClient(base) }
        fun clear() { clients.clear() }
    }
}
