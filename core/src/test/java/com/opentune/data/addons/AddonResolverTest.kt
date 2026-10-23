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
    private val song = Song(videoId = "test-song", title = "Wo Noor", artist = "AP Dhillon", thumbnailUrl = null)
    // Add-ons are asked at once, from several threads.
    private val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
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
        // Every add-on is asked at once; of equal quality, the higher in the order wins.
        assertEquals("good", result.provider)
        assertEquals(16, result.bits); assertEquals(44100, result.sampleRate)
        assertFalse(calls.any { "/wrong" in it })
        assertTrue(calls.any { "bad.example" in it } && calls.any { "last.example" in it })
        calls.clear(); AddonStore.move(AddonStore.addons.value.last().key, -2)
        assertEquals("last", resolve()!!.provider)
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

    /** A FLAC header for [bits]-bit, [rate] Hz stereo, three minutes long. */
    private fun header(bits: Int, rate: Long) = ByteArray(42).apply {
        "fLaC".toByteArray().copyInto(this); this[4] = 0x80.toByte(); this[7] = 34
        val packed = (rate shl 44) or (1L shl 41) or ((bits - 1).toLong() shl 36) or (rate * 180)
        for (i in 0..7) this[18 + i] = (packed ushr (56 - i * 8)).toByte()
    }

    @Test fun aSlowerBetterAnswerIsNotCutOffButComesLate() = runBlocking {
        add("fast"); add("slow")
        fun named(base: String) = AddonClient(base, { url ->
            val name = AddonProtocol.url(base).host.substringBefore('.')
            when {
                "manifest.json" in url -> """{"id":"$name","name":"$name","version":"1"}"""
                "/search" in url -> """{"tracks":[{"id":"right","title":"Wo Noor","artist":"AP Dhillon","duration":180}]}"""
                else -> """{"url":"https://cdn.example/$name","codec":"flac"}"""
            }
        })
        val late = kotlinx.coroutines.CompletableDeferred<AddonStream>()
        val result = AddonResolver.resolve(song, 180000, clientFor = ::named, late = { late.complete(it) },
            probe = { url, _, _ -> if ("slow" in url) { kotlinx.coroutines.delay(4_000); header(24, 96_000) } else header(16, 44_100) })!!
        // The quick one plays first, without waiting the slow one out...
        assertEquals("fast", result.provider)
        // ...and the slow one's better file still arrives, to switch to.
        val better = kotlinx.coroutines.withTimeout(10_000) { late.await() }
        assertEquals("slow", better.provider)
        assertEquals(24, better.bits); assertEquals(96000, better.sampleRate)
    }

    @Test fun aSlowerWorseAnswerStillComesLateToFallBackOn() = runBlocking {
        add("fast"); add("slow")
        fun named(base: String) = AddonClient(base, { url ->
            val name = AddonProtocol.url(base).host.substringBefore('.')
            when {
                "manifest.json" in url -> """{"id":"$name","name":"$name","version":"1"}"""
                "/search" in url -> """{"tracks":[{"id":"right","title":"Wo Noor","artist":"AP Dhillon","duration":180}]}"""
                else -> """{"url":"https://cdn.example/$name","codec":"flac"}"""
            }
        })
        val late = kotlinx.coroutines.CompletableDeferred<AddonStream>()
        val started = System.nanoTime()
        val result = AddonResolver.resolve(song, 180000, clientFor = ::named, late = { late.complete(it) },
            probe = { url, _, _ -> if ("slow" in url) { kotlinx.coroutines.delay(1_500); header(16, 44_100) } else header(24, 96_000) })!!
        // The first verified file is handed over at once, not after the others had their say...
        assertEquals("fast", result.provider)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_200)
        // ...and the slower one, though not better, still arrives as a fallback.
        assertEquals("slow", kotlinx.coroutines.withTimeout(10_000) { late.await() }.provider)
    }

    @Test fun eachSourceSaysWhetherItAnsweredOrErredAndAnsweredOnesCanBeLeftOut() = runBlocking {
        add("bad"); add("good")
        val heard = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val first = AddonResolver.resolveAll(song, 180000, clientFor = ::client, settled = { key, erred -> heard[key] = erred },
            probe = { _, _, _ -> header(16, 44_100) })
        assertEquals("good", first.single().provider)
        val (bad, good) = AddonStore.addons.value.map { it.key }
        // The 503 is an error to ask again about; the verified file is an answer.
        kotlinx.coroutines.withTimeout(5_000) { while (heard.size < 2) kotlinx.coroutines.delay(10) }
        assertEquals(true, heard[bad]); assertEquals(false, heard[good])
        // Asked again, the one that answered is left alone: only the one that erred is asked.
        calls.clear()
        assertTrue(AddonResolver.resolveAll(song, 180000, clientFor = ::client, skip = setOf(good),
            probe = { _, _, _ -> header(16, 44_100) }).isEmpty())
        assertTrue(calls.isNotEmpty() && calls.all { "bad.example" in it })
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
