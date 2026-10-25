package com.opentune.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLibraryTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aPlaylistPageGivesItsSongsAndRevision() {
        // Spotify's answer for "Today's Top Hits" on 2026-10-24, cut to two songs.
        val page = SpotifyLibrary.trackPage(json(checkNotNull(javaClass.classLoader?.getResource("spotify-fetch-playlist.json")).readText()))
        assertEquals(50, page.total)
        assertEquals(2, page.raw)
        assertEquals("AAAAAFzc9BSjq3wzNwVOBvuyCI01MnLO", page.revision)
        val first = page.items.first()
        assertEquals("spotify:track:2sFepQNyl1WjpROruP8bJ0", first.uri)
        assertEquals(Spotify.Track("Housewife", listOf("Tate McRae"), "Housewife", 208_810), first.track)
    }

    @Test
    fun theLibraryListsPlaylistsOnly() {
        val page = SpotifyLibrary.playlistPage(json("""{"data":{"me":{"libraryV3":{"totalCount":3,"items":[
            {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:abc","data":{"__typename":"Playlist","name":"Road trip",
              "ownerV2":{"data":{"name":"Me"}},"images":{"items":[{"sources":[{"url":"https://i.scdn.co/small","width":60},{"url":"https://i.scdn.co/big","width":640}]}]}}}},
            {"item":{"__typename":"FolderResponseWrapper","_uri":"spotify:user:me:folder:1","data":{"__typename":"Folder","name":"Old"}}},
            {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:gone","data":{"__typename":"NotFound"}}}
        ]}}}}"""))
        assertEquals(3, page.total)
        assertEquals(3, page.raw)
        assertEquals(listOf(SpotifyLibrary.Playlist("abc", "Road trip", "Me", "https://i.scdn.co/big")), page.items)
    }

    @Test
    fun likedSongsAreReadFromTheirOwnShape() {
        val page = SpotifyLibrary.likedPage(json("""{"data":{"me":{"library":{"tracks":{"totalCount":1,"items":[
            {"track":{"_uri":"spotify:track:x1","data":{"name":"Song","artists":{"items":[{"profile":{"name":"A"}},{"profile":{"name":"B"}}]},
              "albumOfTrack":{"name":"Album"},"duration":{"totalMilliseconds":180000}}}},
            {"track":{"_uri":"spotify:episode:e1","data":{"name":"An episode"}}}
        ]}}}}}"""))
        assertEquals(1, page.total)
        assertEquals(listOf(SpotifyLibrary.Item("spotify:track:x1", Spotify.Track("Song", listOf("A", "B"), "Album", 180_000))), page.items)
    }

    @Test
    fun homeGivesOnlyTheListenersOwnMixes() {
        // Spotify's signed-out home on 2026-10-24 ("Popular radio", the same for everyone),
        // with a Daily Mix added as a signed-in home lists it, twice.
        val mixes = SpotifyLibrary.mixesIn(json(checkNotNull(javaClass.classLoader?.getResource("spotify-home.json")).readText()))
        assertEquals(listOf("37i9dQZF1E39vTG3GurFPW"), mixes.map { it.id })
        assertEquals("Daily Mix 1", mixes.single().name)
        assertEquals("Arijit Singh, Pritam and more", mixes.single().owner)
    }
}
