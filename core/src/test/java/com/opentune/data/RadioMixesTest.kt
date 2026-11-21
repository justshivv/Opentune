package com.opentune.data

import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMixesTest {
    private fun mix(title: String, id: String = "VLRDTMAK_" + title.replace(" ", "_")) = ShelfItem(title, "Some artists", null, null, id)

    @Test fun theBigMixesTheNumberedOnesAndEachMoodGoTheirOwnWay() {
        val page = RadioMixes.arrange(listOf(
            HomeShelf("Mixed for you", listOf(
                mix("My Mix 2"), mix("Replay Mix"), mix("My Supermix"), mix("My Mix 1"), mix("Discover Mix"),
                mix("New Release Mix"), mix("Archive Mix"),
            )),
            HomeShelf("Moods", listOf(
                mix("Chill Mix 2"), mix("Chill Supermix"), mix("Chill Mix 1"),
                mix("Feel Good Supermix"), mix("Feel Good Mix 1"),
            )),
            HomeShelf("Something else", listOf(mix("Hits of the 90s"), mix("My Supermix"))),
        ))
        assertEquals(RadioMixes.TILES, page.tiles.map { it.title })
        assertEquals(listOf("My Mix 1", "My Mix 2"), page.yours.map { it.title })
        assertEquals(listOf("Chill", "Feel Good"), page.moods.map { it.title })
        assertEquals(listOf("Chill Supermix", "Chill Mix 1", "Chill Mix 2"), page.moods[0].items.map { it.title })
        // What isn't known keeps its heading; a card seen before isn't shown twice.
        assertEquals(listOf(RadioMixes.Row("Something else", listOf(mix("Hits of the 90s")))), page.others)
    }

    @Test fun cardsInAnotherLanguageStayUnderTheirHeading() {
        val page = RadioMixes.arrange(listOf(HomeShelf("आपके लिए मिक्स", listOf(mix("मेरा सुपरमिक्स"), mix("डिस्कवर मिक्स")))))
        assertTrue(page.tiles.isEmpty() && page.yours.isEmpty() && page.moods.isEmpty())
        assertEquals(2, page.others.single().items.size)
        assertTrue(RadioMixes.arrange(emptyList()).isEmpty)
    }
}
