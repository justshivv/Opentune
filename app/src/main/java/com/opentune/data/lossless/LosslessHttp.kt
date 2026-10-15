package com.opentune.data.lossless

import com.opentune.data.Http
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A cancelled lookup closes its socket, including a stalled response body. */
internal object LosslessHttp {
    class HttpFailure(val code: Int, val responseBody: String = "", val retryAfterSeconds: Long? = null) : IOException("Lossless HTTP $code")
    internal fun retryAfterSeconds(value: String?, now: java.time.Instant = java.time.Instant.now()): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) }
        return runCatching {
            val date = java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            ((java.time.Duration.between(now, date).toMillis() + 999) / 1000).coerceAtLeast(0)
        }.getOrDefault(Long.MAX_VALUE) // An unknown server delay must not become an immediate retry.
    }
    data class Data(val bytes: ByteArray, val totalBytes: Long?)
    private val client by lazy {
        Http.client.newBuilder().callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 5_000): String =
        bytes(Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build(), 1_048_576, timeoutMs = timeoutMs).toString(Charsets.UTF_8)

    suspend fun bytes(request: Request, limit: Int, prefixOnly: Boolean = false, timeoutMs: Long = 5_000, followRedirects: Boolean = true): ByteArray =
        read(request, limit, prefixOnly, timeoutMs, followRedirects).bytes

    suspend fun read(request: Request, limit: Int, prefixOnly: Boolean = false, timeoutMs: Long = 5_000, followRedirects: Boolean = true): Data =
        suspendCancellableCoroutine { continuation ->
            val selected = if (timeoutMs == 5_000L && followRedirects) client else client.newBuilder()
                .followRedirects(followRedirects).followSslRedirects(followRedirects)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val call = selected.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) {
                                // Keep a bounded error contract for session/retry decisions, never in exception messages/logs.
                                val errorSource = it.body?.source()
                                errorSource?.request(8_192)
                                val error = errorSource?.readUtf8(minOf(errorSource.buffer.size, 8_192)) ?: ""
                                throw HttpFailure(it.code, error, retryAfterSeconds(it.header("Retry-After")))
                            }
                            val source = it.body?.source() ?: throw IOException("Empty lossless response")
                            source.request(limit.toLong() + if (prefixOnly) 0 else 1)
                            if (!prefixOnly && source.buffer.size > limit) throw IOException("Lossless response too large")
                            val total = if (it.code == 206) it.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                else it.body?.contentLength()?.takeIf { length -> length > 0 }
                            Data(source.readByteArray(minOf(source.buffer.size, limit.toLong())), total)
                        }
                        continuation.resume(bytes)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}

