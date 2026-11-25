package com.opentune.data.metadata

import com.opentune.data.DebugLog as Log
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.PlayerClient
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One person on a song, with what they did on it. */
data class Credit(val name: String, val roles: List<String>)

/**
 * Who made a song, as the label sent it to YouTube.
 *
 * Songs YouTube Music lists from a label ("Provided to YouTube by …") carry the label's credits
 * in the video description: one "Role, Role: Name" line per person. That is where these come
 * from, Indian film songs included ("Composer: Pritam", "Lyricist: Amitabh Bhattacharya"). How
 * much is there is up to the label: some send the full studio sheet, some only composer and
 * lyricist, and uploads that aren't from a label have none.
 */
data class SongCredits(
    val performers: List<Credit>,
    val composition: List<Credit>,
    val production: List<Credit>,
    val label: String?,
    val copyright: String?,
    val released: String?,
) {
    val isEmpty: Boolean get() = performers.isEmpty() && composition.isEmpty() && production.isEmpty()
}

object SongCreditsRepository {
    private const val TAG = "SongCredits"

    private enum class Group { PERFORMER, COMPOSITION, PRODUCTION, SKIP }

    // Checked in this order; a role goes to the first group with a matching word.
    private val skip = listOf("publisher", "copyright", "label", "distributor", "released", "unknown", "license", "licensor")
    private val composition = listOf(
        "composer", "lyricist", "lyrics", "writer", "songwriter", "author", "arranger", "arrangement",
        "music director", "librettist", "translator", "adapter",
    )
    private val production = listOf(
        "producer", "production", "engineer", "mixer", "mixing", "mix ", "master", "programmer", "programming",
        "studio personnel", "recording", "recorded", "editor", "editing", "a&r", "sound design", "assistant",
        "vocal tuning", "technician", "supervisor", "director",
    )

    private fun groupOf(role: String): Group {
        val r = "${role.lowercase(Locale.ROOT)} "
        return when {
            skip.any { it in r } -> Group.SKIP
            composition.any { it in r } -> Group.COMPOSITION
            production.any { it in r } -> Group.PRODUCTION
            else -> Group.PERFORMER
        }
    }

    // Roles every studio sheet repeats; shown only when a person has nothing more telling.
    private val generic = setOf("associated performer", "studio personnel", "performer", "additional personnel")

    private val roleLine = Regex("""^([^:]{2,120}):\s*(.+)$""")

