package com.opentune.data.addons

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AddonResolverTest {
    @Test fun failureDiagnosticsKeepVerificationDistinctAndDoNotLeakPrivateUrls() {
        assertEquals("OpenTune device verification failed", AddonResolver.failureDescription("searching catalogue",
            FirstPartyAddonAuth.VerificationFailure("OpenTune device verification failed")))
        assertEquals("checking audio header: connection timed out", AddonResolver.failureDescription("checking audio header", java.net.SocketTimeoutException("https://private.example/token")))
        assertEquals("searching catalogue: HTTP 429 · rate limited; retry in 12s", AddonResolver.failureDescription("searching catalogue", AddonHttp.Failure(429, 12)))
        assertEquals("resolving audio: invalid JSON response", AddonResolver.failureDescription("resolving audio", org.json.JSONException("private query token")))
        assertEquals("loading source manifest: invalid source response", AddonResolver.failureDescription("loading source manifest", IllegalStateException("private query token")))
    }
    private val song = Song(videoId = "test-song", title = "Wo Noor", artist = "AP Dhillon", thumbnailUrl = null)
    private val calls = mutableListOf<String>()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("audio_addons", 0).edit().clear().commit()
        AddonStore.init(context); AddonStore.outputAllowed = { false }; AddonStore.onChanged = {}; AddonResolver.retry()
    }
    private fun add(name: String, downloads: Boolean = true, gated: Boolean = false) =
        AddonStore.add("https://$name.example/", AddonManifest(name, name, "1", downloads, gated))
    private fun client(base: String) = AddonClient(base, { url ->
        calls += url
        val name = AddonProtocol.url(base).host.substringBefore('.')
        when {
            "manifest.json" in url -> """{"id":"$name","name":"$name","version":"1"}"""
            "/search" in url -> """{"tracks":[{"id":"wrong","title":"Different Song","artist":"AP Dhillon","duration":180},{"id":"right","title":"Wo Noor","artist":"AP Dhillon","duration":180}]}"""
            name == "bad" -> throw AddonHttp.Failure(503)
            else -> """{"url":"https://cdn.example/file","codec":"flac","bitDepth":24,"sampleRate":96000}"""
        }
    })
    private suspend fun resolve(download: Boolean = false): AddonStream? = AddonResolver.resolve(song, 180000, download,
        clientFor = ::client, probe = { _, _, _ -> ByteArray(42).apply {
            "fLaC".toByteArray().copyInto(this); this[4] = 0x80.toByte(); this[7] = 34
            val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or (44100L * 180)
            for (i in 0..7) this[18+i] = (packed ushr (56-i*8)).toByte()
        } })
    @Test fun followsPriorityFallsBackAndRejectsUnrelatedRecording() = runBlocking {
        add("bad"); add("good"); add("last")
        val result = resolve()!!
        assertEquals("good", result.provider)
        assertEquals(16, result.bits); assertEquals(44100, result.sampleRate)
        assertFalse(calls.any { "/wrong" in it || "last.example" in it })
        assertTrue(calls.first().contains("bad.example"))
        calls.clear(); AddonStore.move(AddonStore.addons.value.last().key, -2)
        assertEquals("last", resolve()!!.provider)
        assertFalse(calls.any { "bad.example" in it || "good.example" in it })
    }
    @Test fun disabledGatedAndStreamingOnlySourcesReceiveNoDownloadRequests() = runBlocking {
        add("disabled"); AddonStore.enable(AddonStore.addons.value.first().key, false)
        add("gated", gated = true); add("streaming", downloads = false); add("good")
        assertEquals("good", resolve(download = true)!!.provider)
        assertTrue(calls.all { "good.example" in it })
    }
    @Test fun playbackFailureMovesToNextSource() = runBlocking {
        add("first"); add("second")
        val first = resolve()!!
        AddonResolver.failed(first.addonId, song.videoId)
        calls.clear()
        assertEquals("second", resolve()!!.provider)
        assertTrue(calls.all { "second.example" in it })
    }

    @Test fun bestAvailableComparesSourcesAndKeepsPriorityForTies() = runBlocking {
        add("first"); add("second")
        val result = AddonResolver.resolve(song, 180000, bestAvailable = true, clientFor = ::client,
            probe = { _, _, _ -> ByteArray(42).apply {
                "fLaC".toByteArray().copyInto(this); this[4] = 0x80.toByte(); this[7] = 34
                val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or (44100L * 180)
                for (i in 0..7) this[18+i] = (packed ushr (56-i*8)).toByte()
            } })!!
        assertEquals("first", result.provider)
        assertTrue(calls.any { "second.example" in it })
    }
}
