package com.opentune.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The signed-in listener's Spotify library through the web player's own
 * queries (the extension's playlists() and tracks()): the playlists in it,
 * with Liked Songs first, and each one's songs.
 */
object SpotifyLibrary {
    /** Liked Songs has no playlist id of its own. */
    const val LIKED_ID = "liked"
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    /** Enough for any real library; stops a runaway loop if Spotify's counts are off. */
    private const val MAX_ITEMS = 10_000

    /** A playlist; for a mix, [owner] carries Spotify's description of it instead. */
    data class Playlist(val id: String, val name: String, val owner: String?, val imageUrl: String?)

    /** A song in a playlist, with Spotify's id for it. */
    data class Item(val uri: String, val track: Spotify.Track)

    internal data class Page<T>(val items: List<T>, val total: Int, val raw: Int, val revision: String? = null)

    /** Every playlist in the library, Liked Songs first. */
    suspend fun playlists(cookie: String): List<Playlist> {
        val all = mutableListOf(Playlist(LIKED_ID, "Liked Songs", null, LIKED_COVER))
        var offset = 0
        while (offset < MAX_ITEMS) {
            val page = playlistPage(SpotifyWebPlayer.query("libraryV3", cookie, buildJsonObject {
                putJsonArray("filters") { add(JsonPrimitive("Playlists")) }
                put("order", null as String?)
                put("textFilter", "")
                putJsonArray("features") { listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS").forEach { add(JsonPrimitive(it)) } }
                put("limit", 50)
                put("offset", offset)
                put("flatten", true)
                putJsonArray("expandedFolders") {}
                put("folderUri", null as String?)
                put("includeFoldersWhenFlattening", false)
            }))
            all += page.items
            offset += page.raw
            if (page.raw == 0 || offset >= page.total) break
        }
        return all.distinctBy { it.id }
    }

    /**
     * The kinds of playlist Spotify makes for one listener out of their own
     * listening, as its `format` names them. Artist and song radios
     * ("inspiredby-mix") and editorial playlists are the same for everyone,
     * so they're left out.
     */
    internal val PERSONAL_FORMATS = setOf(
        "daily-mix", "discover-weekly", "release-radar", "on-repeat", "repeat-rewind", "daylist", "artist-mix-reader", "topic-mix",
    )

    /** Spotify's mixes made for the listener, as its home page lists them, in its order. */
    suspend fun madeForYou(cookie: String): List<Playlist> = mixesIn(
        SpotifyWebPlayer.query("home", cookie, buildJsonObject {
            put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
            put("timeZone", java.util.TimeZone.getDefault().id)
            put("sp_t", "")
            put("facet", "")
            put("sectionItemsLimit", 20)
            put("includeHomeChapterVideoCards", false)
            put("includeEpisodeContentRatingsV2", false)
        }),
    )

    /** Spotify descriptions are HTML fragments. Keep link labels, never markup or link targets. */
    fun plainDescription(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val body = org.jsoup.Jsoup.parseBodyFragment(value).body()
        body.select("script, style, template").remove()
        return body.text()
    }

    /** The personal mixes in a `home` answer, each once. */
    internal fun mixesIn(root: JsonObject): List<Playlist> {
        val sections = root.at("data", "home", "sectionContainer", "sections", "items") as? JsonArray ?: return emptyList()
        return sections.flatMap { section -> (section.at("sectionItems", "items") as? JsonArray).orEmpty() }
            .mapNotNull { item ->
                val data = item.at("content", "data") as? JsonObject ?: return@mapNotNull null
                if (data.text("__typename") != "Playlist" || data.text("format") !in PERSONAL_FORMATS) return@mapNotNull null
                val uri = data.text("uri") ?: (item.at("uri") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null
                val name = data.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val images = (data.at("images", "items") as? JsonArray)?.firstOrNull()
                Playlist(uri.substringAfterLast(':'), name, plainDescription(data.text("description")).takeIf { it.isNotBlank() }, largest(images.at("sources")))
            }
            .distinctBy { it.id }
    }

    /**
     * Spotify's version of a playlist, read from one row: its revision, or for
     * Liked Songs (which has none) the count and the newest song.
     */
    suspend fun revision(cookie: String, id: String): String? {
        val page = tracksPage(cookie, id, 0, 1)
        return page.revision ?: "${page.total}:${page.items.firstOrNull()?.uri.orEmpty()}"
    }

    /** All of a playlist's songs, in its order, and its revision as [revision] gives it. */
    suspend fun tracks(cookie: String, id: String): Pair<List<Item>, String?> {
        val all = mutableListOf<Item>()
        var offset = 0
        var revision: String? = null
        var total = 0
        while (offset < MAX_ITEMS) {
            val page = tracksPage(cookie, id, offset, 100)
            if (offset == 0) revision = page.revision ?: "${page.total}:${page.items.firstOrNull()?.uri.orEmpty()}"
            total = page.total
            all += page.items
            offset += page.raw
            if (page.raw == 0 || offset >= page.total) break
        }
        if (all.size < total) com.opentune.data.DebugLog.i("SpotifyLibrary", "$id: ${all.size} of $total songs readable")
        return all to revision
    }

    private suspend fun tracksPage(cookie: String, id: String, offset: Int, limit: Int): Page<Item> =
        if (id == LIKED_ID) {
            likedPage(SpotifyWebPlayer.query("fetchLibraryTracks", cookie, buildJsonObject { put("offset", offset); put("limit", limit) }))
        } else {
            trackPage(SpotifyWebPlayer.query("fetchPlaylist", cookie, buildJsonObject {
                put("uri", "spotify:playlist:$id")
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            }))
        }

    // ---- Parsing ----------------------------------------------------------------

    /** A `libraryV3` page: playlists only, not folders, albums or artists. */
    internal fun playlistPage(root: JsonObject): Page<Playlist> {
        val library = root.at("data", "me", "libraryV3") as? JsonObject ?: throw java.io.IOException("Spotify's library answer was empty")
        val raw = library["items"] as? JsonArray ?: JsonArray(emptyList())
        val items = raw.mapNotNull { entry ->
            val wrapper = entry.at("item") as? JsonObject ?: return@mapNotNull null
            if (wrapper.text("__typename")?.lowercase()?.contains("playlist") != true) return@mapNotNull null
            val data = wrapper["data"] as? JsonObject ?: return@mapNotNull null
            if (data.text("__typename") != "Playlist") return@mapNotNull null
            val uri = wrapper.text("_uri") ?: return@mapNotNull null
            val name = data.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val images = (data.at("images", "items") as? JsonArray)?.firstOrNull()
            Playlist(uri.substringAfterLast(':'), name, (data.at("ownerV2", "data", "name") as? JsonPrimitive)?.contentOrNull, largest(images.at("sources")))
        }
        return Page(items, library.int("totalCount") ?: raw.size, raw.size)
    }

    /** A `fetchPlaylist` page. */
    internal fun trackPage(root: JsonObject): Page<Item> {
        val playlist = root.at("data", "playlistV2") as? JsonObject ?: throw java.io.IOException("Spotify's playlist answer was empty")
        val content = playlist["content"] as? JsonObject ?: throw java.io.IOException("Spotify's playlist answer had no songs")
        val raw = content["items"] as? JsonArray ?: JsonArray(emptyList())
        val items = raw.mapNotNull { entry ->
            val data = entry.at("itemV2", "data") as? JsonObject ?: return@mapNotNull null
            item(data, data.text("uri") ?: data.text("_uri"))
        }
        return Page(items, content.int("totalCount") ?: raw.size, raw.size, playlist.text("revisionId"))
    }

    /** A `fetchLibraryTracks` page: Liked Songs. */
    internal fun likedPage(root: JsonObject): Page<Item> {
        val tracks = root.at("data", "me", "library", "tracks") as? JsonObject ?: throw java.io.IOException("Spotify's liked songs answer was empty")
        val raw = tracks["items"] as? JsonArray ?: JsonArray(emptyList())
        val items = raw.mapNotNull { entry ->
            val wrapper = entry.at("track") as? JsonObject ?: return@mapNotNull null
            val data = wrapper["data"] as? JsonObject ?: return@mapNotNull null
            item(data, wrapper.text("_uri") ?: data.text("uri"))
        }
        return Page(items, tracks.int("totalCount") ?: raw.size, raw.size)
    }

    private fun item(data: JsonObject, uri: String?): Item? {
        if (uri == null || !uri.startsWith("spotify:track:")) return null
        val title = data.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artists = (data.at("artists", "items") as? JsonArray).orEmpty()
            .mapNotNull { (it.at("profile", "name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        val ms = (data.at("trackDuration", "totalMilliseconds") ?: data.at("duration", "totalMilliseconds"))
            .let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
        val album = (data.at("albumOfTrack", "name") as? JsonPrimitive)?.contentOrNull
        return Item(uri, Spotify.Track(title, artists, album, ms))
    }

    /** The widest source: Spotify lists sizes in no fixed order, sometimes without them. */
    private fun largest(sources: JsonElement?): String? = (sources as? JsonArray).orEmpty()
        .mapNotNull { s -> (s.at("url") as? JsonPrimitive)?.contentOrNull?.let { it to ((s.at("width") as? JsonPrimitive)?.intOrNull ?: 0) } }
        .maxByOrNull { it.second }?.first

    private fun JsonElement?.at(vararg keys: String): JsonElement? = keys.fold(this) { at, key -> (at as? JsonObject)?.get(key) }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
