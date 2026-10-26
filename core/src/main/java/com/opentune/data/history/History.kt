package com.opentune.data.history

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    fun toSong() = Song(videoId, title, artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit, artistId = artistId)
}

data class TopEntry(val key: String, val title: String, val subtitle: String, val thumbnailUrl: String?, val plays: Int, val listenedMs: Long, val song: Song?)

data class ReplaySummary(
    val totalPlays: Int,
    val listenedMs: Long,
    /** How many different songs and artists were played; the top lists stop at 25. */
    val songCount: Int,
    val artistCount: Int,
    val topSongs: List<TopEntry>,
    val topArtists: List<TopEntry>,
    val topAlbums: List<TopEntry>,
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
        val r = PlayRecord(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName, now, listenedMs, song.isExplicit, song.artistId)
        _records.value = (listOf(r) + _records.value).take(MAX_RECORDS)
        return now
    }

    fun updateListened(playedAt: Long, listenedMs: Long) {
        _records.value = _records.value.map { if (it.playedAt == playedAt) it.copy(listenedMs = listenedMs) else it }
    }

    fun clear() {
        _records.value = emptyList()
    }

    /** Each track once, most recent listen first. */
    fun recents(list: List<PlayRecord>, limit: Int = 40): List<Song> =
        list.distinctBy { it.videoId }.take(limit).map { it.toSong() }

    fun replay(list: List<PlayRecord>, sinceMs: Long): ReplaySummary {
        val window = list.filter { it.playedAt >= sinceMs }
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
            topSongs = top({ it.videoId }, { p -> p.first().title to p.first().artist }, songOf = true),
            topArtists = top({ Wrapped.mainArtist(it.artist) }, { p -> Wrapped.mainArtist(p.first().artist) to "" }, songOf = true),
            topAlbums = top({ it.albumName }, { p -> (p.first().albumName ?: "") to p.first().artist }, songOf = false),
        )
    }

    fun exportJson(): JsonElement = json.encodeToJsonElement(serializer, _records.value)

    fun importJson(element: JsonElement) {
        _records.value = json.decodeFromJsonElement(serializer, element).sortedByDescending { it.playedAt }.take(MAX_RECORDS)
    }
}
