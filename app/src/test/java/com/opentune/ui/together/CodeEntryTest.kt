package com.opentune.ui.together

import com.opentune.data.together.RoomCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEntryTest {
    private val code = RoomCode.parse("K7QX-M2PA-9DTE")!!

    @Test fun typingUppercasesAndDropsSeparatorsQuietly() {
        assertEquals(CodeEntry.Edit("K"), CodeEntry.accept("", "k"))
        assertEquals(CodeEntry.Edit("K7QXM"), CodeEntry.accept("K7QX", "K7QX-m"))
        assertEquals(CodeEntry.Edit("K7QXM2"), CodeEntry.accept("K7QXM", "K7QXM 2"))
    }

    @Test fun charactersTheCodeNeverUsesAreLeftOutAndFlagged() {
        listOf("O", "I", "0", "1", "!", "é").forEach { c ->
            val edit = CodeEntry.accept("K7", "K7$c")
            assertEquals("for $c", "K7", edit.raw)
            assertTrue("for $c", edit.rejected)
        }
        // Lowercase o and i are the same letters.
        assertTrue(CodeEntry.accept("", "o").rejected)
    }

    @Test fun stopsAtTwelve() {
        val edit = CodeEntry.accept("K7QXM2PA9DTE", "K7QXM2PA9DTEZ")
        assertEquals("K7QXM2PA9DTE", edit.raw)
        assertFalse(edit.rejected)
    }

    @Test fun deletingTakesOneCharacterOff() {
        assertEquals(CodeEntry.Edit("K7Q"), CodeEntry.accept("K7QX", "K7Q"))
        assertEquals(CodeEntry.Edit(""), CodeEntry.accept("K", ""))
    }

    @Test fun pastingACodeOrALinkFillsTheBox() {
        assertEquals(code.raw, CodeEntry.accept("", "k7qx m2pa 9dte").raw)
        assertEquals(code.raw, CodeEntry.accept("", code.link).raw)
        assertEquals(code.raw, CodeEntry.accept("", code.appLink).raw)
        // Pasted after a half-typed code, and inside the message the share button sends.
        assertEquals(code.raw, CodeEntry.accept("AB", "AB" + code.link).raw)
        assertEquals(code.raw, CodeEntry.accept("", "Listen with me on WEAV. Room code: ${code.pretty}\n${code.link}").raw)
    }

    @Test fun anotherLinkIsTurnedAwayWhole() {
        val edit = CodeEntry.accept("K7", "K7https://example.com/watch?v=abc")
        assertEquals("K7", edit.raw)
        assertTrue(edit.rejected)
    }

    @Test fun cellsDashesAndTheActiveCell() {
        assertEquals('K', CodeEntry.cell("K7", 0))
        assertNull(CodeEntry.cell("K7", 2))
        assertEquals(2, CodeEntry.active("K7"))
        assertEquals(0, CodeEntry.active(""))
        assertEquals(11, CodeEntry.active(code.raw))
        assertEquals(listOf(4, 8), (0 until RoomCode.LENGTH).filter(CodeEntry::dashBefore))
    }

    @Test fun prettyAndWhole() {
        assertEquals("K7QX-M2", CodeEntry.pretty("K7QXM2"))
        assertEquals("K7QX-M2PA-9DTE", CodeEntry.pretty(code.raw))
        assertEquals("", CodeEntry.pretty(""))
        assertNull(CodeEntry.code("K7QXM2PA9DT"))
        assertEquals(code, CodeEntry.code(code.raw))
    }
}
