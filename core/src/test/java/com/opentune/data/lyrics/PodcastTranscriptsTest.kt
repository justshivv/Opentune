package com.opentune.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A podcast episode's transcript, from its captions: real ones, saved from YouTube. */
class PodcastTranscriptsTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource("transcripts/$name")!!.readText()
    private fun json(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun lang(t: JsonObject?) = (t?.get("languageCode") as? JsonPrimitive)?.contentOrNull
    private fun kind(t: JsonObject?) = (t?.get("kind") as? JsonPrimitive)?.contentOrNull

    @Test fun automaticCaptionsBecomeSentencesTimedWordByWord() {
        val lines = PodcastTranscripts.lines(text("auto-cs.xml"))
        val first = lines.first()
        assertEquals("Dobrý den, přátelé.", first.text)
        assertEquals(359L, first.startMs)
        assertTrue(first.wordSynced)
        assertEquals(listOf("Dobrý ", "den, ", "přátelé."), first.words.map { it.text })
        // Each word at its own time: the caption's start plus the word's offset.
        assertEquals(359L + 281, first.words[1].startMs)
        assertEquals("ends where the next word starts", first.words[2].startMs, first.words[1].endMs)
        assertEquals("Tady je podcast Kecia politika 292.", lines[1].text)
        // The empty captions between the shown ones leave no blank lines, and lines don't overlap.
        assertTrue(lines.all { it.text.isNotBlank() && it.text.length <= 90 })
        lines.zipWithNext().forEach { (a, b) -> assertTrue("${a.text} / ${b.text}", a.endMs <= b.startMs) }
    }

    @Test fun handMadeCaptionsJoinIntoSentences() {
        val xml = """<timedtext format="3"><body>
            <p t="140" d="3620">- The following is a conversation with
Preston Stewart</p>
            <p t="4180" d="4000">about the war &amp; its drones.</p>
            <p t="8380" d="2000">[Music]</p>
            <p t="12000" d="3000">Here we go.</p>
            </body></timedtext>"""
        val lines = PodcastTranscripts.lines(xml)
        assertEquals(listOf("- The following is a conversation with Preston Stewart about the war & its drones.", "Here we go."), lines.map { it.text })
        assertFalse(lines.first().wordSynced)
        assertEquals(140L, lines.first().startMs)
        assertEquals("the words are spread over each caption's own time", 4180L, lines.first().words.first { it.text.startsWith("about") }.startMs)
    }

    @Test fun thePodcastsOwnLanguageIsChosenNotTheFirstListed() {
        // Arabic, Dutch, English and Polish automatic tracks; the episode is in English.
        val t = PodcastTranscripts.pick(json(text("renderer-several-auto.json")))
        assertEquals("en", lang(t))
    }

    @Test fun handMadeCaptionsBeatAutomaticOnes() {
        val r = json("""{"captionTracks":[{"languageCode":"en","kind":"asr","baseUrl":"a"},{"languageCode":"en-US","baseUrl":"m"},{"languageCode":"de","baseUrl":"d"}],
            "audioTracks":[{"captionTrackIndices":[0,1,2]}],"defaultAudioTrackIndex":0}""")
        val t = PodcastTranscripts.pick(r)
        assertEquals("en-US", lang(t))
        assertNull(kind(t))
    }

    @Test fun automaticCaptionsAloneAreUsedAndNoneMeansNoTranscript() {
        assertEquals("asr", kind(PodcastTranscripts.pick(json("""{"captionTracks":[{"languageCode":"cs","kind":"asr","baseUrl":"a"}]}"""))))
        assertNull(PodcastTranscripts.pick(json("""{"captionTracks":[]}""")))
    }
}
