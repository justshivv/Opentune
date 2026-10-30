package com.opentune.desktop

import com.opentune.data.addons.AddonTrack
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JioSaavn's and the add-ons' rules, and lossless playback that gives the sound card exactly the file's samples. */
class SourcesTest {
    private val wanted = JioSaavn.Wanted("Tum Hi Ho", "Arijit Singh", "Aashiqui 2", 262.0)

    private fun saavn(title: String, seconds: Int, singer: String, hq: Boolean = true, id: String = "abc_12") = JSONObject()
        .put("id", id).put("title", title)
        .put("more_info", JSONObject().put("320kbps", hq.toString()).put("duration", seconds.toString())
            .put("artistMap", JSONObject().put("primary_artists", JSONArray().put(JSONObject().put("name", singer)))))

    @Test fun jioSaavnTakesOnlyTheSameRecording() {
        assertEquals("abc_12", JioSaavn.match(JSONArray().put(saavn("Tum Hi Ho", 263, "Arijit Singh")), wanted))
        // Too long, a remix, someone else, or no 320 kbps file: none of them is the song.
        assertNull(JioSaavn.match(JSONArray().put(saavn("Tum Hi Ho", 270, "Arijit Singh")), wanted))
        assertNull(JioSaavn.match(JSONArray().put(saavn("Tum Hi Ho (Remix)", 262, "Arijit Singh")), wanted))
        assertNull(JioSaavn.match(JSONArray().put(saavn("Tum Hi Ho", 262, "Someone Else")), wanted))
        assertNull(JioSaavn.match(JSONArray().put(saavn("Tum Hi Ho", 262, "Arijit Singh", hq = false)), wanted))
        // A soundtrack's "(From ...)" and HTML entities don't stand in the way.
        assertEquals("abc_12", JioSaavn.match(JSONArray().put(saavn("Tum Hi Ho (From &quot;Aashiqui 2&quot;)", 262, "Arijit Singh")), wanted))
    }

    @Test fun jioSaavnStreamsComeFromItsOwnCdnAt320() {
        assertEquals("https://aac.saavncdn.com/123/abc_320.mp4", JioSaavn.rendition320("http://aac.saavncdn.com/123/abc_96.mp4"))
        assertNull(JioSaavn.rendition320("https://evil.example/123/abc_96.mp4"))
        val ftyp = ByteArray(16).also { "ftyp".toByteArray().copyInto(it, 4) }
        assertTrue(JioSaavn.verified320(ftyp, 10_500_000, 262.0))
        assertFalse(JioSaavn.verified320(ftyp, 3_000_000, 262.0))
    }

    @Test fun addonsMatchTitleArtistsAndLength() {
        fun track(title: String, artist: String, ms: Long) = AddonTrack("1", title, artist, null, ms, null, null, "")
        assertTrue(Addons.matches(wanted, track("Tum Hi Ho", "Arijit Singh, Mithoon", 261_000)))
        assertFalse(Addons.matches(wanted, track("Tum Hi Ho", "Mithoon", 261_000)))
        assertFalse(Addons.matches(wanted, track("Tum Hi Ho", "Arijit Singh", 280_000)))
    }

    @Test fun thePublishedCatalogFoldsInLikeOnThePhone() {
        val published = Addons.publishedOf(
            """{"sources":[{"id":"mono","name":"Monochrome","url":"https://addon-opentune.rethinknow.in/monochrome/manifest.json","enabled":true},
               {"id":"bad id!","name":"X","url":"https://x.example/manifest.json"},
               {"id":"net","name":"NetEase","url":"https://addon-opentune.rethinknow.in/netease","enabled":false}]}""",
        )
        assertEquals(listOf("mono", "net"), published.map { it.id })
        assertEquals("https://addon-opentune.rethinknow.in/netease/manifest.json", published[1].url)
        val mine = Addons.Source("mine", "Mine", "https://mine.example/manifest.json")
        val stale = Addons.Source("admin:gone", "Gone", "https://gone.example/manifest.json", admin = true)
        val turnedOff = Addons.Source("admin:mono", "Old name", "https://old.example/manifest.json", enabled = false, admin = true)
        val merged = Addons.merge(listOf(mine, stale, turnedOff), published)
        // Their own source and their "off" stay; the gone one goes; the new one joins, marked off by its owner.
        assertEquals(listOf("mine", "admin:mono", "admin:net"), merged.map { it.id })
        assertEquals("Monochrome", merged[1].name)
        assertFalse(merged[1].enabled)
        assertTrue(merged[2].ownerOff)
    }

    @Test fun flacHeadersAreRead() {
        val bytes = javaClass.getResourceAsStream("/flac/sine-24-96k.flac")!!.readNBytes(4096)
        val (bits, rate, seconds) = Addons.flacInfo(bytes)!!
        assertEquals(24, bits)
        assertEquals(96_000, rate)
        assertEquals(1.5, seconds, 0.01)
        assertNull(Addons.flacInfo(ByteArray(64)))
    }

    @Test fun onlyTheOfficialServersSearchesAreSigned() {
        assertTrue(AddonPairing.protects("https://addon-opentune.rethinknow.in/monochrome/search?q=a"))
        assertFalse(AddonPairing.protects("https://addon-opentune.rethinknow.in/monochrome/manifest.json"))
        assertFalse(AddonPairing.protects("https://other.example/monochrome/search?q=a"))
        assertEquals(emptyMap<String, String>(), AddonPairing.headersFor("https://other.example/search?q=a"))
    }

