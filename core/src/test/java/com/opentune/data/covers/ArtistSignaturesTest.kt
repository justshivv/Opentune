package com.opentune.data.covers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reading the artist-signature service's answers, as it sends them. */
// org.json is Android's, so these run where Android's real one is.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArtistSignaturesTest {
    private fun wiki(file: String, format: String, licence: String = "PD-signature", w: Int = 500, h: Int = 200) =
        """{"asset":{"url":"https://upload.wikimedia.org/wikipedia/commons/a/ab/$file?utm_source=x","format":"$format","width":$w,"height":$h},"license":{"name":"$licence","status":"known"}}"""

    @Test fun aSingleRasterIsTakenAsItIs() {
        val sig = ArtistSignatures.parse(
            """{"artist":{"name":"Real Boss"},"signatures":[{"asset":{"url":"https://raw.githubusercontent.com/itsmeadarsh2008/signature-archive/main/assets/Real%20Boss.webp","format":"webp","width":null,"height":null},
            "license":{"status":"known"},"verification":"unverified"}]}""",
            "Real Boss",
        )
        assertEquals("https://raw.githubusercontent.com/itsmeadarsh2008/signature-archive/main/assets/Real%20Boss.webp", sig!!.imageUrl)
        assertEquals(2.5f, sig.aspect)
    }

    @Test fun anSvgOnWikimediaIsRenderedAndBeatsAScan() {
        val svg = wiki("The_Weeknd_signature.svg", "svg", w = 556, h = 885)
        val jpg = wiki("The_Weeknd_signature.jpg", "jpeg")
        val sig = ArtistSignatures.parse("""{"signatures":[$jpg,$svg]}""", "The Weeknd")!!
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/The_Weeknd_signature.svg/960px-The_Weeknd_signature.svg.png", sig.imageUrl)
        assertEquals(556f / 885f, sig.aspect, 0.001f)
        // The vector itself, for a loader that can draw it.
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/The_Weeknd_signature.svg?utm_source=x", sig.svgUrl)
    }

    @Test fun onlyAFileNamedForThisArtistIsTaken() {
        // The service's loose matches: another Khalid, other Drakes, a photo of a charter signed at the SIA.
        val khalid = """{"signatures":[${wiki("Khalid_al-Mihdhar_signature.jpg", "jpeg")},${wiki("A_F_M_Khalid_Hossain's_Signature.svg", "svg")},${wiki("Khalid_sig.svg", "svg")}]}"""
        assertTrue(ArtistSignatures.parse(khalid, "Khalid")!!.imageUrl.contains("/Khalid_sig.svg/"))
        val drake = """{"signatures":[${wiki("Nick_Drake_sig.svg", "svg")},${wiki("Francis_Drake_Signature.svg", "svg")},${wiki("Drake_sig.svg", "svg")},${wiki("Drake_Bell_signature.png", "png")}]}"""
        assertTrue(ArtistSignatures.parse(drake, "Drake")!!.imageUrl.contains("/Drake_sig.svg/"))
        val sia = """{"signatures":[${wiki("1997_Signature_de_la_charte_avec_la_FNSEA_au_SIA-132-cliche_Jean_Weber.jpg", "jpeg", "CC BY")},${wiki("Sia_Furler_signature.svg", "svg")}]}"""
        assertTrue(ArtistSignatures.parse(sia, "Sia")!!.imageUrl.contains("/Sia_Furler_signature.svg/"))
        assertNull(ArtistSignatures.parse("""{"signatures":[${wiki("1997_Signature_de_la_charte_avec_la_FNSEA_au_SIA-132-cliche_Jean_Weber.jpg", "jpeg", "CC BY")}]}""", "Sia"))
        assertNull(ArtistSignatures.parse("""{"signatures":[${wiki("Nick_Drake_sig.svg", "svg")}]}""", "Drake"))
    }

    @Test fun namesAreReadHoweverTheFileWritesThem() {
        assertEquals(2, ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Kanyewestsignature.png"), listOf("kanye", "west")))
        assertEquals(2, ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Lady_Gaga%27s_signature.svg"), listOf("lady", "gaga")))
        assertEquals(2, ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Signature_of_Justin_Bieber.png"), listOf("justin", "bieber")))
        assertEquals(2, ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Beyonc%C3%A9_autograph.svg"), listOf("beyonce")))
        assertEquals(2, ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Ladygaga_sig.svg"), listOf("lady", "gaga")))
        assertEquals(1, ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Sia_Furler_signature.svg"), listOf("sia")))
        assertNull(ArtistSignatures.nameMatch(ArtistSignatures.fileWords("https://x.org/Taylorswiftlogo.png"), listOf("taylor", "swift")))
    }

    @Test fun aKnownLicenceComesFirstAndAnSvgElsewhereIsSkipped() {
        val unknown = """{"asset":{"url":"https://a.example/Real_Boss.png","format":"png"},"license":{"status":"unknown"}}"""
        val known = """{"asset":{"url":"https://b.example/Real_Boss_sig.webp","format":"webp"},"license":{"status":"known"}}"""
        assertEquals("https://b.example/Real_Boss_sig.webp", ArtistSignatures.parse("""{"signatures":[$unknown,$known]}""", "Real Boss")!!.imageUrl)
        assertNull(ArtistSignatures.parse("""{"signatures":[{"asset":{"url":"https://c.example/Real_Boss.svg","format":"svg"}}]}""", "Real Boss"))
    }

    @Test fun aScanLosesItsGreyGrainyPaper() {
        // Grey paper with grain and a dark mark in one corner, and a dark stroke across the middle.
        val w = 60; val h = 20
        val px = IntArray(w * h) { i -> val g = 120 + (i * 7919 % 21) - 10; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        px[0] = 0xFF000000.toInt()
        for (x in 5 until 55) px[10 * w + x] = 0xFF282828.toInt()
        val ink = ArtistSignatures.whiteInk(px, w, h)!!
        assertTrue((ink[10 * w + 30] ushr 24) > 230)
        val paper = (0 until w * h).filter { it / w != 10 && it != 0 }.maxOf { ink[it] ushr 24 }
        assertTrue("paper left at alpha $paper", paper < 8)
        assertEquals(0x00FFFFFF, ink[10 * w + 30] and 0x00FFFFFF)
    }

    @Test fun aPhotographIsNotTakenForInk() {
        // Light paper round the edge, a big dark subject filling the middle: far more "ink" than writing has.
        val w = 40; val h = 40
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val g = if (x in 6 until 34 && y in 6 until 34) 40 else 220
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        assertNull(ArtistSignatures.whiteInk(px, w, h))
    }

    @Test fun anAmbiguousNameOrNoAnswerIsNone() {
        assertNull(ArtistSignatures.parse("""{"matches":[{"name":"A"},{"name":"B"}]}""", "A"))
        assertNull(ArtistSignatures.parse("not json", "A"))
        assertNull(ArtistSignatures.parse("""{"signatures":[]}""", "A"))
        assertNull(ArtistSignatures.normalize("ftp://x"))
        assertEquals("https://proud-cascade-1654.de.deplexo.com", ArtistSignatures.normalize(" https://proud-cascade-1654.de.deplexo.com/ "))
    }
}
