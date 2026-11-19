package com.opentune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueDragTest {

    @Test
    fun aRowMovesOntoTheRowItWasDroppedOn() {
        // Songs 5 to 8 to come; 8 dragged to the top.
        assertEquals(8 to 5, queueDragMove(listOf(5, 6, 7, 8), from = 3, to = 0, upNext = listOf(5, 6, 7, 8)))
    }

    @Test
    fun songsAddedMeanwhileDontChangeTheMove() {
        // Radio arrived under the finger.
        assertEquals(6 to 7, queueDragMove(listOf(5, 6, 7), from = 1, to = 2, upNext = listOf(5, 6, 7, 8, 9)))
    }

    @Test
    fun theSongEndingMidDragMovesNothingRatherThanTheWrongRow() {
        // 5 started playing while 8 was carried to its place: it isn't to come any more.
        assertNull(queueDragMove(listOf(5, 6, 7, 8), from = 3, to = 0, upNext = listOf(6, 7, 8)))
    }

    @Test
    fun aDropPastTheRowsThereAreMovesNothing() {
        assertNull(queueDragMove(listOf(5, 6), from = 0, to = 4, upNext = listOf(5, 6)))
        assertNull(queueDragMove(emptyList(), from = 0, to = 1, upNext = emptyList()))
    }
}
