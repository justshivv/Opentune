package com.opentune.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun comparesNumberByNumber() {
        assertTrue(UpdateCheck.isNewer("1.10", "1.9"))
        assertTrue(UpdateCheck.isNewer("2.0.1", "2.0"))
        assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("0.9", "1.0"))
    }

    private val release = Json.parseToJsonElement(
        """
        {"tag_name":"v0.3.0","html_url":"https://github.com/justshivv/Opentune/releases/tag/v0.3.0","body":"Notes",
         "assets":[
          {"name":"OpenTune-v0.3.0-armeabi-v7a.apk","browser_download_url":"https://x/v7a.apk","size":9000000},
          {"name":"OpenTune-v0.3.0-arm64-v8a.apk","browser_download_url":"https://x/arm64.apk","size":9300000},
          {"name":"OpenTune-v0.3.0-universal.apk","browser_download_url":"https://x/universal.apk","size":11800000},
          {"name":"SHA256SUMS.txt","browser_download_url":"https://x/sums","size":300}
         ]}
        """,
    ).jsonObject

    @Test fun picksTheApkForThePhonesProcessor() {
        val r = UpdateCheck.parse(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("0.3.0", r.version)
        assertEquals("OpenTune-v0.3.0-arm64-v8a.apk", r.apk?.name)
        assertEquals(9_300_000L, r.apk?.bytes)
        assertEquals("SHA256SUMS.txt", r.sums?.name)
        assertEquals("Notes", r.notes)
    }

    @Test fun fallsBackToUniversal() {
        assertEquals("OpenTune-v0.3.0-universal.apk", UpdateCheck.parse(release, listOf("riscv64"))?.apk?.name)
    }

    @Test fun readsSha256sumOutput() {
        val sums = """
            0f1e2d3c  OpenTune-v0.3.0-arm64-v8a.apk
            aabbccdd *OpenTune-v0.3.0-universal.apk
        """.trimIndent()
        assertEquals("0f1e2d3c", Updater.expectedSum(sums, "OpenTune-v0.3.0-arm64-v8a.apk"))
        assertEquals("aabbccdd", Updater.expectedSum(sums, "OpenTune-v0.3.0-universal.apk"))
        assertNull(Updater.expectedSum(sums, "OpenTune-v0.3.0-x86_64.apk"))
    }

    @Test fun readsTheLatestReleaseFromWhereTheReleasesPageLeads() {
        val r = UpdateCheck.fromPage("https://github.com/justshivv/Opentune/releases/tag/v0.3.3", listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("0.3.3", r.version)
        assertEquals("OpenTune-v0.3.3-arm64-v8a.apk", r.apk?.name)
        assertEquals("https://github.com/justshivv/Opentune/releases/download/v0.3.3/OpenTune-v0.3.3-arm64-v8a.apk", r.apk?.url)
        assertEquals("https://github.com/justshivv/Opentune/releases/download/v0.3.3/SHA256SUMS.txt", r.sums?.url)
        // A processor releases have no APK of their own for gets the universal one.
        assertEquals("OpenTune-v0.3.3-universal.apk", UpdateCheck.fromPage("https://github.com/justshivv/Opentune/releases/tag/v0.3.3", listOf("riscv64"))!!.apk?.name)
        // No release yet: the page stays on the release list.
        assertNull(UpdateCheck.fromPage("https://github.com/justshivv/Opentune/releases", listOf("arm64-v8a")))
    }
}
