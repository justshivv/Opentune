package com.opentune.data.reco

import com.opentune.data.history.DailyMixes
import com.opentune.data.history.MixSnapshots
import com.opentune.data.history.Wrapped
import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One Discover mix for each of your top genres: songs you haven't played, from the radios
 * of your favourites in that genre. Songs by artists filed under the same genre come first,
 * then ones whose genre isn't known yet; artists filed under another genre stay out. New
 * artists are mixed in among new songs from artists you know. Made every twelve hours and kept
 * on the phone, so Home doesn't ask again, or change, each time it opens.
 */
object GenreDiscover {
    private const val MIN_SONGS = 8
    private const val MAX_SONGS = 30
    private const val MAX_GENRES = 4
    private const val SEEDS_PER_GENRE = 3

    private val gate = Mutex()
    /** The Discover mixes last made, kept for twelve hours (see [MixSnapshots]). */
    val mixes: StateFlow<List<DailyMixes.Mix>> get() = MixSnapshots.mixes(MixSnapshots.DISCOVER)

    /**
     * Makes the Discover mixes from [seeds], each genre's best-loved songs, when the last ones
     * are twelve hours old or a genre has no Discover mix yet. [radio] gives a song's radio.
     */
    suspend fun refresh(
        seeds: Map<String, List<Song>>,
        played: Set<String>,
        hidden: HiddenList,
        genreOf: (artist: String) -> String?,
        knownArtists: Set<String>,
        radio: suspend (Song) -> List<Song>,
    ) = gate.withLock {
        val genres = seeds.keys.take(MAX_GENRES)
        if (!MixSnapshots.due(MixSnapshots.DISCOVER, genres.map(::idOf))) return@withLock
        val made = genres.mapNotNull { genre ->
            val radios = seeds.getValue(genre).take(SEEDS_PER_GENRE).map { seed ->
                try { radio(seed) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            val songs = pick(genre, radios, played, hidden, genreOf, knownArtists)
            if (songs.size < MIN_SONGS) return@mapNotNull null
            val newArtists = songs.map { Wrapped.mainArtist(it.artist) }.filter { a -> knownArtists.none { it.equals(a, true) } }.distinct()
            DailyMixes.Mix(
                idOf(genre),
                com.opentune.i18n.Tr.f("Discover {0}", genre),
                if (newArtists.isEmpty()) com.opentune.i18n.Tr.t("New songs from artists you know")
                else com.opentune.i18n.Tr.f("New to you: {0}", newArtists.take(3).joinToString(", ")),
                songs,
            )
        }
        // A failed try (offline, say) keeps what there was rather than emptying the row,
        // and leaves it due, to be tried again next time.
        if (made.isEmpty()) return@withLock
        // A genre whose radios came back with nothing keeps the mix it had.
        val ids = genres.map(::idOf)
        MixSnapshots.save(MixSnapshots.DISCOVER, ids.mapNotNull { id -> made.firstOrNull { it.id == id } ?: mixes.value.firstOrNull { it.id == id } })
    }

    private fun idOf(genre: String) = "discover:${genre.lowercase()}"

    /**
     * The new songs for [genre] from its seeds' [radios]: none played or hidden before, none by an
     * artist filed under another genre, the same genre before the unknown, and new artists woven in
     * among new songs from known ones. Each artist at most twice, so no one artist fills it.
     */
    internal fun pick(
        genre: String,
        radios: List<List<Song>>,
        played: Set<String>,
        hidden: HiddenList,
        genreOf: (artist: String) -> String?,
        knownArtists: Set<String>,
    ): List<Song> {
        val known = knownArtists.mapTo(HashSet()) { it.lowercase() }
        val perArtist = HashMap<String, Int>()
        val sameNew = mutableListOf<Song>(); val sameKnown = mutableListOf<Song>(); val unknown = mutableListOf<Song>()
        com.opentune.data.ExploreFeed.weave(radios).distinctBy { it.videoId }.forEach { s ->
            if (s.videoId in played || hidden.hides(s.videoId, s.artist)) return@forEach
            val artist = Wrapped.mainArtist(s.artist)
            if (artist.isBlank()) return@forEach
            val g = genreOf(artist)
            if (g != null && !g.equals(genre, true)) return@forEach
            val n = perArtist[artist.lowercase()] ?: 0
            if (n >= 2) return@forEach
            perArtist[artist.lowercase()] = n + 1
            when {
                g == null -> unknown += s
                artist.lowercase() in known -> sameKnown += s
                else -> sameNew += s
            }
        }
        // Two new artists for each new song from a known one, then the unknown genres at the end.
        val out = mutableListOf<Song>()
        val a = sameNew.iterator(); val b = sameKnown.iterator()
        while (a.hasNext() || b.hasNext()) {
            repeat(2) { if (a.hasNext()) out += a.next() }
            if (b.hasNext()) out += b.next()
        }
        return (out + unknown).take(MAX_SONGS)
    }

}