    @Test fun songLengthsReadAsSeconds() {
        assertEquals(225.0, Player.seconds("3:45")!!, 0.0)
        assertEquals(3723.0, Player.seconds("1:02:03")!!, 0.0)
        assertNull(Player.seconds("live"))
    }

    // ---- Lossless playback -----------------------------------------------------------

    /** A sound card that plays instantly and keeps everything it was given. */
    private class Capture(private val only16: Boolean = false) : PcmOut {
        val bytes = ByteArrayOutputStream()
        private var frame = 4
        override fun open(format: AudioFormat): AudioFormat {
            val f = if (only16) AudioFormat(format.sampleRate, 16, format.channels, true, false) else format
            frame = f.frameSize
            return f
        }
        @Synchronized override fun write(bytes: ByteArray, off: Int, len: Int): Int { this.bytes.write(bytes, off, len); return len }
        override fun start() {}
        override fun stop() {}
        override fun flush() {}
        override fun drain() {}
        override fun close() {}
        override val framePosition: Long @Synchronized get() = (bytes.size() / frame).toLong()
        override fun setGainDb(db: Float) = true
        @Synchronized fun taken(): ByteArray = bytes.toByteArray()
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/flac/$name")!!.readAllBytes()

    /** The file decoded straight through, to compare with what the engine played. */
    private fun reference(file: ByteArray): Pair<StreamInfo, ByteArray> {
        val all = ByteArrayOutputStream()
        var info: StreamInfo? = null
        FLACDecoder(file.inputStream()).apply {
            addPCMProcessor(object : PCMProcessor {
                override fun processStreamInfo(si: StreamInfo) { info = si }
                override fun processPCM(pcm: ByteData) { all.write(pcm.data, 0, pcm.len) }
            })
            decode()
        }
        return info!! to all.toByteArray()
    }

    /** Arrives a few kilobytes at a time, as over a slow connection. */
    private fun trickle(bytes: ByteArray): InputStream = object : FilterInputStream(bytes.inputStream()) {
        override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(2); return super.read(b, off, minOf(len, 8192)) }
    }

    private fun play(file: ByteArray, startAtMs: Long = 0, out: Capture = Capture(), slow: Boolean = false): Capture {
        val done = CountDownLatch(1)
        var problem: String? = null
        val engine = FlacEngine(
            source = { if (slow) trickle(file) else file.inputStream() }, startAtMs = { startAtMs }, autoplay = true,
            onStarted = {}, onEnd = { done.countDown() }, onError = { problem = it; done.countDown() }, out = out,
        )
        assertTrue("finished", done.await(30, TimeUnit.SECONDS))
        engine.dispose()
        assertNull(problem)
        return out
    }

    @Test fun aLosslessFileReachesTheCardBitForBit() {
        for (name in listOf("sine-24-96k.flac", "noise-16-44k.flac")) {
            val file = resource(name)
            val (_, pcm) = reference(file)
            assertArrayEquals(name, pcm, play(file).taken())
            assertArrayEquals("$name over a slow connection", pcm, play(file, slow = true).taken())
        }
    }

    @Test fun takingOverStartsOnTheExactSample() {
        val file = resource("sine-24-96k.flac")
        val (si, pcm) = reference(file)
        val frame = si.channels * si.bitsPerSample / 8
        val from = 700L * si.sampleRate / 1000 * frame
        assertArrayEquals(pcm.copyOfRange(from.toInt(), pcm.size), play(file, startAtMs = 700).taken())
    }

    @Test fun aSeekLandsOnTheExactSample() {
        val file = resource("noise-16-44k.flac")
        val (si, pcm) = reference(file)
        val out = Capture()
        val done = CountDownLatch(1)
        val engine = FlacEngine(source = { file.inputStream() }, startAtMs = { 0 }, autoplay = false,
            onStarted = {}, onEnd = { done.countDown() }, onError = { done.countDown() }, out = out)
        val deadline = System.currentTimeMillis() + 10_000
        while ((engine.durationMs() == 0L || !engine.arrivedAll) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        engine.seek(1_000)
        engine.play()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        engine.dispose()
        val frame = si.channels * 2
        val from = (1_000L * si.sampleRate / 1000 * frame).toInt()
        assertArrayEquals(pcm.copyOfRange(from, pcm.size), out.taken())
    }

    @Test fun aCardThatTakesOnly16BitGetsDitheredSamples() {
        val file = resource("sine-24-96k.flac")
        val (si, pcm) = reference(file)
        val got = play(file, out = Capture(only16 = true)).taken()
        assertEquals(pcm.size / 3 * 2, got.size)
        // Each 16-bit sample is the 24-bit one within a step of rounding and dither.
        for (i in 0 until si.totalSamples.toInt() * si.channels step 997) {
            val v24 = (pcm[i * 3].toInt() and 0xff) or ((pcm[i * 3 + 1].toInt() and 0xff) shl 8) or (pcm[i * 3 + 2].toInt() shl 16)
            val v16 = (got[i * 2].toInt() and 0xff) or (got[i * 2 + 1].toInt() shl 8)
            assertTrue("sample $i", kotlin.math.abs(v24 / 256.0 - v16) <= 2.0)
        }
    }

    @Test fun theAddonListIsKeptBetweenRuns() {
        val file = Files.createTempDirectory("opentune").resolve("sources.json").toFile()
        Addons.useFile(file)
        Addons.setJioSaavn(false)
        Addons.useFile(file)
        assertFalse(Addons.jioSaavn.value)
        Addons.setJioSaavn(true)
    }
}
