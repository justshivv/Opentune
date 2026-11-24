package com.opentune.data.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaavnMatchBookTest {
    private val day = 24 * 3600_000L

    @Test
    fun aMatchIsKeptForWeeksAndAMissForAFewDays() {
        var t = 0L
        val book = SaavnMatchBook(now = { t })
        book.matched("a", "SAAVN1")
        book.noMatch("b")
        t += 2 * day
        assertEquals("SAAVN1", book.idFor("a"))
        assertTrue(book.missed("b"))
        assertNull(book.idFor("b"))
        t += 2 * day
        assertFalse(book.missed("b"))
        t += 60 * day
        assertNull(book.idFor("a"))
    }

    @Test
    fun aForgottenMatchIsSearchedForAgainAndWhatWasSavedDoesNotOverwriteNewer() {
        var t = 10L
        val book = SaavnMatchBook(now = { t })
        book.matched("a", "NEW")
        book.load(mapOf("a" to SaavnMatchBook.Entry("OLD", 1), "c" to SaavnMatchBook.Entry("C", 5)))
        assertEquals("NEW", book.idFor("a"))
        assertEquals("C", book.idFor("c"))
        book.forget("a")
        assertNull(book.idFor("a"))
        assertFalse(book.missed("a"))
    }

    @Test
    fun theOldestGoPastTheLimit() {
        var t = 0L
        val book = SaavnMatchBook(now = { t })
        repeat(SaavnMatchBook.MAX + 1) { t++; book.matched("s$it", "x") }
        assertTrue(book.snapshot().size <= SaavnMatchBook.MAX)
        assertNull(book.idFor("s0"))
        assertEquals("x", book.idFor("s${SaavnMatchBook.MAX}"))
    }
}
