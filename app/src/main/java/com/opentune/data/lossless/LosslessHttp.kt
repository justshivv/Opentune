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
    class HttpFailure(val code: Int, val retryAfterMs: Long? = null) : IOException("Lossless HTTP $code")
    data class Data(val bytes: ByteArray, val totalBytes: Long?)
    private val client by lazy {
        Http.client.newBuilder().callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 5_000): String =
        bytes(Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build(), 1_048_576, timeoutMs = timeoutMs).toString(Charsets.UTF_8)

    suspend fun bytes(request: Request, limit: Int, prefixOnly: Boolean = false, timeoutMs: Long = 5_000): ByteArray =
        read(request, limit, prefixOnly, timeoutMs).bytes

    suspend fun read(request: Request, limit: Int, prefixOnly: Boolean = false, timeoutMs: Long = 5_000): Data =
        suspendCancellableCoroutine { continuation ->
            val selected = if (timeoutMs == 5_000L) client else client.newBuilder()
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
                            if (!it.isSuccessful) throw HttpFailure(it.code, retryAfterMillis(it.header("Retry-After")))
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

    internal fun retryAfterMillis(value: String?, now: java.time.Instant = java.time.Instant.now()): Long? {
        val text = value?.trim() ?: return null
        val seconds = text.toLongOrNull()
        if (seconds != null) return seconds.takeIf { it >= 0 }?.coerceAtMost(86_400)?.times(1000)
        return runCatching {
            val date = java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            java.time.Duration.between(now, date).toMillis().coerceIn(0, 86_400_000)
        }.getOrNull()
    }
}

