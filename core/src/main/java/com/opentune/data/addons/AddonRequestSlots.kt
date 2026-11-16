package com.opentune.data.addons

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Keep catalogue fan-out from occupying every slot needed to verify a found stream. */
internal class AddonRequestSlots {
    private class Host {
        val network = Semaphore(3)
        val catalogue = Semaphore(2)
    }
    // Preserve the previous six-query/two-per-host catalogue throughput,
    // with bounded extra capacity for stream resolution and header checks.
    private val network = Semaphore(8)
    private val catalogue = Semaphore(6)
    private val hosts = ConcurrentHashMap<String, Host>()
    private val overflow = Host()

    suspend fun <T> withPermit(host: String, isCatalogue: Boolean, request: suspend () -> T): T {
        val gate = hosts[host] ?: if (hosts.size < 128) hosts.computeIfAbsent(host) { Host() } else overflow
        suspend fun send() = gate.network.withPermit { network.withPermit { request() } }
        // Acquire the host's catalogue slot first: queued searches for one host
        // must not occupy catalogue capacity belonging to other hosts.
        return if (isCatalogue) gate.catalogue.withPermit { catalogue.withPermit { send() } } else send()
    }
}
