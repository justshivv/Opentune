package com.opentune.data.lossless

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LosslessSourceTest {
    @Test fun ranksVerifiedFlacByPrecisionThenSampleRate() {
        val cd = LosslessSource.Track("https://cdn.example/cd", "Tidal", 16, 44_100)
        val hi = LosslessSource.Track("https://cdn.example/hi", "Qobuz", 24, 96_000)
        val highest = hi.copy(sampleRate = 192_000)
        assertEquals(hi, LosslessSource.better(cd, hi))
        assertEquals(hi, LosslessSource.better(hi, cd))
        assertEquals(highest, LosslessSource.better(hi, highest))
    }

    @Test fun resolvesOnlyLinkedProviderEntities() {
        val json = JSONObject("""{"linksByPlatform":{"tidal":{"entityUniqueId":"TIDAL_SONG::123"},"spotify":{"entityUniqueId":"SPOTIFY_SONG::abcdefghijklmnopqrstuv"}},"entitiesByUniqueId":{"TIDAL_SONG::999":{"id":"999"}}}""")
        assertEquals(mapOf("tidal" to "123", "spotify" to "abcdefghijklmnopqrstuv"), LosslessSource.providerIds(json))
        assertTrue(LosslessSource.providerIds(JSONObject("""{"entitiesByUniqueId":{"TIDAL_SONG::999":{"id":"999"}}}""")).isEmpty())
    }

    @Test fun rejectsMalformedProviderIds() {
        assertTrue(LosslessSource.providerIds(JSONObject("""{"linksByPlatform":{"tidal":{"entityUniqueId":"TIDAL_SONG::../123"},"spotify":{"entityUniqueId":"SPOTIFY_SONG::null"}}}""")).isEmpty())
    }

    @Test fun acceptsSingleUnencryptedBtsManifestWithoutGuessingFromExtension() {
        assertEquals("https://cdn.example/audio", LosslessSource.tidalUrl(manifest("""{"mimeType":"audio/flac","encryptionType":"NONE","urls":["https://cdn.example/audio"]}""")))
    }

    @Test fun rejectsSegmentedAndEncryptedManifests() {
        assertNull(LosslessSource.tidalUrl(manifest("""{"urls":["https://cdn.example/a","https://cdn.example/b"]}""")))
        assertNull(LosslessSource.tidalUrl(manifest("""{"encryptionType":"AES","urls":["https://cdn.example/a"]}""")))
        assertNull(LosslessSource.directUrl(JSONObject("""{"decryption_key":"secret","data":{"url":"https://cdn.example/a.flac"}}""")))
    }

    @Test fun rejectsInsecureOrCredentialBearingUrls() {
        for (url in listOf("http://cdn.example/a.flac", "file:///sdcard/a.flac", "https://user:pass@cdn.example/a", "null")) {
            assertNull(LosslessSource.directUrl(JSONObject().put("url", url)))
        }
    }

    @Test fun readsPrecisionRateAndDurationFromFlacStreaminfo() {
        assertEquals(LosslessSource.FlacInfo(24, 96_000, 180_000), LosslessSource.flacInfo(flac(24, 96_000, 180)))
        assertEquals(LosslessSource.FlacInfo(16, 44_100, 210_000), LosslessSource.flacInfo(flac(16, 44_100, 210)))
    }

    @Test fun rejectsHtmlTruncationMissingDurationAndNonFlac() {
        assertNull(LosslessSource.flacInfo("<html>not audio</html>".toByteArray()))
        assertNull(LosslessSource.flacInfo(flac(16, 44_100, 180).copyOf(25)))
        assertNull(LosslessSource.flacInfo(flac(16, 44_100, 0)))
        assertNull(LosslessSource.flacInfo(flac(8, 44_100, 180)))
        assertNull(LosslessSource.flacInfo(flac(16, 8_000, 180)))
        assertNull(LosslessSource.flacInfo(flac(16, 44_100, 180).apply { this[4] = 1 }))
    }

    @Test fun rejectsDifferentEditAndUnknownDuration() {
        assertTrue(LosslessSource.durationMatches(180_000, 182_000))
        assertFalse(LosslessSource.durationMatches(180_000, 185_000))
        assertFalse(LosslessSource.durationMatches(0, 180_000))
    }

    @Test fun registryFiltersUrlsDeduplicatesAndKeepsFallback() {
        val registry = JSONObject("""{"tidal":{"stream":["http://bad.example","https://good.example/","https://good.example","file:///bad"]}}""")
        assertEquals(listOf("https://good.example", "https://tidal.anandserver.cfd"), LosslessRegistry.endpoints(registry, "tidal"))
    }

    private fun manifest(value: String) = JSONObject().put("data", JSONObject().put("manifest", Base64.getEncoder().encodeToString(value.toByteArray())))

    private fun flac(bits: Int, rate: Int, seconds: Int): ByteArray = ByteArray(42).apply {
        "fLaC".toByteArray().copyInto(this)
        this[4] = 0x80.toByte()
        this[7] = 34
        val packed = (rate.toLong() shl 44) or (1L shl 41) or ((bits - 1).toLong() shl 36) or (rate.toLong() * seconds)
        for (i in 0..7) this[18 + i] = (packed ushr (56 - i * 8)).toByte()
    }
}
