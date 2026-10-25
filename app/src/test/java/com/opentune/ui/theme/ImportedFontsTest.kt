package com.opentune.ui.theme

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.opentune.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportedFontsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun copiesARealFontAndDoesNotDependOnTheOriginalDocument() {
        val source = File(context.cacheDir, "import-test.ttf")
        context.resources.openRawResource(R.font.outfit_variable).use { input -> source.outputStream().use { input.copyTo(it) } }
        val (id, name) = ImportedFonts.import(context, Uri.fromFile(source))
        assertEquals("import-test.ttf", name)
        source.delete()
        assertNotNull(ImportedFonts.file(context, id))
        ImportedFonts.remove(context, id)
        assertNull(ImportedFonts.file(context, id))
    }
    @Test fun rejectsNonFontsOversizedFilesAndExternalPaths() {
        assertNull(ImportedFonts.file(context, "../../other-file"))
        val source = File(context.cacheDir, "bad.ttf").apply { writeText("not a font") }
        assertThrows(Exception::class.java) { ImportedFonts.import(context, Uri.fromFile(source)) }
        java.io.RandomAccessFile(source, "rw").use { it.setLength(ImportedFonts.MAX_BYTES.toLong() + 1) }
        assertThrows(IllegalArgumentException::class.java) { ImportedFonts.import(context, Uri.fromFile(source)) }
        source.delete()
    }
}
