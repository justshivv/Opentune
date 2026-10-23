package com.opentune.data.history

import android.content.Context
import com.opentune.data.DebugLog as Log
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** An album, playlist or artist played from its page, and when. */
@Serializable
data class CollectionPlay(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    /** Newest first, the last [ListenedCollections.PLAYS_KEPT]. */
    val playedAt: List<Long> = emptyList(),
)

/**
 * The albums, playlists and artists listened to from their own page (Play,
 * Shuffle, or a song in the list), for Speed dial: the play history keeps
 * songs only, without where they were played from.
 */
object ListenedCollections {
    internal const val PLAYS_KEPT = 30
    private const val KEPT = 200
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CollectionPlay.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _plays = MutableStateFlow<List<CollectionPlay>>(emptyList())
    val plays: StateFlow<List<CollectionPlay>> = _plays.asStateFlow()

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        val f = File(context.filesDir, "listened-collections.json")
        _plays.value = runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyList())
        scope.launch {
            _plays.drop(1).debounce(1_500).collect { list ->
                runCatching {
                    val tmp = File(f.parentFile, "listened-collections.json.tmp")
                    tmp.writeText(json.encodeToString(serializer, list))
                    tmp.renameTo(f)
                }.onFailure { Log.w("ListenedCollections", "Couldn't save played albums and playlists: ${it.message}") }
            }
        }
    }

    fun played(browseId: String, title: String, subtitle: String, thumbnailUrl: String?, now: Long = System.currentTimeMillis()) {
        if (browseId.isBlank() || title.isBlank()) return
        _plays.update { list ->
            val old = list.firstOrNull { it.browseId == browseId }
            val entry = CollectionPlay(browseId, title, subtitle, thumbnailUrl ?: old?.thumbnailUrl, (listOf(now) + old?.playedAt.orEmpty()).take(PLAYS_KEPT))
            (listOf(entry) + list.filterNot { it.browseId == browseId }).take(KEPT)
        }
    }

    fun clear() { _plays.value = emptyList() }
}
