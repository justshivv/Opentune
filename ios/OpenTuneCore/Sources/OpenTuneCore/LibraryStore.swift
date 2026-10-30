import Foundation

/** One listen: a song that played long enough to count. */
public struct PlayRecord: Codable, Hashable, Sendable {
    public var song: Song
    public var playedAt: Date
}

/** A playlist made on the phone. */
public struct LocalPlaylist: Codable, Hashable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var songs: [Song]
    public var createdAt: Date

    /** Up to four different covers, for the playlist's tile. */
    public var covers: [String] {
        var seen = Set<String>()
        return songs.compactMap(\.thumbnailUrl).filter { seen.insert($0).inserted }.prefix(4).map { $0 }
    }
}

/** An album or YouTube Music playlist kept in the library with the heart on its page. */
public struct SavedCollection: Codable, Hashable, Identifiable, Sendable {
    public var browseId: String
    public var title: String
    public var subtitle: String
    public var thumbnailUrl: String?
    public var savedAt: Date

    public var id: String { browseId }

    public init(browseId: String, title: String, subtitle: String, thumbnailUrl: String?, savedAt: Date = Date()) {
        self.browseId = browseId
        self.title = title
        self.subtitle = subtitle
        self.thumbnailUrl = thumbnailUrl
        self.savedAt = savedAt
    }
}

/** A song saved for listening offline: its file sits in the downloads folder under [fileName]. */
public struct DownloadedSong: Codable, Hashable, Identifiable, Sendable {
    public var song: Song
    public var fileName: String
    public var bytes: Int64
    public var loudnessDb: Double?
    public var savedAt: Date

    public var id: String { song.videoId }

    public init(song: Song, fileName: String, bytes: Int64, loudnessDb: Double?, savedAt: Date = Date()) {
        self.song = song
        self.fileName = fileName
        self.bytes = bytes
        self.loudnessDb = loudnessDb
        self.savedAt = savedAt
    }
}

/**
 * Liked songs, playlists, saved albums, downloads, recent searches and
 * listening history, kept on the phone in one JSON file, the way the Android
 * app keeps them. Nothing leaves the device.
 */
public final class LibraryStore: @unchecked Sendable {
    struct Document: Codable {
        var liked: [Song] = []
        var history: [PlayRecord] = []
        var playlists: [LocalPlaylist] = []
        var saved: [SavedCollection] = []
        var downloads: [DownloadedSong] = []
        var searches: [String] = []

        init() {}

