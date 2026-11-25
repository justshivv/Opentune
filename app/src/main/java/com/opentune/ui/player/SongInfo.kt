package com.opentune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.opentune.data.MusicRepository
import com.opentune.data.covers.AppleArtistArtRepository
import com.opentune.data.covers.ArtistPhotos
import com.opentune.data.isYouTubeId
import com.opentune.data.lastfm.LastFm
import com.opentune.data.links.MusicLinks
import com.opentune.data.metadata.AppleTrackMetadata
import com.opentune.data.metadata.AppleTrackMetadataRepository
import com.opentune.data.metadata.ArtistGenres
import com.opentune.data.metadata.Deezer
import com.opentune.data.metadata.SongCredits
import com.opentune.data.metadata.SongCreditsRepository
import com.opentune.data.metadata.SongWiki
import com.opentune.data.metadata.TrackMetadata
import com.opentune.data.metadata.TrackMetadataRepository
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.artistNamesIn
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The parts of a song's info that load on their own, each from its own sources. */
internal enum class SongInfoPart { DESCRIPTION, CATALOG, PHOTOS, ABOUT, LINKS }

/** Who wrote a song's "About" text. */
internal enum class AboutSource(val label: String) { WIKIPEDIA("Wikipedia"), LASTFM("Last.fm"), YOUTUBE("YouTube") }

internal data class AboutText(val source: AboutSource, val text: String, val url: String)

/** The services "Also available on" can open a song in, in the order they're shown. */
internal enum class MusicService(val label: String, val key: String) {
    SPOTIFY("Spotify", "spotify"),
    YOUTUBE_MUSIC("YouTube Music", "youtubeMusic"),
    APPLE_MUSIC("Apple Music", "appleMusic"),
    TIDAL("Tidal", "tidal"),
    DEEZER("Deezer", "deezer"),
    AMAZON_MUSIC("Amazon Music", "amazonMusic"),
    SOUNDCLOUD("SoundCloud", "soundcloud"),
}

internal data class ServiceLink(val service: MusicService, val url: String)

/**
 * Everything the song sheet and About song know about one song, gathered from the
 * sources the app already uses: the YouTube description (and the label's credits in it),
 * Apple's and MusicBrainz's catalogues, the artists' pages, Wikipedia and Last.fm for a
 * write-up, and UniTune and Deezer for the song elsewhere. [pending] are the parts still
 * on their way; a part that's done and found nothing just stays empty.
 */
@Immutable
internal data class SongInfo(
    val description: String? = null,
    val credits: SongCredits? = null,
    val genre: String? = null,
    /** As the source gave it: "2025-10-10", "2019-11" or "2019"; see [releaseText]. */
    val released: String? = null,
    /** The catalogue's album name, for a song whose row didn't carry one. */
    val album: String? = null,
    val isrc: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val appleUrl: String? = null,
    val musicBrainzUrl: String? = null,
    /** Each artist's photo, by the name as the song credits them. */
    val photos: Map<String, String> = emptyMap(),
    val about: List<AboutText> = emptyList(),
    /** Links found elsewhere (UniTune, Deezer); [serviceLinks] adds the ones the app knows itself. */
    val links: List<ServiceLink> = emptyList(),
    val pending: Set<SongInfoPart> = emptySet(),
)

/** Where a song's info comes from; swapped for fixtures in tests. */
internal interface SongInfoSources {
    suspend fun description(videoId: String): String?
    suspend fun apple(song: Song, durationMs: Long): AppleTrackMetadata?
    suspend fun musicBrainz(song: Song, durationMs: Long): TrackMetadata?
    suspend fun artistPhoto(song: Song, name: String): String?
    suspend fun wikipedia(song: Song): SongWiki.Article?
    suspend fun lastFm(song: Song): LastFm.Wiki?
    suspend fun platformLinks(videoId: String): Map<String, String>
    suspend fun deezer(song: Song, durationMs: Long): String?
    fun artistGenre(artist: String): String?
}

/** The real sources, every one already used elsewhere in the app or a public API. */
internal object AppSongInfoSources : SongInfoSources {
    override suspend fun description(videoId: String) = SongCreditsRepository.description(videoId)
    override suspend fun apple(song: Song, durationMs: Long) = AppleTrackMetadataRepository.lookup(song, durationMs)
    override suspend fun musicBrainz(song: Song, durationMs: Long) = TrackMetadataRepository.lookup(song, durationMs)

