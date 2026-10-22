package com.opentune.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLayoutTest {
    /** A page left off the main list can only be reached by searching. */
    @Test fun everyPageIsOnTheMainList() {
        val listed = SETTINGS_HOME.flatMap { it.second }
        assertEquals(SETTINGS_CATEGORIES.map { it.key }.sorted(), listed.sorted())
    }
}
