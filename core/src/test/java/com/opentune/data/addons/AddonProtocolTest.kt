package com.opentune.data.addons

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddonProtocolTest {
    private val manifest = """{"id":"example","name":"Example","resources":["search","stream"],"allowDownloads":0,"checkValidLossless":"1","settings":[{"key":"quality","default":"high","options":[{"value":"lossless"},{"value":"high"}]},{"key":"prefer","default":"studio"}]}"""
    @Test fun normalizesManifestPreservingPrivatePathAndQuery() {
        val base = AddonProtocol.normalize(" https://addon.example/private-test-token/manifest.json?region=in ")
        assertEquals("https://addon.example/private-test-token/?region=in", base)
        val endpoint = AddonProtocol.endpoint(base, listOf("stream", "a/b?x"), mapOf("quality" to "lossless"))
        assertTrue(endpoint.contains("/private-test-token/stream/a%2Fb%3Fx"))
        assertTrue(endpoint.contains("region=in"))
        assertThrows(IllegalArgumentException::class.java) { AddonProtocol.normalize("http://addon.example") }
        assertThrows(IllegalArgumentException::class.java) { AddonProtocol.normalize("https://user:password@addon.example") }
    }
    @Test fun honorsSwitchesDefaultsAndQualityNegotiation() {
        val m = AddonProtocol.manifest(manifest)
        assertFalse(m.downloads); assertTrue(m.outputRequired)
        assertEquals(mapOf("quality" to "lossless", "prefer" to "studio"), m.parameters("LOSSLESS"))
        assertThrows(IllegalArgumentException::class.java) { AddonProtocol.manifest("""{"id":"lyrics","resources":["lyrics"]}""") }
        assertThrows(IllegalArgumentException::class.java) { AddonProtocol.manifest("{}") }
    }
    @Test fun acceptsDirectFlacAndExplicitManifestTransportWithoutUrlSuffix() {
        val stream = AddonProtocol.stream("""{"url":"https://cdn.example/audio","codec":"flac","manifest":"hls","sampleRate":96,"bitDepth":24,"bitrate":2800000,"encrypted":false,"error":null}""")
        assertEquals("hls", stream.transport); assertEquals(96000, stream.sampleRate); assertEquals(24, stream.bits); assertEquals(2800, stream.kbps)
        assertTrue(stream.lossless)
        assertThrows(IllegalArgumentException::class.java) { AddonProtocol.stream("""{"url":"https://cdn.example/a","codec":"flac","encrypted":"widevine"}""") }
        assertFalse(AddonProtocol.stream("""{"url":"https://cdn.example/a","codec":"aac","quality":"Lossless"}""").lossless)
    }
    @Test fun searchUsesSecondsAndDoesNotConvertWrongEnvelopeToEmptyResults() {
        val result = AddonProtocol.search("""{"tracks":[{"id":"track1","title":"Wo Noor","artist":"AP Dhillon","duration":199.4}]}""")
        assertEquals(199400L, result.single().durationMs)
        assertThrows(IllegalStateException::class.java) { AddonProtocol.search("""{"results":[]}""") }
    }
    @Test fun acceptsQobuzTvResponseWithoutExplicitCodec() {
        // Captured response shape; the real signed CDN query is deliberately omitted.
        val stream = AddonProtocol.stream("""{"url":"https://cdn.example/file?expires=123","manifest":"none","encrypted":false,"quality":"LOSSLESS"}""")
        assertEquals("flac", stream.codec)
        assertNull(stream.transport)
        assertNull(stream.bits); assertNull(stream.sampleRate)
        val m = AddonProtocol.manifest("""{"id":"com.bitchord.qobuz","allowDownloads":0,"settings":[{"key":"quality","default":"lossless","options":[{"value":"lossless"},{"value":"high"},{"value":"low"}]}]}""")
        assertEquals("lossless", m.parameters("HI_RES_LOSSLESS")["quality"])
        assertFalse(m.downloads)
    }
    @Test fun concreteCodecEvidenceOverridesLosslessLabels() {
        for (field in listOf("codec", "fileCodec", "format", "mimeType")) {
            val value = if (field == "mimeType") "audio/aac" else "aac"
            assertFalse(AddonProtocol.stream("""{"url":"https://cdn.example/file","$field":"$value","quality":"LOSSLESS"}""").lossless)
        }
        assertEquals("mp3", AddonProtocol.stream("""{"url":"https://cdn.example/song.mp3?format=flac","quality":"LOSSLESS"}""").codec)
        for (field in listOf("container", "mimeType")) {
            val value = if (field == "mimeType") "audio/x-flac; charset=binary" else "flac"
            assertEquals("flac", AddonProtocol.stream("""{"url":"https://cdn.example/file","$field":"$value"}""").codec)
        }
        for (quality in listOf("HI_RES_LOSSLESS", "Hi-Res Lossless", "Lossless · 24-bit / 96 kHz")) {
            assertEquals("flac", AddonProtocol.stream("""{"url":"https://cdn.example/file","quality":"$quality"}""").codec)
        }
        for (quality in listOf("HIGH", "NOT_LOSSLESS", "Losslessish")) {
            assertThrows(AddonStreamException::class.java) { AddonProtocol.stream("""{"url":"https://cdn.example/file","quality":"$quality"}""") }
        }
        assertThrows(AddonStreamException::class.java) { AddonProtocol.stream("""{"url":"https://cdn.example/file","codec":"unsupported","quality":"LOSSLESS"}""") }
    }
    @Test fun concurrentCallsAreSharedAndMalformedResultsAreNotCached() = runBlocking {
        var calls = 0; var clock = 0L
        val client = AddonClient("https://addon.example/", { calls++; manifest }, { clock })
        (1..5).map { async { client.manifest() } }.awaitAll()
        assertEquals(1, calls)
        clock = 600001; client.manifest(); assertEquals(2, calls)
        var attempts = 0
        val invalid = AddonClient("https://addon.example/", { if (++attempts == 1) "{}" else manifest })
        try { invalid.manifest(); fail() } catch (_: IllegalArgumentException) { }
        invalid.manifest(); assertEquals(2, attempts)
    }
    @Test fun aRateLimitIsNotHeldAgainstEverySongAndAFreshStreamIsAskedForAgain() = runBlocking {
        var calls = 0
        val stream = """{"url":"https://cdn.example/a.flac","codec":"flac"}"""
        val client = AddonClient("https://addon.example/", { url -> calls++; if (calls == 1) throw AddonHttp.Failure(429, 10) else if ("stream" in url) stream else manifest })
        try { client.manifest() } catch (_: AddonHttp.Failure) { }
        // The client no longer refuses everything after one 429; that is decided per song.
        client.manifest(); assertEquals(2, calls)
        val parsed = AddonProtocol.manifest(manifest)
        client.stream("t1", parsed, "LOSSLESS"); client.stream("t1", parsed, "LOSSLESS")
        assertEquals(3, calls)
        client.stream("t1", parsed, "LOSSLESS", fresh = true)
        assertEquals(4, calls)
    }
}