    /**
     * Reads the credits out of a label's description; null when it isn't one (no
     * "Provided to YouTube by" line), so an uploader's own notes are never taken for credits.
     */
    fun parse(description: String): SongCredits? {
        val lines = description.lines().map { it.trim() }
        val provided = lines.indexOfFirst { it.startsWith("Provided to YouTube by", ignoreCase = true) }
        if (provided < 0) return null
        val label = lines[provided].substringAfter("Provided to YouTube by", "").trim().trimEnd('.').ifBlank { null }
        // "Title · Artist · Artist" comes next: the main artists.
        val byline = lines.drop(provided + 1).firstOrNull { it.isNotBlank() }
            ?.takeIf { " · " in it }?.split(" · ")?.drop(1)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val copyright = lines.firstOrNull { it.startsWith("℗") }
        val released = lines.firstOrNull { it.startsWith("Released on:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.ifBlank { null }

        val groups = mapOf(Group.PERFORMER to LinkedHashMap<String, LinkedHashSet<String>>(), Group.COMPOSITION to LinkedHashMap(), Group.PRODUCTION to LinkedHashMap())
        val names = HashMap<String, String>()
        fun add(group: Group, name: String, role: String) {
            val key = name.lowercase(Locale.ROOT)
            val shown = names.getOrPut(key) { name }
            groups[group]?.getOrPut(shown) { LinkedHashSet() }?.add(role)
        }
        // The credits follow the ℗ and "Released on" lines. Before those come the byline and the
        // album, whose title may well have a colon in it ("Aashiqui 2: Original Soundtrack").
        val head = maxOf(
            lines.indexOfFirst { it.startsWith("℗") },
            lines.indexOfFirst { it.startsWith("Released on:", ignoreCase = true) },
        ).takeIf { it > provided }
            ?: lines.withIndex().drop(provided + 1).filter { it.value.isNotBlank() }.getOrNull(1)?.index
            ?: return null
        for (line in lines.drop(head + 1)) {
            if (line.startsWith("Auto-generated by YouTube", ignoreCase = true)) break
            val match = roleLine.matchEntire(line) ?: continue
            val name = match.groupValues[2].trim()
            if (name.isEmpty() || name.length > 120 || name.startsWith("http", ignoreCase = true)) continue
            // "Producer, Programmer: Name"; YouTube sometimes leaves two spaces where a comma was.
            val roles = match.groupValues[1].split(',').map { it.trim().replace(Regex("\\s{2,}"), " ") }.filter { it.isNotEmpty() }
            for (role in roles) {
                val group = groupOf(role)
                if (group != Group.SKIP) add(group, name, role.prettyRole())
            }
        }
        // The main artists lead the performers, even when the sheet doesn't list them.
        val performers = groups.getValue(Group.PERFORMER)
        if (byline.isNotEmpty()) {
            val listed = LinkedHashMap<String, LinkedHashSet<String>>()
            for (artist in byline) {
                val shown = names[artist.lowercase(Locale.ROOT)] ?: artist
                listed[shown] = performers.remove(shown) ?: linkedSetOf("Artist")
            }
            listed.putAll(performers)
            performers.clear()
            performers.putAll(listed)
        }
        fun List<Pair<String, Set<String>>>.credits() = map { (name, roles) ->
            val telling = roles.filter { it.lowercase(Locale.ROOT) !in generic }
            Credit(name, telling.ifEmpty { roles.toList() })
        }
        return SongCredits(
            performers = performers.map { it.key to it.value }.credits(),
            composition = groups.getValue(Group.COMPOSITION).map { it.key to it.value }.credits(),
            production = groups.getValue(Group.PRODUCTION).map { it.key to it.value }.credits(),
            label = label,
            copyright = copyright,
            released = released,
        )
    }

    /** "Composer Lyricist" reads as two roles; YouTube writes it as one. */
    private fun String.prettyRole(): String = when (lowercase(Locale.ROOT)) {
        "composer lyricist" -> "Composer & Lyricist"
        else -> this
    }

    private val descriptions = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }
    private val answers = object : LinkedHashMap<String, SongCredits?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongCredits?>?) = size > 64
    }
    private val lock = Mutex()

    /** Keeps the description of a player answer that came by anyway, so credits need no request of their own. */
    fun remember(response: JsonObject) {
        val details = response["videoDetails"] as? JsonObject ?: return
        val id = details["videoId"]?.jsonPrimitive?.contentOrNull ?: return
        val text = details["shortDescription"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        synchronized(descriptions) { descriptions[id] = text }
    }

    /**
     * The credits for [videoId]: null when the song has none to give. Throws when YouTube
     * couldn't be asked, so the sheet can offer to try again.
     */
    suspend fun lookup(videoId: String): SongCredits? = lock.withLock {
        if (videoId.startsWith("local:")) return@withLock null
        if (answers.containsKey(videoId)) return@withLock answers[videoId]
        val description = descriptionOf(videoId)
        val credits = description?.let(::parse)
        answers[videoId] = credits
        credits
    }

    /**
     * The whole description of [videoId] as YouTube has it ("Provided to YouTube by …" for a
     * label's song): null when there is none. Kept, so the credits and the song sheet share
     * one request. Throws when YouTube couldn't be asked.
     */
    suspend fun description(videoId: String): String? = lock.withLock {
        if (videoId.startsWith("local:")) return@withLock null
        descriptionOf(videoId)
    }

    private suspend fun descriptionOf(videoId: String): String? {
        synchronized(descriptions) { descriptions[videoId] }?.let { return it }
        val text = fetchDescription(videoId)?.takeIf { it.isNotBlank() } ?: return null
        synchronized(descriptions) { descriptions[videoId] = text }
        return text
    }

    private suspend fun fetchDescription(videoId: String): String? {
        var failure: Exception? = null
        for (client in listOf(PlayerClient.ANDROID_VR, PlayerClient.IOS)) {
            try {
                val details = Innertube.player(videoId, client)["videoDetails"]?.jsonObject ?: continue
                return details["shortDescription"]?.jsonPrimitive?.contentOrNull.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Innertube.UnplayableException) {
                // A refused song still has no description to read; asking another client won't change that.
                if (!e.looksLikeBotCheck) return null
                failure = e
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let { Log.w(TAG, "Couldn't read the description of $videoId", it); throw it }
        return null
    }
}
