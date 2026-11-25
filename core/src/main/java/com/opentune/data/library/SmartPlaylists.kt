package com.opentune.data.library

import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.history.PlayRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/** Where a smart playlist's songs come from before its rules narrow them down. */
@Serializable
enum class SmartSource {
    /** Everything this phone knows: songs played, liked, downloaded or in a playlist made here. */
    EVERYTHING,
    /** Songs played at least once. */
    HISTORY,
    LIKED,
    /** Finished downloads. */
    DOWNLOADS,
}

@Serializable
enum class SmartSort {
    MOST_PLAYED,
    RECENTLY_PLAYED,
    /** The longest since it was last played first; never played before any of them. */
    LONGEST_UNPLAYED,
    /** The songs that came to this phone most recently first. */
    NEWEST,
    TITLE,
    ARTIST,
    /** A new order every day, kept for the whole day. */
    SHUFFLE,
}

/** Whether a song has to meet every rule of a smart playlist, or any one of them. */
@Serializable
enum class SmartMatch { ALL, ANY }

/** One thing a smart playlist asks of a song. */
@Serializable
sealed interface SmartRule {
    /** Played at least [atLeast] times: within the last [withinDays] days, or ever when that is null. */
    @Serializable @SerialName("plays")
    data class Plays(val atLeast: Int = 1, val withinDays: Int? = null) : SmartRule

    /** Not played at all for [days] days (songs never played count as not played). */
    @Serializable @SerialName("not_played")
    data class NotPlayedFor(val days: Int) : SmartRule

    /** First played or downloaded within the last [days] days. */
    @Serializable @SerialName("new")
    data class NewWithin(val days: Int) : SmartRule

    @Serializable @SerialName("liked")
    data object Liked : SmartRule

    /** A finished download. */
    @Serializable @SerialName("downloaded")
    data object Downloaded : SmartRule

    /** Downloaded from a lossless source. */
    @Serializable @SerialName("lossless")
    data object Lossless : SmartRule

    /** The artist contains [name], ignoring case and accents. */
    @Serializable @SerialName("artist")
    data class Artist(val name: String) : SmartRule
}

/**
 * What a smart playlist holds: the songs [from] a source that meet [conditions] (all of them,
 * or any one, as [match] says; with none, every song from the source), in [sort] order, at
 * most [limit] of them.
 *
 * Saved playlists from before the rules were a list are read through [SmartRulesSerializer].
 */
@Serializable
data class SmartRules(
    val from: SmartSource = SmartSource.EVERYTHING,
    val match: SmartMatch = SmartMatch.ALL,
    val conditions: List<SmartRule> = emptyList(),
    val sort: SmartSort = SmartSort.MOST_PLAYED,
    /** At most this many songs; null for all of them. */
    val limit: Int? = 50,
) {
    /**
     * Rules no song can meet, whatever is played: every rule must hold, and one asks for
     * plays within some days while another asks for none over as many days or more.
     */
    val contradictory: Boolean
        get() = match == SmartMatch.ALL && conditions.any { p ->
            p is SmartRule.Plays && p.withinDays != null &&
                conditions.any { n -> n is SmartRule.NotPlayedFor && n.days >= p.withinDays }
        }
}

/**
 * The rules as they were saved before they became a list: one of each kind, all of them
 * holding. Used to read those saved playlists, and by tests written against that shape.
 */
internal fun legacyRules(
    from: SmartSource = SmartSource.EVERYTHING,
    minPlays: Int = 0,
    playedWithinDays: Int? = null,
    notPlayedForDays: Int? = null,
    newWithinDays: Int? = null,
    likedOnly: Boolean = false,
    downloadedOnly: Boolean = false,
    losslessOnly: Boolean = false,
    artist: String = "",
    sort: SmartSort = SmartSort.MOST_PLAYED,
    limit: Int? = 50,
): SmartRules = SmartRules(
    from = from,
    match = SmartMatch.ALL,
    conditions = buildList {
        // "Played within" alone meant played at least once in those days.
        if (minPlays > 0 || playedWithinDays != null) add(SmartRule.Plays(maxOf(minPlays, 1), playedWithinDays))
        notPlayedForDays?.let { add(SmartRule.NotPlayedFor(it)) }
        newWithinDays?.let { add(SmartRule.NewWithin(it)) }
        if (likedOnly) add(SmartRule.Liked)
        if (downloadedOnly) add(SmartRule.Downloaded)
        if (losslessOnly) add(SmartRule.Lossless)
        if (artist.isNotBlank()) add(SmartRule.Artist(artist.trim()))
    },
    sort = sort,
    limit = limit,
)

/**
 * Reads smart playlist rules in either shape: the list of rules saved now, or the one field
 * per kind of rule saved before (minPlays, playedWithinDays, likedOnly and so on), which is
 * turned into the same rules. Always writes the list.
 */
