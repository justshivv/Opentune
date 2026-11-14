package com.opentune.data.audiobooks

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AudiobooksTest {
    private val files = JSONArray("""[
        {"name": "pp_04-06_austen_64kb.mp3", "format": "64Kbps MP3", "title": "Chapters 4-6", "track": "2/37", "length": "21:05"},
        {"name": "pp_01-03_austen.mp3", "format": "VBR MP3", "title": "Chapters 1-3", "track": "1/37", "length": "1133.07"},
        {"name": "pp_04-06_austen.mp3", "format": "VBR MP3", "title": "Chapters 4-6", "track": "2/37", "length": "1265.5"},
        {"name": "pp_01-03_austen_64kb.mp3", "format": "64Kbps MP3", "title": "Chapters 1-3", "track": "1/37", "length": "18:53"},
        {"name": "cover.jpg", "format": "JPEG"}
    ]""")

    @Test fun chaptersInOrderInTheBestFormat() {
        val chapters = Audiobooks.chapters("pride_and_prejudice_librivox", files)
        assertEquals(listOf("Chapters 1-3", "Chapters 4-6"), chapters.map { it.title })
        assertEquals("book:pride_and_prejudice_librivox/pp_01-03_austen.mp3", chapters[0].id)
        assertEquals(1_133_070L, chapters[0].durationMs)
        assertTrue(Audiobooks.isChapter(chapters[0].id))
        assertEquals("pride_and_prejudice_librivox", Audiobooks.bookOf(chapters[0].id))
        assertEquals("https://archive.org/download/pride_and_prejudice_librivox/pp_01-03_austen.mp3", Audiobooks.urlOf(chapters[0].id))
    }

    @Test fun authorsReadNaturally() {
        assertEquals("Jane Austen", Audiobooks.cleanAuthor("Austen, Jane (1775-1817)"))
        assertEquals("Mark Twain", Audiobooks.cleanAuthor("Mark Twain"))
        assertEquals(1_265_000L, Audiobooks.parseLength("21:05"))
    }
}
