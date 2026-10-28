import OpenTuneCore
import SwiftUI

/** An album or playlist: blurred cover behind the header, then its tracks. */
struct CollectionView: View {
    @EnvironmentObject private var player: Player
    let browseId: String
    var initialTitle: String?
    @State private var page: CollectionPage?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                if let page {
                    HStack(spacing: 12) {
                        Button { player.play(page.songs) } label: { Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent)
                        Button { player.play(page.songs.shuffled()) } label: { Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity) }
                            .buttonStyle(.bordered)
                    }
                    .controlSize(.large)
                    .padding()
                    .disabled(page.songs.isEmpty)
                    let isAlbum = BrowseType.of(browseId) == .album
                    LazyVStack(spacing: 10) {
                        ForEach(Array(page.songs.enumerated()), id: \.element.videoId) { i, song in
                            SongRow(song: song, number: isAlbum ? i + 1 : nil) { player.play(page.songs, from: i) }
                        }
                    }
                    .padding(.horizontal)
                    if let d = page.description, !d.isEmpty {
                        Text(d).font(.footnote).foregroundStyle(Theme.subtle).padding()
                    }
                } else if let error {
                    ErrorState(message: error) { Task { await load() } }
                } else {
                    ProgressView().padding(40)
                }
            }
            .padding(.bottom)
        }
        .background(Theme.background)
        .navigationTitle(page?.title ?? initialTitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { if page == nil { await load() } }
    }

    private var header: some View {
        ZStack {
            Artwork(url: page?.thumbnailUrl, size: Art.header, corner: 0)
                .frame(height: 360).blur(radius: 50).opacity(0.55).clipped()
                .overlay(LinearGradient(colors: [.clear, Theme.background], startPoint: .center, endPoint: .bottom))
            VStack(spacing: 10) {
                Artwork(url: page?.thumbnailUrl, size: Art.header, corner: 14)
                    .frame(width: 220, height: 220)
                    .shadow(color: .black.opacity(0.5), radius: 24, y: 10)
                Text(page?.title ?? initialTitle ?? "").font(.title2.weight(.bold)).multilineTextAlignment(.center)
                if let page {
                    SubtitleLine(text: [page.subtitle, "\(page.songs.count) songs"].filter { !$0.isEmpty }.joined(separator: " • "),
                                 explicit: BrowseType.of(browseId) == .album && page.songs.contains { $0.isExplicit == true })
                }
            }
            .padding(.top, 20)
            .padding(.horizontal)
        }
    }

    private func load() async {
        do { page = try await MusicRepository.collection(browseId); error = nil } catch { self.error = "Couldn't open this page. \(error)" }
    }
}

struct ArtistView: View {
    @EnvironmentObject private var player: Player
    @Environment(\.openRoute) private var openRoute
    let browseId: String
    @State private var page: ArtistPage?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let page {
                    ZStack(alignment: .bottomLeading) {
                        Artwork(url: page.thumbnailUrl, size: Art.header, corner: 0)
                            .frame(height: 320).frame(maxWidth: .infinity).clipped()
                            .overlay(LinearGradient(colors: [.clear, Theme.background], startPoint: .center, endPoint: .bottom))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(page.name ?? "").font(.largeTitle.weight(.bold))
                            if let listeners = page.monthlyListenerCount ?? page.subscriberCountText {
                                Text(listeners).font(.subheadline).foregroundStyle(Theme.subtle)
                            }
                        }
                        .padding()
                    }
                    if !page.songs.isEmpty {
                        HStack {
                            SectionTitle(title: "Top songs")
                            if let more = page.moreSongsBrowseId {
                                Button("All") { openRoute?(.collection(browseId: more, title: page.name)) }
                            }
                        }
                        .padding(.horizontal)
                        VStack(spacing: 10) {
                            ForEach(Array(page.songs.enumerated()), id: \.element.videoId) { i, song in
                                SongRow(song: song) { player.play(page.songs, from: i) }
                            }
                        }
                        .padding(.horizontal)
                    }
                    ForEach(page.shelves) { shelf in
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitle(title: shelf.title).padding(.horizontal)
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(alignment: .top, spacing: 14) {
                                    ForEach(shelf.items) { item in
                                        Button {
                                            if let id = item.browseId { openRoute?(.of(browseId: id, title: item.title)) } else if let s = item.song { player.play([s]) }
                                        } label: { ShelfCard(item: item, width: 140) }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal)
                            }
                        }
                    }
                    if let d = page.description, !d.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            SectionTitle(title: "About")
                            Text(d).font(.footnote).foregroundStyle(Theme.subtle).lineLimit(8)
                        }
                        .padding(.horizontal)
                    }
                } else if let error {
                    ErrorState(message: error) { Task { await load() } }
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(60)
                }
            }
            .padding(.bottom)
        }
        .ignoresSafeArea(edges: .top)
        .background(Theme.background)
        .navigationBarTitleDisplayMode(.inline)
        .task { if page == nil { await load() } }
    }

    private func load() async {
        do { page = try await MusicRepository.artist(browseId); error = nil } catch { self.error = "Couldn't open this artist. \(error)" }
    }
}

struct LibraryView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @State private var tab = 0
    @State private var confirmClear = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                Text("Liked songs").tag(0)
                Text("History").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()
            List {
                if tab == 0 {
                    if library.liked.isEmpty {
                        empty("No liked songs yet", "Tap the heart in the player to keep a song here.")
                    } else {
                        Button { player.play(library.liked.shuffled()) } label: { Label("Shuffle all", systemImage: "shuffle") }
                            .listRowBackground(Color.clear)
                        ForEach(Array(library.liked.enumerated()), id: \.element.videoId) { i, song in
                            SongRow(song: song) { player.play(library.liked, from: i) }.listRowBackground(Color.clear)
                        }
                    }
                } else {
                    let history = library.history
                    if history.isEmpty {
                        empty("Nothing played yet", "Songs you listen to show up here. They stay on this phone.")
                    } else {
                        ForEach(Array(history.enumerated()), id: \.offset) { _, record in
                            SongRow(song: record.song) { player.play([record.song]) }.listRowBackground(Color.clear)
                        }
                        Button("Clear history", role: .destructive) { confirmClear = true }.listRowBackground(Color.clear)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
        .background(Theme.background)
        .navigationTitle("Library")
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) {
            NavigationLink { AppearanceView() } label: { Image(systemName: "gearshape").accessibilityLabel("Appearance settings") }
        } }
        .confirmationDialog("Clear listening history?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { library.clearHistory() }
        }
    }

    private func empty(_ title: String, _ message: String) -> some View {
        VStack(spacing: 6) {
            Text(title).font(.headline)
            Text(message).font(.subheadline).foregroundStyle(Theme.subtle).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .listRowBackground(Color.clear)
    }
}
