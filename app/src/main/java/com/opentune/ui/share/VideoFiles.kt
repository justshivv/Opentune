package com.opentune.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a lyric video is made, and how it's saved and shared, as the cards are (ShareCard). */
internal object VideoFiles {
    /** A fresh file for a video, in the folder the share sheet's FileProvider serves; older ones cleared away. */
    fun fresh(context: Context, name: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    fun share(context: Context, file: File, label: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(label, uri)
        context.startActivity(Intent.createChooser(send, null))
    }

    /** Copies [file] to Movies/OpenTune, where the gallery finds it. */
    suspend fun save(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OpenTune")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Couldn't add the video")
            try {
                resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: error("Couldn't write the video")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OpenTune").apply { mkdirs() }
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            @Suppress("DEPRECATION")
            values.put(MediaStore.Video.Media.DATA, target.absolutePath)
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(target)
        }
    }

    /**
     * Where the song's audio can be read: the downloaded file, your server's stream, or
     * YouTube's; null for a song on the phone itself or when none answers.
     */
    suspend fun audioSource(videoId: String): String? = runCatching {
        com.opentune.data.download.Downloads.fileFor(videoId)?.absolutePath
            ?: when {
                com.opentune.data.local.LocalMusic.isLocal(videoId) -> null
                com.opentune.data.subsonic.Subsonic.isSubsonic(videoId) -> com.opentune.data.subsonic.Subsonic.streamUrl(videoId)
                else -> com.opentune.data.innertube.StreamResolver.resolve(videoId)
            }
    }.getOrNull()
}
