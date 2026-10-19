package com.opentune.data.lossless

import com.opentune.data.Http
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Every request and redirect is checked against the installed manifest's network permission. */
internal class ProviderNetwork(private val hosts: List<String>) {
    private val cookies = mutableListOf<Cookie>()
    private val client = Http.client.newBuilder().followRedirects(false).followSslRedirects(false)
        .dns { host -> Dns.SYSTEM.lookup(host).also { addresses ->
            require(addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it.address.size == 16 && it.address[0].toInt() and 0xfe == 0xfc) }) { "Private network destinations are not allowed" }
        } }
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) = synchronized(cookies) {
                incoming.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; if (c.expiresAt > System.currentTimeMillis()) cookies.add(c) }
                while (cookies.size > 128) cookies.removeAt(0)
            }
            override fun loadForRequest(url: HttpUrl) = synchronized(cookies) { cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } }
        }).build()
    fun validate(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull() ?: throw LosslessFailure("The provider returned an invalid URL.")
        require(allowed(parsed, hosts)) { "Provider requested an undeclared or unsafe network destination: ${parsed.host}" }
        return parsed
    }
    suspend fun request(method: String, url: String, body: String, headers: Map<String, String>, output: File? = null,
        append: Boolean = false, remainingBytes: Long = 512L * 1024 * 1024,
        progress: (Long, Long) -> Unit = { _, _ -> }, redirects: Int = 0): JSONObject {
        require(method in setOf("GET", "POST", "HEAD") && body.length <= 2_000_000 && headers.size <= 64)
        val parsed = validate(url)
        val builder = Request.Builder().url(parsed)
        headers.forEach { (k, v) -> require(!k.equals("Host", true) && !k.equals("Cookie", true) && !k.equals("Content-Length", true)); builder.header(k, v) }
        if (method == "POST") builder.post(body.toRequestBody((headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/json").toMediaType()))
        if (method == "HEAD") builder.head()
        val response = execute(builder.build(), output, append, remainingBytes, progress)
        if (response.optInt("status") in setOf(301, 302, 303, 307, 308)) {
            require(redirects < 4) { "Provider redirect limit reached" }
            val location = response.getJSONObject("headers").optString("location")
            val target = parsed.resolve(location) ?: throw IOException("Invalid provider redirect")
            validate(target.toString())
            val nextMethod = if (response.optInt("status") in setOf(301, 302, 303) && method == "POST") "GET" else method
            val safeHeaders = if (target.host == parsed.host) headers else headers.filterKeys { !it.equals("Authorization", true) && !it.startsWith("X-Zarz-", true) }
            return request(nextMethod, target.toString(), if (nextMethod == "GET") "" else body, safeHeaders, output, append, remainingBytes, progress, redirects + 1)
        }
        return response
    }
    private suspend fun execute(request: Request, output: File?, append: Boolean, limit: Long,
        progress: (Long, Long) -> Unit): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newBuilder().callTimeout(if (output == null) 20 else 300, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { r ->
                        val head = JSONObject(); r.headers.names().forEach { name -> head.put(name.lowercase(), r.header(name)) }
                        val result = JSONObject().put("status", r.code).put("statusCode", r.code).put("ok", r.isSuccessful).put("headers", head)
                        if (output != null && r.isSuccessful) {
                            val body = r.body; val total = body.contentLength(); require(total <= limit)
                            var count = 0L
                            body.byteStream().use { input -> java.io.FileOutputStream(output, append).use { dest ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Cancelled")
                                    val n = input.read(buffer); if (n < 0) break; count += n
                                    require(count <= limit) { "Audio exceeds download limit" }
                                    dest.write(buffer, 0, n); progress(count, total.coerceAtLeast(0))
                                }
                                dest.fd.sync()
                            } }
                            if (total >= 0 && count != total) throw IOException("Incomplete audio response")
                            result.put("bytes", count).put("body", "").put("success", true).put("path", output.path)
                        } else {
                            val source = r.body.source(); val cap = if (r.isSuccessful) 4_194_304L else 16_384L
                            source.request(cap + 1); if (r.isSuccessful && source.buffer.size > cap) throw IOException("Provider response is too large")
                            result.put("body", source.readUtf8(minOf(source.buffer.size, cap)))
                        }
                        result
                    }
                    continuation.resume(result)
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        fun allowed(url: HttpUrl, hosts: List<String>) = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            !Regex("[0-9.]+").matches(url.host) && ':' !in url.host && url.host != "localhost" && hosts.any {
                if (it.startsWith("*.")) url.host.endsWith(it.removePrefix("*")) && url.host != it.removePrefix("*.") else url.host == it
            }
    }
}
