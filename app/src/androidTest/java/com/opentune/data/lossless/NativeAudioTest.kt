package com.opentune.data.lossless

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs against the actual R8 release APK and its native libraries, without a provider account. */
@RunWith(AndroidJUnit4::class)
class NativeAudioTest {
    private fun execute(vararg args: String) {
        val done = CountDownLatch(1)
        var successful = false
        val session = FFmpegSession.create(arrayOf("-v", "error", "-y", *args), {
            successful = ReturnCode.isSuccess(it.returnCode)
            done.countDown()
        }, null, null, LogRedirectionStrategy.NEVER_PRINT_LOGS)
        FFmpegKitConfig.asyncFFmpegExecute(session)
        assertTrue("Native audio command did not complete", done.await(30, TimeUnit.SECONDS))
        assertTrue("Native audio fixture command failed", successful)
        FFmpegKitConfig.clearSessions()
    }

    @Test fun releaseEnginePreparesPlainAndEncryptedContainersAndValidatesFlac() = runBlocking {
        withTimeout(120_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(context.cacheDir, "native-audio-${System.nanoTime()}").apply { mkdirs() }
            try {
                assertTrue(AmazonAudio.checkEngine().isNotBlank())
                val source = File(folder, "fixture.flac")
                execute("-f", "lavfi", "-i", "sine=frequency=997:sample_rate=44100", "-t", "1", "-ac", "2", "-c:a", "flac", source.path)
                val key = "0123456789abcdef0123456789abcdef"
                for (encrypted in listOf(false, true)) {
                    val input = File(folder, "input-${encrypted}.m4a")
                    val options = if (encrypted) arrayOf("-encryption_scheme", "cenc-aes-ctr", "-encryption_key", key, "-encryption_kid", "00112233445566778899aabbccddeeff") else emptyArray()
                    execute("-i", source.path, "-c:a", "copy", "-strict", "-2", *options, "-f", "mp4", input.path)
                    val output = File(folder, "output-${encrypted}.flac")
                    val stages = mutableListOf<LosslessState>()
                    val info = AmazonAudio.finishDownloadedFile(input, output, if (encrypted) key else "", 1000) { stage, _, _ -> stages += stage }
                    assertEquals(44100, info.sampleRate)
                    assertEquals(16, info.bits)
                    assertEquals(1000L, info.durationMs)
                    assertTrue(output.length() > 42)
                    assertFalse(input.exists())
                    assertEquals(listOf(LosslessState.PREPARING, LosslessState.VERIFYING), stages)
                }
            } finally { folder.deleteRecursively() }
        }
    }
}
