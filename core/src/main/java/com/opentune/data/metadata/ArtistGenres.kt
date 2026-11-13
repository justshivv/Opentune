package com.opentune.data.metadata

import android.content.Context
import android.content.SharedPreferences
import com.opentune.data.lossless.LosslessHttp
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Each artist's main genre, as Apple's catalogue files them, for Home's genre mixes. Asked for
 * a few artists at a time, the most played first, and kept on the phone: a genre for four
 * months, an artist Apple doesn't know for two weeks before asking again. Only an exact name
 * match counts; a near one could be another artist.
 */
object ArtistGenres {
    private const val KEEP_FOUND_MS = 120L * 24 * 60 * 60 * 1000
    private const val KEEP_MISS_MS = 14L * 24 * 60 * 60 * 1000
    private var prefs: SharedPreferences? = null
    private val found = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Every artist with a genre, by [key]. */
    val genres: StateFlow<Map<String, String>> = found.asStateFlow()
    private val learning = AtomicBoolean(false)

    fun init(context: Context) {
        val p = context.getSharedPreferences("artist_genres", Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        val kept = mutableMapOf<String, String>()
        val stale = mutableListOf<String>()
        p.all.forEach { (k, v) ->
            val (at, genre) = (v as? String)?.let(::parse) ?: run { stale += k; return@forEach }
            val keep = if (genre.isEmpty()) KEEP_MISS_MS else KEEP_FOUND_MS
            if (now - at >= keep) stale += k else if (genre.isNotEmpty()) kept[k] = genre
        }
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach(::remove) }.apply()
        found.value = kept
    }

    /** The genre filed for [artist], if it's been looked up and Apple knows it. */
    fun genreOf(artist: String): String? = found.value[key(artist)]

    /**
     * Looks up the genres not known yet for [artists], in that order, at most [max] of them
     * this time. Stops at the first failed request, to carry on another time.
     */
    suspend fun learn(artists: List<String>, max: Int = 25) {
        if (!learning.compareAndSet(false, true)) return
        try {
            var asked = 0
            for (artist in artists.distinctBy(::key)) {
                if (asked >= max) break
                val k = key(artist)
                if (k.isEmpty() || k in found.value || prefs?.contains(k) == true) continue
                asked++
                val genre = try {
                    val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                        .addQueryParameter("term", artist).addQueryParameter("media", "music")
                        .addQueryParameter("entity", "musicArtist").addQueryParameter("limit", "10")
                        .addQueryParameter("country", Locale.getDefault().country.takeIf { it.length == 2 } ?: "US").build()
                    pick(Json.parseToJsonElement(AppleSearchGate.paced { LosslessHttp.text(url.toString()) }).jsonObject, artist)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
                prefs?.edit()?.putString(k, "${System.currentTimeMillis()}|${genre.orEmpty()}")?.apply()
                if (genre != null) found.value = found.value + (k to genre)
            }
        } finally {
            learning.set(false)
        }
    }

    /** The genre of the result whose name is [artist]'s exactly, tidied for showing. */
    internal fun pick(response: JsonObject, artist: String): String? {
        val wanted = key(artist)
        return (response["results"] as? JsonArray).orEmpty().firstNotNullOfOrNull { item ->
            val row = item as? JsonObject ?: return@firstNotNullOfOrNull null
            val name = (row["artistName"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
            if (key(name) != wanted) return@firstNotNullOfOrNull null
            (row["primaryGenreName"] as? JsonPrimitive)?.contentOrNull?.let(::display)
        }
    }

    /** Apple's genre names, a few made shorter to read as a mix's name. */
    internal fun display(genre: String): String? = when (val g = genre.trim()) {
        "", "Music" -> null
        "Hip-Hop/Rap" -> "Hip-Hop"
        "R&B/Soul" -> "R&B"
        "Singer/Songwriter" -> "Singer-songwriter"
        "Devotional & Spiritual" -> "Devotional"
        "Soundtrack" -> "Film & Soundtrack"
        else -> g
    }

    /** One spelling of a name: case, accents, spacing and punctuation don't make another artist. */
    fun key(artist: String): String =
        Normalizer.normalize(artist, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun parse(value: String): Pair<Long, String>? {
        val bar = value.indexOf('|')
        if (bar <= 0) return null
        val at = value.substring(0, bar).toLongOrNull() ?: return null
        return at to value.substring(bar + 1)
    }
}
