package com.opentune.data.lyrics

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Lyrics translated line by line through Google Translate's free endpoint —
 * no key, no account. Each line is translated on its own terms so the words
 * stay with their timing: the answer for a line is shown under that line.
 * Lines go in small batches (joined by newlines, which the endpoint keeps);
 * a batch whose answer doesn't line up falls back to one request per line.
 * A finished translation is kept, in memory and on disk, so a song opens
 * already translated next time, offline included.
 */
object LyricsTranslator {
    private const val TAG = "LyricsTranslator"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    /** Roughly what one request comfortably carries. */
    private const val CHUNK_CHARS = 900
    private const val MAX_DISK_ENTRIES = 60

    data class Language(val code: String, val name: String)

    /** Google Translate's codes; the names are the platform's own, so they never need writing out. */
    private const val LANGUAGE_CODES =
        "ar,en,fr,es,de,it,pt,ru,tr,fa,ur,hi,bn,pa,ta,te,ml,kn,mr,gu,ne,si,my,km,lo,th," +
                "vi,id,ms,tl,zh-CN,zh-TW,ja,ko,nl,sv,no,da,fi,pl,cs,sk,hu,ro,bg,hr,sr,sl,uk,be,el,he," +
                "am,sw,ha,yo,ig,so,af,ca,eu,gl,is,ga,lt,lv,et,mk,sq,az,ka,hy,kk,uz,mn,yi,la,eo"

    /** The names Google uses, where the platform's own name for a code differs. */
    private val NAME_OVERRIDES = mapOf(
        "bn" to "Bengali",
        "tl" to "Filipino",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
    )

    /** Every language the lyrics can be translated into, A to Z by name. */
    val LANGUAGES: List<Language> = LANGUAGE_CODES.split(",")
        .map { code ->
            val name = NAME_OVERRIDES[code]
                ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
            Language(code, name)
        }
        .sortedBy { it.name }

    fun languageName(code: String): String? = LANGUAGES.firstOrNull { it.code == code }?.name

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, List<String?>>()
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.cacheDir, "lyrics-translations")
    }

    /**
     * Every line translated into [target] (a code from [LANGUAGES]), in the
     * lines' own order; null where a line is blank or couldn't be carried.
     * Fails only when nothing at all could be translated.
     */
    suspend fun translate(lines: List<String>, target: String): Result<List<String?>> = withContext(Dispatchers.IO) {
        runCatching {
            if (lines.all { it.isBlank() }) return@runCatching lines.map { null }
            val key = key(lines, target)
            memory[key]?.let { return@runCatching it }
            readDisk(key)?.let { disk ->
                memory[key] = disk
                return@runCatching disk
            }
            val out = arrayOfNulls<String>(lines.size)
            val work = lines.indices.filter { lines[it].isNotBlank() }
            var i = 0
            while (i < work.size) {
                val chunk = mutableListOf<Int>()
                var len = 0
                while (i < work.size && (chunk.isEmpty() || len + lines[work[i]].length + 1 <= CHUNK_CHARS)) {
                    len += lines[work[i]].length + 1
                    chunk += work[i]
                    i++
                }
                val texts = chunk.map { lines[it] }
                val got = fetchChunk(texts, target) ?: fetchPerLine(texts, target)
                ?: throw java.io.IOException("Google Translate couldn't be reached")
                chunk.forEachIndexed { j, at -> out[at] = got.getOrNull(j) }
            }
            val done = out.toList()
            memory[key] = done
            writeDisk(key, done)
            done
        }.onFailure { Log.w(TAG, "couldn't translate into $target", it) }
    }

    /** One request for the whole batch; null when the answer's lines don't line up with the batch's. */
    private fun fetchChunk(texts: List<String>, target: String): List<String?>? {
        val joined = fetch(texts.joinToString("\n"), target) ?: return null
        val parts = joined.split("\n")
        if (parts.size != texts.size) return null
        return parts.map { it.trim().ifBlank { null } }
    }

    /** One request per line; null only when every single one failed. */
    private fun fetchPerLine(texts: List<String>, target: String): List<String?>? {
        val got = texts.map { fetch(it, target)?.trim()?.ifBlank { null } }
        return if (got.all { it == null }) null else got
    }

    /** The translated text for [text], lines and all; null on any failure. */
    private fun fetch(text: String, target: String): String? = runCatching {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            val body = r.body?.string() ?: return@runCatching null
            val root = json.parseToJsonElement(body) as? JsonArray ?: return@runCatching null
            val segments = root.getOrNull(0) as? JsonArray ?: return@runCatching null
            segments.joinToString("") { seg ->
                ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
        }
    }.getOrNull()

    private fun key(lines: List<String>, target: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((target + "\n" + lines.joinToString("\n")).toByteArray(Charsets.UTF_8))
        return target + "-" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun readDisk(key: String): List<String?>? {
        val f = dir?.let { File(it, "$key.json") }?.takeIf { it.isFile } ?: return null
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), f.readText()).map { it.ifBlank { null } }
        }.getOrNull()
    }

    private fun writeDisk(key: String, lines: List<String?>) {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            File(d, "$key.json").writeText(json.encodeToString(ListSerializer(String.serializer()), lines.map { it.orEmpty() }))
            // Keep the newest translations; the rest go.
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_DISK_ENTRIES)?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "couldn't keep the translation", it) }
    }
}
