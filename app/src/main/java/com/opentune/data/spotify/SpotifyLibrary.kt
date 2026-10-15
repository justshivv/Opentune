package com.opentune.data.spotify

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

/** Read-only library access. Imported tracks still resolve through OpenTune's audio sources. */
object SpotifyLibrary {
    data class Playlist(val id: String, val name: String)
    private const val API = "https://api.spotify.com/v1"
    internal fun allowedUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.isHttps && it.host == "api.spotify.com" && it.port == 443 && it.username.isEmpty() &&
            it.password.isEmpty() && it.fragment == null && it.encodedPath.startsWith("/v1/")
    } == true

    private suspend fun pages(first: String, read: suspend (String) -> JSONObject = SpotifyAccount::get): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var next: String? = first
        while (next != null) {
            currentCoroutineContext().ensureActive()
            val url = next
            if (!allowedUrl(url) || !seen.add(url) || seen.size > 200) throw IOException("Spotify returned an invalid or oversized library page")
            val page = read(url)
            val items = page.optJSONArray("items") ?: throw IOException("Spotify returned an incomplete library page")
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(all::add)
            next = page.optString("next").takeUnless { it.isBlank() || it == "null" }
        }
        return all
    }

    suspend fun playlists(): List<Playlist> = pages("$API/me/playlists?limit=50").mapNotNull {
        val id = it.optString("id")
        if (!Regex("[A-Za-z0-9]{22}").matches(id)) null else Playlist(id, it.optString("name").ifBlank { "Spotify playlist" })
    }
    suspend fun likedSongs(): List<Spotify.Track> = pages("$API/me/tracks?limit=50").mapNotNull(::track)
    suspend fun playlist(id: String): List<Spotify.Track> {
        require(Regex("[A-Za-z0-9]{22}").matches(id))
        return pages("$API/playlists/$id/items?limit=50").mapNotNull(::track)
    }

    internal fun track(item: JSONObject): Spotify.Track? {
        val track = item.optJSONObject("track") ?: item.optJSONObject("item") ?: return null
        if (item.optBoolean("is_local") || track.optBoolean("is_local") || track.optString("type") != "track") return null
        val title = track.optString("name").takeIf { it.isNotBlank() } ?: return null
        val list = track.optJSONArray("artists") ?: return null
        val artists = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
        if (artists.isEmpty()) return null
        return Spotify.Track(title, artists, track.optJSONObject("album")?.optString("name"), track.optLong("duration_ms"))
    }
}
