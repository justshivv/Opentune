package com.opentune.data.history

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One listen: a track that played long enough to count. */
@Serializable
data class PlayRecord(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val playedAt: Long,
    val listenedMs: Long = 0,
    val explicit: Boolean? = null,
    val artistId: String? = null,
    /** A listen made elsewhere on the account (YouTube Music, another app), brought in from its history. */
    val fromAccount: Boolean = false,
) {
    fun toSong(): Song {
        // Same rule as the library's refs: the fullest credit the book
        // knows for the song is the one it is shown and played with.
        val fuller = com.opentune.data.model.fullerArtist(artist, com.opentune.data.library.LibraryStore.artistLine(videoId))
        return Song(videoId, title, fuller ?: artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit, artistId = artistId)
    }
}

data class TopEntry(val key: String, val title: String, val subtitle: String, val thumbnailUrl: String?, val plays: Int, val listenedMs: Long, val song: Song?)

data class ReplaySummary(
    val totalPlays: Int,
    val listenedMs: Long,
    /** How many different songs and artists were played; the top lists stop at 25. */
    val songCount: Int,
    val artistCount: Int,
    /** How many different albums, among the listens that name one. */
    val albumCount: Int = 0,
    val topSongs: List<TopEntry>,
    val topArtists: List<TopEntry>,
    val topAlbums: List<TopEntry>,
    /** Time listened in each hour of the day, midnight first, in the phone's time zone. */
    val byHour: List<Long> = List(24) { 0L },
    /** Time listened on each day of the week, Monday first. */
    val byWeekday: List<Long> = List(7) { 0L },
    /** Days with any listening. */
    val activeDays: Int = 0,
    /** The day with the most listening (its start, in ms) and how long it was. */
    val biggestDay: Pair<Long, Long>? = null,
)

/**
 * Listening history, kept on this device only, in one JSON file. Written by
 * the playback service, read by Home's Recents, the Library and Replay.
 */
object History {
    private const val MAX_RECORDS = 10_000
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PlayRecord.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null

    private val _records = MutableStateFlow<List<PlayRecord>>(emptyList())
    /** Newest first. */
    val records: StateFlow<List<PlayRecord>> = _records.asStateFlow()

