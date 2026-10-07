package com.opentune.ui

import com.opentune.ui.settings.Entry
import com.opentune.ui.settings.Section
import com.opentune.ui.settings.SettingsCategory
import com.opentune.ui.settings.matchingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val sections = listOf(
        Section(SettingsCategory.APPEARANCE, "Theme & color", listOf(Entry("Material You", "wallpaper dynamic color") {})),
        Section(SettingsCategory.MOTION, "Dock & navigation", listOf(Entry("Tab highlight", "stretch glide") {})),
        Section(SettingsCategory.LIBRARY, "Downloads", listOf(Entry("Download on Wi-Fi only", "mobile data metered") {})),
        Section(SettingsCategory.ACCOUNTS, "Last.fm", listOf(Entry("Connect Last.fm", "scrobbling") {})),
    )

    @Test fun globalSearchFindsKeywordsAndAllWordsRegardlessOfCase() {
        assertEquals("Material You", matchingSettings(sections, " COLOR   wallpaper ", null).single().entries.single().title)
        assertEquals("Tab highlight", matchingSettings(sections, "navigation glide", null).single().entries.single().title)
    }

    @Test fun punctuationDoesNotHideWifiAndServices() {
        assertEquals(SettingsCategory.LIBRARY, matchingSettings(sections, "download wifi", null).single().category)
        assertEquals(SettingsCategory.ACCOUNTS, matchingSettings(sections, "lastfm", null).single().category)
    }

    @Test fun categorySearchCannotShowUnrelatedControls() {
        assertTrue(matchingSettings(sections, "download", SettingsCategory.APPEARANCE).isEmpty())
        assertEquals(SettingsCategory.MOTION, matchingSettings(sections, "", SettingsCategory.MOTION).single().category)
    }

    @Test fun clearingSearchRestoresAllEntriesWithoutChangingOrder() {
        assertEquals(sections.map { it.category }, matchingSettings(sections, "   ", null).map { it.category })
        assertEquals(sections.flatMap { it.entries }, matchingSettings(sections, "", null).flatMap { it.entries })
    }
}
