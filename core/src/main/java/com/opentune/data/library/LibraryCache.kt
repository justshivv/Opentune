package com.opentune.data.library

import android.content.Context
import com.opentune.data.Collection
import com.opentune.data.account.AccountStore
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.model.ShelfItem
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Private, account-scoped snapshots; music files are still managed by Downloads. */
object LibraryCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var root: File? = null
    private val _playlists = MutableStateFlow<List<ShelfItem>>(emptyList())
    val playlists = _playlists.asStateFlow()
    private var lastOwner: String? = null
    private var lastSession: String? = null
    private val _owned = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Whether the account made each of its library's playlists, by browse
     * id, for those whose page has been read. The library list itself can't
     * say; see [com.opentune.data.innertube.InnertubeParser.parsePlaylistOwned].
     */
    val owned = _owned.asStateFlow()
    fun owner(): String = AccountStore.account.value?.email?.takeIf { it.isNotBlank() }
        ?.let(::hash) ?: if (AccountStore.signedIn.value) "unidentified" else "guest"

    fun init(context: Context) {
        root = File(context.filesDir, "library-cache").apply { mkdirs() }
        scope.launch {
            AccountStore.account.collectLatest {
                val key = owner()
                val session = com.opentune.data.innertube.Innertube.cookie
                // The same session whose profile has only just arrived (the cache was "unidentified" until the
                // email was known): what it holds is already this account's, so it stays, and is kept on disk
                // under the account's name from now on, rather than being cleared and read from a file that
                // doesn't exist yet.
                val sameSession = session != null && session == lastSession && lastOwner == "unidentified" && key != "unidentified"
                lastOwner = key
                lastSession = session
                if (sameSession) {
                    val kept = _playlists.value
                    if (kept.isNotEmpty() && owner() == key) {
                        write(file(key, "index"), json.encodeToString(ListSerializer(ShelfItem.serializer()), kept))
                        return@collectLatest
                    }
                }
                _playlists.value = emptyList()
                _owned.value = emptyMap()
                val saved = runCatching {
                    json.decodeFromString(ListSerializer(ShelfItem.serializer()), file(key, "index").readText())
                }.getOrDefault(emptyList())
                val owned = runCatching {
                    json.decodeFromString(OWNED, file(key, "owned").readText())
                }.getOrDefault(emptyMap())
                if (owner() == key) {
                    _playlists.value = saved
                    _owned.value = owned
                }
            }
        }
    }

    fun collection(id: String, owner: String = owner()): Collection? = runCatching {
        if (owner == "unidentified") return null
        json.decodeFromString(Collection.serializer(), file(owner, id).readText())
    }.getOrNull()

    fun fresh(id: String, owner: String): Boolean = runCatching {
        val f = file(owner, id)
        f.isFile && System.currentTimeMillis() - f.lastModified() < 6 * 60 * 60 * 1000L
    }.getOrDefault(false)

    @Synchronized
    fun save(collection: Collection, owner: String) {
        if (this.owner() != owner || owner == "unidentified") return
        write(file(owner, collection.browseId), json.encodeToString(Collection.serializer(), collection))
        LibraryArtwork.keep(listOf(collection.thumbnailUrl) + collection.songs.map { it.thumbnailUrl })
        val isOwn = collection.owned
        if (isOwn != null && InnertubeParser.isEditablePlaylist(collection.browseId) && _owned.value[collection.browseId] != isOwn) {
            val owned = _owned.value + (collection.browseId to isOwn)
            _owned.value = owned
            write(file(owner, "owned"), json.encodeToString(OWNED, owned))
        }
    }

    private val OWNED = MapSerializer(String.serializer(), Boolean.serializer())

    @Synchronized
    fun savePlaylists(items: List<ShelfItem>, owner: String) {
        if (this.owner() != owner) return
        if (owner != "unidentified") write(file(owner, "index"), json.encodeToString(ListSerializer(ShelfItem.serializer()), items))
        _playlists.value = items
        LibraryArtwork.keep(items.map { it.thumbnailUrl })
    }

    private fun file(owner: String, id: String) = File(root ?: error("Library cache not initialized"), "$owner-${hash(id)}.json")
    private fun write(file: File, value: String) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(value)
            check(tmp.renameTo(file))
        }
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
