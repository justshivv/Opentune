package com.opentune.data.local

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LibrarySettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/**
 * Songs from one folder picked through Android's folder picker: found in the folder and its
 * subfolders (and nowhere else), named by their tags or else their file, and played from
 * the file itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MusicFolderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "Music")

    @Before fun setUp() {
        AppSettings.init(context)
        Robolectric.setupContentProvider(FakeDocuments::class.java, AUTHORITY)
        File(context.filesDir, "music-folder-tags.json").delete()
    }

    private fun settings(filter: Boolean = false) = LibrarySettings(localTree = tree.toString(), filterNonMusic = filter)

    @Test fun anIdLeadsBackToItsFile() {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, "Music/Rock/a b:c.flac")
        val id = MusicFolder.idOf(doc)
        assertTrue(LocalMusic.isLocal(id))
        assertTrue(MusicFolder.isFolderSong(id))
        assertFalse(id.substringAfter(LocalMusic.PREFIX).contains('/'))
        assertEquals(doc, LocalMusic.contentUri(id))
        // The phone's own MediaStore songs are still told apart.
        assertFalse(MusicFolder.isFolderSong(LocalMusic.PREFIX + "42"))
    }

    @Test fun findsTheFolderAndItsSubfoldersAudioOnly() = runBlocking {
        val songs = LocalMusic.tracks(context, settings())
        assertEquals(listOf("intro", "song", "Track two"), songs.map { it.title })
        assertTrue(songs.all { MusicFolder.isFolderSong(it.videoId) })
    }

    @Test fun tagsNameTheSongAndTheFileNameStandsInWithoutThem() = runBlocking {
        val tagged = DocumentsContract.buildDocumentUriUsingTree(tree, "Music/Rock/song.mp3")
        val source = DataSource.toDataSource(context, tagged)
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_TITLE, "Song")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_ARTIST, "Band")
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "200000")
        val songs = LocalMusic.tracks(context, settings())
        val song = songs.single { it.title == "Song" }
        assertEquals("Band", song.artist)
        assertEquals("3:20", song.durationText)
        val untagged = songs.single { it.title == "Track two" }
        assertEquals("Unknown artist", untagged.artist)
        assertNull(untagged.thumbnailUrl)
    }

    @Test fun theNonMusicFilterStillLeavesOutShortClips() = runBlocking {
        val long = DocumentsContract.buildDocumentUriUsingTree(tree, "Music/Rock/song.mp3")
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(context, long), MediaMetadataRetriever.METADATA_KEY_DURATION, "200000")
        val songs = LocalMusic.tracks(context, settings(filter = true))
        assertEquals(listOf("song"), songs.map { it.title })
    }

    @Test fun aFolderNeverAllowedIsntReadable() {
        assertFalse(MusicFolder.canRead(context, tree.toString()))
        assertFalse(LocalMusic.canRead(context, settings()))
        assertFalse(MusicFolder.canRead(context, null))
    }

    @Test fun audioIsKnownByItsTypeOrItsName() {
        assertTrue(MusicFolder.isAudio("x.bin", "audio/mpeg"))
        assertTrue(MusicFolder.isAudio("Song.FLAC", "application/octet-stream"))
        assertFalse(MusicFolder.isAudio("cover.jpg", "image/jpeg"))
        assertFalse(MusicFolder.isAudio("notes.txt", null))
    }

    /** A folder "Music" holding a subfolder, a cover, a hidden folder and a few songs. */
    class FakeDocuments : DocumentsProvider() {
        private val children = mapOf(
            "Music" to listOf(
                Row("Music/Rock", "Rock", Document.MIME_TYPE_DIR),
                Row("Music/.thumbnails", ".thumbnails", Document.MIME_TYPE_DIR),
                Row("Music/cover.jpg", "cover.jpg", "image/jpeg"),
                Row("Music/Track two.m4a", "Track two.m4a", "audio/mp4"),
                Row("Music/intro.ogg", "intro.ogg", "application/octet-stream"),
            ),
            "Music/Rock" to listOf(Row("Music/Rock/song.mp3", "song.mp3", "audio/mpeg")),
            "Music/.thumbnails" to listOf(Row("Music/.thumbnails/x.mp3", "x.mp3", "audio/mpeg")),
        )

        data class Row(val id: String, val name: String, val mime: String)

        override fun onCreate() = true

        override fun attachInfo(context: Context, info: ProviderInfo) {
            // A real documents provider is exported, grants uri permissions and is guarded by
            // MANAGE_DOCUMENTS; DocumentsProvider checks for that.
            info.exported = true
            info.grantUriPermissions = true
            info.readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            info.writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            super.attachInfo(context, info)
        }

        private fun cursor(projection: Array<out String>?, rows: List<Row>): Cursor {
            val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply {
                rows.forEach { r ->
                    addRow(columns.map<String, Any?> { c ->
                        when (c) {
                            Document.COLUMN_DOCUMENT_ID -> r.id
                            Document.COLUMN_DISPLAY_NAME -> r.name
                            Document.COLUMN_MIME_TYPE -> r.mime
                            Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                            Document.COLUMN_SIZE -> 1000L
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
        }

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf())

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            cursor(projection, listOf(children.values.flatten().firstOrNull { it.id == documentId } ?: Row(documentId, documentId, Document.MIME_TYPE_DIR)))

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            cursor(projection, children[parentDocumentId].orEmpty())

        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            val f = File.createTempFile("doc", ".bin").apply { writeBytes(ByteArray(16)) }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private companion object {
        const val AUTHORITY = "com.opentune.test.documents"
    }
}
