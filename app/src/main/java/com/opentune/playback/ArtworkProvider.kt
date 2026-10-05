package com.opentune.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.download.Downloads
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Serves cover art to Android Auto, which only loads `content://` artwork
 * for the items it browses (see [CarArtwork]).
 *
 * `download/<videoId>` is a downloaded song's own cover file. `web?u=<url>`
 * is fetched once into the cache and served from there. Only YouTube's image
 * hosts are fetched, so nothing can use this as a general web proxy. The
 * provider isn't exported: the browser that asked for a list is granted
 * read access to the artwork in it.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val file = when (uri.pathSegments.firstOrNull()) {
            "download" -> uri.pathSegments.getOrNull(1)?.let(Downloads::artFor)
            "web" -> uri.getQueryParameter("u")?.let(::cached)
            else -> null
        } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cached(url: String): File? {
        val host = url.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.host ?: return null
        if (ALLOWED_HOSTS.none { host == it || host.endsWith(".$it") }) return null
        val context = context ?: return null
        val dir = File(context.cacheDir, "car-art").apply { mkdirs() }
        val file = File(dir, sha1(url))
        if (file.length() > 0) return file
        return runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return null
                val tmp = File(dir, "${file.name}.tmp")
                tmp.outputStream().use { body.byteStream().copyTo(it) }
                tmp.renameTo(file)
            }
            file.takeIf { it.length() > 0 }
        }.onFailure { Log.w(TAG, "artwork fetch failed", it) }.getOrNull()
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "ArtworkProvider"
        val ALLOWED_HOSTS = listOf("googleusercontent.com", "ytimg.com", "ggpht.com")
    }
}
