package com.opentune.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpgradeHandoffTest {
    @Test fun flacArrivingDuringSaavnPreparationRetiresTheIntermediateLoad() = runBlocking {
        var flacReady = false
        var polls = 0
        val result = awaitUpgradeBuffer(
            superseded = { flacReady }, invalid = { false }, ready = { false },
            poll = { polls++; flacReady = true },
        )
        assertEquals(UpgradeBufferResult.SUPERSEDED, result)
        assertEquals(1, polls)
        // Retiring a silent intermediate load leaves the upgrade coroutine alive for FLAC.
        assertEquals(UpgradeBufferResult.READY, awaitUpgradeBuffer(
            superseded = { false }, invalid = { false }, ready = { flacReady },
            poll = { error("The preferred stream is already ready") },
        ))
    }

    @Test fun simultaneousReadyCandidatesPreferFlacBeforeSaavnBecomesAudible() = runBlocking {
        assertEquals(UpgradeBufferResult.SUPERSEDED, awaitUpgradeBuffer(
            superseded = { true }, invalid = { false }, ready = { true },
            poll = { error("No extra wait") },
        ))
    }

    @Test fun changedSongOrFailedStreamNeverBecomesReady() = runBlocking {
        assertEquals(UpgradeBufferResult.FAILED, awaitUpgradeBuffer(
            superseded = { false }, invalid = { true }, ready = { true },
            poll = { error("No extra wait") },
        ))
    }


    @Test fun smallTimingOffsetsUpgradeWithoutRepeatedSeekingOrOverlappingVocals() {
        for (drift in listOf(-20L, 0L, 20L)) assertEquals(UpgradeAlignment.BLEND, upgradeAlignment(drift))
        for (drift in listOf(-250L, -21L, 21L, 250L)) assertEquals(UpgradeAlignment.SHORT_FADE, upgradeAlignment(drift))
        for (drift in listOf(-251L, 251L, Long.MIN_VALUE, Long.MAX_VALUE)) assertEquals(UpgradeAlignment.SEEK, upgradeAlignment(drift))
    }

    @Test fun verifiedFlacDoesNotCountAsPlayingWhileDecoderStillHasYouTubeAudio() {
        assertFalse(upgradeFormatReady(true, "audio/mp4a-latm"))
        assertFalse(upgradeFormatReady(true, "audio/opus"))
        assertFalse(upgradeFormatReady(true, null))
        assertFalse(upgradeFormatReady(false, null))
        assertTrue(upgradeFormatReady(true, "audio/flac"))
        assertTrue(upgradeFormatReady(true, "audio/alac"))
        assertTrue(upgradeFormatReady(true, "audio/raw"))
        assertTrue(upgradeFormatReady(false, "audio/mp4a-latm"))
    }

    @Test fun loadedFlacStillReplacesYouTubeWhenSeamlessAlignmentFails() = runBlocking {
        var active = "YouTube"
        val switched = performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.READY_FOR_REPLACEMENT },
            stillCurrent = { true },
            prebuffer = { error("Already buffered: do not start a second load") },
            replace = { active = "FLAC"; true },
        )
        assertTrue(switched)
        assertEquals("FLAC", active)
    }

    @Test fun completedBridgeDoesNotReplaceOrPrebufferAgain() = runBlocking {
        assertTrue(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.COMPLETED },
            stillCurrent = { error("Bridge already completed") },
            prebuffer = { error("Must not load again") },
            replace = { error("Must not replace again") },
        ))
    }

    @Test fun failedDecoderKeepsTheOriginalStream() = runBlocking {
        assertFalse(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.ABORTED }, stillCurrent = { true },
            prebuffer = { error("Must not retry a failed decoder") },
            replace = { error("Must not select the failed candidate") },
        ))
    }

    @Test fun skipDuringBridgePreparationCannotReplaceTheNextSong() = runBlocking {
        assertFalse(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.READY_FOR_REPLACEMENT }, stillCurrent = { false },
            prebuffer = { error("Already prepared") },
            replace = { error("User changed songs") },
        ))
    }

    @Test fun pausedPlaybackPrebuffersBeforeReplacement() = runBlocking {
        val calls = mutableListOf<String>()
        assertTrue(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.UNAVAILABLE }, stillCurrent = { true },
            prebuffer = { calls += "buffer"; true },
            replace = { calls += "replace"; true },
        ))
        assertEquals(listOf("buffer", "replace"), calls)
    }

    @Test fun skipDuringPrebufferCannotReplaceTheNextSong() = runBlocking {
        var current = true
        assertFalse(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.UNAVAILABLE }, stillCurrent = { current },
            prebuffer = { current = false; true },
            replace = { error("User changed songs") },
        ))
    }

    @Test fun failureToLoadOrActivateIsNotReportedAsSuccess() = runBlocking {
        assertFalse(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.UNAVAILABLE }, stillCurrent = { true },
            prebuffer = { false }, replace = { error("Nothing buffered") },
        ))
        assertFalse(performUpgradeHandoff(
            bridge = { UpgradeBridgeResult.READY_FOR_REPLACEMENT }, stillCurrent = { true },
            prebuffer = { error("Already prepared") }, replace = { false },
        ))
    }

    @Test fun cancellingHandoffNeverStartsTheFallbackSwap() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                performUpgradeHandoff(
                    bridge = { throw CancellationException("Song skipped") }, stillCurrent = { true },
                    prebuffer = { error("Cancelled") }, replace = { error("Cancelled") },
                )
            }
        }
    }
}