object SmartRulesSerializer : JsonTransformingSerializer<SmartRules>(SmartRules.serializer()) {
    private val LEGACY = setOf(
        "minPlays", "playedWithinDays", "notPlayedForDays", "newWithinDays",
        "likedOnly", "downloadedOnly", "losslessOnly", "artist",
    )

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject || "conditions" in element || element.keys.none { it in LEGACY }) return element
        fun int(key: String) = (element[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.intOrNull
        fun bool(key: String) = (element[key] as? JsonPrimitive)?.booleanOrNull ?: false
        val old = legacyRules(
            minPlays = int("minPlays") ?: 0,
            playedWithinDays = int("playedWithinDays"),
            notPlayedForDays = int("notPlayedForDays"),
            newWithinDays = int("newWithinDays"),
            likedOnly = bool("likedOnly"),
            downloadedOnly = bool("downloadedOnly"),
            losslessOnly = bool("losslessOnly"),
            artist = (element["artist"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
        val conditions = Json.encodeToJsonElement(ListSerializer(SmartRule.serializer()), old.conditions)
        return JsonObject(element.filterKeys { it !in LEGACY } + ("conditions" to conditions))
    }
}

@Serializable
data class SmartPlaylist(
    val id: String,
    val name: String,
    @Serializable(with = SmartRulesSerializer::class)
    val rules: SmartRules = SmartRules(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Smart playlists: lists that fill themselves from the rules they were given,
 * worked out on this phone from its history, likes and downloads every time
 * they are shown, so they change as the listening does.
 */
object SmartPlaylists {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** What a smart playlist is worked out from. */
    data class Facts(
        /** Newest first, as [com.opentune.data.history.History.records] keeps it. */
        val history: List<PlayRecord>,
        val liked: List<SongRef>,
        val downloads: Map<String, DownloadEntry>,
        val playlists: List<LocalPlaylist> = emptyList(),
    )

    /** Ready-made rules to start from, with the name each is offered under. */
    val presets: List<Pair<String, SmartRules>> = listOf(
        "On repeat" to SmartRules(SmartSource.HISTORY, conditions = listOf(SmartRule.Plays(3, 30)), sort = SmartSort.MOST_PLAYED, limit = 50),
        "Forgotten favourites" to SmartRules(SmartSource.LIKED, conditions = listOf(SmartRule.NotPlayedFor(90)), sort = SmartSort.LONGEST_UNPLAYED, limit = 50),
        "New to you" to SmartRules(SmartSource.HISTORY, conditions = listOf(SmartRule.NewWithin(30)), sort = SmartSort.NEWEST, limit = 100),
        "All-time top 100" to SmartRules(SmartSource.HISTORY, sort = SmartSort.MOST_PLAYED, limit = 100),
        "Lossless on this phone" to SmartRules(SmartSource.DOWNLOADS, conditions = listOf(SmartRule.Lossless), sort = SmartSort.ARTIST, limit = null),
        "Liked this week" to SmartRules(SmartSource.LIKED, conditions = listOf(SmartRule.Plays(1, 7)), sort = SmartSort.RECENTLY_PLAYED, limit = 50),
    )

    /** The artists this phone knows, most played first, for picking one in a rule. */
    fun artists(facts: Facts, max: Int = 40): List<String> {
        val count = LinkedHashMap<String, Int>()
        fun add(line: String, weight: Int) = line.split(", ", " & ", " x ", " feat. ", " ft. ").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { count[it] = (count[it] ?: 0) + weight }
        facts.history.forEach { add(it.artist, 2) }
        facts.liked.forEach { add(it.artist, 1) }
        facts.downloads.values.forEach { if (it.state == DownloadState.DONE) add(it.song.artist, 1) }
        return count.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    private class Known(val ref: SongRef) {
        /** When it was played, newest first (as the history is kept). */
        val played = ArrayList<Long>(2)
        var lastPlayed = 0L
        var firstSeen = Long.MAX_VALUE

        /** How many times it was played since [since]; ever when that is null. */
        fun playsSince(since: Long?): Int = if (since == null) played.size else played.count { it >= since }
    }

    // Only the accents Latin, Greek and Cyrillic letters take (Combining Diacritical Marks, spelt
    // out: Android's regex has no block names): the vowel signs of Devanagari or Gurmukhi are
    // marks too, but part of the word, and stay.
    private val MARKS = Regex("[\\u0300-\\u036F]+")

    /** [text] lowercased with its accents taken off, so "Beyoncé" and "BEYONCE" read alike. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)

    /**
     * The songs [rules] pick out of [facts] at [now], in the order they ask for.
     * A shuffle keeps its order through the day as the clock in [zone] has it.
     */
    fun songs(
        rules: SmartRules,
        facts: Facts,
        now: Long = System.currentTimeMillis(),
        seed: Long = 0,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<SongRef> {
        val known = LinkedHashMap<String, Known>()
        fun know(ref: SongRef) = known.getOrPut(ref.videoId) { Known(ref.copy(setVideoId = null)) }
        for (r in facts.history) {
            val k = know(SongRef(r.videoId, r.title, r.artist, r.thumbnailUrl, r.durationText, r.albumName, r.explicit))
            k.played.add(r.playedAt)
            if (r.playedAt > k.lastPlayed) k.lastPlayed = r.playedAt
            if (r.playedAt < k.firstSeen) k.firstSeen = r.playedAt
        }
        val likedIds = facts.liked.mapTo(HashSet()) { it.videoId }
        facts.liked.forEach { know(it) }
        val done = facts.downloads.filterValues { it.state == DownloadState.DONE }
        for ((id, entry) in done) {
            val k = known.getOrPut(id) { Known(entry.song.copy(setVideoId = null)) }
            if (entry.addedAt < k.firstSeen) k.firstSeen = entry.addedAt
        }
        facts.playlists.forEach { p -> p.songs.forEach { know(it) } }

        val pool = known.values.filter { k ->
            when (rules.from) {
                SmartSource.EVERYTHING -> true
                SmartSource.HISTORY -> k.played.isNotEmpty()
                SmartSource.LIKED -> k.ref.videoId in likedIds
                SmartSource.DOWNLOADS -> k.ref.videoId in done
            }
        }
        // Each rule as a test on a song, the artist's name folded once rather than per song.
        // An artist rule with no name yet asks nothing, rather than matching every song.
        val asked = rules.conditions.filterNot { it is SmartRule.Artist && it.name.isBlank() }
        val tests: List<(Known) -> Boolean> = asked.map { rule ->
            when (rule) {
                is SmartRule.Plays -> {
                    val since = rule.withinDays?.let { now - it * DAY_MS };
                    val least = maxOf(rule.atLeast, 1);
                    { k: Known -> k.playsSince(since) >= least }
                }
                is SmartRule.NotPlayedFor -> ({ k: Known -> k.lastPlayed < now - rule.days * DAY_MS })
                is SmartRule.NewWithin -> ({ k: Known -> k.firstSeen != Long.MAX_VALUE && k.firstSeen >= now - rule.days * DAY_MS })
                SmartRule.Liked -> ({ k: Known -> k.ref.videoId in likedIds })
                SmartRule.Downloaded -> ({ k: Known -> k.ref.videoId in done })
                SmartRule.Lossless -> ({ k: Known -> done[k.ref.videoId]?.bits != null })
                is SmartRule.Artist -> {
                    val needle = fold(rule.name.trim());
                    { k: Known -> fold(k.ref.artist).contains(needle) }
                }
            }
        }
        val picked = when {
            tests.isEmpty() -> pool
            rules.match == SmartMatch.ALL -> pool.filter { k -> tests.all { it(k) } }
            else -> pool.filter { k -> tests.any { it(k) } }
        }
        // Most played counts the plays over the span the rules ask about, or all of them.
        val countSince = rules.conditions.firstNotNullOfOrNull { (it as? SmartRule.Plays)?.withinDays }?.let { now - it * DAY_MS }
        val plays = if (rules.sort == SmartSort.MOST_PLAYED) picked.associateWith { it.playsSince(countSince) } else emptyMap()
        // Titles and names in the reader's alphabet, accented letters beside their plain ones;
        // accents, then case, only break ties.
        val words = Collator.getInstance().apply { strength = Collator.TERTIARY }
        val byTitle = compareBy(words) { k: Known -> k.ref.title }
        val sorted = when (rules.sort) {
            SmartSort.MOST_PLAYED -> picked.sortedWith(compareByDescending<Known> { plays[it] ?: 0 }.thenByDescending { it.lastPlayed })
            SmartSort.RECENTLY_PLAYED -> picked.sortedByDescending { it.lastPlayed }
            SmartSort.LONGEST_UNPLAYED -> picked.sortedBy { it.lastPlayed }
            SmartSort.NEWEST -> picked.sortedByDescending { if (it.firstSeen == Long.MAX_VALUE) Long.MIN_VALUE else it.firstSeen }
            SmartSort.TITLE -> picked.sortedWith(byTitle)
            SmartSort.ARTIST -> picked.sortedWith(compareBy(words) { k: Known -> k.ref.artist }.then(byTitle))
            // The day as the clock on the wall has it, so the order turns over at midnight, not at UTC's.
            SmartSort.SHUFFLE -> picked.sortedBy { it.ref.videoId }
                .shuffled(Random(seed xor Math.floorDiv(now + zone.getOffset(now), DAY_MS)))
        }
        val refs = sorted.map { it.ref }
        return rules.limit?.takeIf { it > 0 }?.let { refs.take(it) } ?: refs
    }
}
