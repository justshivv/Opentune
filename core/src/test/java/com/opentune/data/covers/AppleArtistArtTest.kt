package com.opentune.data.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppleArtistArtTest {
    private fun page(header: String) =
        "<html><script type=\"application/json\" id=\"serialized-server-data\">[{\"data\":{\"sections\":[{\"items\":[$header]}]}}]</script></html>"

    @Test fun readsTheHeroAndTheClip() {
        val html = page(
            """{"artistLogo":null,"artwork":{"dictionary":{"url":"https://is1.mzstatic.com/a/{w}x{h}{c}.{f}","width":4000,"height":2000,"bgColor":"1a2b3c"}},""" +
                """"videoArtwork":{"dictionary":{"motionArtistSquare1x1":{"video":"https://mvod.itunes.apple.com/clip.m3u8","previewFrame":{"bgColor":"445566"}}}}}""",
        )
        val art = AppleArtistArtRepository.parse(html)!!
        assertEquals("https://is1.mzstatic.com/a/1200x600bb.jpg", art.heroUrl)
        assertEquals(2f, art.heroAspect)
        assertEquals("https://mvod.itunes.apple.com/clip.m3u8", art.videoUrl)
        // The clip's own colour, since it's what's on screen.
        assertEquals(0xFF445566.toInt(), art.background)
    }

    @Test fun aPhotoWithoutAClip() {
        val html = page("""{"artistLogo":null,"artwork":{"dictionary":{"url":"https://x/{w}x{h}{c}.{f}","width":100,"height":100,"bgColor":"000000"}}}""")
        val art = AppleArtistArtRepository.parse(html)!!
        assertNull(art.videoUrl)
        assertEquals(0xFF000000.toInt(), art.background)
    }

    @Test fun aPageWithoutAHeaderHasNoArt() {
        assertNull(AppleArtistArtRepository.parse("<html>nothing here</html>"))
        assertNull(AppleArtistArtRepository.parse(page("""{"title":"no header"}""")))
    }

    @Test fun namesMatchWhateverTheirCaseAndAccents() {
        assertEquals(AppleArtistArtRepository.normalized("Beyoncé"), AppleArtistArtRepository.normalized("BEYONCE"))
    }
}
