package com.opentune.data.lossless

import com.opentune.data.isYouTubeId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LosslessStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val track = LosslessTrack("B012345678", "Wo Noor", "AP Dhillon", durationMs = 190000)
    private fun entry() = LosslessEntry(track, UUID.randomUUID().toString())
    @Test fun temporaryPreviewUrlsNeverEnterTheDurableDownloadIndex() {
        val store = LosslessStore(temp.newFolder())
        val e = entry().copy(track = track.copy(previewUrl = "https://cdn.example/preview?temporary=secret"))
        store.put(e)
        val json = java.io.File(store.root, "index.json").readText()
        assertFalse(json.contains("temporary"))
        assertFalse(json.contains("previewUrl"))
        assertNull(LosslessStore(store.root).entries.value[track.id]!!.track.previewUrl)
    }
    @Test fun restartPreservesWaitingTrackAndDoesNotTreatItAsYouTube() {
        val store = LosslessStore(temp.newFolder()); val e = entry().copy(state = LosslessState.WAITING)
        store.put(e)
        // Dynamic defaults must be serialized, or a restart silently changes the queue date.
        assertTrue(java.io.File(store.root, "index.json").readText().contains("\"addedAt\":${e.addedAt}"))
        assertEquals(e, LosslessStore(store.root).entries.value[track.id])
        assertFalse(isYouTubeId(track.song().videoId)); assertEquals("3:10", track.song().durationText)
    }
    @Test fun removalAndReplacementRejectLateWorkerCompletion() {
        val store = LosslessStore(temp.newFolder()); val old = entry(); store.put(old)
        val next = entry(); store.put(next)
        assertFalse(store.update(track.id, old.workId) { it.copy(state = LosslessState.DONE) })
        assertEquals(next, store.entries.value[track.id])
        store.remove(track.id)
        assertFalse(store.update(track.id, next.workId) { it.copy(state = LosslessState.DONE) })
        assertTrue(LosslessStore(store.root).entries.value.isEmpty())
    }
    @Test fun savedQualitySurvivesRestartAndMissingFileBecomesRetryable() {
        val store = LosslessStore(temp.newFolder()); val e = entry(); store.put(e)
        store.folder(e).mkdirs(); store.file(e).writeBytes(ByteArray(100))
        store.update(track.id, e.workId) { it.copy(state = LosslessState.DONE, bits = 24, sampleRate = 96000) }
        assertEquals(24, LosslessStore(store.root).entries.value[track.id]!!.bits)
        store.file(e).delete()
        assertEquals(LosslessState.FAILED, LosslessStore(store.root).entries.value[track.id]!!.state)
    }
    @Test fun progressDoesNotCommitCompletionAndPathsStayInsideStorage() {
        val store = LosslessStore(temp.newFolder()); val e = entry(); store.put(e)
        store.update(track.id, e.workId, false) { it.copy(state = LosslessState.DOWNLOADING, bytes = 800) }
        assertEquals(LosslessState.QUEUED, LosslessStore(store.root).entries.value[track.id]!!.state)
        assertThrows(IllegalArgumentException::class.java) { store.put(e.copy(workId = "../../outside")) }
        assertThrows(IllegalArgumentException::class.java) { store.put(e.copy(track = track.copy(id = "../outside"))) }
    }
    @Test fun errorMessagesIdentifyStageWithoutLeakingProviderBodies() {
        val text = losslessError("Requesting download ticket", LosslessHttp.HttpFailure(403, "private material"))
        assertTrue(text.contains("Requesting download ticket")); assertTrue(text.contains("403")); assertFalse(text.contains("private material"))
    }
}
