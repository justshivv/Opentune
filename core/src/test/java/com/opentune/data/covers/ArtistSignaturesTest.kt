package com.opentune.data.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reading the artist-signature service's answers, as it sends them. */
// org.json is Android's, so these run where Android's real one is.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArtistSignaturesTest {
    @Test fun aSingleRasterIsTakenAsItIs() {
        val sig = ArtistSignatures.parse(
            """{"artist":{"name":"Real Boss"},"signatures":[{"asset":{"url":"https://raw.githubusercontent.com/itsmeadarsh2008/signature-archive/main/assets/Real%20Boss.webp","format":"webp","width":null,"height":null},
            "license":{"status":"known"},"verification":"unverified"}]}""",
        )
        assertEquals("https://raw.githubusercontent.com/itsmeadarsh2008/signature-archive/main/assets/Real%20Boss.webp", sig!!.imageUrl)
        assertEquals(2.5f, sig.aspect)
    }

    @Test fun aRasterBeatsAnSvgAndAnSvgAloneIsRenderedByWikimedia() {
        val svg = """{"asset":{"url":"https://upload.wikimedia.org/wikipedia/commons/d/d0/The_Weeknd_signature.svg?utm_source=x","format":"svg","width":556,"height":885},"license":{"status":"known"}}"""
        val png = """{"asset":{"url":"https://upload.wikimedia.org/wikipedia/commons/1/19/The_Weeknd_signature.png?utm_source=x","format":"png","width":556,"height":885},"license":{"status":"known"}}"""
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/1/19/The_Weeknd_signature.png?utm_source=x",
            ArtistSignatures.parse("""{"signatures":[$svg,$png]}""")!!.imageUrl,
        )
        val alone = ArtistSignatures.parse("""{"signatures":[$svg]}""")!!
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/d/d0/The_Weeknd_signature.svg/960px-The_Weeknd_signature.svg.png", alone.imageUrl)
        assertEquals(556f / 885f, alone.aspect, 0.001f)
    }

    @Test fun aKnownLicenceComesFirstAndAnSvgElsewhereIsSkipped() {
        val unknown = """{"asset":{"url":"https://a.example/x.png","format":"png"},"license":{"status":"unknown"}}"""
        val known = """{"asset":{"url":"https://b.example/y.webp","format":"webp"},"license":{"status":"known"}}"""
        assertEquals("https://b.example/y.webp", ArtistSignatures.parse("""{"signatures":[$unknown,$known]}""")!!.imageUrl)
        assertNull(ArtistSignatures.parse("""{"signatures":[{"asset":{"url":"https://c.example/z.svg","format":"svg"}}]}"""))
    }

    @Test fun anAmbiguousNameOrNoAnswerIsNone() {
        assertNull(ArtistSignatures.parse("""{"matches":[{"name":"A"},{"name":"B"}]}"""))
        assertNull(ArtistSignatures.parse("not json"))
        assertNull(ArtistSignatures.parse("""{"signatures":[]}"""))
        assertNull(ArtistSignatures.normalize("ftp://x"))
        assertEquals("https://proud-cascade-1654.de.deplexo.com", ArtistSignatures.normalize(" https://proud-cascade-1654.de.deplexo.com/ "))
    }
}
