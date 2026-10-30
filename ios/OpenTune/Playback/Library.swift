import Foundation
import OpenTuneCore

/** Likes, playlists, saved albums, downloads and history for the screens, on top of the core's store. */
@MainActor
final class Library: ObservableObject {
    @Published private(set) var liked: [Song] = []
    @Published private(set) var recent: [Song] = []
    @Published private(set) var historyCount = 0
    @Published private(set) var playlists: [LocalPlaylist] = []
    @Published private(set) var saved: [SavedCollection] = []
    @Published private(set) var downloads: [DownloadedSong] = []
    @Published private(set) var searches: [String] = []
    /** Songs being saved now, with how far along each is, 0 to 1. */
    @Published private(set) var downloading: [String: Double] = [:]
    /** The last download that failed, for a message. */
    @Published var downloadError: String?

    private let store: LibraryStore
    private var downloadTasks: [String: Task<Void, Never>] = [:]
    private var waiting: [Song] = []

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OpenTune")
        store = LibraryStore(directory: dir)
        refresh()
    }

    func isLiked(_ song: Song) -> Bool { liked.contains { $0.videoId == song.videoId } }

    func toggleLike(_ song: Song) {
        store.toggleLike(song)
        refresh()
    }

    func recordPlay(_ song: Song) {
        store.recordPlay(song)
        refresh()
    }

    func clearHistory() {
        store.clearHistory()
        refresh()
    }

    var history: [PlayRecord] { store.history }

    // MARK: Playlists

    @discardableResult
    func createPlaylist(_ name: String, with song: Song? = nil) -> String {
        let id = store.createPlaylist(name)
        if let song { store.addToPlaylist(id, song) }
        refresh()
        return id
    }

    func renamePlaylist(_ id: String, to name: String) {
        store.renamePlaylist(id, to: name)
        refresh()
    }

    func deletePlaylist(_ id: String) {
        store.deletePlaylist(id)
        refresh()
    }

    @discardableResult
    func add(_ song: Song, to playlist: String) -> Bool {
        let added = store.addToPlaylist(playlist, song)
        refresh()
        return added
    }

    func remove(_ videoId: String, from playlist: String) {
        store.removeFromPlaylist(playlist, videoId: videoId)
        refresh()
    }

    func reorder(_ playlist: String, _ songs: [Song]) {
        store.reorderPlaylist(playlist, songs)
        refresh()
    }

    func playlist(_ id: String) -> LocalPlaylist? { playlists.first { $0.id == id } }

    // MARK: Saved albums and playlists

    func isSaved(_ browseId: String) -> Bool { saved.contains { $0.browseId == browseId } }

    func toggleSaved(_ item: SavedCollection) {
        store.toggleSaved(item)
        refresh()
    }

    // MARK: Downloads

    func isDownloaded(_ song: Song) -> Bool { downloads.contains { $0.song.videoId == song.videoId } }

    /** The saved file and its loudness, for playing without the network. */
    func offline(_ videoId: String) -> (url: URL, loudnessDb: Double?)? {
        guard let url = store.downloadedFile(videoId) else { return nil }
        return (url, store.download(videoId)?.loudnessDb)
    }

    /** Queues [song] to be saved; two download at a time, the rest wait their turn. */
    func download(_ song: Song) {
        guard !isDownloaded(song), downloading[song.videoId] == nil else { return }
        downloading[song.videoId] = 0
        waiting.append(song)
        startDownloads()
    }

    private func startDownloads() {
        while downloadTasks.count < 2, !waiting.isEmpty {
            let song = waiting.removeFirst()
            let id = song.videoId
            downloadTasks[id] = Task { [weak self] in
                guard let self else { return }
                do {
                    let stream = try await Innertube.shared.stream(id)
                    let ext = (stream.mimeType ?? "").contains("mp4") ? "m4a" : "audio"
                    let name = id + "." + ext
                    let bytes = try await SongDownloader.fetch(stream, to: self.store.downloadsFolder.appendingPathComponent(name)) { fraction in
                        Task { @MainActor [weak self] in
                            if self?.downloading[id] != nil { self?.downloading[id] = fraction }
                        }
                    }
                    self.store.addDownload(DownloadedSong(song: song, fileName: name, bytes: bytes, loudnessDb: stream.loudnessDb))
                } catch is CancellationError {
                } catch {
                    if !Task.isCancelled { self.downloadError = "Couldn't save “\(song.title)”. \(error)" }
                }
                self.downloadTasks[id] = nil
                self.downloading[id] = nil
                self.refresh()
                self.startDownloads()
            }
        }
    }

    func cancelDownload(_ videoId: String) {
        if let i = waiting.firstIndex(where: { $0.videoId == videoId }) {
            waiting.remove(at: i)
            downloading[videoId] = nil
        }
        downloadTasks[videoId]?.cancel()
    }

    func removeDownload(_ videoId: String) {
        store.removeDownload(videoId)
        refresh()
    }

    /** Every song in [songs] not saved yet, one after another. */
    func downloadAll(_ songs: [Song]) {
        for song in songs { download(song) }
    }

    var downloadedBytes: Int64 { downloads.reduce(0) { $0 + $1.bytes } }

    // MARK: Recent searches

    func recordSearch(_ query: String) {
        store.recordSearch(query)
        refresh()
    }

    func removeSearch(_ query: String) {
        store.removeSearch(query)
        refresh()
    }

    func clearSearches() {
        store.clearSearches()
        refresh()
    }

    private func refresh() {
        liked = store.liked
        recent = store.recentSongs(limit: 20)
        historyCount = store.history.count
        playlists = store.playlists
        saved = store.saved
        downloads = store.downloads
        searches = store.searches
    }
}