        // A library from before playlists and downloads has only the first two; the rest start empty.
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            liked = try c.decodeIfPresent([Song].self, forKey: .liked) ?? []
            history = try c.decodeIfPresent([PlayRecord].self, forKey: .history) ?? []
            playlists = try c.decodeIfPresent([LocalPlaylist].self, forKey: .playlists) ?? []
            saved = try c.decodeIfPresent([SavedCollection].self, forKey: .saved) ?? []
            downloads = try c.decodeIfPresent([DownloadedSong].self, forKey: .downloads) ?? []
            searches = try c.decodeIfPresent([String].self, forKey: .searches) ?? []
        }
    }

    private let file: URL
    private let queue = DispatchQueue(label: "opentune.library")
    private var doc: Document

    /** How many plays history keeps. */
    public static let historyLimit = 500
    /** How many recent searches are kept. */
    public static let searchLimit = 20

    /** Where downloaded songs' files are kept. */
    public let downloadsFolder: URL

    public init(directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        file = directory.appendingPathComponent("library.json")
        downloadsFolder = directory.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: downloadsFolder, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: file), let d = try? JSONDecoder().decode(Document.self, from: data) {
            doc = d
        } else {
            doc = Document()
        }
    }

    public var liked: [Song] { queue.sync { doc.liked } }
    public var history: [PlayRecord] { queue.sync { doc.history } }
    public var playlists: [LocalPlaylist] { queue.sync { doc.playlists } }
    public var saved: [SavedCollection] { queue.sync { doc.saved } }
    public var downloads: [DownloadedSong] { queue.sync { doc.downloads } }
    public var searches: [String] { queue.sync { doc.searches } }

    public func isLiked(_ videoId: String) -> Bool { queue.sync { doc.liked.contains { $0.videoId == videoId } } }

    /** Likes or unlikes [song]; returns whether it is now liked. */
    @discardableResult
    public func toggleLike(_ song: Song) -> Bool {
        queue.sync {
            if let i = doc.liked.firstIndex(where: { $0.videoId == song.videoId }) {
                doc.liked.remove(at: i)
                save()
                return false
            }
            doc.liked.insert(song, at: 0)
            save()
            return true
        }
    }

    public func recordPlay(_ song: Song, at date: Date = Date()) {
        queue.sync {
            // Played again soon after: one listen, moved to the top.
            doc.history.removeAll { $0.song.videoId == song.videoId && date.timeIntervalSince($0.playedAt) < 600 }
            doc.history.insert(PlayRecord(song: song, playedAt: date), at: 0)
            if doc.history.count > Self.historyLimit { doc.history.removeLast(doc.history.count - Self.historyLimit) }
            save()
        }
    }

    /** Recent distinct songs, newest first. */
    public func recentSongs(limit: Int = 20) -> [Song] {
        var seen = Set<String>()
        return history.map(\.song).filter { seen.insert($0.videoId).inserted }.prefix(limit).map { $0 }
    }

    public func clearHistory() {
        queue.sync {
            doc.history = []
            save()
        }
    }

    // MARK: Playlists

    /** A new, empty playlist at the top of the list; its id is returned. */
    @discardableResult
    public func createPlaylist(_ name: String, at date: Date = Date()) -> String {
        let id = "pl-" + UUID().uuidString.prefix(8).lowercased()
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        queue.sync {
            doc.playlists.insert(LocalPlaylist(id: id, name: clean.isEmpty ? "New playlist" : clean, songs: [], createdAt: date), at: 0)
            save()
        }
        return id
    }

    public func renamePlaylist(_ id: String, to name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        editPlaylist(id) { $0.name = clean }
    }

    public func deletePlaylist(_ id: String) {
        queue.sync {
            doc.playlists.removeAll { $0.id == id }
            save()
        }
    }

    /** Adds [song] at the end; false when it was already there. */
    @discardableResult
    public func addToPlaylist(_ id: String, _ song: Song) -> Bool {
        var added = false
        editPlaylist(id) { p in
            guard !p.songs.contains(where: { $0.videoId == song.videoId }) else { return }
            p.songs.append(song)
            added = true
        }
        return added
    }

    public func removeFromPlaylist(_ id: String, videoId: String) {
        editPlaylist(id) { $0.songs.removeAll { $0.videoId == videoId } }
    }

    /** The playlist's songs in a new order, as the list's drag left them. */
    public func reorderPlaylist(_ id: String, _ songs: [Song]) {
        editPlaylist(id) { p in
            // Only a reorder: nothing added or lost on the way.
            guard Set(songs.map(\.videoId)) == Set(p.songs.map(\.videoId)), songs.count == p.songs.count else { return }
            p.songs = songs
        }
    }

    private func editPlaylist(_ id: String, _ change: (inout LocalPlaylist) -> Void) {
        queue.sync {
            guard let i = doc.playlists.firstIndex(where: { $0.id == id }) else { return }
            change(&doc.playlists[i])
            save()
        }
    }

    // MARK: Saved albums and playlists

    public func isSaved(_ browseId: String) -> Bool { queue.sync { doc.saved.contains { $0.browseId == browseId } } }

    /** Saves or forgets [item]; returns whether it is now saved. */
    @discardableResult
    public func toggleSaved(_ item: SavedCollection) -> Bool {
        queue.sync {
            if let i = doc.saved.firstIndex(where: { $0.browseId == item.browseId }) {
                doc.saved.remove(at: i)
                save()
                return false
            }
            doc.saved.insert(item, at: 0)
            save()
            return true
        }
    }

    // MARK: Downloads

    public func download(_ videoId: String) -> DownloadedSong? { queue.sync { doc.downloads.first { $0.song.videoId == videoId } } }

    /** The downloaded file for [videoId], when it's there and on disk. */
    public func downloadedFile(_ videoId: String) -> URL? {
        guard let d = download(videoId) else { return nil }
        let url = downloadsFolder.appendingPathComponent(d.fileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    public func addDownload(_ d: DownloadedSong) {
        queue.sync {
            doc.downloads.removeAll { $0.song.videoId == d.song.videoId }
            doc.downloads.insert(d, at: 0)
            save()
        }
    }

    /** Forgets the download and deletes its file. */
    public func removeDownload(_ videoId: String) {
        queue.sync {
            for d in doc.downloads where d.song.videoId == videoId {
                try? FileManager.default.removeItem(at: downloadsFolder.appendingPathComponent(d.fileName))
            }
            doc.downloads.removeAll { $0.song.videoId == videoId }
            save()
        }
    }

    // MARK: Recent searches

    /** Keeps [query] at the top of recent searches, once. */
    public func recordSearch(_ query: String) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        queue.sync {
            doc.searches.removeAll { $0.caseInsensitiveCompare(q) == .orderedSame }
            doc.searches.insert(q, at: 0)
            if doc.searches.count > Self.searchLimit { doc.searches.removeLast(doc.searches.count - Self.searchLimit) }
            save()
        }
    }

    public func removeSearch(_ query: String) {
        queue.sync {
            doc.searches.removeAll { $0 == query }
            save()
        }
    }

    public func clearSearches() {
        queue.sync {
            doc.searches = []
            save()
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(doc) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