    override suspend fun artistPhoto(song: Song, name: String): String? {
        val id = MusicRepository.artistIdFor(song, name) ?: return null
        return ArtistPhotos.pinned(id)
            ?: AppleArtistArtRepository.cached(name, id)?.heroUrl
            ?: MusicRepository.artist(id).thumbnailUrl.artworkAt(ARTIST_ROUND_PX)
    }

    override suspend fun wikipedia(song: Song) = SongWiki.article(song.title, song.artist)
    override suspend fun lastFm(song: Song) = LastFm.trackWiki(song.artist, SongWiki.bareTitle(song.title))
    override suspend fun platformLinks(videoId: String) = MusicLinks.platformLinks("https://music.youtube.com/watch?v=$videoId")

    override suspend fun deezer(song: Song, durationMs: Long): String? {
        val first = song.artist.split(", ", " & ").first().trim()
        val title = SongWiki.bareTitle(song.title)
        val match = Deezer.search("artist:\"$first\" track:\"$title\"", limit = 5).firstOrNull { t ->
            sameText(t.title, title) && sameText(t.artist, first) &&
                (durationMs <= 0 || t.durationS <= 0 || kotlin.math.abs(t.durationS * 1000 - durationMs) <= 3_000)
        } ?: return null
        return "https://www.deezer.com/track/${match.id}"
    }

    override fun artistGenre(artist: String) = ArtistGenres.genreOf(artist)

    private fun sameText(a: String, b: String) = normal(SongWiki.bareTitle(a)) == normal(b)
    private fun normal(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
}

/**
 * Song info per song, kept while the app runs, so opening the sheet again for a song shows
 * at once what was found before. Each part loads on the store's own scope, not the sheet's:
 * closing the sheet halfway doesn't throw away a lookup, and a part that failed (no
 * network) is tried again the next time the sheet opens.
 */
internal class SongInfoStore(
    private val sources: SongInfoSources,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private class Entry(val state: MutableStateFlow<SongInfo>) {
        val done = HashSet<SongInfoPart>()
        val running = HashSet<SongInfoPart>()
    }

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 24
    }

