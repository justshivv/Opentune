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
    private const val USER_AGENT = "BitChord/v1.8.1"
    /** [paused]: the add-on said its owner paused it ([retryAfterSeconds] is then how long, up to a day). */
    class Failure(val status: Int, val retryAfterSeconds: Long = 0, val paused: Boolean = false) : IOException(if (paused) "Add-on paused by its owner" else "Add-on returned HTTP $status")
    private val client by lazy { Http.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .dns { host -> Dns.SYSTEM.lookup(host).also { addresses -> if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw java.net.UnknownHostException("Add-ons must use a public HTTPS address.") } }
        .build() }
    val mediaClient by lazy { client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(25, TimeUnit.SECONDS).followRedirects(true)
        // The file is asked for with BitChord's User-Agent and, when its server turns that down,
        // once more without it; the answer is kept per server.
        .addInterceptor { chain ->
            val host = chain.request().url.host
            if (host in plainHosts) return@addInterceptor chain.proceed(chain.request())
            val tagged = chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            if (tagged.code !in REFUSED) return@addInterceptor tagged
            tagged.close()
            chain.proceed(chain.request()).also { if (it.code !in REFUSED) plainHosts += host }
        }
        // Every hop, redirects included, must stay on a public address.
        .addNetworkInterceptor { chain ->
            AddonProtocol.url(chain.request().url.toString())
            chain.proceed(chain.request())
        }.build() }
    private fun publicAddress(a: InetAddress): Boolean = !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isMulticastAddress &&
        !(a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc) &&
        !(a.address.size == 4 && (a.address[0].toInt() and 255) == 100 && (a.address[1].toInt() and 255) in 64..127)
    suspend fun text(url: String): String = bytes(url, 2_097_152).toString(Charsets.UTF_8)
    suspend fun bytes(url: String, limit: Int, prefix: Boolean = false): ByteArray =
        waitingOutRateLimits { withAgentFallback(AddonProtocol.url(url).host) { agent -> fetch(url, limit, prefix, agent) } }

    private suspend fun fetch(url: String, limit: Int, prefix: Boolean, agent: String?): ByteArray {
        var address = AddonProtocol.url(url)
        repeat(5) {
            var outgoing = Request.Builder().url(address).header("Accept", if (prefix) "*/*" else "application/json")
                .apply { if (agent != null) header("User-Agent", agent) }
                .apply { if (prefix) header("Range", "bytes=0-${limit - 1}") }.build()
            if (FirstPartyAddonAuth.protects(address)) outgoing = FirstPartyAddonAuth.authorize(outgoing)
            val response = request(outgoing, limit, prefix)
            if (response.redirect != null) { address = AddonProtocol.url(address.resolve(response.redirect)?.toString() ?: error("Invalid redirect")) }
            else return response.bytes
        }
        throw IOException("Too many add-on redirects")
    }

    /** Servers that turned BitChord's User-Agent down and answered without it. */
    private val plainHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** What a server says when it won't serve this client at all, rather than lacking the file or being busy. */
    private val REFUSED = setOf(400, 401, 403, 406, 451)

    /**
     * Asks [host] with BitChord's User-Agent, and, when it refuses that, once more without
     * it (OkHttp's own). Whichever it answers is used for it from then on. A server that
     * refuses both gets the first refusal back.
     */
    internal suspend fun <T> withAgentFallback(host: String, ask: suspend (agent: String?) -> T): T {
        if (host in plainHosts) return ask(null)
        return try { ask(USER_AGENT) } catch (e: Failure) {
            if (e.status !in REFUSED) throw e
            try { ask(null).also { plainHosts += host } } catch (_: Failure) { throw e }
        }
    }

    /**
     * A 429 that asks for a short wait is waited out and asked again, a couple of times,
     * as BitChord does: a busy add-on delays an answer rather than failing it. A longer
     * wait goes back to the caller, which holds the add-on for that song.
     */
    internal suspend fun <T> waitingOutRateLimits(ask: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try { return ask() } catch (e: Failure) {
                if (e.status != 429 || attempt >= RATE_RETRIES) throw e
                val wait = if (e.retryAfterSeconds > 0) e.retryAfterSeconds * 1_000 else RATE_BACKOFF_MS shl attempt
                if (wait > RATE_WAIT_CAP_MS) throw e
                kotlinx.coroutines.delay(wait)
                attempt++
            }
        }
    }
    private const val RATE_RETRIES = 2
    private const val RATE_BACKOFF_MS = 500L
    private const val RATE_WAIT_CAP_MS = 8_000L
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
                            if (!it.isSuccessful) {
                                val paused = it.header("X-OpenTune-Paused") == "1"
                                throw Failure(it.code, it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, if (paused) 86_400 else 600) ?: 0, paused)
                            }
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
