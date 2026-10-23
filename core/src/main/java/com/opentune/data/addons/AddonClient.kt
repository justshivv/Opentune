package com.opentune.data.addons

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Each source coalesces requests and caches successful responses, never failures. */
class AddonClient(val base: String, private val fetch: suspend (String) -> String = AddonHttp::text,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Cached(val at: Long, val text: String)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val mutex = Mutex()
    private var cooldown = 0L
    private suspend fun get(path: List<String>, params: Map<String, String>, ttl: Long, validate: (String) -> Unit): String = mutex.withLock {
        val url = AddonProtocol.endpoint(base, path, params)
        cache[url]?.takeIf { now() - it.at < ttl }?.let { return@withLock it.text }
        if (now() < cooldown) throw AddonHttp.Failure(429, ((cooldown - now() + 999) / 1000).coerceAtLeast(1))
        try {
            val text = fetch(url)
            validate(text)
            if (cache.size > 128) cache.clear()
            cache[url] = Cached(now(), text); text
        } catch (e: AddonHttp.Failure) {
            if (e.status == 429) cooldown = now() + (e.retryAfterSeconds.takeIf { it > 0 } ?: 30) * 1000
            throw e
        }
    }
    suspend fun manifest(): AddonManifest = AddonProtocol.manifest(get(listOf("manifest.json"), emptyMap(), 600_000) { AddonProtocol.manifest(it) })
    suspend fun search(query: String, manifest: AddonManifest, tier: String): List<AddonTrack> =
        AddonProtocol.search(get(listOf("search"), manifest.parameters(tier) + ("q" to query), 600_000) { AddonProtocol.search(it) })
    suspend fun stream(id: String, manifest: AddonManifest, tier: String): AddonStream =
        AddonProtocol.stream(get(listOf("stream", id), manifest.parameters(tier), 300_000) { AddonProtocol.stream(it) })
    companion object {
        private val clients = ConcurrentHashMap<String, AddonClient>()
        fun forBase(base: String): AddonClient = clients.getOrPut(base) { AddonClient(base) }
        fun clear() { clients.clear() }
    }
}
