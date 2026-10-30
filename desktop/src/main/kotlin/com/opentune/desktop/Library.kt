package com.opentune.desktop

import com.opentune.data.model.Song
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Liked songs, your playlists, every song played and the volume, kept in an
 * SQLite database in the user's own folder: ~/.opentune/library.db. Each
 * change is one transaction, so a crash mid-write never leaves it half
 * saved, and the full play history is kept rather than the last few.
 *
 * The library the desktop app kept before, ~/.opentune/desktop.json, is
 * read into it once and then kept as desktop.json.imported.
 */
object Library {
    @Serializable
    data class Saved(val id: String, val title: String, val artist: String, val cover: String? = null, val length: String? = null) {
        fun toSong() = Song(id, title, artist, cover, length)
    }

    @Serializable
    data class Playlist(val id: String, val name: String, val songs: List<Saved> = emptyList()) {
        /** Up to four different covers, for the playlist's tile. */
        val covers: List<String> get() = songs.mapNotNull { it.cover }.distinct().take(4)
    }

    /** The old JSON file's shape, for reading it in once. */
    @Serializable
    private data class Legacy(
        val liked: List<Saved> = emptyList(),
        val recent: List<Saved> = emptyList(),
        val volume: Double = 0.8,
        val playlists: List<Playlist> = emptyList(),
    )

    /** How many recent songs the pages list; the history itself keeps every play. */
    private const val RECENT = 50
    private val json = Json { ignoreUnknownKeys = true }
    private var legacy = File(System.getProperty("user.home"), ".opentune/desktop.json")
    private var db: Connection = open(legacy)

    private val _liked = MutableStateFlow<List<Song>>(emptyList())
    val liked: StateFlow<List<Song>> = _liked.asStateFlow()
    private val _recent = MutableStateFlow<List<Song>>(emptyList())
    val recent: StateFlow<List<Song>> = _recent.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    @Volatile private var volume = 0.8

    init { publish() }

    /** For tests: keeps the library beside [at] (the old JSON file's place) instead. */
    @Synchronized
    internal fun useFile(at: File) {
        runCatching { db.close() }
        legacy = at
        db = open(at)
        publish()
    }

    fun isLiked(id: String) = _liked.value.any { it.videoId == id }

    fun setLiked(song: Song, liked: Boolean) = write {
        if (liked) {
            keep(song.saved())
            exec("INSERT OR REPLACE INTO liked(id, at) VALUES (?, ?)", song.videoId, System.currentTimeMillis())
        } else {
            exec("DELETE FROM liked WHERE id = ?", song.videoId)
        }
    }

    fun played(song: Song) = write {
        keep(song.saved())
        exec("INSERT INTO plays(id, at) VALUES (?, ?)", song.videoId, System.currentTimeMillis())
    }

    /** A new, empty playlist; its id is returned. */
    fun createPlaylist(name: String): String {
        val id = "pl-" + java.util.UUID.randomUUID().toString().take(8)
        write {
            val next = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlists") { it.getInt(1) }.first()
            exec("INSERT INTO playlists(id, name, position) VALUES (?, ?, ?)", id, name.trim().ifEmpty { "New playlist" }, next)
        }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        write { exec("UPDATE playlists SET name = ? WHERE id = ?", clean, id) }
    }

    fun deletePlaylist(id: String) = write {
        exec("DELETE FROM playlist_songs WHERE playlist = ?", id)
        exec("DELETE FROM playlists WHERE id = ?", id)
    }

    /** Adds [song] at the end, unless it's already there. */
    fun addToPlaylist(id: String, song: Song) = write {
        val there = query("SELECT 1 FROM playlist_songs WHERE playlist = ? AND id = ?", id, song.videoId) { 1 }.isNotEmpty()
        if (!there) {
            keep(song.saved())
            val next = query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist = ?", id) { it.getInt(1) }.first()
            exec("INSERT INTO playlist_songs(playlist, position, id) VALUES (?, ?, ?)", id, next, song.videoId)
        }
    }

    fun removeFromPlaylist(id: String, videoId: String) = write {
        exec("DELETE FROM playlist_songs WHERE playlist = ? AND id = ?", id, videoId)
    }

