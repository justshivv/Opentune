package com.opentune.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.opentune.data.library.SongRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies of finished downloads in the phone's Music folder (Music/NepTune),
 * named "Artist - Title", for other music apps to find and play. Settings ›
 * Downloads › Export compatible downloads turns it on.
 *
 * Only formats Android's Music collection takes are copied: AAC in MP4,
 * FLAC, Ogg, MP3 and WAV. The media store refuses WebM outright, so with the
 * setting on, YouTube downloads are fetched as AAC in MP4 from the start
 * (see [com.opentune.data.innertube.StreamResolver.resolveForDownload]).
 * Android 10 and newer, where an app may add to the Music folder without
 * asking for storage permission.
 */
object DownloadExport {
    private const val TAG = "DownloadExport"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val MIME = mapOf(
        "m4a" to "audio/mp4", "mp4" to "audio/mp4", "aac" to "audio/aac",
        "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/ogg",
        "mp3" to "audio/mpeg", "wav" to "audio/wav",
    )

    /** Whether [file] is in a format the Music collection takes. */
    fun compatible(file: File): Boolean = file.extension.lowercase() in MIME

    /** Adds a copy of [file] for [song] to Music/NepTune; its row's URI, or null where it can't be. */
    suspend fun publish(context: Context, song: SongRef, file: File): Uri? = withContext(Dispatchers.IO) {
        if (!supported || !file.exists()) return@withContext null
        val ext = file.extension.lowercase()
        val mime = MIME[ext] ?: return@withContext null
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, com.opentune.data.Ringtones.fileName(song.toSong(), ext))
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            song.albumName?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NepTune")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }
            .onFailure { Log.w(TAG, "Couldn't add ${song.videoId} to the Music folder", it) }
            .getOrNull() ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse {
            Log.w(TAG, "Couldn't write ${song.videoId} to the Music folder", it)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Takes the copy at [uri] back out of the Music folder. */
    suspend fun unpublish(context: Context, uri: String) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
            .onFailure { Log.w(TAG, "Couldn't remove $uri from the Music folder", it) }
    }
}
