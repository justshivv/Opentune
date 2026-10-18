package com.opentune.data.lossless

import org.junit.Assert.*
import org.junit.Test

class DownloadNetworkTest {
    private val entry = LosslessEntry(LosslessTrack("B012345678", "Song", "Artist"), "job", unmeteredOnly = true)
    @Test fun queueExplainsOfflineMeteredAndSchedulerWaitSeparately() {
        assertEquals("Waiting for an internet connection", DownloadNetwork.waitingReason(entry, DownloadNetworkState()))
        assertEquals("Waiting for Wi-Fi or another unmetered network", DownloadNetwork.waitingReason(entry, DownloadNetworkState(true, true)))
        assertEquals("Waiting for Android to start the download", DownloadNetwork.waitingReason(entry.copy(unmeteredOnly = false), DownloadNetworkState(true, true)))
        assertEquals("Waiting for the active download to finish", DownloadNetwork.waitingReason(entry, DownloadNetworkState(true, false), true))
    }
}
