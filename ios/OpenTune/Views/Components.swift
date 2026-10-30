import OpenTuneCore
import SwiftUI

enum Theme {
    static let accent = Color(red: 1.0, green: 0.353, blue: 0.396)
    static let background = Color(red: 0.043, green: 0.043, blue: 0.055)
    static let card = Color(white: 0.11)
    static let subtle = Color.white.opacity(0.6)
}

/** Where a tap on a card or row goes. */
enum Route: Hashable {
    case collection(browseId: String, title: String?)
    case artist(browseId: String)
    /** A playlist made on the phone. */
    case playlist(id: String)

    static func of(browseId: String, title: String? = nil) -> Route {
        BrowseType.of(browseId) == .artist ? .artist(browseId: browseId) : .collection(browseId: browseId, title: title)
    }
}

/** A cover, fetched at the size it's drawn. */
struct Artwork: View {
    let url: String?
    var size: Int = Art.row
    var corner: CGFloat = 8
    var circle = false

    var body: some View {
        AsyncImage(url: url.artworkAt(size), transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
            switch phase {
            case let .success(image): image.resizable().scaledToFill()
            default:
                ZStack {
                    Theme.card
                    Image(systemName: "music.note").foregroundStyle(.white.opacity(0.35))
                }
            }
        }
        .clipShape(circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: corner, style: .continuous)))
    }
}

/** YouTube Music's explicit mark, a small square E in front of a subtitle. */
struct ExplicitBadge: View {
    var body: some View {
        Text("E")
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(Theme.subtle)
            .frame(width: 14, height: 14)
            .background(RoundedRectangle(cornerRadius: 3).fill(.white.opacity(0.18)))
            .accessibilityLabel("Explicit")
    }
}

struct SubtitleLine: View {
    let text: String
    var explicit = false
    var font: Font = .subheadline

    var body: some View {
        HStack(spacing: 5) {
            if explicit { ExplicitBadge() }
            Text(text).font(font).foregroundStyle(Theme.subtle).lineLimit(1)
        }
    }
}

/** One track in a list: tap plays, the menu queues it, likes it or opens its pages. */
struct SongRow: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    let song: Song
    var number: Int?
    var inPlaylist: String?
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                if let number {
                    Text("\(number)").font(.subheadline.monospacedDigit()).foregroundStyle(Theme.subtle).frame(width: 28)
                } else {
                    ZStack {
                        Artwork(url: song.thumbnailUrl).frame(width: 50, height: 50)
                        if player.current?.videoId == song.videoId {
                            RoundedRectangle(cornerRadius: 8).fill(.black.opacity(0.45)).frame(width: 50, height: 50)
                            NowPlayingBars(playing: player.isPlaying)
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).font(.body.weight(.medium)).lineLimit(1)
                        .foregroundStyle(player.current?.videoId == song.videoId ? Theme.accent : .white)
                    HStack(spacing: 5) {
                        if library.isDownloaded(song) {
                            Image(systemName: "arrow.down.circle.fill").font(.caption2).foregroundStyle(Theme.accent).accessibilityLabel("Downloaded")
                        }
                        SubtitleLine(text: [song.artist, song.durationText].compactMap { $0 }.joined(separator: " • "), explicit: song.isExplicit == true)
                    }
                }
                Spacer(minLength: 0)
                if let fraction = library.downloading[song.videoId] {
                    ZStack {
                        Circle().stroke(.white.opacity(0.15), lineWidth: 2.5)
                        Circle().trim(from: 0, to: max(0.03, fraction))
                            .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .animation(.easeOut(duration: 0.3), value: fraction)
                    }
                    .frame(width: 18, height: 18)
                    .accessibilityLabel("Downloading")
                }
                SongMenu(song: song, inPlaylist: inPlaylist).foregroundStyle(Theme.subtle)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/** The "…" menu for a song. */
struct SongMenu: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @Environment(\.openRoute) private var openRoute
    let song: Song
    /** The playlist this row is in, for a "Remove from playlist" item. */
    var inPlaylist: String?
    @State private var choosingPlaylist = false

    var body: some View {
        Menu {
            Button { player.playNext(song) } label: { Label("Play next", systemImage: "text.insert") }
            Button { player.addToQueue(song) } label: { Label("Add to queue", systemImage: "text.append") }
            Button { player.play([song]) } label: { Label("Start radio", systemImage: "dot.radiowaves.left.and.right") }
            Button { library.toggleLike(song) } label: {
                library.isLiked(song) ? Label("Remove from liked", systemImage: "heart.slash") : Label("Like", systemImage: "heart")
            }
            Button { choosingPlaylist = true } label: { Label("Add to playlist", systemImage: "text.badge.plus") }
            if let inPlaylist {
                Button(role: .destructive) { library.remove(song.videoId, from: inPlaylist) } label: { Label("Remove from playlist", systemImage: "minus.circle") }
            }
            if library.isDownloaded(song) {
                Button(role: .destructive) { library.removeDownload(song.videoId) } label: { Label("Remove download", systemImage: "trash") }
            } else if library.downloading[song.videoId] != nil {
                Button { library.cancelDownload(song.videoId) } label: { Label("Stop downloading", systemImage: "xmark.circle") }
            } else {
                Button { library.download(song) } label: { Label("Download", systemImage: "arrow.down.circle") }
            }
            if let openRoute, let artistId = song.artistId { Button { openRoute(.artist(browseId: artistId)) } label: { Label("Go to artist", systemImage: "person") } }
            if let openRoute, let albumId = song.albumId { Button { openRoute(.collection(browseId: albumId, title: song.albumName)) } label: { Label("Go to album", systemImage: "square.stack") } }
            ShareLink(item: URL(string: "https://music.youtube.com/watch?v=\(song.videoId)")!) { Label("Share", systemImage: "square.and.arrow.up") }
        } label: {
            Image(systemName: "ellipsis").frame(width: 36, height: 36).contentShape(Rectangle())
        }
        .sheet(isPresented: $choosingPlaylist) { PlaylistPicker(song: song) }
    }
}

