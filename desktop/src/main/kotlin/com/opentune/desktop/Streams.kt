package com.opentune.desktop

import com.opentune.data.Http
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Where a song's audio is, found with NewPipe as the phone app's last
 * fallback does. Only AAC in MP4 is kept: it's what the desktop's audio
 * engine plays on every system.
 */
object Streams {
    data class Stream(val url: String, val kbps: Int, val mimeType: String)

    /** YouTube's addresses last about six hours; reused for well under that. */
    private const val KEEP_MS = 60 * 60 * 1000L
    private val found = ConcurrentHashMap<String, Pair<Long, Stream>>()

    /** One extraction at a time: they share NewPipe's state and slow each other right down. */
    private val gate = Mutex()

    private val init by lazy { NewPipe.init(OkHttpDownloader) }

    suspend fun resolve(videoId: String, fresh: Boolean = false): Stream {
        if (!fresh) found[videoId]?.let { (at, s) -> if (System.currentTimeMillis() - at < KEEP_MS) return s }
        return gate.withLock {
            withContext(Dispatchers.IO) {
                init
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                val stream = pick(extractor.audioStreams) ?: error("No audio this computer can play for $videoId")
                Stream(stream.content, stream.averageBitrate, stream.format?.mimeType ?: "audio/mp4")
                    .also { found[videoId] = System.currentTimeMillis() to it }
            }
        }
    }

    /** The best AAC stream with a plain address; manifests and other codecs left out. */
    internal fun pick(streams: List<AudioStream>): AudioStream? = streams
        .filter { !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .filter { it.format?.mimeType == "audio/mp4" }
        .maxByOrNull { it.averageBitrate }

    /** The stream already found for [videoId], for saying what's playing. */
    fun known(videoId: String): Stream? = found[videoId]?.second

    /** Forgets an address the server refused, so the next try finds a new one. */
    fun forget(videoId: String) {
        found.remove(videoId)
    }

    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    /** Well-formed, empty, and over NewPipe's fifty-character floor. */
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    private val client = Http.client.newBuilder()
        .connectionPool(okhttp3.ConnectionPool())
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private object OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // "What plays next" is fetched by NewPipe and never read here, and
            // it's the one request that can hang the whole extraction.
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())
            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) hasUserAgent = true
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }
            val response = client.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(response.code, response.message, response.headers.toMultimap(), response.body?.string(), response.request.url.toString())
        }
    }
}
