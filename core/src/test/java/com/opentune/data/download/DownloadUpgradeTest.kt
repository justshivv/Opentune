package com.opentune.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** "Download again in lossless": the old copy stays until the new file is in, and stays for good if none comes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadUpgradeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val song = Song("upgrade-1", "Song", "Artist", null)
    private lateinit var oldFile: File

    @Before fun setup() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        Downloads.init(context)
        oldFile = File(context.cacheDir, "upgrade-1.webm").apply { writeText("old audio") }
        Downloads.put(song.videoId) {
            DownloadEntry(SongRef.of(song), state = DownloadState.DONE, path = oldFile.path, bytes = 9,
                workId = "00000000-0000-0000-0000-000000000001", addedAt = 42, smartDownload = true)
        }
    }

    private fun upgradeId() = Downloads.entryNow(song.videoId)!!.upgrade!!.workId

    @Test fun oldCopyStaysListedAndPlayableWhileTheLosslessOneDownloads() {
        assertTrue(Downloads.downloadAgainLossless(context, song.videoId))
        val entry = Downloads.entryNow(song.videoId)!!
        assertEquals(DownloadState.DONE, entry.state)
        assertNotNull(entry.upgrade)
        assertFalse("asked for by name, so no longer the smart budget's", entry.smartDownload)
        assertEquals(oldFile, Downloads.fileFor(song.videoId))
        // Asked twice, it isn't queued twice.
        assertFalse(Downloads.downloadAgainLossless(context, song.videoId))
        assertTrue(Downloads.updateUpgrade(song.videoId, upgradeId(), 0.5f))
        assertEquals(0.5f, Downloads.entryNow(song.videoId)!!.upgrade!!.progress)
        assertFalse(Downloads.updateUpgrade(song.videoId, "someone-else", 0.7f))
    }

    @Test fun losslessFileReplacesTheOldCopy() {
        Downloads.downloadAgainLossless(context, song.videoId)
        val work = upgradeId()
        val flac = File(context.cacheDir, "upgrade-1.flac").apply { writeText("new audio") }
        val fresh = Downloads.entryNow(song.videoId)!!.copy(path = flac.path, bytes = 9, source = "Lossless Bridge",
            bits = 24, sampleRate = 96_000, codec = "flac", upgrade = null)
        assertTrue(Downloads.finishUpgrade(context, song.videoId, work, fresh))
        val now = Downloads.entryNow(song.videoId)!!
        assertEquals(flac.path, now.path)
        assertEquals(work, now.workId)
        assertNull(now.upgrade)
        assertEquals("keeps its place in Recently downloaded", 42L, now.addedAt)
        assertTrue(DownloadedCopy.lossless(now))
        // A late finish from a withdrawn job changes nothing.
        assertFalse(Downloads.finishUpgrade(context, song.videoId, work, fresh.copy(path = "/elsewhere")))
    }

    @Test fun noLosslessCopyKeepsTheOldOne() {
        Downloads.downloadAgainLossless(context, song.videoId)
        Downloads.abandonUpgrade(song.videoId, upgradeId())
        val now = Downloads.entryNow(song.videoId)!!
        assertNull(now.upgrade)
        assertEquals(DownloadState.DONE, now.state)
        assertEquals(oldFile, Downloads.fileFor(song.videoId))
    }

    @Test fun removingDuringTheRedownloadDropsBoth() {
        Downloads.downloadAgainLossless(context, song.videoId)
        val work = upgradeId()
        Downloads.remove(context, song.videoId)
        assertNull(Downloads.entryNow(song.videoId))
        assertFalse(Downloads.updateUpgrade(song.videoId, work, 0.9f))
    }

    @Test fun indexWrittenBeforeThisChangeStillReads() {
        val old = """{"song":{"videoId":"x","title":"T","artist":"A"},"state":"DONE","path":"/d/x.webm"}"""
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString(DownloadEntry.serializer(), old)
        assertNull(entry.upgrade)
        assertNull(entry.codec)
    }
}
