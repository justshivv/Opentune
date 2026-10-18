package com.opentune.data.lossless

import android.content.Context as AndroidContext
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.CoroutineContext

/** Synchronous SpotiFLAC provider API with no Java reflection, app files, or ambient credentials. */
internal object ProviderRuntime {
    private lateinit var prelude: String
    fun init(context: AndroidContext) { prelude = context.assets.open("lossless/runtime.js").bufferedReader().use { it.readText() } }
    internal class Abort : Error()
    internal fun evaluate(source: String, bootstrap: String, expression: String, timeoutMs: Long = 30000,
        cancelled: () -> Boolean = { false }, host: (String, JSONArray) -> Any? = { _, _ -> null }): Any? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
                instructionObserverThreshold = 1000
                maximumInterpreterStackDepth = 512
                setClassShutter { false }
            }
            override fun observeInstructionCount(cx: Context, count: Int) {
                if (cancelled() || System.nanoTime() > deadline) throw Abort()
            }
        }
        return factory.call { cx ->
            val scope = cx.initSafeStandardObjects()
            ScriptableObject.defineProperty(scope, "__host", object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, self: Scriptable, args: Array<out Any>): Any {
                    if (cancelled() || System.nanoTime() > deadline) throw Abort()
                    val op = Context.toString(args.getOrNull(0))
                    val input = Context.toString(args.getOrNull(1)); require(input.length <= 4_194_304)
                    val result = host(op, JSONArray(input))
                    return JSONObject.wrap(result).let { if (it == null || it == JSONObject.NULL) "null" else when (it) {
                        is JSONObject, is JSONArray, is Boolean, is Number -> it.toString()
                        else -> JSONObject.quote(it.toString())
                    } }
                }
            }, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
            cx.evaluateString(scope, bootstrap, "OpenTune host", 1, null)
            cx.evaluateString(scope, source, "Installed provider", 1, null)
            val result = Context.toString(cx.evaluateString(scope, expression, "Provider call", 1, null))
            require(result.length <= 4_194_304) { "Provider result is too large" }
            JSONTokener(result).nextValue()
        }
    }
    fun validate(pkg: ProviderPackage) {
        val ok = evaluate(pkg.script, prelude, "JSON.stringify(!!extension && typeof extension.download === 'function' && typeof extension.customSearch === 'function')", timeoutMs = 3000)
        require(ok == true) { "Provider is missing search or download functions." }
    }
    suspend fun search(provider: String, query: String, event: (String) -> Unit = {}): List<LosslessTrack> = withContext(Dispatchers.IO) {
        val pkg = ProviderPackages.get(provider) ?: throw LosslessFailure("Install this provider in the Providers tab first.")
        val invocation = Invocation(pkg, currentCoroutineContext(), null, { _, _, _ -> }, event, 45000)
        val result = invocation.call("customSearch", JSONArray().put(query.take(200)).put(JSONObject().put("filter", if (provider == "amazon") "songs" else "track").put("limit", 30)))
        val array = result as? JSONArray ?: throw LosslessFailure("Provider search returned an unsupported result.")
        if (array.length() == 0) invocation.failure?.let { throw LosslessFailure(it.summary() + ". " + it.guidance()) }
        (0 until minOf(array.length(), 100)).mapNotNull { parseTrack(array.optJSONObject(it), provider) }.distinctBy { it.key }
    }
    internal fun parseTrack(item: JSONObject?, provider: String): LosslessTrack? {
        item ?: return null
        val kind = item.optString("item_type").ifBlank { item.optString("type") }
        if (kind.isNotEmpty() && kind !in setOf("track", "song")) return null
        val id = item.optString("id").removePrefix("$provider:").removePrefix("${provider.removeSuffix("-web")}:")
        if (!Regex("[A-Za-z0-9_-]{1,100}").matches(id)) return null
        val title = item.optString("name").ifBlank { item.optString("title") }
        val artist = when (val value = item.opt("artists")) {
            is JSONArray -> (0 until value.length()).map { i -> value.optJSONObject(i)?.optString("name") ?: value.optString(i) }.joinToString(", ")
            is String -> value
            else -> item.optString("artist")
        }
        if (title.isBlank() || artist.isBlank()) return null
        return LosslessTrack(id, title.take(500), artist.take(500),
            item.optString("cover_url").ifBlank { item.optString("thumbnail_url") }.takeIf { it.startsWith("https://") },
            item.optLong("duration_ms").coerceIn(0, 86_400_000), provider)
    }
    suspend fun download(track: LosslessTrack, quality: String, folder: File,
        progress: (LosslessState, Long, Long) -> Unit, event: (String) -> Unit): LosslessSource.FlacInfo = withContext(Dispatchers.IO) {
        withTimeout(8 * 60_000L) {
        val pkg = ProviderPackages.get(track.provider) ?: throw LosslessFailure("Install ${ProviderPackages.displayName(track.provider)} in Providers, then retry.")
        require(pkg.qualities.any { it.first == quality }) { "Choose an available lossless quality" }
        require(folder.usableSpace >= 1024L * 1024 * 1024) { "At least 1 GB free space is needed" }
        event("Checking local audio engine")
        event("Audio engine ready · FFmpeg ${AmazonAudio.checkEngine()}")
        val invocation = Invocation(pkg, currentCoroutineContext(), folder, progress, event, 8 * 60_000)
        progress(LosslessState.REQUESTING, 0, 0)
        event("Running ${pkg.name} ${pkg.version} · ${pkg.listing.sha256.take(12)}")
        val result = invocation.call("download", JSONArray().put(track.id).put(quality).put(File(folder, "provider.flac").path).put(JSONObject())) as? JSONObject
            ?: throw LosslessFailure("Provider download returned an unsupported result.")
        if (!result.optBoolean("success")) {
            invocation.failure?.let { throw LosslessFailure(it.summary() + ". " + it.guidance()) }
            val type = result.optString("error_type")
            throw LosslessFailure(when (type) {
                "verification_required" -> "Verify this provider in Manage connection, then resume."
                "not_found", "unavailable" -> "This recording is unavailable from the selected provider."
                "duration_mismatch" -> "The provider returned an incomplete or different recording."
                "rate_limited" -> "The provider is rate limiting downloads. Wait before retrying."
                else -> "The installed provider could not complete this download. Open Details for the request stages."
            })
        }
        val duration = track.durationMs.takeIf { it > 0 } ?: 1000L
        if (!CatalogIdentity(track.title, track.artist, null, duration).matches(
                result.optString("title").ifBlank { track.title }, listOf(result.optString("artist").ifBlank { track.artist }), null, duration))
            throw LosslessFailure("The downloaded audio does not match the selected recording.")
        val input = invocation.file(result.optString("file_path"))
        val key = result.optJSONObject("decryption")?.optString("key").orEmpty().ifBlank { result.optString("decryption_key") }
        require(key.isEmpty() || Regex("[a-fA-F0-9]{32}").matches(key))
        val info = AmazonAudio.finishDownloadedFile(input, File(folder, "audio.flac"), key, track.durationMs, progress)
        val cover = result.optString("cover_url").ifBlank { track.artwork.orEmpty() }
        if (cover.isNotBlank()) runCatching {
            invocation.network.request("GET", cover, "", emptyMap(), File(folder, "cover.jpg"), remainingBytes = 4_194_304)
        }.onFailure { if (it is CancellationException) throw it; File(folder, "cover.jpg").delete() }
        info
        }
    }
    private class Invocation(val pkg: ProviderPackage, val parent: CoroutineContext, val folder: File?,
        val progress: (LosslessState, Long, Long) -> Unit, val event: (String) -> Unit, val timeoutMs: Long) {
        val network = ProviderNetwork(pkg.hosts)
        private val start = System.nanoTime()
        private var transferred = 0L
        var failure: ProviderDiagnostic? = null
        fun file(path: String): File {
            val root = folder?.canonicalFile ?: throw LosslessFailure("File access is unavailable during search.")
            val target = File(path).canonicalFile
            require(target.parentFile == root && Regex("[A-Za-z0-9._-]{1,120}").matches(target.name)) { "Provider file path is outside its download folder" }
            return target
        }
        fun call(function: String, args: JSONArray): Any? {
            val settings = JSONObject()
            pkg.manifest.optJSONArray("settings")?.let { array -> for (i in 0 until array.length()) { val entry = array.getJSONObject(i); settings.put(entry.getString("key"), entry.opt("default")) } }
            val script = if (function == "download") "JSON.stringify(extension.download(__args[0],__args[1],__args[2],function(){},__args[3]))" else "JSON.stringify(extension.customSearch(__args[0],__args[1]))"
            val expression = "var __args = ${args}; if (extension.initialize) extension.initialize(${settings}); $script"
            try { return evaluate(pkg.script, prelude, expression, timeoutMs, { !parent.isActive }, ::host) }
            catch (e: Abort) { parent.ensureActive(); throw LosslessFailure("Provider operation timed out. Retry or choose another provider.") }
            catch (e: CancellationException) { throw e }
            catch (e: org.mozilla.javascript.RhinoException) { throw LosslessFailure("Provider runtime failed during $function at line ${e.lineNumber()}. Reinstall or update the provider.") }
        }
        private fun headers(value: JSONObject?): Map<String, String> = value?.keys()?.asSequence()?.associateWith { value.getString(it) }.orEmpty()
        private fun record(response: JSONObject, label: String) {
            val status = response.optInt("statusCode")
            if (status !in 200..299) {
                failure = ProviderDiagnostic.from(LosslessHttp.HttpFailure(status, response.optString("body")))
                event("$label → ${failure!!.summary()}")
            } else event("$label → HTTP $status")
        }
        private fun transfer(url: String, target: File, headers: Map<String, String>, append: Boolean): JSONObject {
            val remaining = 512L * 1024 * 1024 - transferred
            require(remaining > 0)
            val response = runBlocking(parent) { network.request("GET", url, "", headers, target, append, remaining, progress = { bytes, total ->
                progress(LosslessState.DOWNLOADING, if (append) transferred + bytes else bytes, if (append) 0 else total)
            }) }
            record(response, "Audio transfer")
            transferred += response.optLong("bytes")
            if (!response.optBoolean("ok")) return JSONObject().put("success", false).put("error", "HTTP ${response.optInt("statusCode")}")
                .put("error_type", if (response.optInt("statusCode") in setOf(401, 403)) "expired_stream" else "download_error")
            return response
        }
        private fun host(op: String, args: JSONArray): Any? {
            parent.ensureActive()
            return when (op) {
                "http" -> {
                    val url = network.validate(args.getString(1))
                    val response = runBlocking(parent) { network.request(args.getString(0), url.toString(), args.getString(2), headers(args.optJSONObject(3))) }
                    record(response, "Catalog · ${url.host}"); response
                }
                "signed" -> {
                    val method = args.getString(0); val path = args.getString(1)
                    val label = if (path == "/tickets") "Download ticket" else "Audio request"
                    event("$label · requesting")
                    try {
                        val body = runBlocking(parent) { ExtensionSessions.forProvider(pkg.id).signedRaw(method, path, args.getString(2), headers(args.optJSONObject(3))) }
                        failure = null; event("$label → HTTP 200")
                        JSONObject().put("ok", true).put("statusCode", 200).put("status", 200).put("body", body)
                    } catch (e: LosslessHttp.HttpFailure) {
                        val d = ProviderDiagnostic.from(e); failure = d; event("$label → ${d.summary()}")
                        val contract = runCatching { JSONObject(e.responseBody) }.getOrDefault(JSONObject())
                        JSONObject().put("ok", false).put("statusCode", e.code).put("status", e.code).put("body", e.responseBody)
                            .put("error", d.summary()).put("code", d.code).put("origin", d.origin).put("action", d.action)
                            .put("needsVerification", ExtensionProtocol.verificationRequired(e.code, e.responseBody))
                            .put("retryable", contract.optBoolean("retryable")).put("retryMode", contract.optString("retry_mode"))
                            .put("retryAfterSeconds", e.retryAfterSeconds ?: contract.optLong("retry_after_seconds"))
                    }
                }
                "sessionStatus" -> JSONObject().put("authenticated", ExtensionSessions.forProvider(pkg.id).current() != null)
                "download" -> transfer(args.getString(0), file(args.getString(1)), headers(args.optJSONObject(2)), false)
                "segments" -> {
                    val items = args.getJSONArray(0); require(items.length() in 1..4096)
                    val target = file(args.getString(1)); target.delete(); var result = JSONObject()
                    for (i in 0 until items.length()) {
                        val entry = items.optJSONObject(i)
                        val url = entry?.getString("url") ?: items.getString(i)
                        result = transfer(url, target, headers(args.optJSONObject(2)) + headers(entry?.optJSONObject("headers")), i > 0)
                        if (!result.optBoolean("success")) break
                    }
                    result
                }
                "exists" -> file(args.getString(0)).isFile
                "delete" -> file(args.getString(0)).delete()
                "quality" -> AmazonAudio.readInfo(file(args.getString(0)))?.let { JSONObject().put("codec", "flac").put("bitDepth", it.bits).put("sampleRate", it.sampleRate).put("duration", it.durationMs / 1000.0) }
                "userAgent" -> "SpotiFLAC-Mobile/${pkg.sessionVersion}"
                "url" -> {
                    val url = args.getString(0).toHttpUrlOrNull() ?: args.optString(1).toHttpUrlOrNull()?.resolve(args.getString(0)) ?: error("Invalid URL")
                    val pairs = JSONObject(); url.queryParameterNames.forEach { pairs.put(it, url.queryParameter(it)) }
                    JSONObject().put("href", url.toString()).put("origin", "${url.scheme}://${url.host}" + if (url.port != 443 && url.port != 80) ":${url.port}" else "")
                        .put("protocol", "${url.scheme}:").put("host", url.host).put("hostname", url.host).put("pathname", url.encodedPath)
                        .put("search", url.encodedQuery?.let { "?$it" }.orEmpty()).put("hash", url.encodedFragment?.let { "#$it" }.orEmpty()).put("parameters", pairs)
                }
                "sha256", "md5" -> MessageDigest.getInstance(if (op == "md5") "MD5" else "SHA-256").digest(args.getString(0).toByteArray()).joinToString("") { "%02x".format(it) }
                "base64Decode" -> String(Base64.getDecoder().decode(args.getString(0)), Charsets.UTF_8)
                "base64Encode" -> Base64.getEncoder().encodeToString(args.getString(0).toByteArray())
                "sleep" -> { runBlocking(parent) { delay(args.optLong(0).coerceIn(0, 30000)) }; true }
                "cancelled" -> !parent.isActive
                "remaining" -> (timeoutMs - (System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
                "storageGet", "storageSet", "storageRemove" -> ProviderPackages.storage(pkg.id, args.getString(0), args.opt(1), op == "storageSet", op == "storageRemove")
                else -> throw LosslessFailure("This provider requested an unsupported host function.")
            }
        }
    }
}
