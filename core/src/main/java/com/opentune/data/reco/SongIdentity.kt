package com.opentune.data.reco

import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import java.text.Normalizer

/**
 * Whether two tracks are the same song, whatever upload they are: the
 * catalogue track and its music video, a lyric video, a re-upload with
 * "(Official Audio)" on the end, a live take, a slowed or sped-up edit, or
 * someone else's cover. Radio and AutoPlay keep one of them, so a song isn't
 * heard twice in a row in another version.
 *
 * Two tracks are the same song when their titles come down to the same words
 * once the dressing is taken off ("Official Video", "Lyrics", "feat. X",
 * "Artist - " in front), and then either the artist is the same, one of them
 * says it's a version of something (live, remix, cover, slowed...), or they
 * run within a few seconds of each other. Two different songs that share a
 * plain title ("Hello") have different artists and different lengths, so
 * they're both kept.
 */
object SongIdentity {
    /** Bracketed asides that only describe the upload. */
    private val DRESSING = Regex(
        """\s*[(\[【]([^)\]】]*)[)\]】]""",
    )
    private val UPLOAD_WORDS = Regex(
        """\b(official|music|lyric|lyrics|lyrical|video|audio|visuali[sz]er|hd|hq|4k|full|song|mv|m/v|clip|explicit|clean|version|remaster(ed)?|\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val FEATURING = Regex("""\s+(feat\.?|ft\.?|featuring|with|prod\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val VERSION = Regex(
        """\b(live|remix|mix|edit|cover|acoustic|unplugged|slowed|reverb|sped\s*up|speed\s*up|nightcore|lofi|lo-fi|karaoke|instrumental|8d|bass\s*boosted|mashup|reprise|demo|version|rendition|tribute|piano|orchestral)\b""",
        RegexOption.IGNORE_CASE,
    )
    private const val SAME_LENGTH_MS = 6_000L
    private val VERSION_ONLY = Regex("""(?i)(live|remix|slowed|reverb|sped up|acoustic|cover|lofi|lo-fi|instrumental|karaoke)(\s*[+&]\s*\w+)*""")

    /** The song's title with the upload's dressing taken off, folded for comparing. */
    fun titleKey(song: Song): String = titleKey(song.title, song.artist)

    fun titleKey(title: String, artist: String): String {
        var t = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        // "Artist - Title" (a video's own naming): the part that isn't the artist.
        val dash = Regex("""\s+[-–—|]\s+""").split(t)
        if (dash.size >= 2) {
            val main = fold(HiddenList.mainArtist(artist))
            // Parts that only describe the upload ("Lyrical Video") aren't the title.
            val named = dash.filter { fold(UPLOAD_WORDS.replace(it.replace(DRESSING, " "), " ")).isNotEmpty() && !VERSION_ONLY.matches(it.trim()) }
            t = when {
                named.size <= 1 -> named.firstOrNull() ?: dash.first()
                // One part is the credited artist: the title is the other.
                named.any { fold(it) == main } -> named.first { fold(it) != main }
                // Otherwise the usual "Artist - Title".
                else -> named[1]
            }
        }
        // Asides in brackets, versions included, say which upload it is, not which song.
        t = t.replace(DRESSING, " ")
        t = FEATURING.replace(t, "")
        t = UPLOAD_WORDS.replace(t, " ")
        return fold(t)
    }

    /**
     * Whether the title says it's a version of a song rather than the song: a
     * version word in brackets or after a dash ("Song (Live)", "Song - Slowed"),
     * not one that's part of the name ("Live Forever").
     */
    fun isVersion(song: Song): Boolean {
        val asides = DRESSING.findAll(song.title).map { it.groupValues[1] } +
            Regex("""\s+[-–—|]\s+""").split(song.title).drop(1).asSequence()
        return asides.any { VERSION.containsMatchIn(it) }
    }

    fun sameSong(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        val ka = titleKey(a)
        if (ka.isEmpty() || ka != titleKey(b)) return false
        if (artistKey(a) == artistKey(b)) return true
        // A label's upload names the singer in its title: "Arijit Singh - Tum Hi Ho".
        if (credits(a, b) || credits(b, a)) return true
        if (isVersion(a) || isVersion(b)) return true
        val da = a.durationText.durationMillis()
        val db = b.durationText.durationMillis()
        return da != null && db != null && kotlin.math.abs(da - db) <= SAME_LENGTH_MS
    }

    /**
     * [candidates] less any that are the same song as one in [known] or as one
     * before it in the list. Of two versions in the list, the catalogue track is
     * kept over a video, and the original over a version.
     */
    fun distinct(candidates: List<Song>, known: List<Song> = emptyList()): List<Song> {
        val byKey = HashMap<String, MutableList<Song>>()
        fun seen(s: Song) = byKey[titleKey(s)].orEmpty().any { sameSong(it, s) }
        fun add(s: Song) { byKey.getOrPut(titleKey(s)) { mutableListOf() } += s }
        known.forEach(::add)
        // The better version of each song first, so it's the one kept; the order is then put back.
        val better = candidates.withIndex().sortedWith(
            compareBy<IndexedValue<Song>> { titleKey(it.value) }
                .thenBy { if (it.value.isVideo) 1 else 0 }
                .thenBy { if (isVersion(it.value)) 1 else 0 }
                .thenBy { it.index },
        )
        val keep = HashSet<Int>()
        for ((i, s) in better) {
            if (titleKey(s).isEmpty()) { keep += i; continue }
            if (seen(s)) continue
            add(s)
            keep += i
        }
        return candidates.filterIndexed { i, _ -> i in keep }
    }

    private fun artistKey(song: Song) = fold(HiddenList.mainArtist(song.artist))

    /** Whether [upload]'s title names [track]'s artist. */
    private fun credits(track: Song, upload: Song): Boolean {
        val artist = artistKey(track)
        return artist.length >= 4 && fold(upload.title).contains(artist)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .lowercase().filter { it.isLetterOrDigit() }
}
