package com.opentune.data.lossless

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class ProviderListing(val id: String, val name: String, val version: String,
    val description: String, val url: String, val sha256: String, val lossless: Boolean) {
    val supported get() = id in ProviderPackages.supported
    fun json() = JSONObject().put("id", id).put("display_name", name).put("version", version)
        .put("description", description).put("download_url", url).put("sha256", sha256)
        .put("tags", JSONArray(if (lossless) listOf("lossless") else emptyList<String>()))
}
internal data class ProviderPackage(val listing: ProviderListing, val manifest: JSONObject, val script: String) {
    val id get() = listing.id
    val name get() = listing.name
    val version get() = listing.version
    val sessionVersion get() = manifest.getJSONObject("signedSession").getString("appVersion")
    val hosts: List<String> get() = manifest.getJSONObject("permissions").getJSONArray("network").strings()
    val qualities: List<Pair<String, String>> get() = manifest.optJSONArray("qualityOptions")?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optString("kind") == "lossless" }
            .map { it.getString("id") to it.getString("label") }
    }.orEmpty()
}
internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** The installed, checksum-verified script is what the runtime executes. */
internal object ProviderPackages {
    const val DEFAULT_REPOSITORY = "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json"
    val supported = setOf("amazon", "qobuz-web", "tidal-web")
    private lateinit var root: File
    private val data = MutableStateFlow<Map<String, ProviderPackage>>(emptyMap())
    val installed = data.asStateFlow()
    private val registryData = MutableStateFlow<List<ProviderListing>>(emptyList())
    val listings = registryData.asStateFlow()
    fun init(context: Context) {
        root = File(context.noBackupFilesDir, "lossless-providers").apply { mkdirs() }
        registryData.value = runCatching { parseRegistry(File(root, "registry.json").readText()) }.getOrDefault(emptyList())
        data.value = supported.mapNotNull { id -> runCatching {
            val bundle = JSONObject(AtomicFile(File(root, "$id.package")).openRead().bufferedReader().use { it.readText() })
            val listing = parseListing(bundle.getJSONObject("listing"))
            id to inspect(java.util.Base64.getDecoder().decode(bundle.getString("archive")), listing)
        }.getOrNull() }.toMap()
    }
    fun get(id: String) = data.value[id]
    @Synchronized fun storage(id: String, key: String, value: Any? = null, writeValue: Boolean = false, remove: Boolean = false): Any? {
        require(id in supported && key.length <= 200)
        val path = File(root, "$id-storage.json")
        val json = runCatching { JSONObject(AtomicFile(path).openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
        if (writeValue || remove) {
            if (remove) json.remove(key) else json.put(key, value)
            val bytes = json.toString().toByteArray(); require(bytes.size <= 1_048_576)
            write(path, bytes)
        }
        return json.opt(key)
    }
    fun displayName(id: String) = get(id)?.name ?: when (id) { "amazon" -> "Amazon Music"; "qobuz-web" -> "Qobuz"; "tidal-web" -> "Tidal"; else -> id }
    fun repositoryUrl(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: error("Enter a repository URL.")
        require(url.isHttps && url.host == "raw.githubusercontent.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        val path = url.pathSegments
        require(path.size == 4 && path[0].lowercase() in setOf("spotiflacapp", "zarzet") && path[1].equals("spotiflac-extension", true) && path[2] == "main" && path[3] == "registry.json") { "Use the SpotiFLAC extension repository URL." }
        return url.toString()
    }
    fun parseListing(json: JSONObject): ProviderListing {
        val id = json.getString("id")
        require(Regex("[a-z0-9-]{1,64}").matches(id))
        val url = json.getString("download_url").toHttpUrlOrNull() ?: error("Invalid package URL")
        require(url.isHttps && url.host == "raw.githubusercontent.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        val path = url.pathSegments
        require(path.size == 5 && path[0].lowercase() in setOf("spotiflacapp", "zarzet") && path[1].equals("spotiflac-extension", true) && path[2] == "main" && path[3] == "extensions" && path[4].endsWith(".sflx"))
        val hash = json.getString("sha256").lowercase(); require(Regex("[a-f0-9]{64}").matches(hash))
        return ProviderListing(id, json.getString("display_name").take(100), json.getString("version").take(40), json.optString("description").take(1200), url.toString(), hash,
            json.optJSONArray("tags")?.strings()?.contains("lossless") == true)
    }
    fun parseRegistry(text: String): List<ProviderListing> {
        val root = JSONObject(text); require(root.optInt("version") == 1)
        val array = root.getJSONArray("extensions"); require(array.length() <= 100)
        val items = (0 until array.length()).map { parseListing(array.getJSONObject(it)) }
        require(items.distinctBy { it.id }.size == items.size); return items
    }
    suspend fun importRepository(url: String) = withContext(Dispatchers.IO) {
        val text = LosslessHttp.text(repositoryUrl(url), timeoutMs = 15000)
        val items = parseRegistry(text)
        write(File(root, "registry.json"), text.toByteArray()); registryData.value = items
    }
    suspend fun install(listing: ProviderListing): ProviderPackage = withContext(Dispatchers.IO) {
        require(listing.supported) { "This provider needs host features that OpenTune does not support yet." }
        val bytes = LosslessHttp.bytes(Request.Builder().url(listing.url).build(), 8 * 1024 * 1024, timeoutMs = 30000)
        val pkg = inspect(bytes, listing)
        // Verify/compile before replacing a working installation. No provider initialization/network here.
        ProviderRuntime.validate(pkg)
        synchronized(this@ProviderPackages) {
            val bundle = JSONObject().put("listing", listing.json()).put("archive", java.util.Base64.getEncoder().encodeToString(bytes))
            write(File(root, "${listing.id}.package"), bundle.toString().toByteArray())
            data.value = data.value + (listing.id to pkg)
        }
        pkg
    }
    @Synchronized fun uninstall(id: String) {
        require(id in supported)
        AtomicFile(File(root, "$id.package")).delete()
        data.value = data.value - id
    }
    fun inspect(bytes: ByteArray, listing: ProviderListing): ProviderPackage {
        require(bytes.size <= 8 * 1024 * 1024 && digest(bytes) == listing.sha256) { "Package checksum does not match the repository. Import the repository again." }
        val entries = mutableMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(entries.size < 64 && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it == "." } && name.none(Char::isISOControl) && name !in entries) { "Unsafe provider archive" }
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= 8 * 1024 * 1024) { "Provider archive is too large" }; out.write(buffer, 0, n) }
                entries[name] = out.toByteArray()
            }
        }
        val manifest = JSONObject(entries["manifest.json"]?.toString(Charsets.UTF_8) ?: error("Missing provider manifest"))
        val script = entries["index.js"]?.toString(Charsets.UTF_8) ?: error("Missing provider script")
        require(manifest.getString("name") == listing.id && manifest.getString("version") == listing.version) { "Package identity differs from the repository" }
        require(script.length in 1..4_000_000 && listing.id in supported)
        val features = manifest.optJSONArray("requiredRuntimeFeatures")?.strings().orEmpty()
        require(features.all { it in setOf("signedSession@1", "signedSession@2", "signedSession@3", "sessionGrant@1", "preparedContext@1", "downloadSegments@1") }) { "This package requires a newer OpenTune extension runtime." }
        val session = manifest.getJSONObject("signedSession")
        require(session.getString("baseUrl") == ExtensionProtocol.BASE && session.getString("platform") == "extension" && session.getString("schemeLabel") == "ZARZ-HMAC-V1" && session.getString("headerPrefix") == "X-Zarz-" && session.getInt("timeWindowSeconds") == 300)
        require(Regex("[a-z0-9.-]+@[0-9.]+").matches(session.getString("appVersion")))
        val pkg = ProviderPackage(listing, manifest, script)
        require(pkg.qualities.isNotEmpty() && pkg.hosts.size in 1..64)
        require(pkg.hosts.all { Regex("(?:\\*\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+").matches(it) })
        return pkg
    }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun write(path: File, bytes: ByteArray) {
        val atomic = AtomicFile(path); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}
