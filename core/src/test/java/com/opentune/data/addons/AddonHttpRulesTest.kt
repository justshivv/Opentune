package com.opentune.data.addons

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AddonHttpRulesTest {
    @Test fun aServerThatRefusesTheBitChordAgentIsAskedWithoutItFromThenOn() = runBlocking {
        val asked = mutableListOf<String?>()
        val answer = AddonHttp.withAgentFallback("picky.example") { agent ->
            asked += agent
            if (agent != null) throw AddonHttp.Failure(403) else "ok"
        }
        assertEquals("ok", answer)
        assertEquals(2, asked.size); assertNotNull(asked[0]); assertNull(asked[1])
        asked.clear()
        AddonHttp.withAgentFallback("picky.example") { agent -> asked += agent; "ok" }
        assertEquals(listOf<String?>(null), asked)
    }

    @Test fun aServerThatTakesTheAgentOrLacksTheFileIsNotAskedTwice() = runBlocking {
        val asked = mutableListOf<String?>()
        assertEquals("ok", AddonHttp.withAgentFallback("fine.example") { agent -> asked += agent; "ok" })
        try { AddonHttp.withAgentFallback("missing.example") { agent -> asked += agent; throw AddonHttp.Failure(404) }; fail() }
        catch (e: AddonHttp.Failure) { assertEquals(404, e.status) }
        assertEquals(2, asked.size)
        assertTrue(asked.all { it != null })
    }

    @Test fun aShortRateLimitIsWaitedOutAndALongOneIsHandedBack() = runBlocking {
        var calls = 0
        assertEquals("ok", AddonHttp.waitingOutRateLimits { if (++calls < 3) throw AddonHttp.Failure(429) else "ok" })
        assertEquals(3, calls)
        calls = 0
        try { AddonHttp.waitingOutRateLimits<String> { calls++; throw AddonHttp.Failure(429, 60) }; fail() }
        catch (e: AddonHttp.Failure) { assertEquals(429, e.status) }
        assertEquals(1, calls)
    }
}
