package com.opentune.data.lossless

import android.content.Context
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.LogRedirectionStrategy
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.opentune.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Prepares provider-supplied MP4 audio locally; remux only, never lossy-to-FLAC transcoding. */
internal object AmazonAudio {
    private lateinit var directory: File
    private val lock = Mutex()
    private const val MAX_BYTES = 256L * 1024 * 1024
    fun init(context: Context) { directory = File(context.cacheDir, "amazon-audio").apply { mkdirs() } }
    internal data class Media(val url: String, val key: String, val meta: JSONObject)
    internal fun media(text: String): Media? {
        val root = if (text.trimStart().startsWith('[')) JSONArray(text).optJSONObject(0) else JSONObject(text)
        val audio = root?.optJSONObject("audio") ?: return null
        if (!audio.optString("codec").equals("flac", true)) return null
        val url = audio.optString("url").toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null &&
                (it.host.endsWith(".cloudfront.net") || it.host.endsWith(".amazonaws.com") || it.host == "api.zarz.moe")
        } ?: return null
        val key = audio.optString("key").trim()
        if (key.isNotEmpty() && !Regex("[0-9a-fA-F]{32}").matches(key)) return null
        return Media(url.toString(), key, root.optJSONObject("meta") ?: JSONObject())
    }
    suspend fun resolve(identity: CatalogIdentity, report: (String) -> Unit): LosslessSource.Track? {
        if (!ExtensionSessions.enabled()) return null
        if (ExtensionSessions.current() == null) { report("Amazon Music: verify in Audio settings → SpotiFLAC providers"); return null }
        return try {
            withTimeoutOrNull(70_000) { lock.withLock {
                val id = AmazonCatalog.find(identity)
                if (id == null) { report("Amazon Music: no exact catalog match"); return@withLock null }
                val generation = ExtensionSessions.revision.value
                val media = requestMedia(id)
                if (media == null) { report("Amazon Music: unsupported audio response"); return@withLock null }
                val meta = media.meta
                if (!identity.matches(meta.optString("title"), listOf(meta.optString("artist")), meta.optString("album"), identity.durationMs)) {
                    report("Amazon Music: returned recording does not match"); return@withLock null
                }
                val track = if (media.key.isEmpty()) LosslessSource.verify(media.url, "Amazon Music", identity.durationMs) else null
                val result = track ?: prepare(media, identity.durationMs)
                if (generation != ExtensionSessions.revision.value || ExtensionSessions.current() == null) return@withLock null
                if (result == null) report("Amazon Music: could not verify full-length FLAC")
                result
            } }.also { if (it == null) report("Amazon Music: no verified lossless upgrade; current audio continues") }
        } catch (e: CancellationException) { throw e }
        catch (e: LosslessHttp.HttpFailure) {
            report(if (ExtensionSessions.current() == null) "Amazon Music: verification required in Audio settings"
                else "Amazon Music: provider returned HTTP ${e.code}"); null
        } catch (_: Exception) { report("Amazon Music: provider or audio preparation unavailable"); null }
    }
    internal fun retry(error: LosslessHttp.HttpFailure, attempt: Int): Pair<Boolean, Long>? = runCatching {
        if (attempt >= 2 || error.code !in setOf(429, 503)) return null
        val root = JSONObject(error.responseBody)
        val mode = root.optString("retry_mode")
        if (root.opt("retryable") != true || mode !in setOf("new_ticket", "poll_existing")) return null
        val seconds = maxOf(error.retryAfterSeconds ?: 0, root.optLong("retry_after_seconds"), 1)
        if (seconds > 5) return null // Never retry earlier than the provider permits.
        (mode == "poll_existing") to seconds * 1000
    }.getOrNull()
    private suspend fun requestMedia(id: String): Media? {
        val alias = "amazeamazeamaze"
        var ticket = ""
        for (attempt in 0..2) {
            if (ticket.isBlank()) {
                ticket = JSONObject(ExtensionSessions.signed("/tickets", JSONObject().put("capability", "download_ticket")
                    .put("provider", alias).put("resource_hash", ExtensionProtocol.hash("$alias:track:${id.lowercase()}"))))
                    .let { it.optString("ticket_id").ifBlank { it.optString("ticket") } }
                require(ticket.isNotBlank() && ticket.length <= 8192 && ticket.none(Char::isISOControl))
            }
            try {
                return media(ExtensionSessions.signed("/dl/$alias", JSONObject().put("asin", id).put("codec", "flac"), mapOf("X-Zarz-Ticket" to ticket)))
            } catch (e: LosslessHttp.HttpFailure) {
                val retry = retry(e, attempt) ?: throw e
                if (!retry.first) ticket = ""
                kotlinx.coroutines.delay(retry.second)
            }
        }
        return null
    }
    private suspend fun prepare(media: Media, durationMs: Long): LosslessSource.Track? = withContext(Dispatchers.IO) {
        // Keep young files (including the one currently playing); refuse growth past the budget.
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        if ((directory.listFiles()?.sumOf { it.length() } ?: 0) > MAX_BYTES * 2 || directory.usableSpace < MAX_BYTES * 2) return@withContext null
        val name = UUID.randomUUID().toString()
        val input = File(directory, "$name.input")
        val output = File(directory, "$name.flac")
        var keep = false
        try {
            download(media.url, input)
            currentCoroutineContext().ensureActive()
            convert(input, output, media.key)
            currentCoroutineContext().ensureActive()
            val info = java.io.DataInputStream(output.inputStream()).use { source ->
                val header = ByteArray(42); source.readFully(header); LosslessSource.flacInfo(header)
            } ?: return@withContext null
            if (!LosslessSource.durationMatches(durationMs, info.durationMs)) return@withContext null
            keep = true
            LosslessSource.Track(output.toURI().toString(), "Amazon Music", info.bits, info.sampleRate)
        } finally { input.delete(); if (!keep) output.delete() }
    }
    private suspend fun download(url: String, target: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val call = Http.client.newBuilder().callTimeout(45, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
            .newCall(Request.Builder().url(url).header("User-Agent", AmazonCatalog.UA).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw LosslessHttp.HttpFailure(it.code)
                        val body = it.body ?: throw IOException("Empty audio")
                        if (body.contentLength() > MAX_BYTES) throw IOException("Audio exceeds cache limit")
                        body.byteStream().use { source -> target.outputStream().use { dest ->
                            val buffer = ByteArray(65_536); var total = 0L
                            while (true) {
                                if (!continuation.isActive) throw IOException("Cancelled")
                                val count = source.read(buffer); if (count < 0) break
                                total += count; if (total > MAX_BYTES) throw IOException("Audio exceeds cache limit")
                                dest.write(buffer, 0, count)
                            }
                        } }
                    }
                    if (!continuation.isActive) target.delete()
                    continuation.resume(Unit)
                } catch (e: Exception) { target.delete(); continuation.resumeWithException(e) }
            }
        })
    }
    internal fun conversionArguments(input: String, output: String, key: String): Array<String> = buildList {
        addAll(listOf("-hide_banner", "-loglevel", "quiet", "-nostdin", "-y", "-protocol_whitelist", "file,crypto,data"))
        if (key.isNotBlank()) addAll(listOf("-decryption_key", key))
        addAll(listOf("-i", input, "-map", "0:a:0", "-vn", "-c:a", "copy", "-f", "flac", output))
    }.toTypedArray()
    private suspend fun convert(input: File, output: File, key: String) = suspendCancellableCoroutine<Unit> { continuation ->
        val session = FFmpegSession.create(conversionArguments(input.path, output.path, key), { result ->
            // FFmpeg's in-memory history retains arguments; discard the ephemeral key immediately.
            FFmpegKitConfig.clearSessions()
            if (!continuation.isActive) { output.delete(); return@create }
            if (ReturnCode.isSuccess(result.returnCode)) continuation.resume(Unit)
            else continuation.resumeWithException(IOException("Audio container conversion failed"))
        }, null, null, LogRedirectionStrategy.NEVER_PRINT_LOGS)
        FFmpegKitConfig.asyncFFmpegExecute(session)
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}
