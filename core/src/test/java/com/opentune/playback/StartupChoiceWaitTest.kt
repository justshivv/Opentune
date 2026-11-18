package com.opentune.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StartupChoiceWaitTest {
    @Test fun upgradeWaitsForAStartupChoiceThatHasNotBegunYet() = runBlocking {
        var choosing = true
        val waited = async { awaitStartupChoice({ choosing }, timeoutMs = 2_000, pollMs = 10) }
        delay(50)
        assertFalse(waited.isCompleted)
        choosing = false
        assertTrue(withTimeout(2_000) { waited.await() })
    }

    @Test fun upgradeGivesUpWhenTheStartupChoiceNeverComes() = runBlocking {
        assertFalse(awaitStartupChoice({ true }, timeoutMs = 100, pollMs = 10))
        assertTrue(awaitStartupChoice({ false }, timeoutMs = 100, pollMs = 10))
    }
}
