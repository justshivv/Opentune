package com.opentune.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Copy a user-selected font into private storage; never depend on a document provider staying mounted. */
internal object ImportedFonts {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val idPattern = Regex("[a-f0-9-]{36}")
    fun file(context: Context, id: String): File? = id.takeIf { idPattern.matches(it) }
        ?.let { File(context.filesDir, "fonts/$it.font") }?.takeIf { it.isFile }

    fun import(context: Context, uri: Uri): Pair<String, String> {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }.take(100)
        require(name.endsWith(".ttf", true) || name.endsWith(".otf", true)) { "Choose a .ttf or .otf font file." }
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
        val destination = File(dir, "$id.font")
        try {
            requireNotNull(resolver.openInputStream(uri)) { "This file could not be opened." }.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192); var size = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count; require(size <= MAX_BYTES) { "Fonts must be smaller than 8 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val header = java.io.DataInputStream(destination.inputStream()).use { input -> ByteArray(4).also { input.readFully(it) } }
            require(validHeader(header)) { "This is not a supported TrueType or OpenType font." }
            // Validate using Android's actual font parser before storing the selection.
            if (android.os.Build.VERSION.SDK_INT >= 29) android.graphics.fonts.Font.Builder(destination).build()
            requireNotNull(Typeface.Builder(destination).build()) { "Android could not read this font." }
            return id to name
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
    internal fun validHeader(bytes: ByteArray): Boolean = bytes.contentEquals(byteArrayOf(0, 1, 0, 0)) || bytes.contentEquals("OTTO".toByteArray(Charsets.US_ASCII))
    fun remove(context: Context, id: String) { file(context, id)?.delete() }
}
