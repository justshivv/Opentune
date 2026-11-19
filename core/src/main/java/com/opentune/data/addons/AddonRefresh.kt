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
    /**
     * Once a day, or when Refresh is tapped ([force]). The time is saved, so opening the app or a
     * background start doesn't check again: every check was a request to the catalog server and
     * to each add-on, from every phone, many times a day.
     */
    internal const val INTERVAL = 24 * 60 * 60_000L
    /** A check that failed (offline, say) is tried again after this long, not a day later. */
    internal const val RETRY = 60 * 60_000L

    /** Whether a check is due, given when the last one was recorded (0 for never). */
    internal fun due(checkedAt: Long, now: Long): Boolean = checkedAt <= 0L || now < checkedAt || now - checkedAt >= INTERVAL

    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && !due(AddonStore.checkedAt, now)) return@withLock true
        withContext(Dispatchers.IO) {
            val catalogOk = CommunityAddons.refresh(force)
            val manifestsOk = refreshInstalled(AddonStore.addons.value, force)
            val ok = catalogOk && manifestsOk
            AddonStore.checkedAt = if (ok) now else now - INTERVAL + RETRY
            ok
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
