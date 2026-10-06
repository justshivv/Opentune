package com.opentune.data.spotify

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportedPlaylistsTest {
    private val csv = "Track Name,Artist Name(s),Duration (ms)\nBlinding Lights,The Weeknd,200040\n"

    @Test fun readsOneCsvNamedAfterTheFile() {
        val out = ExportedPlaylists.read("today's_top_hits.csv", csv.toByteArray())
        assertEquals(listOf("today's top hits"), out.map { it.name })
        assertEquals("Blinding Lights", out.single().tracks.single().title)
    }

    @Test fun readsEveryCsvInAnExportAllZip() {
        val bytes = ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { z ->
                listOf("liked_songs.csv", "road_trip.csv").forEach { name ->
                    z.putNextEntry(ZipEntry(name)); z.write(csv.toByteArray()); z.closeEntry()
                }
                z.putNextEntry(ZipEntry("readme.txt")); z.write("x".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(listOf("liked songs", "road trip"), ExportedPlaylists.read("spotify_playlists.zip", bytes).map { it.name })
    }

    @Test fun aFileWithoutSongsGivesNothing() {
        assertEquals(0, ExportedPlaylists.read("x.csv", "a,b\n1,2\n".toByteArray()).size)
    }
}
