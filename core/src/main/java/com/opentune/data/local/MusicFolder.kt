package com.opentune.data.local

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.core.net.toUri
import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LibrarySettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Songs in one folder the user picked through Android's folder picker, read without access
 * to every audio file on the phone. The folder and its subfolders are walked through the
 * Storage Access Framework; each file's tags (and its cover, saved next to the app's files)
 * are read once and kept until the file changes.
 */
object MusicFolder {
    private const val TAG = "MusicFolder"
    /** After [LocalMusic.PREFIX]: the file's document uri, Base64 so the id holds no '/' or ':'. */
    internal const val MARK = "f:"
    private const val TAGS_FILE = "music-folder-tags.json"
    private const val ART_DIR = "music-folder-art"
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff", "aif", "mka", "webm", "amr", "3gp")

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** What a file's tags said, kept against its size and last change. */
    @Serializable
    internal data class Tags(
        val modified: Long,
        val size: Long,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0,
        val art: Boolean = false,
    )

    /** One audio file found in the folder. */
    internal data class Found(val uri: Uri, val name: String, val mimeType: String?, val folder: String, val modified: Long, val size: Long)

    fun idOf(document: Uri): String =
        LocalMusic.PREFIX + MARK + Base64.encodeToString(document.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun isFolderSong(videoId: String) = videoId.startsWith(LocalMusic.PREFIX + MARK)

    fun documentOf(videoId: String): Uri =
        String(Base64.decode(videoId.removePrefix(LocalMusic.PREFIX + MARK), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).toUri()

    /** Whether [tree] is still a folder the app may read (the permission survives restarts, but can be taken away). */
    fun canRead(context: Context, tree: String?): Boolean =
        tree != null && context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == tree }

    /**
     * Makes [tree], just picked, the folder songs are read from: keeps the permission to read
     * it across restarts and gives back the one held for a folder picked earlier.
     */
    fun use(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "couldn't keep access to the folder: ${it.message}") }
        val old = AppSettings.library.value.localTree
        if (old != null && old != tree.toString()) release(context, old)
        AppSettings.updateLibrary { it.copy(localTree = tree.toString(), localTreeName = nameOf(context, tree)) }
    }

    /** Back to every audio folder on the phone: the picked folder's permission is given back. */
    fun forget(context: Context) {
        AppSettings.library.value.localTree?.let { release(context, it) }
        AppSettings.updateLibrary { it.copy(localTree = null, localTreeName = null) }
    }

    private fun release(context: Context, tree: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun nameOf(context: Context, tree: Uri): String? = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** The songs in the picked folder and its subfolders; none when no folder is picked or it can't be read. */
    internal suspend fun tracks(context: Context, settings: LibrarySettings): List<LocalMusic.Track> = withContext(Dispatchers.IO) {
        val tree = settings.localTree?.toUri() ?: return@withContext emptyList()
        val files = runCatching { walk(context, tree) }
            .onFailure { Log.w(TAG, "couldn't read the folder: ${it.message}") }
            .getOrDefault(emptyList())
        lock.withLock {
            val known = loadTags(context)
            val fresh = coroutineScope {
                files.filter { f -> known[f.uri.toString()]?.let { it.modified != f.modified || it.size != f.size } ?: true }
                    .chunked(maxOf(1, files.size / 4 + 1))
                    .map { part -> async { part.map { it.uri.toString() to readTags(context, it) } } }
                    .awaitAll().flatten()
            }
            fresh.forEach { (k, v) -> known[k] = v }
            // Files gone from the folder take their tags and covers with them.
            val present = files.mapTo(HashSet()) { it.uri.toString() }
            val gone = known.keys.filterNot { it in present }
            gone.forEach { k -> known.remove(k); artFile(context, k).delete() }
            if (fresh.isNotEmpty() || gone.isNotEmpty()) saveTags(context, known)
            files.map { f -> trackOf(context, f, known.getValue(f.uri.toString())) }
                .sortedBy { it.song.title.lowercase() }
        }
    }

    internal fun trackOf(context: Context, f: Found, t: Tags): LocalMusic.Track = LocalMusic.Track(
        song = Song(
            videoId = idOf(f.uri),
            title = t.title?.takeIf { it.isNotBlank() } ?: f.name.substringBeforeLast('.'),
            artist = t.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            thumbnailUrl = if (t.art) Uri.fromFile(artFile(context, f.uri.toString())).toString() else null,
            durationText = com.opentune.data.model.durationTextOf(t.durationMs),
            albumName = t.album?.takeIf { it.isNotBlank() },
        ),
        folder = f.folder,
        mimeType = f.mimeType,
        durationMs = t.durationMs,
        // The user picked this folder for its music: only the duration and folder rules sort out the rest.
        isMusic = true,
        dateAdded = f.modified / 1000,
    )

    /** Every audio file under [tree], depth first, with the path below the picked folder. */
    private fun walk(context: Context, tree: Uri): List<Found> {
        val out = mutableListOf<Found>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending += DocumentsContract.getTreeDocumentId(tree) to ""
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty()) {
            val (parent, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            context.contentResolver.query(children, columns, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending += id to "$path$name/"
                    } else if (isAudio(name, mime)) {
                        out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, path, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        return out
    }

    internal fun isAudio(name: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private fun readTags(context: Context, f: Found): Tags {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, f.uri)
            val art = r.embeddedPicture?.let { bytes ->
                runCatching { artFile(context, f.uri.toString()).apply { parentFile?.mkdirs() }.writeBytes(bytes) }.isSuccess
            } ?: false
            Tags(
                modified = f.modified,
                size = f.size,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                art = art,
            )
        } catch (e: Exception) {
            // A file the platform can't read the tags of still plays (or fails) as a song named after the file.
            Log.d(TAG, "no tags for ${f.name}: ${e.message}")
            Tags(f.modified, f.size)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun artFile(context: Context, document: String): File =
        File(File(context.filesDir, ART_DIR), Integer.toHexString(document.hashCode()) + "-" + document.length + ".img")

    private val tagsSerializer = MapSerializer(String.serializer(), Tags.serializer())

    private fun savedTags(context: Context) = com.opentune.data.SavedFile(File(context.filesDir, TAGS_FILE), TAG)

    // Read afresh each scan (scans are rare): the file is the one copy, so nothing goes stale.
    private fun loadTags(context: Context): MutableMap<String, Tags> =
        savedTags(context).read(emptyMap()) { json.decodeFromString(tagsSerializer, it) }.toMutableMap()

    private fun saveTags(context: Context, known: Map<String, Tags>) {
        runCatching { savedTags(context).write(json.encodeToString(tagsSerializer, known)) }
            .onFailure { Log.w(TAG, "couldn't save the folder's tags: ${it.message}") }
    }
}
