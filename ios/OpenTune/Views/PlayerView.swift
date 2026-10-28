import AVKit
import OpenTuneCore
import SwiftUI

/** The full player: the cover (or lyrics, or the queue), then the controls. */
struct PlayerView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @State private var pane = Pane.cover
    @State private var scrub: Double?

    enum Pane { case cover, lyrics, queue }

    var body: some View {
        ZStack {
            backdrop
            VStack(spacing: 18) {
                Capsule().fill(.white.opacity(0.3)).frame(width: 38, height: 5).padding(.top, 8)
                Group {
                    switch pane {
                    case .cover: cover
                    case .lyrics: LyricsPane()
                    case .queue: QueuePane()
                    }
                }
                .frame(maxHeight: .infinity)
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
                titleRow
                seekBar
                controls
                bottomBar
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: pane)
        }
        .preferredColorScheme(.dark)
    }

    private var backdrop: some View {
        ZStack {
            Theme.background
            Artwork(url: player.current?.thumbnailUrl, size: Art.card, corner: 0)
                .scaleEffect(1.6).blur(radius: 70).opacity(0.6)
            LinearGradient(colors: [.black.opacity(0.15), .black.opacity(0.65)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.6), value: player.current?.videoId)
    }

    private var cover: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            Artwork(url: player.current?.thumbnailUrl, size: Art.player, corner: 18)
                .frame(width: side, height: side)
                .scaleEffect(player.isPlaying ? 1 : 0.88)
                .shadow(color: .black.opacity(0.45), radius: 30, y: 16)
                .animation(.spring(response: 0.5, dampingFraction: 0.7), value: player.isPlaying)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onTapGesture(count: 2) { if let s = player.current { library.toggleLike(s) } }
        }
    }

    @ViewBuilder private var titleRow: some View {
        if let song = player.current {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(song.title).font(.title2.weight(.bold)).lineLimit(1)
                    SubtitleLine(text: song.artist, explicit: song.isExplicit == true, font: .body)
                }
                Spacer()
                Button { library.toggleLike(song) } label: {
                    Image(systemName: library.isLiked(song) ? "heart.fill" : "heart")
                        .font(.title2)
                        .foregroundStyle(library.isLiked(song) ? Theme.accent : .white)
                        .symbolEffectBounce(library.isLiked(song))
                }
                SongMenu(song: song).font(.title3)
            }
            if let error = player.error {
                Text(error).font(.caption).foregroundStyle(.orange).frame(maxWidth: .infinity, alignment: .leading).lineLimit(2)
            }
        }
    }

    private var seekBar: some View {
        VStack(spacing: 4) {
            Slider(value: Binding(
                get: { scrub ?? player.position },
                set: { scrub = $0 }
            ), in: 0...max(player.duration, 1)) { editing in
                if !editing, let s = scrub { player.seek(to: s); scrub = nil }
            }
            .tint(.white)
            HStack {
                Text(formatTime(scrub ?? player.position))
                Spacer()
                Text("-" + formatTime(max(0, player.duration - (scrub ?? player.position))))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(Theme.subtle)
        }
    }

    private var controls: some View {
        HStack {
            Button { player.toggleShuffle() } label: {
                Image(systemName: "shuffle").foregroundStyle(player.shuffled ? Theme.accent : .white.opacity(0.7))
            }
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 30)) }
            Spacer()
            Button { player.toggle() } label: {
                ZStack {
                    Circle().fill(.white).frame(width: 74, height: 74)
                    if player.isBuffering && !player.isPlaying {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(.black)
                            .offset(x: player.isPlaying ? 0 : 2)
                    }
                }
            }
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 30)) }
            Spacer()
            Button { player.repeatOne.toggle() } label: {
                Image(systemName: "repeat.1").foregroundStyle(player.repeatOne ? Theme.accent : .white.opacity(0.7))
            }
        }
        .buttonStyle(.plain)
        .font(.title3)
    }

    private var bottomBar: some View {
        HStack {
            paneButton(.lyrics, "quote.bubble")
            Spacer()
            AirPlayButton().frame(width: 30, height: 30)
            Spacer()
            paneButton(.queue, "list.bullet")
        }
        .padding(.horizontal, 30)
    }

    private func paneButton(_ p: Pane, _ icon: String) -> some View {
        Button { pane = pane == p ? .cover : p } label: {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(pane == p ? .black : .white.opacity(0.8))
                .frame(width: 40, height: 34)
                .background(pane == p ? AnyShapeStyle(.white) : AnyShapeStyle(.clear), in: RoundedRectangle(cornerRadius: 9))
        }
        .buttonStyle(.plain)
    }
}

