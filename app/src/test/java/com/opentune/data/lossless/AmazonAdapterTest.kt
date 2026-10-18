package com.opentune.data.lossless

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AmazonAdapterTest {
    private val identity = CatalogIdentity("Wo Noor", "AP Dhillon", null, 190_000)
    private fun item(title: String = "Wo Noor", artist: String = "AP Dhillon", duration: String = "3:10") = JSONObject()
        .put("interface", "Web.DescriptiveRowItemElement").put("primaryText", title).put("secondaryText1", artist)
        .put("secondaryText3", duration).put("primaryTextLink", JSONObject().put("deeplink", "/albums/B012345679?trackAsin=B012345678"))
    @Test fun selectsExactTrackInsteadOfAlbumRemixOrOtherArtist() {
        assertEquals("B012345678", AmazonCatalog.match(item(), identity))
        assertEquals("B012345678", AmazonCatalog.match(item().put("primaryText",
            JSONObject().put("observer", JSONObject().put("defaultValue", "Wo Noor"))), identity))
        assertNull(AmazonCatalog.match(item(title = "Wo Noor (Remix)"), identity))
        assertNull(AmazonCatalog.match(item(artist = "Someone Else"), identity))
        assertNull(AmazonCatalog.match(item(duration = "0:30"), identity))
        assertNull(AmazonCatalog.asin("/albums/B012345678"))
        assertEquals("B012345678", AmazonCatalog.asin("/tracks/B012345678"))
    }
    @Test fun onlyAcceptsFlacAndKnownAudioDeliveryHosts() {
        val audio = JSONObject().put("url", "https://a.cloudfront.net/audio.m4a").put("codec", "flac").put("key", "0123456789abcdef0123456789abcdef")
        val root = JSONObject().put("audio", audio)
        assertNotNull(AmazonAudio.media(root.toString()))
        assertNotNull(AmazonAudio.media("[$root]"))
        assertNull(AmazonAudio.media(root.put("audio", audio.put("codec", "opus")).toString()))
        audio.put("codec", "flac").put("key", "malformed")
        assertNull(AmazonAudio.media(root.toString()))
        audio.put("key", "").put("url", "http://a.cloudfront.net/audio")
        assertNull(AmazonAudio.media(root.toString()))
        audio.put("url", "https://127.0.0.1/audio")
        assertNull(AmazonAudio.media(root.toString()))
        audio.put("url", "https://cloudfront.net.evil.example/audio")
        assertNull(AmazonAudio.media(root.toString()))
    }
    @Test fun preparesWithoutReencodingOrInterpolatingShellCommands() {
        val args = AmazonAudio.conversionArguments("/private/a file", "/private/result.flac", "0123456789abcdef0123456789abcdef").toList()
        assertEquals("copy", args[args.indexOf("-c:a") + 1])
        assertEquals("/private/a file", args[args.indexOf("-i") + 1])
        assertFalse(args.contains("-ar")); assertFalse(args.contains("-sample_fmt"))
        assertFalse(AmazonAudio.conversionArguments("in", "out", "").contains("-decryption_key"))
    }
    @Test fun pendingOperationsReuseTicketsWithoutRetryingTerminalFailures() {
        val pending = LosslessHttp.HttpFailure(503, """{"retryable":true,"retry_mode":"poll_existing","retry_after_seconds":2}""")
        assertEquals(true to 2000L, AmazonAudio.retry(pending, 0))
        assertNull(AmazonAudio.retry(pending, 2))
        assertNull(AmazonAudio.retry(LosslessHttp.HttpFailure(503, pending.responseBody, 30), 0))
        assertEquals(false to 1000L, AmazonAudio.retry(LosslessHttp.HttpFailure(503, """{"retryable":true,"retry_mode":"new_ticket"}"""), 0))
        assertNull(AmazonAudio.retry(LosslessHttp.HttpFailure(401, pending.responseBody), 0))
        assertNull(AmazonAudio.retry(LosslessHttp.HttpFailure(503, """{"retryable":false,"retry_mode":"new_ticket"}"""), 0))
    }
    @Test fun nativeSearchKeepsProviderIdsAndRejectsForeignLinks() {
        val tracks = AmazonCatalog.tracks(JSONObject().put("rows", org.json.JSONArray().put(item()).put(item())))
        assertEquals(1, tracks.size)
        assertEquals("B012345678", tracks.single().id)
        assertEquals(190_000L, tracks.single().durationMs)
        assertNull(AmazonCatalog.asin("https://evil.example/tracks/B012345678"))
    }
    @Test fun fullFileValidationDecodesAndRejectsErrors() {
        val args = AmazonAudio.verificationArguments("/private/audio.flac").toList()
        assertTrue(args.contains("-xerror"))
        assertEquals("explode", args[args.indexOf("-err_detect") + 1])
        assertEquals("null", args[args.indexOf("-f") + 1])
        assertFalse(args.contains("-c:a"))
    }
}
