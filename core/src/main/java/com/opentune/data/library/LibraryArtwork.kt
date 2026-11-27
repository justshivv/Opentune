package com.opentune.data.library

import android.content.Context
import android.graphics.BitmapFactory
import com.opentune.data.Http
import com.opentune.data.model.PLAYER_ART_PX
import com.opentune.data.model.artworkAt
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.Request

/** Durable artwork shared by every display size. Bounded independently of Coil's temporary cache. */
object LibraryArtwork {
    private var dir: File? = null
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    fun init(context: Context) {
        dir = File(context.filesDir, "library-artwork").apply { mkdirs() }
        scope.launch {
            for (url in queue) {
                try { runCatching { fetch(url) } } finally { pending.remove(url) }
            }
        }
    }

    fun keep(urls: List<String?>) {
        // Not started (a watch): nothing is kept, and nothing piles up waiting.
        if (dir == null) return
        scope.launch {
        urls.filterNotNull().mapNotNull { it.artworkAt(PLAYER_ART_PX) }.distinct()
            .filter { it.startsWith("https://") && local(it) == null }
            .forEach { if (pending.add(it)) queue.trySend(it) }
        }
    }

    fun local(url: String): String? = target(url)?.takeIf { it.isFile && it.length() > 0 }?.toURI()?.toString()

    private fun target(url: String): File? {
        val normalized = url.artworkAt(PLAYER_ART_PX) ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
        return dir?.let { File(it, key) }
    }

    private fun fetch(url: String) {
        val target = target(url) ?: return
        if (target.exists()) return
        val tmp = File(target.path + ".tmp")
        try {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytes = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            if (bytes > MAX_IMAGE_BYTES) throw IOException("Artwork is too large")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            if (!tmp.renameTo(target)) return
            val files = dir?.listFiles()?.filter { !it.name.endsWith(".tmp") }.orEmpty().sortedBy { it.lastModified() }
            var bytes = files.sumOf { it.length() }
            for (file in files) {
                if (bytes <= MAX_BYTES) break
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        } finally { tmp.delete() }
    }
}