    /** The info for [song], loading whatever isn't known yet. */
    fun load(song: Song, durationMs: Long): StateFlow<SongInfo> {
        val entry = synchronized(entries) { entries.getOrPut(song.videoId) { Entry(MutableStateFlow(SongInfo())) } }
        val online = isYouTubeId(song.videoId)
        fun start(part: SongInfoPart, wanted: Boolean, block: suspend (MutableStateFlow<SongInfo>) -> Unit) {
            synchronized(entry) {
                if (!wanted || part in entry.done || part in entry.running) return
                entry.running += part
            }
            entry.state.update { it.copy(pending = it.pending + part) }
            scope.launch {
                var ok = false
                try {
                    block(entry.state)
                    ok = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Left undone: tried again next time.
                } finally {
                    synchronized(entry) {
                        entry.running -= part
                        if (ok) entry.done += part
                    }
                    entry.state.update { it.copy(pending = it.pending - part) }
                }
            }
        }
        start(SongInfoPart.DESCRIPTION, online) { state ->
            val text = sources.description(song.videoId)?.let(::tidyDescription)
            val credits = text?.let(SongCreditsRepository::parse)
            state.update { it.copy(description = text, credits = credits, released = it.released ?: credits?.released?.takeIf(::isDate)) }
        }
        // The catalogue match needs the song's length: until it's known, this part waits for a later load.
        start(SongInfoPart.CATALOG, durationMs > 0 && song.artist.isNotBlank()) { state ->
            coroutineScope {
                val apple = async { runCatching { sources.apple(song, durationMs) }.getOrNull() }
                val brainz = async { runCatching { sources.musicBrainz(song, durationMs) }.getOrNull() }
                val a = apple.await()
                state.update { info ->
                    info.copy(
                        // The track's own genre over its artist's (which the photos part may have put in first).
                        genre = a?.genre?.takeIf { it.isNotBlank() && it != "Music" } ?: info.genre,
                        released = info.released ?: a?.releaseDate,
                        album = info.album ?: a?.album,
                        discNumber = a?.discNumber, trackNumber = a?.trackNumber,
                        appleUrl = a?.url,
                    )
                }
                val b = brainz.await()
                state.update { info ->
                    info.copy(
                        released = info.released ?: b?.releaseDate,
                        album = info.album ?: b?.album,
                        isrc = b?.isrcs?.firstOrNull(),
                        musicBrainzUrl = b?.let { "https://musicbrainz.org/recording/${it.recordingId}" },
                    )
                }
            }
        }
        val names = artistNamesIn(song.artist).take(MAX_ARTISTS)
        start(SongInfoPart.PHOTOS, online && names.isNotEmpty()) { state ->
            if (state.value.genre == null) names.firstNotNullOfOrNull { sources.artistGenre(it) }?.let { g -> state.update { it.copy(genre = it.genre ?: g) } }
            coroutineScope {
                names.map { name ->
                    async {
                        val url = runCatching { sources.artistPhoto(song, name) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                        if (url != null) state.update { it.copy(photos = it.photos + (name to url)) }
                    }
                }.forEach { it.await() }
            }
        }
        start(SongInfoPart.ABOUT, song.title.isNotBlank() && song.artist.isNotBlank()) { state ->
            coroutineScope {
                val wiki = async { runCatching { sources.wikipedia(song) }.getOrNull() }
                val fm = async { runCatching { sources.lastFm(song) }.getOrNull() }
                val texts = listOfNotNull(
                    wiki.await()?.let { AboutText(AboutSource.WIKIPEDIA, it.text, it.url) },
                    fm.await()?.let { AboutText(AboutSource.LASTFM, it.text, it.url) },
                )
                state.update { it.copy(about = texts) }
            }
        }
        start(SongInfoPart.LINKS, online) { state ->
            coroutineScope {
                val platforms = async { runCatching { sources.platformLinks(song.videoId) }.getOrDefault(emptyMap()) }
                val deezer = async { runCatching { sources.deezer(song, durationMs) }.getOrNull() }
                val found = platforms.await()
                val links = MusicService.entries.mapNotNull { service -> found[service.key]?.let { ServiceLink(service, it) } }
                val withDeezer = if (links.none { it.service == MusicService.DEEZER }) links + listOfNotNull(deezer.await()?.let { ServiceLink(MusicService.DEEZER, it) }) else links
                state.update { it.copy(links = withDeezer) }
            }
        }
        return entry.state
    }

    companion object {
        private const val MAX_ARTISTS = 6
    }
}

/** The app's one store of song info. */
internal val songInfoStore by lazy { SongInfoStore(AppSongInfoSources) }

/** The sheet's info for [song], from [store]; what's known shows at once. */
@Composable
internal fun rememberSongInfo(song: Song, durationMs: Long, store: SongInfoStore = songInfoStore): SongInfo {
    val flow = remember(song.videoId, durationMs > 0, store) { store.load(song, durationMs) }
    val info by flow.collectAsState()
    return info
}

/** "Auto-generated by YouTube." says nothing about the song. */
internal fun tidyDescription(text: String): String? =
    text.lines().filterNot { it.trim().equals("Auto-generated by YouTube.", ignoreCase = true) }
        .joinToString("\n").trim().replace(Regex("\n{3,}"), "\n\n").ifBlank { null }

private fun isDate(s: String) = Regex("""\d{4}(-\d{2}(-\d{2})?)?""").matches(s)

/** A release date as the phone's language writes it: "10 Oct 2025", "Nov 2019", "2019". */
internal fun releaseText(raw: String, locale: Locale = Locale.getDefault()): String = runCatching {
    when (raw.length) {
        10 -> LocalDate.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        7 -> YearMonth.parse(raw).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
        else -> raw
    }
}.getOrDefault(raw)

/**
 * Every place [song] can be opened, in [MusicService] order: what [info] found, YouTube
 * Music for a song from YouTube (it's where the app plays it from), and Apple Music from
 * the catalogue match when UniTune didn't give it.
 */
internal fun serviceLinks(song: Song, info: SongInfo): List<ServiceLink> {
    val byService = info.links.associateBy { it.service }.toMutableMap()
    if (isYouTubeId(song.videoId) && MusicService.YOUTUBE_MUSIC !in byService) {
        byService[MusicService.YOUTUBE_MUSIC] = ServiceLink(MusicService.YOUTUBE_MUSIC, "https://music.youtube.com/watch?v=${song.videoId}")
    }
    if (MusicService.APPLE_MUSIC !in byService) info.appleUrl?.let { byService[MusicService.APPLE_MUSIC] = ServiceLink(MusicService.APPLE_MUSIC, it) }
    return MusicService.entries.mapNotNull { byService[it] }
}

/** Round artist photos are drawn small: this is plenty. */
private const val ARTIST_ROUND_PX = 360
