package com.opentune.ui.home

import com.opentune.data.innertube.Innertube
import com.opentune.data.settings.Recommender
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Another country's Home is cached apart, so moving country fetches a fresh one. */
class HomeCacheKeyTest {
    @After fun reset() { Innertube.regionOverride = null }

    @Test fun eachCountryHasItsOwnHome() {
        val engine = Recommender.entries.first()
        Innertube.regionOverride = "IN"
        val india = homeCacheKey(engine)
        Innertube.regionOverride = "US"
        val us = homeCacheKey(engine)
        assertNotEquals(india, us)
        // The same country asks for the same Home: nothing is fetched again for it.
        assertEquals(us, homeCacheKey(engine))
    }

    @Test fun eachEngineStillHasItsOwnHome() {
        Innertube.regionOverride = "US"
        val (a, b) = Recommender.entries.take(2)
        assertNotEquals(homeCacheKey(a), homeCacheKey(b))
    }

    @Test fun aSavedHomeIsFiledUnderTheLocaleItWasMadeFor() {
        val engine = Recommender.entries.first()
        assertEquals("home:${engine.name}:IN-hi", homeCacheKey(engine, "IN-hi"))
    }
}
