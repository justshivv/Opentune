package com.opentune.desktop

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking songs in a list as the desktop does: Ctrl+click, Shift+click, Select all, and the queue's moves. */
class SelectionTest {
    private fun songs(n: Int, tag: String = "s") = (0 until n).map { Song("$tag${it.toString().padStart(10, '0')}", "Song $it", "Artist", null) }
    private fun SongSelection.titles() = inOrder().map { it.title.removePrefix("Song ") }

    @Test fun ctrlClickTogglesOneRowAndAPlainClickPlaysUntilSomethingIsPicked() {
        val list = songs(6)
        val s = SongSelection()
        s.attach(list)
        assertFalse("a plain click plays", s.click(list, 2, ctrl = false, shift = false))
        assertFalse(s.active)
        assertTrue(s.click(list, 2, ctrl = true, shift = false))
        assertTrue(s.click(list, 4, ctrl = true, shift = false))
        assertEquals(listOf("2", "4"), s.titles())
        // Once picking, a plain click picks or lets go too.
        assertTrue(s.click(list, 2, ctrl = false, shift = false))
        assertEquals(listOf("4"), s.titles())
        s.toggle(list, 4)
        assertFalse(s.active)
    }

    @Test fun shiftClickPicksTheRunFromTheLastClickEitherWay() {
        val list = songs(10)
        val s = SongSelection()
        s.attach(list)
        s.toggle(list, 3)
        s.click(list, 6, ctrl = false, shift = true)
        assertEquals(listOf("3", "4", "5", "6"), s.titles())
        // Upwards from the same anchor adds the run above it, keeping what's picked.
        s.range(list, 1)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), s.titles())
        // With no anchor, Shift picks just the row and makes it the anchor.
        s.clear()
        s.range(list, 8)
        assertEquals(listOf("8"), s.titles())
        s.range(list, 9)
        assertEquals(listOf("8", "9"), s.titles())
    }

    @Test fun selectAllTakesTheListLastClickedInAndKeepsItsOrder() {
        val first = songs(3, "a")
        val second = songs(4, "b")
        val s = SongSelection()
        s.attach(first)
        s.attach(second)
        // The last list to show, until a click says otherwise.
        assertEquals(second, s.list)
        s.toggle(first, 1)
        assertEquals(first, s.list)
        assertFalse(s.all)
        s.selectAll()
        assertTrue(s.all)
        assertEquals(first.map { it.videoId }, s.inOrder().map { it.videoId })
        // A list that goes out of sight is no longer the one gone by.
        s.detach(first)
        assertEquals(second, s.list)
        s.clear()
        assertFalse(s.active)
    }

    @Test fun aPickStaysPickedWhenTheListIsNarrowed() {
        val list = songs(5)
        val s = SongSelection()
        s.attach(list)
        s.toggle(list, 1)
        s.toggle(list, 3)
        val narrowed = list.filterIndexed { i, _ -> i != 1 }
        s.attach(narrowed)
        s.detach(list)
        s.toggle(narrowed, 0)
        // In the narrowed list's order, then the one picked before that it no longer shows.
        assertEquals(listOf("0", "3", "1"), s.titles())
        assertEquals(listOf(0, 2), s.indicesIn(narrowed))
    }

    @Test fun rowsOfOneListCountedSoItGoesOnlyWithTheLast() {
        val list = songs(3)
        val s = SongSelection()
        repeat(3) { s.attach(list) }
        s.detach(list)
        s.detach(list)
        assertEquals(list, s.list)
        s.detach(list)
        assertTrue(s.list.isEmpty())
    }

    @Test fun theQueuesPickedSongsMoveAroundTheSongPlaying() {
        // Playing the song at 2; 0, 4 and 5 picked (and 2 itself, which never moves).
        assertEquals(listOf(1, 2, 0, 4, 5, 3, 6), QueueEdit.playNext(7, 2, setOf(0, 2, 4, 5)))
        assertEquals(listOf(1, 2, 3, 6, 0, 4, 5), QueueEdit.toEnd(7, 2, setOf(0, 2, 4, 5)))
        assertEquals((0 until 4).toList(), QueueEdit.playNext(4, 1, emptySet()))
    }

    @Test fun anOrderIsPutInPlaceByMovesThatKeepTheSongPlaying() {
        val list = songs(7, "q")
        Player.cue(list, 2)
        QueueEdit.apply(QueueEdit.playNext(7, 2, setOf(0, 4, 5)))
        assertEquals(listOf("1", "2", "0", "4", "5", "3", "6"), Player.queue.value.map { it.title.removePrefix("Song ") })
        assertEquals("2", Player.queue.value[Player.index.value].title.removePrefix("Song "))
        QueueEdit.remove(listOf(0, 1, 2))
        assertEquals(listOf("2", "4", "5", "3", "6"), Player.queue.value.map { it.title.removePrefix("Song ") })
        assertEquals(0, Player.index.value)
    }

    @Test fun theKeyboardReachesThePagesSelection() {
        val list = songs(4, "k")
        PageSelection.attach(list)
        try {
            assertTrue(Selections.selectAll())
            assertEquals(4, PageSelection.count)
            assertTrue("Esc lets go", Selections.clearAll())
            assertFalse(PageSelection.active)
            assertFalse("nothing left to let go", Selections.clearAll())
        } finally {
            PageSelection.detach(list)
        }
    }
}
