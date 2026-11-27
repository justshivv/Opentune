package com.opentune.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDownloadCheckTest {
    private fun rules(wifi: Boolean) = DeviceDownloads(160, { wifi }, keepFreeBytes = 500, noSpaceMessage = "No space")

    @Test
    fun withoutDeviceRulesEverythingGoes() {
        assertEquals(DeviceDownloadCheck.Verdict.GO, DeviceDownloadCheck.verdict(null, 0))
    }

    @Test
    fun theWatchWaitsForItsWiFiThenForRoom() {
        assertEquals(DeviceDownloadCheck.Verdict.WAIT, DeviceDownloadCheck.verdict(rules(wifi = false), 10_000))
        assertEquals(DeviceDownloadCheck.Verdict.NO_SPACE, DeviceDownloadCheck.verdict(rules(wifi = true), 499))
        assertEquals(DeviceDownloadCheck.Verdict.GO, DeviceDownloadCheck.verdict(rules(wifi = true), 500))
    }
}
