package com.opentune.ui.components

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConnectionNoticeStateTest {
    @Test fun resumeAndRepeatedOnlineCallbacksNeverAnnounceRecovery() {
        val before = ConnectionNoticeState()
        assertNull(before.accept(true))
        assertNull(before.accept(true))
        val resumed = ConnectionNoticeState()
        assertNull(resumed.accept(true))
    }

    @Test fun recoveryRequiresAnObservedOutageInThisForegroundSession() {
        val state = ConnectionNoticeState()
        assertEquals(false, state.accept(false))
        assertNull(state.accept(false))
        assertEquals(true, state.accept(true))
        assertNull(state.accept(true))
        // Offline while backgrounded does not manufacture a recovery announcement on resume.
        assertNull(ConnectionNoticeState().accept(true))
    }

    @Test fun briefValidationLossDuringWakeDoesNotBecomeAnOfflineEvent() = runBlocking {
        val states = flow {
            emit(false)
            delay(10)
            emit(true)
        }.steadyConnectivity(initialOfflineMs = 100, offlineMs = 100, onlineMs = 0).toList()
        assertEquals(listOf(true), states)
    }

    @Test fun sustainedOutageAndRecoveryBothReachTheNoticePolicy() = runBlocking {
        val states = flow {
            emit(false)
            delay(60)
            emit(true)
        }.steadyConnectivity(initialOfflineMs = 10, offlineMs = 10, onlineMs = 0).toList()
        val policy = ConnectionNoticeState()
        assertEquals(listOf(false, true), states.mapNotNull(policy::accept))
    }
}
