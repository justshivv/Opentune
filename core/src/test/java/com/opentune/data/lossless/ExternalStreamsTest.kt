package com.opentune.data.lossless

import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalStreamsTest {
    @Test fun startupCacheRejectsExpiredFailedAndDisabledRenditions() {
        AppSettings.init(RuntimeEnvironment.getApplication())
        AppSettings.updatePlayback { it.copy(wifiQuality = AudioQuality.MAX, mobileQuality = AudioQuality.MAX, losslessUnmeteredOnly = false, jioSaavnQuality = true) }
        val fresh = ExternalStreams.Rendition("startup-fresh", "https://cdn.example/audio", "FLAC", true, System.nanoTime() / 1_000_000)
        assertNull(ExternalStreams.cached("not-looked-up"))
        ExternalStreams.remember("startup-cache", fresh)
        assertEquals(fresh, ExternalStreams.cached("startup-cache"))
        ExternalStreams.remember("startup-expired", fresh.copy(key = "expired", at = fresh.at - 11 * 60_000))
        assertNull(ExternalStreams.cached("startup-expired"))
        ExternalStreams.failed("startup-cache")
        assertNull(ExternalStreams.cached("startup-cache"))
        ExternalStreams.remember("startup-disabled", fresh.copy(key = "disabled"))
        AppSettings.updatePlayback { it.copy(wifiQuality = AudioQuality.HIGH, mobileQuality = AudioQuality.HIGH, losslessStreaming = false) }
        assertNull(ExternalStreams.cached("startup-disabled"))
    }

    @Test fun replacingAResultDoesNotInvalidateBytesThePlayerIsStillReading() {
        AppSettings.init(RuntimeEnvironment.getApplication())
        AppSettings.updatePlayback { it.copy(wifiQuality = AudioQuality.MAX, mobileQuality = AudioQuality.MAX, losslessUnmeteredOnly = false, jioSaavnQuality = true) }
        val before = ExternalStreams.Rendition("old", "https://cdn.example/old", "JioSaavn", false, System.nanoTime() / 1_000_000)
        val after = before.copy(key = "new", url = "https://cdn.example/new", lossless = true)
        ExternalStreams.remember("song", before)
        ExternalStreams.remember("song", after)
        assertEquals(before.url, ExternalStreams.resolve("song", "old"))
        assertEquals(after.url, ExternalStreams.resolve("song", "new"))
        assertNull(ExternalStreams.get("different-song", "new"))
        ExternalStreams.retryLookups()
        assertEquals(after.url, ExternalStreams.resolve("song", "new"))
        ExternalStreams.failed("song")
        assertNull(ExternalStreams.get("song", "old"))
        assertNull(ExternalStreams.get("song", "new"))
    }
    @Test fun failureOfOldStreamKeepsTheNewCandidate() {
        AppSettings.init(RuntimeEnvironment.getApplication())
        AppSettings.updatePlayback { it.copy(wifiQuality = AudioQuality.MAX, mobileQuality = AudioQuality.MAX, losslessUnmeteredOnly = false, jioSaavnQuality = true) }
        val old = ExternalStreams.Rendition("race-old", "https://cdn.example/old", "JioSaavn", false, System.nanoTime() / 1_000_000)
        val next = old.copy(key = "race-new", lossless = true)
        ExternalStreams.remember("race", old)
        ExternalStreams.remember("race", next)
        ExternalStreams.failed("race", old.key)
        assertNull(ExternalStreams.get("race", old.key))
        assertEquals(next, ExternalStreams.cached("race"))
        assertEquals(next.url, ExternalStreams.resolve("race", next.key))
    }

    @Test
    fun theAddOnsAnswerIsKeptWhenJioSaavnAnswersLater() {
        // The FLAC search and JioSaavn's quick lookup run side by side; the later one used to replace the other.
        ExternalStreams.report("both-answer", "Qobuz: no matching recording\nFLAC: no verified matching stream available")
        ExternalStreams.reportSaavn("both-answer", "JioSaavn: no matching 320 kbps recording (title, artists, album and duration)")
        assertEquals(
            "Qobuz: no matching recording\nFLAC: no verified matching stream available\nJioSaavn: no matching 320 kbps recording (title, artists, album and duration)",
            ExternalStreams.selectionDetail("both-answer"),
        )
        // The same JioSaavn line from the FLAC search's own ask isn't shown twice.
        ExternalStreams.report("both-answer", "FLAC: no verified matching stream available\nJioSaavn: no matching 320 kbps recording (title, artists, album and duration)")
        assertEquals(2, ExternalStreams.selectionDetail("both-answer")!!.lines().size)
    }
}
