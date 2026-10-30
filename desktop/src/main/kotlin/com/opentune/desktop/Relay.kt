package com.opentune.desktop

import com.opentune.data.Http
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/**
 * A small server on this computer only, between the audio engine and
 * YouTube. The engine opens a plain address for a song; the relay finds the
 * song's stream, asks for it in pieces, as YouTube expects, and passes on
 * what comes back, seeking included. A refused address is looked up afresh.
 */
object Relay {
    /** YouTube serves large ranges slowly; a megabyte at a time keeps it quick. */
    private const val PIECE = 1L shl 20

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "relay").apply { isDaemon = true } }
            createContext("/song/") { exchange -> try { serve(exchange) } finally { exchange.close() } }
            start()
        }
    }

    /** The address an engine plays [videoId] from, in [kind]; JavaFX goes by the extension. */
    fun addressOf(videoId: String, kind: Streams.Kind = Streams.Kind.AAC): String =
        "http://127.0.0.1:${server.address.port}/song/$videoId.${kind.extension}"

    private fun serve(exchange: HttpExchange) {
        val name = exchange.requestURI.path.removePrefix("/song/")
        val kind = Streams.Kind.entries.firstOrNull { name.endsWith(".${it.extension}") } ?: return exchange.sendResponseHeaders(404, -1)
        val videoId = name.removeSuffix(".${kind.extension}").takeIf { it.matches(VIDEO_ID) }
            ?: return exchange.sendResponseHeaders(404, -1)
        var stream = runCatching { runBlocking { Streams.resolve(videoId, kind = kind) } }.getOrElse {
            System.err.println("Relay: no stream for $videoId: $it")
            return exchange.sendResponseHeaders(502, -1)
        }
        val size = runCatching { length(stream.url) }.getOrElse { first ->
            System.err.println("Relay: $videoId refused ($first), looking again")
            Streams.forget(videoId)
            stream = runCatching { runBlocking { Streams.resolve(videoId, fresh = true, kind = kind) } }.getOrNull()
                ?: return exchange.sendResponseHeaders(502, -1)
            runCatching { length(stream.url) }.getOrElse {
                System.err.println("Relay: $videoId refused again ($it)")
                return exchange.sendResponseHeaders(502, -1)
            }
        }
        val (from, to) = Ranges.parse(exchange.requestHeaders.getFirst("Range"), size)
            ?: run {
                exchange.responseHeaders.add("Content-Range", "bytes */$size")
                return exchange.sendResponseHeaders(416, -1)
            }
        exchange.responseHeaders.add("Content-Type", stream.mimeType)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        val partial = exchange.requestHeaders.getFirst("Range") != null
        if (partial) exchange.responseHeaders.add("Content-Range", "bytes $from-$to/$size")
        exchange.sendResponseHeaders(if (partial) 206 else 200, to - from + 1)
        if (exchange.requestMethod == "HEAD") return
        val out = exchange.responseBody
        var at = from
        while (at <= to) {
            val end = minOf(to, at + PIECE - 1)
            val piece = Request.Builder().url(stream.url).header("Range", "bytes=$at-$end").build()
            Http.client.newCall(piece).execute().use { r ->
                if (!r.isSuccessful) {
                    Streams.forget(videoId)
                    return
                }
                // The engine closing its connection (a seek, the next song) ends this early, which is fine.
                runCatching { r.body!!.byteStream().copyTo(out) }.onFailure { return }
            }
            at = end + 1
        }
    }

    /** The stream's full length, from a one-byte request. */
    private fun length(url: String): Long {
        val probe = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        Http.client.newCall(probe).execute().use { r ->
            check(r.isSuccessful) { "refused: ${r.code}" }
            return r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 }
                ?: error("no length")
        }
    }

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
}

/** The byte range a request asks for, clipped to the stream. */
internal object Ranges {
    /** First and last byte for [header] on a stream of [size] bytes; null when it asks past the end. */
    fun parse(header: String?, size: Long): Pair<Long, Long>? {
        if (header == null) return 0L to size - 1
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val start = spec.substringBefore('-')
        val end = spec.substringAfter('-', "")
        return when {
            start.isEmpty() -> {
                val tail = end.toLongOrNull() ?: return null
                maxOf(0, size - tail) to size - 1
            }
            else -> {
                val from = start.toLongOrNull() ?: return null
                if (from >= size) return null
                from to minOf(size - 1, end.toLongOrNull() ?: (size - 1))
            }
        }
    }
}
