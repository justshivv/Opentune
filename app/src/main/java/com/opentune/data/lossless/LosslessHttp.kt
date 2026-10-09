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
    private val client by lazy {
        Http.client.newBuilder().callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        bytes(Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build(), 1_048_576).toString(Charsets.UTF_8)

    suspend fun bytes(request: Request, limit: Int, prefixOnly: Boolean = false): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw IOException("Lossless HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Empty lossless response")
                            source.request(limit.toLong() + 1)
                            if (!prefixOnly && source.buffer.size > limit) throw IOException("Lossless response too large")
                            source.readByteArray(minOf(source.buffer.size, limit.toLong()))
                        }
                        continuation.resume(bytes)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}