/** Picks a playlist for a song, or makes a new one with it in. */
struct PlaylistPicker: View {
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    let song: Song
    @State private var newName = ""
    @State private var naming = false

    var body: some View {
        NavigationStack {
            List {
                Button { naming = true } label: { Label("New playlist", systemImage: "plus") }
                ForEach(library.playlists) { p in
                    let there = p.songs.contains { $0.videoId == song.videoId }
                    Button {
                        library.add(song, to: p.id)
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            PlaylistCover(playlist: p).frame(width: 44, height: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).foregroundStyle(.white)
                                Text(songCount(p.songs.count)).font(.caption).foregroundStyle(Theme.subtle)
                            }
                            Spacer()
                            if there { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
                        }
                    }
                    .disabled(there)
                }
            }
            .navigationTitle("Add to playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .alert("New playlist", isPresented: $naming) {
                TextField("Name", text: $newName)
                Button("Create") {
                    library.createPlaylist(newName, with: song)
                    dismiss()
                }
                Button("Cancel", role: .cancel) {}
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/** A playlist's tile: four covers in a square, one when there's one, a note when there are none. */
struct PlaylistCover: View {
    let playlist: LocalPlaylist
    var corner: CGFloat = 8

    var body: some View {
        let covers = playlist.covers
        GeometryReader { geo in
            let half = geo.size.width / 2
            Group {
                if covers.count >= 4 {
                    VStack(spacing: 0) {
                        HStack(spacing: 0) { tile(covers[0], half); tile(covers[1], half) }
                        HStack(spacing: 0) { tile(covers[2], half); tile(covers[3], half) }
                    }
                } else if let first = covers.first {
                    Artwork(url: first, size: Art.card, corner: 0)
                } else {
                    ZStack {
                        LinearGradient(colors: [Theme.accent.opacity(0.55), Theme.card], startPoint: .topLeading, endPoint: .bottomTrailing)
                        Image(systemName: "music.note.list").font(.system(size: geo.size.width * 0.32)).foregroundStyle(.white.opacity(0.8))
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }

    private func tile(_ url: String, _ side: CGFloat) -> some View {
        Artwork(url: url, size: Art.row, corner: 0).frame(width: side, height: side).clipped()
    }
}

func songCount(_ n: Int) -> String { n == 1 ? "1 song" : "\(n) songs" }

/** A card in a horizontal shelf. */
struct ShelfCard: View {
    let item: ShelfItem
    var width: CGFloat = 150

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: item.thumbnailUrl, size: Art.card, corner: 10, circle: item.type == .artist)
                .frame(width: width, height: width)
            Text(item.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                .frame(maxWidth: width, alignment: item.type == .artist ? .center : .leading)
            if !item.subtitle.isEmpty || item.explicit {
                SubtitleLine(text: item.subtitle, explicit: item.explicit, font: .caption)
                    .frame(maxWidth: width, alignment: item.type == .artist ? .center : .leading)
            }
        }
        .frame(width: width)
    }
}

/** Three bars that move while the song plays. */
struct NowPlayingBars: View {
    let playing: Bool
    @State private var phase = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<3) { i in
                Capsule().fill(.white)
                    .frame(width: 3, height: playing ? (phase ? [14, 8, 11][i] : [6, 14, 8][i]) : 4)
            }
        }
        .frame(height: 16)
        .animation(playing ? .easeInOut(duration: 0.45).repeatForever(autoreverses: true) : .default, value: phase)
        .onAppear { phase.toggle() }
    }
}

struct SectionTitle: View {
    let title: String
    var strapline: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            if !strapline.isEmpty { Text(strapline.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(Theme.subtle) }
            Text(title).font(.title3.weight(.bold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ErrorState: View {
    let message: String
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark").font(.largeTitle).foregroundStyle(Theme.subtle)
            Text(message).multilineTextAlignment(.center).foregroundStyle(Theme.subtle)
            if let retry { Button("Try again", action: retry).buttonStyle(.borderedProminent) }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

// MARK: Navigation from anywhere

private struct OpenRouteKey: EnvironmentKey {
    static let defaultValue: ((Route) -> Void)? = nil
}

extension EnvironmentValues {
    /** Pushes a page onto the current tab's stack; set by each tab's NavigationStack, nil elsewhere (the player sheet). */
    var openRoute: ((Route) -> Void)? {
        get { self[OpenRouteKey.self] }
        set { self[OpenRouteKey.self] = newValue }
    }
}

/** A tab's navigation stack, with album, playlist and artist pages wired in. */
struct RoutedStack<Content: View>: View {
    @State private var path = NavigationPath()
    @ViewBuilder var content: () -> Content

    var body: some View {
        NavigationStack(path: $path) {
            content()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case let .collection(id, title): CollectionView(browseId: id, initialTitle: title)
                    case let .artist(id): ArtistView(browseId: id)
                    case let .playlist(id): LocalPlaylistView(id: id)
                    }
                }
        }
        .environment(\.openRoute) { path.append($0) }
    }
}

func formatTime(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let s = Int(seconds)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}
