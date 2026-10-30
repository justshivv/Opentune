import OpenTuneCore
import SwiftUI

/** Liked songs, playlists, saved albums, downloads and history. */
struct LibraryView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @Environment(\.openRoute) private var openRoute
    @AppStorage("library.tab") private var tab = Tab.playlists.rawValue
    @State private var confirmClear = false
    @State private var naming = false
    @State private var newName = ""

    enum Tab: String, CaseIterable {
        case playlists = "Playlists", liked = "Liked", saved = "Albums", downloads = "Downloads", history = "History"
    }

    private var current: Tab { Tab(rawValue: tab) ?? .playlists }

    var body: some View {
        VStack(spacing: 0) {
            chips
            List {
                switch current {
                case .playlists: playlists
                case .liked: liked
                case .saved: saved
                case .downloads: downloads
                case .history: history
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .animation(.easeInOut(duration: 0.2), value: tab)
        }
        .background(Theme.background)
        .navigationTitle("Library")
        .toolbar { ToolbarItem(placement: .navigationBarTrailing) {
            NavigationLink { AppearanceView() } label: { Image(systemName: "gearshape").accessibilityLabel("Settings") }
        } }
        .confirmationDialog("Clear listening history?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { library.clearHistory() }
        }
        .alert("New playlist", isPresented: $naming) {
            TextField("Name", text: $newName)
            Button("Create") {
                let id = library.createPlaylist(newName)
                newName = ""
                openRoute?(.playlist(id: id))
            }
            Button("Cancel", role: .cancel) { newName = "" }
        }
        .alert("Download failed", isPresented: Binding(get: { library.downloadError != nil }, set: { if !$0 { library.downloadError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(library.downloadError ?? "") }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Tab.allCases, id: \.self) { t in
                    let on = current == t
                    Button { tab = t.rawValue } label: {
                        Text(t.rawValue)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(on ? .black : .white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(on ? AnyShapeStyle(.white) : AnyShapeStyle(Theme.card), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
        }
    }

    @ViewBuilder private var playlists: some View {
        Button { naming = true } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(Theme.card)
                    Image(systemName: "plus").font(.title3.weight(.semibold))
                }
                .frame(width: 56, height: 56)
                Text("New playlist").font(.body.weight(.medium))
            }
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        ForEach(library.playlists) { p in
            Button { openRoute?(.playlist(id: p.id)) } label: {
                HStack(spacing: 12) {
                    PlaylistCover(playlist: p).frame(width: 56, height: 56)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.name).font(.body.weight(.medium)).lineLimit(1)
                        Text(songCount(p.songs.count)).font(.subheadline).foregroundStyle(Theme.subtle)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(Theme.subtle)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .listRowBackground(Color.clear)
            .swipeActions { Button(role: .destructive) { library.deletePlaylist(p.id) } label: { Label("Delete", systemImage: "trash") } }
        }
        if library.playlists.isEmpty {
            empty("No playlists yet", "Make one here, or add a song to one from its menu.")
        }
    }

    @ViewBuilder private var liked: some View {
        if library.liked.isEmpty {
            empty("No liked songs yet", "Tap the heart in the player to keep a song here.")
        } else {
            playShuffle(library.liked)
            ForEach(Array(library.liked.enumerated()), id: \.element.videoId) { i, song in
                SongRow(song: song) { player.play(library.liked, from: i) }.listRowBackground(Color.clear)
            }
        }
    }

    @ViewBuilder private var saved: some View {
        if library.saved.isEmpty {
            empty("No saved albums or playlists", "Tap the heart at the top of an album or playlist to keep it here.")
        } else {
            ForEach(library.saved) { item in
                Button { openRoute?(.collection(browseId: item.browseId, title: item.title)) } label: {
                    HStack(spacing: 12) {
                        Artwork(url: item.thumbnailUrl).frame(width: 56, height: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.title).font(.body.weight(.medium)).lineLimit(1)
                            Text(item.subtitle).font(.subheadline).foregroundStyle(Theme.subtle).lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(Theme.subtle)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.clear)
                .swipeActions { Button(role: .destructive) { library.toggleSaved(item) } label: { Label("Remove", systemImage: "heart.slash") } }
            }
        }
    }

    @ViewBuilder private var downloads: some View {
        let songs = library.downloads.map(\.song)
        if songs.isEmpty && library.downloading.isEmpty {
            empty("Nothing downloaded", "Choose Download in a song's menu, or Download all on an album, to listen without the internet.")
        } else {
            if !songs.isEmpty {
                playShuffle(songs)
                Text("\(songCount(songs.count)) • \(ByteCountFormatter.string(fromByteCount: library.downloadedBytes, countStyle: .file)) on this phone")
                    .font(.caption).foregroundStyle(Theme.subtle).listRowBackground(Color.clear)
            }
            if !library.downloading.isEmpty {
                Text("Downloading \(library.downloading.count)…").font(.caption.weight(.semibold)).foregroundStyle(Theme.accent).listRowBackground(Color.clear)
            }
            ForEach(Array(songs.enumerated()), id: \.element.videoId) { i, song in
                SongRow(song: song) { player.play(songs, from: i) }
                    .listRowBackground(Color.clear)
                    .swipeActions { Button(role: .destructive) { library.removeDownload(song.videoId) } label: { Label("Remove", systemImage: "trash") } }
            }
        }
    }

    @ViewBuilder private var history: some View {
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

    private func playShuffle(_ songs: [Song]) -> some View {
        HStack(spacing: 12) {
            Button { player.play(songs) } label: { Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
            Button { player.play(songs.shuffled()) } label: { Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
        .controlSize(.regular)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    private func empty(_ title: String, _ message: String) -> some View {
        VStack(spacing: 6) {
            Text(title).font(.headline)
            Text(message).font(.subheadline).foregroundStyle(Theme.subtle).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}

/** A playlist made on the phone: play it, reorder it, rename it, download it. */
struct LocalPlaylistView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var renaming = false
    @State private var name = ""
    @State private var confirmDelete = false

    var body: some View {
        Group {
            if let p = library.playlist(id) {
                List {
                    header(p).listRowBackground(Color.clear).listRowSeparator(.hidden)
                    ForEach(Array(p.songs.enumerated()), id: \.element.videoId) { i, song in
                        SongRow(song: song, inPlaylist: id) { player.play(p.songs, from: i) }.listRowBackground(Color.clear)
                    }
                    .onDelete { offsets in offsets.map { p.songs[$0].videoId }.forEach { library.remove($0, from: id) } }
                    .onMove { from, to in
                        var songs = p.songs
                        songs.move(fromOffsets: from, toOffset: to)
                        library.reorder(id, songs)
                    }
                    if p.songs.isEmpty {
                        Text("Add songs from any song's menu: Add to playlist.")
                            .font(.subheadline).foregroundStyle(Theme.subtle).frame(maxWidth: .infinity).padding(.vertical, 30)
                            .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .navigationTitle(p.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItemGroup(placement: .navigationBarTrailing) {
                        EditButton()
                        Menu {
                            Button { name = p.name; renaming = true } label: { Label("Rename", systemImage: "pencil") }
                            Button { library.downloadAll(p.songs) } label: { Label("Download all", systemImage: "arrow.down.circle") }
                            Button(role: .destructive) { confirmDelete = true } label: { Label("Delete playlist", systemImage: "trash") }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            } else {
                Text("This playlist was deleted.").foregroundStyle(Theme.subtle)
            }
        }
        .background(Theme.background)
        .alert("Rename playlist", isPresented: $renaming) {
            TextField("Name", text: $name)
            Button("Save") { library.renamePlaylist(id, to: name) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Delete this playlist?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                library.deletePlaylist(id)
                dismiss()
            }
        }
    }

    private func header(_ p: LocalPlaylist) -> some View {
        VStack(spacing: 12) {
            PlaylistCover(playlist: p, corner: 14)
                .frame(width: 200, height: 200)
                .shadow(color: .black.opacity(0.5), radius: 24, y: 10)
            Text(p.name).font(.title2.weight(.bold)).multilineTextAlignment(.center)
            Text([songCount(p.songs.count), totalLength(p.songs)].filter { !$0.isEmpty }.joined(separator: " • "))
                .font(.subheadline).foregroundStyle(Theme.subtle)
            HStack(spacing: 12) {
                Button { player.play(p.songs) } label: { Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                Button { player.play(p.songs.shuffled()) } label: { Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
            }
            .controlSize(.large)
            .disabled(p.songs.isEmpty)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    private func totalLength(_ songs: [Song]) -> String {
        let total = songs.reduce(Int64(0)) { $0 + $1.durationMs } / 1000
        guard total > 0 else { return "" }
        return total >= 3600 ? "\(total / 3600) h \(total % 3600 / 60) min" : "\(total / 60) min"
    }
}
