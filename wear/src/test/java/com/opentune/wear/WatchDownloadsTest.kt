package com.opentune.wear

import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.library.SongRef
import com.opentune.wear.ui.DownloadText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchDownloadsTest {
    private fun entry(id: String, state: DownloadState, bytes: Long = 0, at: Long = 0, progress: Float = 0f, error: String? = null) =
        DownloadEntry(SongRef(id, "Song $id", "Artist"), state = state, bytes = bytes, addedAt = at, progress = progress, error = error)

    @Test
    fun theDownloadedScreenSplitsWhatsHereFromWhatsComing() {
        val s = WatchDownloads.summary(mapOf(
            "a" to entry("a", DownloadState.DONE, bytes = 3_000_000, at = 1),
            "b" to entry("b", DownloadState.DONE, bytes = 4_000_000, at = 2),
            "c" to entry("c", DownloadState.QUEUED),
            "d" to entry("d", DownloadState.DOWNLOADING),
            "e" to entry("e", DownloadState.FAILED),
        ))
        assertEquals(listOf("b", "a"), s.done.map { it.song.videoId })
        assertEquals(setOf("c", "d"), s.going.map { it.song.videoId }.toSet())
        assertEquals(listOf("e"), s.failed.map { it.song.videoId })
        assertEquals(7_000_000L, s.bytes)
    }

    @Test
    fun sizesReadInMegabytesThenGigabytes() {
        assertEquals("7 MB", WatchDownloads.size(7L * 1024 * 1024))
        assertEquals("1.5 GB", WatchDownloads.size(1536L * 1024 * 1024))
    }

    @Test
    fun aWaitingDownloadSaysWhetherItsWaitingForWiFi() {
        assertNull(DownloadText.of(null, wifi = true))
        assertEquals("Waiting for Wi-Fi", DownloadText.of(entry("a", DownloadState.QUEUED), wifi = false))
        assertEquals("Waiting to download", DownloadText.of(entry("a", DownloadState.QUEUED), wifi = true))
        assertEquals("Downloading 42%", DownloadText.of(entry("a", DownloadState.DOWNLOADING, progress = 0.42f), wifi = true))
        assertEquals("Not enough space on the watch", DownloadText.of(entry("a", DownloadState.FAILED, error = "Not enough space on the watch"), wifi = true))
    }
}