    /**
     * Every play, newest first, as the phone's history keeps them, for the
     * stats, mixes and speed dial the shared code makes from it. At most
     * [limit], read fresh each time.
     */
    @Synchronized
    fun history(limit: Int = 10_000): List<com.opentune.data.history.PlayRecord> = runCatching {
        db.query(
            "SELECT s.id, s.title, s.artist, s.cover, s.length, p.at FROM plays p JOIN songs s ON s.id = p.id ORDER BY p.at DESC, p.rowid DESC LIMIT ?",
            limit,
        ) { r -> com.opentune.data.history.PlayRecord(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), playedAt = r.getLong(6)) }
    }.getOrDefault(emptyList())

    fun volume(): Double = volume
    fun setVolume(v: Double) = write { exec("INSERT OR REPLACE INTO settings(key, value) VALUES ('volume', ?)", v.toString()) }

    private fun Song.saved() = Saved(videoId, title, artist, thumbnailUrl, durationText)

    // ---- The database -------------------------------------------------------------

    /** Runs [change] as one transaction, then shows the result. A failed write leaves the library as it was. */
    @Synchronized
    private fun write(change: Connection.() -> Unit) {
        runCatching {
            db.autoCommit = false
            try {
                db.change()
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
        }.onFailure { System.err.println("Library: couldn't save: $it") }
        publish()
    }

    /** Every song's details, once, however many lists it's on. */
    private fun Connection.keep(s: Saved) =
        exec("INSERT OR REPLACE INTO songs(id, title, artist, cover, length) VALUES (?, ?, ?, ?, ?)", s.id, s.title, s.artist, s.cover, s.length)

    @Synchronized
    private fun publish() {
        runCatching {
            _liked.value = db.query("SELECT s.id, s.title, s.artist, s.cover, s.length FROM liked l JOIN songs s ON s.id = l.id ORDER BY l.at DESC, l.rowid DESC", row = ::song)
            _recent.value = db.query(
                "SELECT s.id, s.title, s.artist, s.cover, s.length FROM (SELECT id, MAX(rowid) AS last FROM plays GROUP BY id) p " +
                    "JOIN songs s ON s.id = p.id ORDER BY p.last DESC LIMIT $RECENT",
                row = ::song,
            )
            val lists = db.query("SELECT id, name FROM playlists ORDER BY position") { it.getString(1) to it.getString(2) }
            _playlists.value = lists.map { (id, name) ->
                Playlist(
                    id, name,
                    db.query(
                        "SELECT s.id, s.title, s.artist, s.cover, s.length FROM playlist_songs p JOIN songs s ON s.id = p.id WHERE p.playlist = ? ORDER BY p.position",
                        id,
                    ) { r -> Saved(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)) },
                )
            }
            volume = db.query("SELECT value FROM settings WHERE key = 'volume'") { it.getString(1).toDoubleOrNull() }.firstOrNull() ?: 0.8
        }.onFailure { System.err.println("Library: couldn't read: $it") }
    }

    private fun song(r: java.sql.ResultSet) = Song(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5))

    private fun open(legacyFile: File): Connection {
        val folder = legacyFile.absoluteFile.parentFile.apply { mkdirs() }
        val c = DriverManager.getConnection("jdbc:sqlite:" + File(folder, "library.db").absolutePath)
        c.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode = WAL")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS songs(id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, cover TEXT, length TEXT)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS liked(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plays(id TEXT NOT NULL, at INTEGER NOT NULL)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS plays_by_id ON plays(id)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlists(id TEXT PRIMARY KEY, name TEXT NOT NULL, position INTEGER NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_songs(playlist TEXT NOT NULL, position INTEGER NOT NULL, id TEXT NOT NULL)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)")
        }
        importLegacy(c, legacyFile)
        return c
    }

    /** The old JSON library, read in once; the file is kept aside, not deleted. */
    private fun importLegacy(c: Connection, file: File) {
        if (!file.isFile) return
        val old = runCatching { json.decodeFromString(Legacy.serializer(), file.readText()) }.getOrNull() ?: return
        c.autoCommit = false
        try {
            val now = System.currentTimeMillis()
            fun keep(s: Saved) = c.exec("INSERT OR REPLACE INTO songs(id, title, artist, cover, length) VALUES (?, ?, ?, ?, ?)", s.id, s.title, s.artist, s.cover, s.length)
            // Newest first in the file: given falling times so the order holds.
            old.liked.forEachIndexed { i, s -> keep(s); c.exec("INSERT OR IGNORE INTO liked(id, at) VALUES (?, ?)", s.id, now - i) }
            old.recent.asReversed().forEachIndexed { i, s -> keep(s); c.exec("INSERT INTO plays(id, at) VALUES (?, ?)", s.id, now - old.recent.size + i) }
            old.playlists.forEachIndexed { at, p ->
                c.exec("INSERT OR IGNORE INTO playlists(id, name, position) VALUES (?, ?, ?)", p.id, p.name, at)
                p.songs.forEachIndexed { i, s -> keep(s); c.exec("INSERT INTO playlist_songs(playlist, position, id) VALUES (?, ?, ?)", p.id, i, s.id) }
            }
            c.exec("INSERT OR REPLACE INTO settings(key, value) VALUES ('volume', ?)", old.volume.toString())
            c.commit()
            file.renameTo(File(file.parentFile, file.name + ".imported"))
        } catch (e: Exception) {
            c.rollback()
            System.err.println("Library: couldn't read the old library in: $e")
        } finally {
            c.autoCommit = true
        }
    }

    private fun Connection.exec(sql: String, vararg args: Any?) {
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun <T> Connection.query(sql: String, vararg args: Any?, row: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { r -> buildList { while (r.next()) add(row(r)) } }
        }
}
