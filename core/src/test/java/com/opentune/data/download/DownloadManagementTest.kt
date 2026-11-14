package com.opentune.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadManagementTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val songs = (1..4).map { Song("download-$it", "Song $it", "Artist", null) }
    private fun entry(index: Int, state: DownloadState, generation: String = "00000000-0000-0000-0000-00000000000$index") =
        DownloadEntry(SongRef.of(songs[index]), state = state, workId = generation)

    @Before fun setup() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        Downloads.init(context)
    }

    @Test fun cancelPlaylistKeepsCompletedAndUnrelatedDownloadsAndRejectsLateCallbacks() {
        Downloads.put(songs[0].videoId) { entry(0, DownloadState.DONE) }
        Downloads.put(songs[1].videoId) { entry(1, DownloadState.DOWNLOADING) }
        Downloads.put(songs[2].videoId) { entry(2, DownloadState.QUEUED) }
        Downloads.put(songs[3].videoId) { entry(3, DownloadState.DOWNLOADING) }
        Downloads.cancelAll(context, songs.take(3) + songs[1])
        assertEquals(DownloadState.DONE, Downloads.entryNow(songs[0].videoId)?.state)
        assertNull(Downloads.entryNow(songs[1].videoId))
        assertNull(Downloads.entryNow(songs[2].videoId))
        assertNotNull(Downloads.entryNow(songs[3].videoId))
        assertFalse(Downloads.updateWorker(songs[1].videoId, entry(1, DownloadState.QUEUED).workId) { it.copy(state = DownloadState.DONE) })
        assertNull(Downloads.entryNow(songs[1].videoId))
    }

    @Test fun removedOrReplacedWorkersCannotOverwriteAnotherAttempt() {
        val old = entry(0, DownloadState.DOWNLOADING)
        Downloads.put(songs[0].videoId) { old }
        Downloads.remove(context, songs[0].videoId)
        assertFalse(Downloads.updateWorker(songs[0].videoId, old.workId) { it.copy(state = DownloadState.FAILED) })
        val replacement = old.copy(workId = "00000000-0000-0000-0000-000000000099")
        Downloads.put(songs[0].videoId) { replacement }
        assertFalse(Downloads.updateWorker(songs[0].videoId, old.workId) { it.copy(progress = 1f) })
        assertTrue(Downloads.updateWorker(songs[0].videoId, replacement.workId) { it.copy(progress = .5f) })
        assertEquals(.5f, Downloads.entryNow(songs[0].videoId)!!.progress, 0f)
    }

    @Test fun removePlaylistClearsAllItsStatesButNotOtherSongs() {
        songs.forEachIndexed { i, song -> Downloads.put(song.videoId) { entry(i, DownloadState.entries[i]) } }
        Downloads.removeAll(context, songs.take(3))
        songs.take(3).forEach { assertNull(Downloads.entryNow(it.videoId)) }
        assertNotNull(Downloads.entryNow(songs[3].videoId))
    }

    @Test fun smartCleanupRechecksManualOwnershipAndCompletedState() {
        val id = songs[0].videoId
        Downloads.put(id) { entry(0, DownloadState.QUEUED).copy(smartDownload = true) }
        // A manual save promotes the row after automatic cleanup collected its IDs.
        Downloads.put(id) { it!!.copy(smartDownload = false) }
        Downloads.removeSmart(context, id)
        assertNotNull(Downloads.entryNow(id))
        Downloads.put(id) { it!!.copy(smartDownload = true, state = DownloadState.DONE) }
        Downloads.removeSmart(context, id, onlyPending = true)
        assertNotNull(Downloads.entryNow(id))
        Downloads.removeSmart(context, id)
        assertNull(Downloads.entryNow(id))
    }

    @Test fun selectionDeduplicatesSongsAndExcludesDeviceFiles() {
        val local = Song("local:123", "Device song", "Artist", null)
        val selected = downloadSelection(songs + songs[0] + local, mapOf(
            songs[0].videoId to entry(0, DownloadState.DONE),
            songs[1].videoId to entry(1, DownloadState.DOWNLOADING),
            songs[2].videoId to entry(2, DownloadState.FAILED)))
        assertEquals(4, selected.wanted.size)
        assertEquals(setOf(songs[0].videoId), selected.complete)
        assertEquals(setOf(songs[1].videoId), selected.active)
        assertEquals(3, selected.stored.size)
    }
}
