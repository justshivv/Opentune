package com.opentune.data.addons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Refresh the catalog and installed manifests together, including personal sources. */
object AddonRefresh {
    private val reachability = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val availability: kotlinx.coroutines.flow.StateFlow<Map<String, Boolean>> = reachability
    private val mutex = Mutex()
    private var checkedAt: Long? = null
    private const val INTERVAL = 10 * 60_000L

    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && checkedAt?.let { now - it < INTERVAL } == true) return@withLock true
        withContext(Dispatchers.IO) {
            val catalogOk = CommunityAddons.refresh(force)
            val manifestsOk = refreshInstalled(AddonStore.addons.value, force)
            checkedAt = now
            catalogOk && manifestsOk
        }
    }

    internal suspend fun refreshInstalled(
        sources: List<AudioAddon>,
        force: Boolean = false,
        fetch: suspend (AudioAddon, Boolean) -> AddonManifest = { addon, fresh -> AddonClient.forBase(addon.base).manifest(fresh) },
    ): Boolean = coroutineScope {
        val slots = Semaphore(3)
        sources.filter { it.enabled && !it.ownerOff }.map { addon ->
            async {
                slots.withPermit {
                    try {
                        val manifest = fetch(addon, force)
                        AddonStore.refreshed(addon.key, manifest, expectedBase = addon.base)
                        reachable(addon, true)
                        true
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { reachable(addon, false); false } // Keep the last working manifest on every failure.
                }
            }
        }.awaitAll().all { it }
    }

    @Synchronized private fun reachable(addon: AudioAddon, value: Boolean) {
        val current = AddonStore.addons.value
        if (current.none { it.key == addon.key && it.base == addon.base }) return
        reachability.value = reachability.value.filterKeys { key -> current.any { it.key == key } } + (addon.key to value)
    }
}
