package com.opentune.data.innertube

import org.junit.Assert.assertEquals
import org.junit.Test

class CardCreditTest {
    @Test fun aCardsTypeWordIsNeverTheArtist() {
        assertEquals("Karan Aujla", InnertubeParser.artistFromSubtitle("Song • Karan Aujla"))
        assertEquals("Karan Aujla", InnertubeParser.artistFromSubtitle("Song • Karan Aujla • IYKYK"))
        assertEquals("Anuv Jain", InnertubeParser.artistFromSubtitle("Anuv Jain • 2.1M plays"))
        // Only the kind of item, no credit: no artist rather than one called "Song".
        assertEquals("", InnertubeParser.artistFromSubtitle("Song"))
        assertEquals("", InnertubeParser.artistFromSubtitle("Single • 3:20"))
    }
}
