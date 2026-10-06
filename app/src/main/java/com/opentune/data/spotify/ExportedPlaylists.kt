package com.opentune.data.spotify

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Playlists exported as files: one CSV, or the zip of every playlist that
 * Exportify's "Export All" makes. The in-app Exportify page hands what it
 * catches to the import page through [pending].
 */
object ExportedPlaylists {
    data class Exported(val name: String, val tracks: List<Spotify.Track>)

    private val _pending = MutableStateFlow<List<Exported>?>(null)
    /** Playlists caught from the in-app Exportify page, waiting for the import page. */
    val pending: StateFlow<List<Exported>?> = _pending.asStateFlow()

    fun hand(playlists: List<Exported>) { _pending.value = playlists }

    fun take(): List<Exported>? = _pending.value.also { _pending.value = null }

    /** The playlists in a downloaded file, named after the file (or each CSV in a zip). */
    fun read(fileName: String, bytes: ByteArray): List<Exported> {
        val zipped = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!zipped) {
            val tracks = PlaylistCsv.parse(bytes.decodeToString())
            return if (tracks.isEmpty()) emptyList() else listOf(Exported(nameOf(fileName), tracks))
        }
        val out = mutableListOf<Exported>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    val tracks = PlaylistCsv.parse(zip.readBytes().decodeToString())
                    if (tracks.isNotEmpty()) out += Exported(nameOf(entry.name.substringAfterLast('/')), tracks)
                }
                zip.closeEntry()
            }
        }
        return out
    }

    /** "today's_top_hits.csv" as "today's top hits". */
    internal fun nameOf(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Imported playlist" }
}
