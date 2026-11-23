package com.opentune.data.innertube

import android.net.Uri
import com.opentune.data.settings.PlaybackSettings
import com.opentune.playback.cacheKeyOf
import com.opentune.playback.isVideoOnlyUri
import com.opentune.playback.videoHeightOf
import com.opentune.playback.videoOnlyUri
import com.opentune.playback.videoStreamUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VideoQualityTest {
    private val avc = "video/mp4; codecs=\"avc1.640028\""
    private val vp9 = "video/webm; codecs=\"vp9\""
    private val av1 = "video/mp4; codecs=\"av01.0.08M.08\""

    @Test fun rungsAreNamedByTheShortSideLikeYouTube() {
        assertEquals(1080, VideoQuality.rungOf(1920, 1080))
        assertEquals(720, VideoQuality.rungOf(1280, 720))
        // Upright, 4:3 and a wide film all read by the size YouTube gives them.
        assertEquals(1080, VideoQuality.rungOf(1080, 1920))
        assertEquals(1080, VideoQuality.rungOf(1440, 1080))
        assertEquals(1080, VideoQuality.rungOf(1920, 800))
        assertEquals(720, VideoQuality.rungOf(1280, 536))
        // A few pixels short of a rung is still that rung.
        assertEquals(1080, VideoQuality.rungOf(1916, 1072))
        assertEquals(360, VideoQuality.rungOf(0, 360))
        assertEquals(0, VideoQuality.rungOf(0, 0))
    }

    @Test fun listIsAutoThenDecodableSizesTallestFirstOnceEach() {
        val offers = listOf(
            VideoOffer(avc, 640, 360, 400),
            VideoOffer(av1, 3840, 2160, 9_000),
            VideoOffer(avc, 1920, 1080, 4_000),
            VideoOffer(vp9, 1920, 1080, 2_600),
            VideoOffer(vp9, 1280, 720, 1_400),
            VideoOffer(avc, 854, 480, 900),
            VideoOffer(avc, 0, 0, 100),
        )
        // This phone has no AV1 decoder.
        val list = VideoQuality.choices(offers) { mime, _, _ -> "av01" !in mime }
        assertEquals(listOf(VideoQuality.AUTO, 1080, 720, 480, 360), list)
    }

    @Test fun sizeTheDecoderCannotTakeIsLeftOut() {
        val offers = listOf(VideoOffer(vp9, 3840, 2160), VideoOffer(vp9, 2560, 1440), VideoOffer(vp9, 1920, 1080))
        val list = VideoQuality.choices(offers) { _, w, h -> w * h <= 1920 * 1080 }
        assertEquals(listOf(VideoQuality.AUTO, 1080), list)
    }

    @Test fun aPhoneThatSeemsToDecodeNothingIsOfferedEverySize() {
        val offers = listOf(VideoOffer(vp9, 1280, 720), VideoOffer(vp9, 640, 360))
        assertEquals(listOf(VideoQuality.AUTO, 720, 360), VideoQuality.choices(offers) { _, _, _ -> false })
    }

    @Test fun nothingKnownIsJustAuto() {
        assertEquals(listOf(VideoQuality.AUTO), VideoQuality.choices(emptyList()) { _, _, _ -> true })
    }

    @Test fun wifiAndMobileChoicesAreKeptApart() {
        val start = PlaybackSettings()
        // Auto on both by default.
        assertEquals(VideoQuality.AUTO, VideoQuality.chosen(start, metered = false))
        assertEquals(VideoQuality.AUTO, VideoQuality.chosen(start, metered = true))
        val onWifi = VideoQuality.withChoice(start, metered = false, rung = 1080)
        assertEquals(1080, VideoQuality.chosen(onWifi, metered = false))
        assertEquals(VideoQuality.AUTO, VideoQuality.chosen(onWifi, metered = true))
        val onMobile = VideoQuality.withChoice(onWifi, metered = true, rung = 360)
        assertEquals(1080, VideoQuality.chosen(onMobile, metered = false))
        assertEquals(360, VideoQuality.chosen(onMobile, metered = true))
        assertEquals(1080, onMobile.wifiVideoQuality)
        assertEquals(360, onMobile.mobileVideoQuality)
    }

    @Test fun oldSavedSettingsLoadOnAuto() {
        val old = com.opentune.data.settings.AppSettings.json.decodeFromString(PlaybackSettings.serializer(), """{"wifiQuality":"MAX"}""")
        assertEquals(VideoQuality.AUTO, old.wifiVideoQuality)
        assertEquals(VideoQuality.AUTO, old.mobileVideoQuality)
    }

    @Test fun chosenSizePlaysTheTallestAtOrUnderItThenTheNextOver() {
        data class F(val rung: Int, val kbps: Int)
        val items = listOf(F(360, 400), F(1080, 4_000), F(720, 1_400), F(720, 2_200), F(1440, 8_000), F(2160, 12_000))
        val ranked = VideoQuality.rankForCap(items, 720, { it.rung }, { it.kbps })
        assertEquals(listOf(F(720, 2_200), F(720, 1_400), F(360, 400), F(1080, 4_000), F(1440, 8_000), F(2160, 12_000)), ranked)
        // Smaller than anything offered: the smallest over it first.
        assertEquals(F(360, 400), VideoQuality.rankForCap(items, 144, { it.rung }, { it.kbps }).first())
    }

    @Test fun heightCapIsTheLiteralHeightOfAnUprightClip() {
        val upright = listOf(VideoOffer(avc, 720, 1280), VideoOffer(avc, 1080, 1920), VideoOffer(avc, 360, 640))
        assertEquals(1280, VideoQuality.heightCap(upright, 720))
        assertEquals(720, VideoQuality.heightCap(emptyList(), 720))
    }

    @Test fun chosenSizeIsInTheItemAddressAndItsCacheKey() {
        val auto = videoOnlyUri("abc")
        val hd = videoOnlyUri("abc", height = 720)
        assertTrue(isVideoOnlyUri(hd))
        assertEquals(720, videoHeightOf(hd))
        assertEquals(0, videoHeightOf(auto))
        assertEquals(1080, videoHeightOf(videoStreamUri("abc", height = 1080)))
        // A picture half cached at one size is never finished from another.
        assertNotEquals(cacheKeyOf(auto), cacheKeyOf(hd))
        assertEquals("abc:video", cacheKeyOf(auto))
        assertEquals("abc:video:s1:h720", cacheKeyOf(videoOnlyUri("abc", step = 1, height = 720)))
        assertEquals(0, videoHeightOf(Uri.parse("https://example.com/x?vh=720")))
    }
}
