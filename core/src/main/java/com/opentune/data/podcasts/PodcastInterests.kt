package com.opentune.data.podcasts

import java.text.Normalizer
import kotlin.math.pow

/**
 * What the listener is into, from everything they've listened to on the phone: each
 * episode counts for the time spent in it, less the longer ago that was. From it come
 * the shows they listen to most and the topics their listening falls under, for the
 * chips under the podcast search.
 */
object PodcastInterests {
    /** A listen loses half its weight in this many days. */
    private const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0
    /** A subscribed show counts as this much listening even when none was. */
    private const val SUBSCRIBED_MS = 10 * 60_000.0

    /** How much one episode counts: the time listened, halved every [HALF_LIFE_DAYS] since. */
    internal fun weight(p: Podcasts.Progress, now: Long): Double {
        val heard = if (p.finished && p.durationMs > 0) p.durationMs else p.positionMs
        val age = ((now - p.updatedAt).coerceAtLeast(0)) / DAY_MS
        return heard.coerceAtLeast(60_000L) * 0.5.pow(age / HALF_LIFE_DAYS)
    }

    /** The shows listened to most (and the subscribed ones), most first. */
    fun topShows(progress: Collection<Podcasts.Progress>, subscribed: List<PodcastShow>, now: Long = System.currentTimeMillis(), limit: Int = 4): List<PodcastShow> {
        val score = HashMap<String, Double>()
        val shows = LinkedHashMap<String, PodcastShow>()
        subscribed.forEach { s ->
            shows[s.browseId] = s
            score[s.browseId] = (score[s.browseId] ?: 0.0) + SUBSCRIBED_MS
        }
        progress.forEach { p ->
            val id = p.episode.showBrowseId ?: return@forEach
            shows.getOrPut(id) { PodcastShow(id, p.episode.showTitle, thumbnailUrl = p.episode.thumbnailUrl) }
            score[id] = (score[id] ?: 0.0) + weight(p, now)
        }
        return shows.values.filter { it.title.isNotBlank() }.sortedByDescending { score[it.browseId] ?: 0.0 }.take(limit)
    }

    /**
     * How much of the listening falls under each topic, by the words in what was listened to
     * (episode and show titles, Czech and English); topics nothing fell under are left out.
     */
    fun topicScores(progress: Collection<Podcasts.Progress>, now: Long = System.currentTimeMillis()): Map<String, Double> {
        val out = HashMap<String, Double>()
        progress.forEach { p ->
            val words = words("${p.episode.title} ${p.episode.showTitle}")
            val w = weight(p, now)
            KEYWORDS.forEach { (topic, keys) -> if (keys.any { k -> matches(k, words) }) out[topic] = (out[topic] ?: 0.0) + w }
        }
        return out
    }

    /**
     * The topics, the listener's first: those picked from the chips ([picked], each pick a
     * point) and those their listening falls under ([scores], the strongest worth three
     * points); the rest keep their order.
     */
    fun topicsInOrder(picked: Map<String, Int>, scores: Map<String, Double>): List<String> {
        val top = scores.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        fun points(t: String) = (picked[t] ?: 0) + 3 * (scores[t] ?: 0.0) / top
        return Podcasts.TOPICS.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { points(it.value) }.thenBy { it.index }).map { it.value }
    }

    /** The topics the listener is into, most first. */
    fun favouriteTopics(picked: Map<String, Int>, scores: Map<String, Double>): List<String> =
        topicsInOrder(picked, scores).filter { (picked[it] ?: 0) > 0 || (scores[it] ?: 0.0) > 0 }

    /** Minutes in YouTube Music's "1 hr 3 min", "25 min" or "45 sec"; null when it doesn't say. */
    fun minutes(durationText: String?): Int? {
        val t = durationText?.lowercase() ?: return null
        val hr = Regex("""(\d+)\s*(?:hr|hour|h\b)""").find(t)?.groupValues?.get(1)?.toInt() ?: 0
        val min = Regex("""(\d+)\s*(?:min|m\b)""").find(t)?.groupValues?.get(1)?.toInt() ?: 0
        val sec = Regex("""(\d+)\s*(?:sec|s\b)""").find(t)?.groupValues?.get(1)?.toInt() ?: 0
        if (hr == 0 && min == 0 && sec == 0) return null
        return hr * 60 + min + (if (sec > 0 && hr == 0 && min == 0) 1 else 0)
    }

    private fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** "=x" is the word x itself; anything else, a word's beginning. */
    private fun matches(key: String, words: List<String>) =
        if (key.startsWith("=")) words.any { it == key.substring(1) } else words.any { it.startsWith(key) }

    /** Words (without accents) that put an episode under a topic, Czech and English. */
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "News" to listOf("news", "politi", "zprav", "volb", "elect", "vlad", "parlament", "minist", "babis", "fiala", "trump", "putin", "ukrajin", "ukrain", "valk", "=war", "president", "prezident"),
        "Comedy" to listOf("comedy", "komedi", "standup", "humor", "vtip", "funny", "smich"),
        "True crime" to listOf("crime", "krimi", "zlocin", "vrazd", "murder", "scam", "podvod", "swindl", "heist", "killer", "vrah"),
        "Sports" to listOf("sport", "fotbal", "football", "soccer", "hokej", "hockey", "=nba", "=nfl", "tenis", "tennis", "olymp", "zapas"),
        "Business" to listOf("business", "byznys", "podnik", "invest", "money", "penize", "financ", "startup", "ekonom", "econom", "=ceo"),
        "Technology" to listOf("tech", "hack", "cyber", "kyber", "darknet", "spyware", "malware", "software", "comput", "pocitac", "internet", "crypto", "krypto", "robot", "=ai", "gadget"),
        "Health" to listOf("health", "zdrav", "fyzio", "medic", "lekar", "doctor", "nutri", "vyziv", "fitness", "sleep", "spank", "stres", "mental", "psych", "nemoc", "disease"),
        "Science" to listOf("scien", "vedec", "=veda", "physic", "fyzik", "biolog", "chemi", "astro", "vesmir", "=space", "neuro"),
        "History" to listOf("histor", "dejin", "starovek", "ancient", "empire", "medieval", "stredovek"),
        "Society & culture" to listOf("kultur", "cultur", "societ", "spolecn", "relig", "nabozen", "filozof", "philosoph"),
        "Education" to listOf("educa", "vzdelav", "skol", "school", "learn", "kurz", "course", "language", "jazyk", "lesson", "lekce"),
        "Self-improvement" to listOf("motiv", "produktiv", "productiv", "habit", "navyk", "mindset", "success", "uspech", "rozvoj"),
        "Music" to listOf("music", "hudb", "album", "kapel", "=band", "=rap", "rapper", "song", "pisn"),
        "TV & film" to listOf("film", "movie", "serial", "netflix", "kino", "cinema", "hollywood"),
        "Gaming" to listOf("gaming", "=game", "=games", "=hra", "=hry", "xbox", "playstation", "nintendo", "esport"),
        "Kids & family" to listOf("=kids", "=deti", "rodic", "parent", "family", "rodin", "pohad"),
    )
}
