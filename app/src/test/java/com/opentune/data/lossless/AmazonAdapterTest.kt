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
    @Test fun registryDoesNotInstallUnreviewedCodeOrVersions() {
        assertNotNull(ExtensionRegistry.canonicalUrl(ExtensionRegistry.MIRROR))
        assertNull(ExtensionRegistry.canonicalUrl("https://evil.example/registry.json"))
        assertNull(ExtensionRegistry.canonicalUrl(ExtensionRegistry.OFFICIAL + "?redirect=evil"))
        val entries = ExtensionRegistry.parse("""{"version":1,"extensions":[{"id":"amazon","display_name":"Amazon Music","version":"2.3.11"},{"id":"tidal-web","version":"1.2.7"}]}""")
        assertTrue(entries.first().supported)
        assertFalse(entries.last().supported)
        assertFalse(ExtensionRegistry.parse("""{"version":1,"extensions":[{"id":"amazon","version":"99.0"}]}""").single().supported)
    }
}
