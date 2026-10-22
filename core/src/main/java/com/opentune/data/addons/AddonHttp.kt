package com.opentune.data.addons

import com.opentune.data.Http
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded requests with cancellation and no ambient cookies or authentication. */
object AddonHttp {
    class Failure(val status: Int, val retryAfterSeconds: Long = 0) : IOException("Add-on returned HTTP $status")
    private val client by lazy { Http.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).also { addresses -> if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw java.net.UnknownHostException("Add-ons must use a public HTTPS address.") } }
        .build() }
    val mediaClient by lazy { client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(25, TimeUnit.SECONDS).followRedirects(true).addNetworkInterceptor { chain ->
            AddonProtocol.url(chain.request().url.toString())
            chain.proceed(chain.request())
        }.build() }
    private fun publicAddress(a: InetAddress): Boolean = !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isMulticastAddress &&
        !(a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc) &&
        !(a.address.size == 4 && (a.address[0].toInt() and 255) == 100 && (a.address[1].toInt() and 255) in 64..127)
    suspend fun text(url: String): String = bytes(url, 2_097_152).toString(Charsets.UTF_8)
    suspend fun bytes(url: String, limit: Int, prefix: Boolean = false): ByteArray {
        var address = AddonProtocol.url(url)
        repeat(5) {
            val response = request(Request.Builder().url(address).header("Accept", if (prefix) "*/*" else "application/json")
                .header("User-Agent", "BitChord/v1.8.1")
                .apply { if (prefix) header("Range", "bytes=0-${limit - 1}") }.build(), limit, prefix)
            if (response.redirect != null) { address = AddonProtocol.url(address.resolve(response.redirect)?.toString() ?: error("Invalid redirect")) }
            else return response.bytes
        }
        throw IOException("Too many add-on redirects")
    }
    private data class Reply(val bytes: ByteArray, val redirect: String? = null)
    private suspend fun request(request: Request, limit: Int, prefix: Boolean): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.isRedirect) Reply(byteArrayOf(), it.header("Location") ?: throw IOException("Invalid redirect"))
                        else {
                            if (!it.isSuccessful) throw Failure(it.code, it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 600) ?: 0)
                            val source = it.body?.source() ?: throw IOException("Empty add-on response")
                            source.request(limit.toLong() + if (prefix) 0 else 1)
                            if (!prefix && source.buffer.size > limit) throw IOException("Add-on response too large")
                            Reply(source.readByteArray(minOf(limit.toLong(), source.buffer.size)))
                        }
                    }
                    continuation.resume(result)
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        })
    }
}
