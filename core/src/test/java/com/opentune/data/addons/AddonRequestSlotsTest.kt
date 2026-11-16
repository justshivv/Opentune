package com.opentune.data.addons

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AddonRequestSlotsTest {
    @Test fun stalledCataloguesLeaveCapacityToVerifyAStream() = runBlocking {
        val slots = AddonRequestSlots()
        val started = List(6) { CompletableDeferred<Unit>() }
        val busy = started.mapIndexed { index, signal -> launch {
            slots.withPermit("catalogue-${index / 2}", true) { signal.complete(Unit); awaitCancellation() }
        } }
        try {
            withTimeout(2_000) { started.forEach { it.await() } }
            assertEquals("verified", withTimeout(2_000) { slots.withPermit("cdn", false) { "verified" } })
            // Even the same host retains a connection for stream resolution.
            assertEquals("resolved", withTimeout(2_000) { slots.withPermit("catalogue-0", false) { "resolved" } })
        } finally { busy.forEach { it.cancelAndJoin() } }
    }

    @Test fun queuedSearchesForOneHostDoNotOccupyOtherHostsCapacity() = runBlocking {
        val slots = AddonRequestSlots()
        val started = CompletableDeferred<Unit>()
        val first = launch { slots.withPermit("slow", true) { started.complete(Unit); awaitCancellation() } }
        val queued = (1..8).map { launch { slots.withPermit("slow", true) { awaitCancellation() } } }
        try {
            withTimeout(2_000) { started.await() }
            yield()
            assertEquals("found", withTimeout(2_000) { slots.withPermit("fast", true) { "found" } })
        } finally { queued.forEach { it.cancelAndJoin() }; first.cancelAndJoin() }
        assertEquals("reused", withTimeout(2_000) { slots.withPermit("slow", true) { "reused" } })
    }

    @Test fun rateLimitBackoffDoesNotHoldTheHostConnection() = runBlocking {
        val slots = AddonRequestSlots()
        val limited = List(2) { CompletableDeferred<Unit>() }
        val retries = limited.map { signal -> async {
            var calls = 0
            AddonHttp.waitingOutRateLimits {
                slots.withPermit("limited", true) {
                    if (++calls == 1) { signal.complete(Unit); throw AddonHttp.Failure(429, 2) }
                    "retried"
                }
            }
        } }
        try {
            withTimeout(2_000) { limited.forEach { it.await() } }
            assertEquals("other request", withTimeout(1_000) { slots.withPermit("limited", true) { "other request" } })
            assertTrue(retries.none { it.isCompleted })
        } finally { retries.forEach { it.cancelAndJoin() } }
    }
}
