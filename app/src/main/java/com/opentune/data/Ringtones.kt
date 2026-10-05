package com.opentune.data

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.opentune.data.download.Downloads
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.Song
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A song as the phone's ringtone, notification or alarm sound.
 *
 * A file on the phone is used where it is. A downloaded song lives in the
 * app's private storage, which the system's ringer can't read, so a copy
 * goes to Ringtones/OpenTune first, marked as a ringtone rather than music
 * so it doesn't turn up in music apps.
 */
object Ringtones {
    enum class Kind(val label: String, val type: Int, val column: String) {
        RINGTONE("Ringtone", RingtoneManager.TYPE_RINGTONE, MediaStore.Audio.Media.IS_RINGTONE),
        NOTIFICATION("Notification sound", RingtoneManager.TYPE_NOTIFICATION, MediaStore.Audio.Media.IS_NOTIFICATION),
        ALARM("Alarm", RingtoneManager.TYPE_ALARM, MediaStore.Audio.Media.IS_ALARM),
    }

    /** Whether [song] has audio on the phone to make a ringtone from. */
    fun canUse(song: Song) = LocalMusic.isLocal(song.videoId) || Downloads.fileFor(song.videoId) != null

    /** Android only lets an app change the default sounds once the user allows "Modify system settings". */
    fun allowed(context: Context) = Settings.System.canWrite(context)

    /** Android 9 and older write the copy to shared storage with the storage permission. */
    val needsStoragePermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Sets [song] as the default sound of [kind]; the URI it was set from. */
    suspend fun set(context: Context, song: Song, kind: Kind): Uri = withContext(Dispatchers.IO) {
        val uri = if (LocalMusic.isLocal(song.videoId)) {
            LocalMusic.contentUri(song.videoId)
        } else {
            val file = Downloads.fileFor(song.videoId) ?: error("The song isn't downloaded")
            publish(context, song, file, kind)
        }
        RingtoneManager.setActualDefaultRingtoneUri(context, kind.type, uri)
        uri
    }

    private fun publish(context: Context, song: Song, file: File, kind: Kind): Uri {
        val ext = file.extension.ifEmpty { "m4a" }
        val name = fileName(song, ext)
        val mime = if (ext == "m4a") "audio/mp4" else "audio/webm"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, song.title)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(kind.column, 1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            existing(context, collection, name)?.let { uri ->
                resolver.update(uri, ContentValues().apply { put(kind.column, 1) }, null, null)
                return uri
            }
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/OpenTune")
            values.put(MediaStore.Audio.Media.IS_PENDING, 1)
            val uri = resolver.insert(collection, values) ?: error("Couldn't add the ringtone")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Couldn't write the ringtone")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES), "OpenTune").apply { mkdirs() }
        val target = File(dir, name)
        if (!target.exists()) file.copyTo(target)
        @Suppress("DEPRECATION")
        values.put(MediaStore.Audio.Media.DATA, target.absolutePath)
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return existing(context, collection, name) ?: resolver.insert(collection, values) ?: error("Couldn't add the ringtone")
    }

    @android.annotation.SuppressLint("Recycle") // closed by use below; lint doesn't follow the elvis
    private fun existing(context: Context, collection: Uri, name: String): Uri? {
        val cursor = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        ) ?: return null
        return cursor.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }

    /** "Artist - Title.ext", with characters file systems refuse taken out. */
    internal fun fileName(song: Song, ext: String): String {
        val base = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { song.videoId }
        return base.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(120) + ".$ext"
    }
}
