package com.opentune.data.lossless

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
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Prepares provider-supplied MP4 audio locally; remux only, never lossy-to-FLAC transcoding. */
internal object AmazonAudio {
    private const val MAX_BYTES = 512L * 1024 * 1024
    internal data class Media(val url: String, val key: String, val meta: JSONObject, val cover: String = "")
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
        return Media(url.toString(), key, root.optJSONObject("meta") ?: JSONObject(), root.optString("cover")
            .replace("{size}", "1200").replace("{jpegQuality}", "95").replace("{format}", "jpg"))
    }
    /** Ticket -> complete file -> stream-copy container preparation -> full decode validation. */
    suspend fun downloadTrack(track: LosslessTrack, folder: File,
        progress: (LosslessState, Long, Long) -> Unit): LosslessSource.FlacInfo = withContext(Dispatchers.IO) {
        kotlinx.coroutines.withTimeout(8 * 60_000L) {
            if (folder.usableSpace < MAX_BYTES * 2) throw LosslessFailure("At least 1 GB free space is needed to prepare lossless audio.")
            progress(LosslessState.REQUESTING, 0, 0)
            var stage = "Requesting FLAC"
            val media = try { requestMedia(track.id) { stage = it } }
                catch (e: LosslessHttp.HttpFailure) { throw LosslessFailure(losslessError(stage, e)) }
                ?: throw LosslessFailure("The provider did not return a supported FLAC download.")
            // Compare metadata here; actual file duration is checked after preparation.
            val duration = track.durationMs.takeIf { it > 0 } ?: 1000L
            if (!CatalogIdentity(track.title, track.artist, null, duration).matches(media.meta.optString("title"),
                    listOf(media.meta.optString("artist")), null, duration)) throw LosslessFailure("Returned audio does not match the selected recording.")
            val input = File(folder, "audio.part")
            val output = File(folder, "audio.flac")
            var keep = false
            try {
                progress(LosslessState.DOWNLOADING, 0, 0)
                download(media.url, input) { bytes, total -> progress(LosslessState.DOWNLOADING, bytes, total) }
                currentCoroutineContext().ensureActive()
                progress(LosslessState.PREPARING, input.length(), input.length())
                if (media.key.isEmpty() && readInfo(input) != null) {
                    if (!input.renameTo(output)) throw IOException("Could not save FLAC")
                } else convert(input, output, media.key)
                progress(LosslessState.VERIFYING, output.length(), output.length())
                val info = readInfo(output) ?: throw LosslessFailure("Downloaded audio is not music-quality FLAC.")
                if (track.durationMs > 0 && !LosslessSource.durationMatches(track.durationMs, info.durationMs))
                    throw LosslessFailure("Downloaded recording is incomplete or has a different duration.")
                runFfmpeg(verificationArguments(output.path), output)
                currentCoroutineContext().ensureActive()
                val cover = media.cover.ifBlank { track.artwork.orEmpty() }.toHttpUrlOrNull()
                if (cover != null && cover.isHttps && (cover.host.endsWith(".media-amazon.com") || cover.host.endsWith(".images-amazon.com"))) {
                    try { File(folder, "cover.jpg").writeBytes(LosslessHttp.bytes(Request.Builder().url(cover).build(), 4_194_304, timeoutMs = 6000, followRedirects = false)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { File(folder, "cover.jpg").delete() }
                }
                keep = true; info
            } finally { input.delete(); if (!keep) output.delete() }
        }
    }
    private fun readInfo(file: File): LosslessSource.FlacInfo? = runCatching {
        java.io.DataInputStream(file.inputStream()).use { input -> val bytes = ByteArray(42); input.readFully(bytes); LosslessSource.flacInfo(bytes) }
    }.getOrNull()
    internal fun verificationArguments(path: String) = arrayOf("-hide_banner", "-loglevel", "quiet", "-nostdin", "-xerror",
        "-err_detect", "explode", "-protocol_whitelist", "file,crypto,data", "-i", path, "-map", "0:a:0", "-vn", "-f", "null", "-")
    internal fun retry(error: LosslessHttp.HttpFailure, attempt: Int): Pair<Boolean, Long>? = runCatching {
        if (attempt >= 2 || error.code !in setOf(429, 503)) return null
        val root = JSONObject(error.responseBody)
        val mode = root.optString("retry_mode")
        if (root.opt("retryable") != true || mode !in setOf("new_ticket", "poll_existing")) return null
        val seconds = maxOf(error.retryAfterSeconds ?: 0, root.optLong("retry_after_seconds"), 1)
        if (seconds > 5) return null // Never retry earlier than the provider permits.
        (mode == "poll_existing") to seconds * 1000
    }.getOrNull()
    private suspend fun requestMedia(id: String, stage: (String) -> Unit): Media? {
        val alias = "amazeamazeamaze"
        var ticket = ""
        for (attempt in 0..2) {
            if (ticket.isBlank()) {
                stage("Requesting download ticket")
                ticket = JSONObject(ExtensionSessions.signed("/tickets", JSONObject().put("capability", "download_ticket")
                    .put("provider", alias).put("resource_hash", ExtensionProtocol.hash("$alias:track:${id.lowercase()}"))))
                    .let { it.optString("ticket_id").ifBlank { it.optString("ticket") } }
                require(ticket.isNotBlank() && ticket.length <= 8192 && ticket.none(Char::isISOControl))
            }
            try {
                stage("Requesting Amazon audio")
                return media(ExtensionSessions.signed("/dl/$alias", JSONObject().put("asin", id).put("codec", "flac"), mapOf("X-Zarz-Ticket" to ticket)))
            } catch (e: LosslessHttp.HttpFailure) {
                val retry = retry(e, attempt) ?: throw e
                if (!retry.first) ticket = ""
                kotlinx.coroutines.delay(retry.second)
            }
        }
        return null
    }
    private suspend fun download(url: String, target: File, progress: (Long, Long) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val call = Http.client.newBuilder().callTimeout(300, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
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
                                progress(total, body.contentLength().coerceAtLeast(0))
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
    private suspend fun convert(input: File, output: File, key: String) = runFfmpeg(conversionArguments(input.path, output.path, key), output)
    private suspend fun runFfmpeg(arguments: Array<String>, output: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val session = FFmpegSession.create(arguments, { result ->
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