/** Synced lyrics that follow the song; tap a line to go there. */
struct LyricsPane: View {
    @EnvironmentObject private var player: Player

    var body: some View {
        Group {
            switch player.lyrics {
            case let .synced(lines, source)?:
                let active = lines.activeIndex(Int64(player.position * 1000))
                ScrollViewReader { proxy in
                    ScrollView(showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 22) {
                            ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                                Text(line.text)
                                    .font(.system(size: 28, weight: .bold))
                                    .foregroundStyle(.white.opacity(i == active ? 1 : (i < active ? 0.45 : 0.3)))
                                    .scaleEffect(i == active ? 1 : 0.96, anchor: .leading)
                                    .blur(radius: i == active ? 0 : (abs(i - active) > 2 ? 1.2 : 0.5))
                                    .animation(.spring(response: 0.45, dampingFraction: 1), value: active)
                                    .id(i)
                                    .onTapGesture { player.seek(to: Double(line.startMs) / 1000) }
                            }
                            Text("Lyrics from \(source)").font(.caption).foregroundStyle(Theme.subtle).padding(.top, 20)
                        }
                        .padding(.vertical, 120)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .onChange(of: active) { i in
                        withAnimation(.spring(response: 0.6, dampingFraction: 1)) { proxy.scrollTo(max(i, 0), anchor: .center) }
                    }
                    .onAppear { proxy.scrollTo(max(active, 0), anchor: .center) }
                }
                .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.12), .init(color: .black, location: 0.85), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
            case let .plain(text, source)?:
                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("These lyrics aren't synced to the music.").font(.caption).foregroundStyle(Theme.subtle)
                        Text(text).font(.title3.weight(.semibold)).lineSpacing(8)
                        Text("Lyrics from \(source)").font(.caption).foregroundStyle(Theme.subtle)
                    }
                    .padding(.vertical, 20)
                }
            case nil:
                if player.lyricsLoading {
                    ProgressView()
                } else {
                    VStack(spacing: 8) {
                        Image(systemName: "quote.bubble").font(.largeTitle).foregroundStyle(Theme.subtle)
                        Text("No lyrics for this one").foregroundStyle(Theme.subtle)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct QueuePane: View {
    @EnvironmentObject private var player: Player

    var body: some View {
        List {
            if let now = player.current {
                Section("Now playing") {
                    SongRow(song: now) {}.listRowBackground(Color.clear)
                }
            }
            Section {
                ForEach(Array(player.upNext.enumerated()), id: \.element.videoId) { offset, song in
                    SongRow(song: song) { player.jump(to: player.index + 1 + offset) }.listRowBackground(Color.clear)
                }
                .onDelete { player.remove(at: $0) }
                .onMove { player.move(from: $0, to: $1) }
            } header: {
                HStack {
                    Text("Up next")
                    Spacer()
                    Toggle("Autoplay", isOn: Binding(get: { player.autoplay }, set: { player.autoplay = $0 }))
                        .labelsHidden().tint(Theme.accent)
                    Text("Autoplay").font(.caption)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.active))
    }
}

/** The system's AirPlay picker, for speakers and TVs. */
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerViewWrapper { AVRoutePickerViewWrapper() }
    func updateUIView(_ uiView: AVRoutePickerViewWrapper, context: Context) {}
}

final class AVRoutePickerViewWrapper: UIView {
    private let picker = AVRoutePickerView()
    override init(frame: CGRect) {
        super.init(frame: frame)
        picker.tintColor = .white
        picker.activeTintColor = UIColor(red: 1, green: 0.353, blue: 0.396, alpha: 1)
        picker.prioritizesVideoDevices = false
        addSubview(picker)
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() { super.layoutSubviews(); picker.frame = bounds }
}

private extension View {
    /** A bounce on iOS 17's symbol effects; nothing before. */
    @ViewBuilder func symbolEffectBounce(_ value: Bool) -> some View {
        if #available(iOS 17.0, *) { self.symbolEffect(.bounce, value: value) } else { self }
    }
}
