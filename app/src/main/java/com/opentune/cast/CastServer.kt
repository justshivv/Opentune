package com.opentune.cast

import android.content.Context
import android.net.Uri
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * A small web server on the phone that a cast device fetches songs from.
 * Each song gets an unguessable path for as long as it's cast; behind it is
 * a file on the phone (a download or a song from the phone's library) or a
 * stream the phone fetches and passes through, with the headers the stream
 * needs, which a TV couldn't send itself. Ranges are honoured so the
 * device can seek.
 */
object CastServer {
    private const val TAG = "CastServer"

    /** Where a cast song's bytes come from. */
    sealed interface Source {
        val contentType: String

        class OnPhone(val file: File, override val contentType: String) : Source
        class Content(val uri: Uri, override val contentType: String) : Source

        /**
         * A stream on the web. [open] gives its address and headers; it's
         * asked again with `fresh = true` if the address stops working.
         */
        class Web(override val contentType: String, val open: suspend (fresh: Boolean) -> Pair<String, Map<String, String>>) : Source
    }

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sources = ConcurrentHashMap<String, Source>()
    private lateinit var app: Context

    /** Starts listening if it isn't; returns the port. */
    @Synchronized
    fun start(context: Context): Int {
        app = context.applicationContext
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(client) }.onFailure { Log.d(TAG, "request ended: ${it.message}") } }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        sources.clear()
    }

    /** Puts [source] up and returns its address as seen from [host] (the phone's address on the device's network). */
    fun publish(source: Source, host: InetAddress): String {
        val port = server?.localPort ?: error("not started")
        // Only a few songs need to be reachable at once: the one playing and the one before.
        if (sources.size > 8) sources.keys.take(sources.size - 8).forEach(sources::remove)
        val token = UUID.randomUUID().toString().replace("-", "")
        sources[token] = source
        val ext = when (source.contentType) {
            "audio/mp4" -> "m4a"
            "audio/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "audio/flac" -> "flac"
            "audio/ogg" -> "ogg"
            else -> "audio"
        }
        return "http://${host.hostAddress}:$port/s/$token.$ext"
    }

    private fun serve(socket: Socket) = socket.use { s ->
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = s.getOutputStream()
        val token = path.removePrefix("/s/").substringBefore('.').substringBefore('?')
        val source = sources[token]
        if (source == null || (method != "GET" && method != "HEAD")) {
            respond(out, "404 Not Found", mapOf("Content-Length" to "0"))
            return
        }
        val range = headers["range"]
        val head = method == "HEAD"
        when (source) {
            is Source.OnPhone -> sendLocal(out, source.contentType, source.file.length(), range, head) { source.file.inputStream() }
            is Source.Content -> {
                val length = runCatching { app.contentResolver.openFileDescriptor(source.uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
                sendLocal(out, source.contentType, length, range, head) { app.contentResolver.openInputStream(source.uri) ?: error("can't open ${source.uri}") }
            }
            is Source.Web -> sendWeb(out, source, range, head)
        }
    }

    private fun sendLocal(out: OutputStream, type: String, length: Long, range: String?, head: Boolean, open: () -> InputStream) {
        val span = Ranges.parse(range, length)
        val headers = linkedMapOf("Content-Type" to type, "Accept-Ranges" to "bytes") + DLNA_HEADERS
        if (span == null || length < 0) {
            respond(out, "200 OK", headers + (if (length >= 0) mapOf("Content-Length" to "$length") else emptyMap()))
            if (!head) open().use { it.copyTo(out) }
        } else {
            val (start, end) = span
            respond(out, "206 Partial Content", headers + mapOf("Content-Length" to "${end - start + 1}", "Content-Range" to "bytes $start-$end/$length"))
            if (!head) open().use { input ->
                var skip = start
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
                copy(input, out, end - start + 1)
            }
        }
    }

    private fun sendWeb(out: OutputStream, source: Source.Web, range: String?, head: Boolean) {
        var fresh = false
        repeat(2) {
            val (url, extra) = runBlocking { source.open(fresh) }
            val request = Request.Builder().url(url).apply {
                extra.forEach { (k, v) -> header(k, v) }
                if (range != null) header("Range", range)
                if (head) head()
            }.build()
            Http.client.newCall(request).execute().use { response ->
                // An address that's run out gets one fresh try.
                if (response.code == 403 || response.code == 410) {
                    fresh = true
                    return@repeat
                }
                val status = when (response.code) {
                    206 -> "206 Partial Content"
                    in 200..299 -> "200 OK"
                    416 -> "416 Range Not Satisfiable"
                    else -> "502 Bad Gateway"
                }
                val headers = linkedMapOf("Content-Type" to source.contentType, "Accept-Ranges" to "bytes") + DLNA_HEADERS
                val passed = listOf("Content-Length", "Content-Range").mapNotNull { k -> response.header(k)?.let { k to it } }
                respond(out, status, headers + passed)
                if (!head) response.body?.byteStream()?.use { it.copyTo(out) }
                return
            }
        }
        respond(out, "502 Bad Gateway", mapOf("Content-Length" to "0"))
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    /** What DLNA renderers look for before they'll stream and seek. */
    private val DLNA_HEADERS = mapOf(
        "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
    )
}

internal object Ranges {
    /** The first byte range in a Range header, as inclusive (start, end) within [length]; null for the whole thing. */
    fun parse(header: String?, length: Long): Pair<Long, Long>? {
        if (header == null || length <= 0) return null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val from = spec.substringBefore('-').trim()
        val to = spec.substringAfter('-', "").trim()
        return if (from.isEmpty()) {
            val suffix = to.toLongOrNull() ?: return null
            (length - suffix).coerceAtLeast(0) to length - 1
        } else {
            val start = from.toLongOrNull() ?: return null
            if (start >= length) return null
            val end = to.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
            start to end
        }
    }
}
