package com.opentune.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.innertube.Innertube
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home is kept for the country and language it was made for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeLocaleTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val home = File(context.filesDir, "home.json")

    @After fun reset() {
        Innertube.regionOverride = null
        home.delete()
    }

    @Test fun theLocaleFollowsTheCountry() {
        Innertube.regionOverride = "IN"
        val india = MusicRepository.homeLocale()
        Innertube.regionOverride = "de"
        val germany = MusicRepository.homeLocale()
        assertNotEquals(india, germany)
        assertEquals("DE-${Innertube.currentLanguage}", germany)
    }

    @Test fun aSavedHomeSaysWhichCountryItWasFor() {
        home.writeText("""{"first":[{"title":"Quick picks","items":[]}],"locale":"IN-hi"}""")
        Innertube.regionOverride = "US"
        val first = MusicRepository.init(context)
        assertEquals("Quick picks", first?.single()?.title)
        assertEquals("IN-hi", MusicRepository.savedHomeLocale)
    }

    /** One saved before the locale was kept is taken as this one's, so the update doesn't open on an empty Home. */
    @Test fun anOlderSavedHomeCountsAsThisLocales() {
        home.writeText("""{"first":[{"title":"Quick picks","items":[]}]}""")
        Innertube.regionOverride = "BR"
        MusicRepository.init(context)
        assertEquals(MusicRepository.homeLocale(), MusicRepository.savedHomeLocale)
    }
}
