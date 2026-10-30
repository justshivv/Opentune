import OpenTuneCore
import SwiftUI

struct SearchView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @Environment(\.openRoute) private var openRoute
    @State private var query = ""
    @State private var results: [SearchResult] = []
    @State private var suggestions: [String] = []
    @State private var searching = false
    @State private var error: String?
    @State private var searched = ""

    var body: some View {
        List {
            if query.isEmpty, searched.isEmpty, !library.searches.isEmpty {
                Section {
                    ForEach(library.searches, id: \.self) { s in
                        Button { query = s; submit() } label: {
                            Label(s, systemImage: "clock.arrow.circlepath").foregroundStyle(.white)
                        }
                        .swipeActions { Button(role: .destructive) { library.removeSearch(s) } label: { Label("Remove", systemImage: "xmark") } }
                    }
                } header: {
                    HStack {
                        Text("Recent searches")
                        Spacer()
                        Button("Clear") { library.clearSearches() }.font(.caption)
                    }
                }
                .listRowBackground(Color.clear)
            }
            if query != searched, !suggestions.isEmpty {
                Section {
                    ForEach(suggestions, id: \.self) { s in
                        Button { query = s; submit() } label: {
                            Label(s, systemImage: "magnifyingglass").foregroundStyle(.white)
                        }
                    }
                }
            }
            if searching {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            } else if let error {
                ErrorState(message: error) { submit() }.listRowBackground(Color.clear)
            } else if !searched.isEmpty && results.isEmpty {
                Text("Nothing found for “\(searched)”").foregroundStyle(Theme.subtle).listRowBackground(Color.clear)
            }
            ForEach(results) { r in row(r).listRowBackground(Color.clear) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Songs, albums, artists")
        .onSubmit(of: .search, submit)
        .onChange(of: query) { q in if q.isEmpty { searched = ""; results = [] } }
        .task(id: query) {
            guard !query.isEmpty, query != searched else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 250_000_000)
            suggestions = (try? await MusicRepository.suggestions(query)) ?? []
        }
    }

    @ViewBuilder private func row(_ r: SearchResult) -> some View {
        switch r {
        case let .topTrack(song):
            Button { player.play([song]) } label: {
                HStack(spacing: 14) {
                    Artwork(url: song.thumbnailUrl, size: Art.card, corner: 10).frame(width: 84, height: 84)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Top result").font(.caption.weight(.semibold)).foregroundStyle(Theme.subtle)
                        Text(song.title).font(.title3.weight(.bold)).lineLimit(2)
                        SubtitleLine(text: song.artist, explicit: song.isExplicit == true)
                    }
                    Spacer()
                    Image(systemName: "play.circle.fill").font(.system(size: 40)).foregroundStyle(Theme.accent)
                }
                .padding(12)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
        case let .track(song):
            SongRow(song: song) { player.play([song]) }
        case let .browse(item):
            Button { openRoute?(item.type == .artist || item.browseId.hasPrefix("UC") ? .artist(browseId: item.browseId) : .collection(browseId: item.browseId, title: item.title)) } label: {
                HStack(spacing: 12) {
                    Artwork(url: item.thumbnailUrl, circle: item.type == .artist).frame(width: 50, height: 50)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                        SubtitleLine(text: item.subtitle, explicit: item.explicit)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Theme.subtle)
                }
            }
            .buttonStyle(.plain)
        }
    }

    private func submit() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        searched = q
        library.recordSearch(q)
        suggestions = []
        searching = true
        error = nil
        Task {
            defer { searching = false }
            do { results = try await MusicRepository.search(q) } catch { self.error = "Search didn't go through. \(error)" }
        }
    }
}
