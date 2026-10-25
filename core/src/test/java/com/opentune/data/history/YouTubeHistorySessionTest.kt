package com.opentune.data.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubeHistorySessionTest {
    private class Fake : YouTubeHistorySession.Transport {
        val events = mutableListOf<String>()
        var onPrepare: () -> Unit = {}
        var ready = true
        override suspend fun prepare(): Boolean { events += "prepare"; onPrepare(); return ready }
        override suspend fun playback(): Boolean { events += "playback"; return true }
        override suspend fun atr(): Boolean { events += "atr"; return true }
        override suspend fun watchtime(heardSeconds: Long, final: Boolean): Boolean {
            events += "$heardSeconds:$final"; return true
        }
    }

    @Test fun actualListeningThresholdAndBatchedReportsCloseOnlyOnce() = runBlocking {
        val transport = Fake()
        val session = YouTubeHistorySession(transport) { true }
        session.heard(5_000, 240_000) // A seek to 3:50 must not replace these actual five seconds.
        assertTrue(transport.events.isEmpty())
        session.heard(30_000, 240_000)
        session.heard(35_000, 240_000)
        session.heard(60_000, 240_000)
        session.heard(64_000, 240_000, final = true)
        session.heard(70_000, 240_000, final = true)
        assertEquals(listOf("prepare", "playback", "atr", "30:false", "60:false", "64:true"), transport.events)
    }

    @Test fun pauseOrAccountChangeDuringPreparationPreventsEveryWrite() = runBlocking {
        var allowed = true
        val transport = Fake().apply { onPrepare = { allowed = false } }
        val session = YouTubeHistorySession(transport) { allowed }
        session.heard(30_000, 180_000)
        session.heard(60_000, 180_000, final = true)
        assertEquals(listOf("prepare"), transport.events)
    }

    @Test fun disabledSessionDoesNotEvenPrepareAndShortSongsNeedHalfTheirLength() = runBlocking {
        val disabled = Fake()
        YouTubeHistorySession(disabled) { false }.heard(90_000, 180_000)
        assertTrue(disabled.events.isEmpty())
        val short = Fake()
        val session = YouTubeHistorySession(short) { true }
        session.heard(9_000, 20_000)
        assertTrue(short.events.isEmpty())
        session.heard(10_000, 20_000, final = true)
        assertEquals("10:true", short.events.last())
    }

    @Test fun unavailableHistoryHasBoundedRetriesWithoutARequestPerTick() = runBlocking {
        val transport = Fake().apply { ready = false }
        val session = YouTubeHistorySession(transport) { true }
        for (seconds in 30L..240L step 5) session.heard(seconds * 1000, 300_000)
        assertEquals(listOf("prepare", "prepare", "prepare"), transport.events)
    }
}
