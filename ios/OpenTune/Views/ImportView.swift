import OpenTuneCore
import SwiftUI
import UniformTypeIdentifiers

/**
 * Brings a playlist exported as CSV (Exportify for Spotify, TuneMyMusic,
 * Soundiiz…) over as a playlist on the phone: each song is looked up on
 * YouTube Music and the closest catalogue song kept. Lives outside the
 * screen so closing it doesn't stop the import.
 */
@MainActor
final class PlaylistImporter: ObservableObject {
    static let shared = PlaylistImporter()
    private static let parallel = 4

    struct Progress {
        var name: String
        var total: Int
        var done = 0
        var matched = 0
        var missed: [String] = []
        var playlistId: String?
        var finished = false
        var error: String?
    }

    @Published private(set) var progress: Progress?
    private var task: Task<Void, Never>?

    var running: Bool { task != nil }

    func start(name: String, tracks: [ImportTrack], library: Library) {
        guard task == nil else { return }
        progress = Progress(name: name, total: tracks.count)
        task = Task {
            var found = [Song?](repeating: nil, count: tracks.count)
            var failed = 0
            await withTaskGroup(of: (Int, Result<Song?, Error>).self) { group in
                var next = 0
                func addNext() {
                    guard next < tracks.count else { return }
                    let i = next, track = tracks[i]
                    next += 1
                    group.addTask {
                        do { return (i, .success(try await PlaylistMatch.match(track))) } catch { return (i, .failure(error)) }
                    }
                }
                for _ in 0..<Self.parallel { addNext() }
                while let (i, result) = await group.next() {
                    // Lookups cut short by Cancel weren't misses.
                    if Task.isCancelled { group.cancelAll(); continue }
                    let song = try? result.get()
                    if case .failure = result { failed += 1 }
                    found[i] = song
                    progress?.done += 1
                    if song != nil {
                        progress?.matched += 1
                    } else {
                        let t = tracks[i]
                        progress?.missed.append([t.artists.first, t.title].compactMap { $0 }.joined(separator: " – "))
                    }
                    addNext()
                }
            }
            if Task.isCancelled {
                progress?.error = "Cancelled"
            } else if failed > 0 && progress?.matched == 0 {
                progress?.error = "Couldn't reach YouTube Music"
            } else {
                progress?.playlistId = library.createPlaylist(name, songs: found.compactMap { $0 })
            }
            progress?.finished = true
            task = nil
        }
    }

    func cancel() { task?.cancel() }

    func dismiss() { if task == nil { progress = nil } }
}

struct ImportView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var importer = PlaylistImporter.shared
    let library: Library
    /** Opens the finished playlist in Library. */
    let open: (String) -> Void
    @State private var picking = false
    @State private var problem: String?

    var body: some View {
        NavigationStack {
            Group {
                if let p = importer.progress { progressView(p) } else { start }
            }
            .background(Theme.background)
            .navigationTitle("Import a playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Close") { dismiss() } } }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.commaSeparatedText, .plainText]) { result in
            switch result {
            case let .success(url): read(url)
            case let .failure(error): problem = error.localizedDescription
            }
        }
        .alert("Couldn't import", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(problem ?? "") }
    }

    private var start: some View {
        List {
            Section {
                Text("Bring a Spotify playlist or your Liked Songs over. Each song is found on YouTube Music and saved as a playlist on this phone. Nothing streams from Spotify.")
                    .font(.subheadline).foregroundStyle(Theme.subtle)
            }
            Section("1. Export from Spotify") {
                Link(destination: URL(string: "https://exportify.app")!) {
                    Label("Open Exportify", systemImage: "safari")
                }
                Text("Sign in with Spotify there and tap Export next to a playlist. The CSV file is saved to Files. A CSV from TuneMyMusic, Soundiiz and similar works too.")
                    .font(.footnote).foregroundStyle(Theme.subtle)
            }
            Section("2. Import it") {
                Button { picking = true } label: { Label("Choose CSV file", systemImage: "doc.text") }
            }
        }
        .scrollContentBackground(.hidden)
    }

    private func progressView(_ p: PlaylistImporter.Progress) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text(p.name).font(.headline)
                    ProgressView(value: Double(p.done), total: Double(max(p.total, 1)))
                    Text(status(p)).font(.subheadline).foregroundStyle(Theme.subtle)
                }
                .padding(.vertical, 4)
                if !p.finished {
                    Button("Cancel", role: .destructive) { importer.cancel() }
                } else {
                    if let id = p.playlistId {
                        Button { dismiss(); open(id) } label: { Label("Open playlist", systemImage: "music.note.list") }
                    }
                    Button("Import another") { importer.dismiss() }
                }
            }
            if !p.missed.isEmpty {
                Section("Not found on YouTube Music (\(p.missed.count))") {
                    ForEach(Array(p.missed.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.subheadline).foregroundStyle(Theme.subtle)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    private func status(_ p: PlaylistImporter.Progress) -> String {
        if let error = p.error { return "\(error). Nothing was saved." }
        if p.finished { return "Saved \(p.matched) of \(p.total) songs." }
        return "Finding songs: \(p.done) of \(p.total), \(p.matched) found"
    }

    private func read(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { problem = "The file couldn't be read."; return }
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        let tracks = PlaylistCsv.parse(text)
        guard !tracks.isEmpty else {
            problem = "No songs found. The file needs a header row with track name and artist columns."
            return
        }
        // Exportify names files like "liked_songs.csv".
        let name = url.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "_", with: " ")
        importer.start(name: name, tracks: tracks, library: library)
    }
}
