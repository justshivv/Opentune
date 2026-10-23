package com.opentune.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SavedFileTest {
    private val dir: File = Files.createTempDirectory("saved").toFile()

    @Test fun aFileThatWontReadIsKeptAsideNotSavedOver() {
        val file = File(dir, "library.json").apply { writeText("{\"liked\": [half a save") }
        val saved = SavedFile(file, "Test")
        val read = saved.read(emptyList<String>()) { error("unreadable") }
        assertEquals(emptyList<String>(), read)
        // The unreadable copy is still there, under another name, for the next save not to wipe.
        val aside = dir.listFiles()!!.single { it.name.startsWith("library.json.broken-") }
        assertEquals("{\"liked\": [half a save", aside.readText())
        saved.write("[]")
        assertEquals("{\"liked\": [half a save", aside.readText())
    }

    @Test fun writesWholeAndOnlyFlushesWhatsWaiting() {
        val file = File(dir, "history.json")
        val saved = SavedFile(file, "Test")
        assertEquals("none", saved.read("none") { it })
        saved.write("one")
        assertEquals("one", file.readText())
        assertFalse(File(dir, "history.json.tmp").exists())
        saved.flushIfDirty { "two" }
        assertEquals("one", file.readText())
        saved.dirty = true
        saved.flushIfDirty { "two" }
        assertEquals("two", file.readText())
        assertFalse(saved.dirty)
    }
}
