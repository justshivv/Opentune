package com.opentune.data.spotify

/**
 * A playlist exported as CSV: Exportify (every song of a Spotify playlist,
 * Liked Songs included), TuneMyMusic, Soundiiz and similar. Columns are
 * found by their header, so the exact layout doesn't matter.
 */
object PlaylistCsv {
    private val TITLE = listOf("track name", "track", "title", "song name", "song", "name")
    private val ARTIST = listOf("artist name(s)", "artist names", "artist name", "artists", "artist")
    private val ALBUM = listOf("album name", "album")
    private val DURATION = listOf("duration (ms)", "duration_ms", "duration ms", "track duration (ms)")

    /** The songs in [text]; empty when it has no recognisable title and artist columns. */
    fun parse(text: String): List<Spotify.Track> {
        val rows = rows(text.removePrefix("\uFEFF"))
        if (rows.size < 2) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun column(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val title = column(TITLE) ?: return emptyList()
        val artist = column(ARTIST) ?: return emptyList()
        val album = column(ALBUM)
        val duration = column(DURATION)
        return rows.drop(1).mapNotNull { r ->
            val name = r.getOrNull(title)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Spotify.Track(
                title = name,
                artists = Spotify.artists(r.getOrNull(artist).orEmpty().replace(";", ",")),
                album = album?.let { r.getOrNull(it)?.trim()?.takeIf { a -> a.isNotEmpty() } },
                durationMs = duration?.let { r.getOrNull(it)?.trim()?.toLongOrNull() } ?: 0,
            )
        }
    }

    /** RFC 4180 rows: quoted fields may hold commas, doubled quotes and line breaks. */
    internal fun rows(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotEmpty() }) out += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) out += row
        }
        return out
    }
}
