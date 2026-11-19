package com.opentune.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {
    /** A page left off the main list can only be reached by searching. */
    @Test fun everyPageIsOnTheMainList() {
        val listed = SETTINGS_HOME.flatMap { it.second }
        assertEquals(SETTINGS_CATEGORIES.map { it.key }.sorted(), listed.sorted())
    }

    /** Every setting on the Settings screen has a page and a group; none falls into a "More" pile. */
    @Test fun everySettingHasItsPlace() {
        val source = listOf("src/main/java", "app/src/main/java").map { File(it, "com/opentune/ui/settings/SettingsScreen.kt") }.first { it.exists() }.readText()
        val rows = Regex("""\bEntry\(\s*"([^"]+)"""").findAll(source).map { it.groupValues[1] }.toSet()
        val named = SETTINGS_CATEGORIES.flatMap { c -> c.groups.flatMap { it.second } }
        assertEquals("named twice", named.groupBy { it }.filter { it.value.size > 1 }.keys, emptySet<String>())
        assertEquals("settings with no place", rows - named.toSet(), emptySet<String>())
        assertEquals("places with no setting", named.toSet() - rows, emptySet<String>())
        assertTrue(rows.size > 100)
    }
}
