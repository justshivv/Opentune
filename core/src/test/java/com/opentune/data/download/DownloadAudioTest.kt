package com.opentune.data.download

import java.io.File
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadAudioTest {
    @Test fun rejectsHtmlAndZeroFilledDownloads() {
        for (data in listOf("<html>Access denied</html>".toByteArray(), ByteArray(4096))) {
            val file = File.createTempFile("download", ".m4a")
            try {
                file.writeBytes(data)
                assertTrue(runCatching { DownloadAudio.validate(file) }.exceptionOrNull() is IOException)
            } finally { file.delete() }
        }
    }
    @Test fun rejectsAFlacDowngradeEvenWhenTheFileHeaderIsPlayable() {
        val file = File.createTempFile("downgraded", ".flac")
        try {
            val header = ByteArray(42).apply {
                "fLaC".toByteArray().copyInto(this)
                this[4] = 0x80.toByte()
                this[7] = 34
                val packed = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or (44_100L * 180)
                for (i in 0..7) this[18 + i] = (packed ushr (56 - i * 8)).toByte()
            }
            file.writeBytes(header)
            val promised = com.opentune.data.addons.AddonStream("https://cdn.example/audio", "flac", null, 24, 96_000, null)
            assertTrue(runCatching { DownloadAudio.validate(file, promised) }.exceptionOrNull() is IOException)
        } finally { file.delete() }
    }

}
