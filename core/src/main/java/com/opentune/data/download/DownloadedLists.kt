package com.opentune.data.download

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Albums and playlists downloaded as a whole, so the library can show them
 * as lists of their downloaded songs. The audio itself is in [Downloads];
 * this keeps only which songs belong together, in their order.
 */
object DownloadedLists {
    @Serializable
    data class Saved(
        val id: String,
        val title: String,
        val subtitle: String = "",
        val thumbnailUrl: String? = null,
        val songIds: List<String>,
        val savedAt: Long = System.currentTimeMillis(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Saved.serializer())
    private var file: com.opentune.data.SavedFile? = null
    private val _lists = MutableStateFlow<List<Saved>>(emptyList())
    /** Newest first. */
    val lists: StateFlow<List<Saved>> = _lists.asStateFlow()

    fun init(context: Context) {
        val f = com.opentune.data.SavedFile(File(File(context.filesDir, "downloads").apply { mkdirs() }, "lists.json"), "DownloadedLists")
        file = f
        _lists.value = f.read(emptyList()) { json.decodeFromString(serializer, it) }
    }

    /** Keeps [id] as a downloaded list of [songIds]; downloading it again updates it and moves it to the top. */
    fun save(id: String, title: String, subtitle: String, thumbnailUrl: String?, songIds: List<String>) {
        val entry = Saved(id, title, subtitle, thumbnailUrl, songIds.distinct())
        publish(listOf(entry) + _lists.value.filterNot { it.id == id })
    }

    fun remove(id: String) = publish(_lists.value.filterNot { it.id == id })

    fun get(id: String): Saved? = _lists.value.firstOrNull { it.id == id }

    private fun publish(lists: List<Saved>) {
        _lists.value = lists
        // Whole or not at all: it was written straight over, and a save cut short lost every list.
        file?.let { f -> runCatching { f.write(json.encodeToString(serializer, lists)) } }
    }
}