    private var saved: com.opentune.data.SavedFile? = null

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val f = File(context.filesDir, "history.json")
        file = f
        val store = com.opentune.data.SavedFile(f, "History").also { saved = it }
        // History that won't read is kept aside, never saved over with an empty list.
        _records.value = store.read(emptyList()) { json.decodeFromString(serializer, it) }
        scope.launch {
            _records.drop(1).onEach { store.dirty = true }.debounce(1_500).collect { list ->
                runCatching { store.write(json.encodeToString(serializer, list)) }
                    .onFailure { Log.w("History", "Couldn't save history", it) }
            }
        }
    }

    /** Saves at once a change still waiting for its save: the app is leaving the screen or closing. */
    fun flush() { saved?.flushIfDirty { json.encodeToString(serializer, _records.value) } }

    /** Records a listen and returns its key for [updateListened]. */
    fun record(song: Song, listenedMs: Long): Long {
        val now = System.currentTimeMillis()
        // The credit written is the fullest known for the song: the played
        // song's own, widened by the library's book — and the book learns
        // from the play in return, so one good credit heals every list.
        val artist = com.opentune.data.model.fullerArtist(song.artist,
            com.opentune.data.library.LibraryStore.artistLine(song.videoId)) ?: song.artist
        val r = PlayRecord(song.videoId, song.title, artist, song.thumbnailUrl, song.durationText, song.albumName, now, listenedMs, song.isExplicit, song.artistId)
        _records.update { (listOf(r) + it).take(MAX_RECORDS) }
        com.opentune.data.library.LibraryStore.learnArtistLines(mapOf(song.videoId to artist))
        return now
    }

    /** Widens stored credits from the library's fullest known lines. */
    fun applyArtistLines(lines: Map<String, String>) {
        if (lines.isEmpty() || _records.value.isEmpty()) return
        // Atomic: this runs on sync threads while plays are recorded on the main one.
        _records.update { records -> records.map { widenRecord(it, lines) }.takeIf { it != records } ?: records }
    }

    /**
     * Brings in listens from the account's own history: [sections] as YouTube
     * Music groups it ("Today", "Yesterday", "This week"), newest first. A song
     * already in this history on that day (played here, and so reported there,
     * or brought in before) isn't added again. YouTube Music doesn't say when in
     * the day, so each is placed in order through its day.
     */
    fun mergeAccount(sections: List<Pair<String, List<Song>>>, now: Long = System.currentTimeMillis()) {
        // The account's history is parsed fresh, so its credits are whole:
        // teach them to the library's book, which heals the local records
        // of the same songs along with every list.
        com.opentune.data.library.LibraryStore.learnArtistLines(
            sections.flatMap { it.second }.associate { it.videoId to it.artist })
        val added = accountListens(_records.value, sections, now)
        if (added.isEmpty()) return
        _records.update { (it + added).sortedByDescending { r -> r.playedAt }.take(MAX_RECORDS) }
    }

    internal fun accountListens(existing: List<PlayRecord>, sections: List<Pair<String, List<Song>>>, now: Long): List<PlayRecord> {
        val day = 24 * 3_600_000L
        val clock = java.util.Calendar.getInstance().apply { timeInMillis = now }
        clock.set(java.util.Calendar.HOUR_OF_DAY, 0); clock.set(java.util.Calendar.MINUTE, 0)
        clock.set(java.util.Calendar.SECOND, 0); clock.set(java.util.Calendar.MILLISECOND, 0)
        val today = clock.timeInMillis
        val out = mutableListOf<PlayRecord>()
        for ((title, songs) in sections) {
            // The window the section covers, and where in it to place its songs.
            val (from, to) = when (title.trim().lowercase()) {
                "today" -> today to now
                "yesterday" -> today - day to today
                "this week" -> today - 6 * day to today - day
                else -> continue
            }
            val step = ((to - from) / (songs.size + 1)).coerceIn(1_000L, 10 * 60_000L)
            songs.forEachIndexed { i, song ->
                val known = (existing + out).any { it.videoId == song.videoId && it.playedAt in from - day..now }
                if (known) return@forEachIndexed
                val length = song.durationText.durationMillis() ?: 180_000L
                out += PlayRecord(
                    song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName,
                    playedAt = (to - (i + 1) * step).coerceAtLeast(from), listenedMs = length,
                    explicit = song.isExplicit, artistId = song.artistId, fromAccount = true,
                )
            }
        }
        return out
    }

    fun updateListened(playedAt: Long, listenedMs: Long) {
        _records.update { records -> records.map { if (it.playedAt == playedAt) it.copy(listenedMs = listenedMs) else it } }
    }

    fun clear() {
        _records.value = emptyList()
    }

    /** Each track once, most recent listen first. */
    /**
     * The newest [limit] songs played, each once. Read only as far as it takes to find them:
     * Library's shelf asks for twelve as it's drawn, and going through the whole history to
     * pick out every song first held up the main thread on a long one.
     */
    fun recents(list: List<PlayRecord>, limit: Int = 40): List<Song> =
        list.asSequence().distinctBy { it.videoId }.take(limit).map { it.toSong() }.toList()

    fun replay(list: List<PlayRecord>, sinceMs: Long): ReplaySummary {
        val window = list.filter { it.playedAt >= sinceMs }
        val clock = java.util.Calendar.getInstance()
        val byDay = window.groupBy { r ->
            clock.timeInMillis = r.playedAt
            clock.set(java.util.Calendar.HOUR_OF_DAY, 0); clock.set(java.util.Calendar.MINUTE, 0)
            clock.set(java.util.Calendar.SECOND, 0); clock.set(java.util.Calendar.MILLISECOND, 0)
            clock.timeInMillis
        }.mapValues { (_, plays) -> plays.sumOf { it.listenedMs } }
        fun top(group: (PlayRecord) -> String?, title: (List<PlayRecord>) -> Pair<String, String>, songOf: Boolean): List<TopEntry> =
            window.groupBy { group(it) ?: "" }
                .filterKeys { it.isNotBlank() }
                .map { (key, plays) ->
                    val (t, s) = title(plays)
                    TopEntry(key, t, s, plays.first().thumbnailUrl, plays.size, plays.sumOf { it.listenedMs }, if (songOf) plays.first().toSong() else null)
                }
                .sortedWith(compareByDescending<TopEntry> { it.plays }.thenByDescending { it.listenedMs })
                .take(25)
        return ReplaySummary(
            totalPlays = window.size,
            listenedMs = window.sumOf { it.listenedMs },
            songCount = window.distinctBy { it.videoId }.size,
            artistCount = window.map { Wrapped.mainArtist(it.artist) }.filter { it.isNotBlank() }.distinct().size,
            albumCount = window.mapNotNull { it.albumName?.takeIf { a -> a.isNotBlank() } }.distinct().size,
            topSongs = top({ it.videoId }, { p -> p.first().title to p.first().artist }, songOf = true),
            topArtists = top({ Wrapped.mainArtist(it.artist) }, { p -> Wrapped.mainArtist(p.first().artist) to "" }, songOf = true),
            topAlbums = top({ it.albumName }, { p -> (p.first().albumName ?: "") to p.first().artist }, songOf = false),
            byHour = clock.let { c ->
                val hours = LongArray(24)
                window.forEach { r -> c.timeInMillis = r.playedAt; hours[c.get(java.util.Calendar.HOUR_OF_DAY)] += r.listenedMs }
                hours.toList()
            },
            byWeekday = clock.let { c ->
                val days = LongArray(7)
                // Calendar counts Sunday as 1; Monday first here.
                window.forEach { r -> c.timeInMillis = r.playedAt; days[(c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7] += r.listenedMs }
                days.toList()
            },
            activeDays = byDay.size,
            biggestDay = byDay.maxByOrNull { it.value }?.toPair(),
        )
    }

    fun exportJson(): JsonElement = json.encodeToJsonElement(serializer, _records.value)

    fun importJson(element: JsonElement) {
        _records.value = json.decodeFromJsonElement(serializer, element).sortedByDescending { it.playedAt }.take(MAX_RECORDS)
    }
}

/** [record] with its credit widened from [lines], under the one rule: only by a credit naming it and more. */
internal fun widenRecord(record: PlayRecord, lines: Map<String, String>): PlayRecord {
    val wider = com.opentune.data.model.fullerArtist(record.artist, lines[record.videoId])
    return if (wider != null && wider != record.artist) record.copy(artist = wider) else record
}
