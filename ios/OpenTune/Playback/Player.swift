import AVFoundation
import Combine
import MediaPlayer
import OpenTuneCore
import UIKit

/** What happens at the end of the queue, or of the song. */
enum RepeatMode: String {
    case off, all, one

    var next: RepeatMode { self == .off ? .all : self == .all ? .one : .off }
}

/**
 * What's playing and what's next. One AVPlayer, fed a stream URL per song as
 * its turn comes (URLs go stale, so nothing is resolved ahead of time except
 * the very next song). When the queue runs out it carries on with YouTube
 * Music's radio for the last song, the way the Android app's autoplay does.
 */
@MainActor
final class Player: ObservableObject {
    @Published private(set) var queue: [Song] = []
    @Published private(set) var index: Int = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var error: String?
    @Published private(set) var lyrics: Lyrics?
    @Published private(set) var lyricsLoading = false
    @Published var repeatMode: RepeatMode = RepeatMode(rawValue: UserDefaults.standard.string(forKey: "player.repeat") ?? "") ?? .off {
        didSet { UserDefaults.standard.set(repeatMode.rawValue, forKey: "player.repeat") }
    }
    /** How fast songs play, 0.5 to 2. */
    @Published var speed: Float = Player.savedSpeed {
        didSet {
            UserDefaults.standard.set(speed, forKey: "player.speed")
            applyRate()
        }
    }
    /** Off, a faster song also sounds higher, as a record does; on, it keeps its key. */
    @Published var keepPitch: Bool = UserDefaults.standard.object(forKey: "player.keepPitch") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(keepPitch, forKey: "player.keepPitch")
            player.currentItem?.audioTimePitchAlgorithm = pitchAlgorithm
        }
    }
    /** When the sleep timer stops the music; nil when it's off or waiting for the song's end. */
    @Published private(set) var sleepAt: Date?
    /** The sleep timer stops the music when this song ends. */
    @Published private(set) var sleepAtSongEnd = false
    @Published var autoplay = true
    @Published private(set) var shuffled = false

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: ArraySlice<Song> { queue.indices.contains(index + 1) ? queue[(index + 1)...] : [] }

    private let player = AVPlayer()
    private let library: Library
    private var timeObserver: Any?
    private var itemObservers: [NSKeyValueObservation] = []
    private var endObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    private var radioTask: Task<Void, Never>?
    private var lyricsTask: Task<Void, Never>?
    private var countedPlay = false
    private var artworkFor: String?
    private var unshuffled: [Song]?
    private var cancellables = Set<AnyCancellable>()
    /** The song's loudness level, and the sleep timer's fade on top of it. */
    private var baseVolume: Float = 1
    private var fade: Float = 1

    nonisolated private static var savedSpeed: Float {
        let v = UserDefaults.standard.float(forKey: "player.speed")
        return v >= 0.5 && v <= 2 ? v : 1
    }

    init(library: Library) {
        self.library = library
        player.automaticallyWaitsToMinimizeStalling = true
        player.defaultRate = speed
        configureSession()
        configureRemoteCommands()
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time.seconds) }
        }
        player.publisher(for: \.timeControlStatus).receive(on: RunLoop.main).sink { [weak self] status in
            guard let self else { return }
            self.isPlaying = status == .playing || (status == .waitingToPlayAtSpecifiedRate && self.player.rate > 0)
            self.isBuffering = status == .waitingToPlayAtSpecifiedRate
            self.updateNowPlaying()
        }.store(in: &cancellables)
    }

    // MARK: Queue

    /** Plays [songs] from [start]; a lone song gets a radio queue after it. */
    func play(_ songs: [Song], from start: Int = 0) {
        guard songs.indices.contains(start) else { return }
        radioTask?.cancel()
        queue = songs
        index = start
        unshuffled = nil
        shuffled = false
        load()
        if songs.count == 1 { extendWithRadio() }
    }

    func playNext(_ song: Song) {
        guard current != nil else { return play([song]) }
        queue.removeAll { $0.videoId == song.videoId && $0.videoId != current?.videoId }
        queue.insert(song, at: min(index + 1, queue.count))
    }

    func addToQueue(_ song: Song) {
        guard current != nil else { return play([song]) }
        queue.append(song)
    }

    func jump(to i: Int) {
        guard queue.indices.contains(i) else { return }
        index = i
        load()
    }

    func remove(at offsets: IndexSet) {
        // Offsets are within upNext.
        let absolute = IndexSet(offsets.map { $0 + index + 1 })
        queue.remove(atOffsets: absolute)
    }

    func move(from source: IndexSet, to destination: Int) {
        let base = index + 1
        var tail = Array(queue[base...])
        tail.move(fromOffsets: source, toOffset: destination)
        queue.replaceSubrange(base..., with: tail)
    }

    func toggleShuffle() {
        guard let cur = current else { return }
        if shuffled, let original = unshuffled {
            queue = original
            index = original.firstIndex { $0.videoId == cur.videoId } ?? 0
            unshuffled = nil
            shuffled = false
        } else {
            unshuffled = queue
            var rest = queue.enumerated().filter { $0.offset != index }.map(\.element)
            rest.shuffle()
            queue = [cur] + rest
            index = 0
            shuffled = true
        }
    }

    // MARK: Transport

    func toggle() {
        if isPlaying { player.pause() } else {
            // A timer that ran out while paused doesn't stop the music the moment it starts again.
            if let sleepAt, sleepAt.timeIntervalSinceNow <= 0 { sleep(inMinutes: nil) }
            if player.currentItem == nil { load() } else { player.play() }
        }
    }

    func pause() { player.pause() }

    func next() {
        if index + 1 < queue.count {
            index += 1
            load()
        } else if repeatMode == .all, !queue.isEmpty {
            index = 0
            load()
        } else if autoplay {
            extendWithRadio(thenAdvance: true)
        }
    }

    func previous() {
        // Past the opening seconds, back to the start of this song first.
        if position > 3 || index == 0 {
            seek(to: 0)
        } else {
            index -= 1
            load()
        }
    }

    func seek(to seconds: Double) {
        let t = CMTime(seconds: max(0, seconds), preferredTimescale: 600)
        position = max(0, seconds)
        player.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in self?.updateNowPlaying() }
        }
    }

    // MARK: Loading

    private func load(autoplay play: Bool = true) {
        guard let song = current else { return }
        loadTask?.cancel()
        error = nil
        countedPlay = false
        position = 0
        duration = Double(song.durationMs) / 1000
        isBuffering = true
        player.pause()
        player.replaceCurrentItem(with: nil)
        updateNowPlaying()
        loadLyrics(for: song)
        // A saved song plays from its file, with or without the network.
        if let offline = library.offline(song.videoId) {
            let item = AVPlayerItem(url: offline.url)
            setLoudness(offline.loudnessDb)
            start(item, playing: play)
            return
        }
        loadTask = Task { [weak self] in
            do {
                let stream = try await Innertube.shared.stream(song.videoId)
                guard !Task.isCancelled, let self, self.current?.videoId == song.videoId else { return }
                let asset = AVURLAsset(url: stream.url, options: ["AVURLAssetHTTPHeaderFieldsKey": stream.headers])
                let item = AVPlayerItem(asset: asset)
                item.preferredForwardBufferDuration = 30
                self.setLoudness(stream.loudnessDb)
                if let ms = stream.durationMs, ms > 0 { self.duration = Double(ms) / 1000 }
                self.start(item, playing: play)
            } catch {
                guard !Task.isCancelled, let self, self.current?.videoId == song.videoId else { return }
                self.isBuffering = false
                self.error = "\(error)"
                // Skip what can't be played rather than stopping the music.
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if self.current?.videoId == song.videoId, self.index + 1 < self.queue.count { self.next() }
            }
        }
    }

    private func start(_ item: AVPlayerItem, playing: Bool) {
        item.audioTimePitchAlgorithm = pitchAlgorithm
        observe(item)
        player.replaceCurrentItem(with: item)
        if playing { player.play() } else { isBuffering = false }
    }

    /** YouTube's loudness measure: loud masters come down, as on Android. */
    private func setLoudness(_ db: Double?) {
        baseVolume = db.map { Float(min(1, pow(10, -max(0, $0) / 20))) } ?? 1
        applyVolume()
    }

    private func applyVolume() { player.volume = baseVolume * fade }

    private var pitchAlgorithm: AVAudioTimePitchAlgorithm { keepPitch ? .spectral : .varispeed }

    private func applyRate() {
        player.defaultRate = speed
        if player.rate > 0 { player.rate = speed }
        updateNowPlaying()
    }

    // MARK: Sleep timer

    /** Stops the music in [minutes], with the last seconds faded out; nil turns the timer off. */
    func sleep(inMinutes minutes: Int?) {
        sleepAtSongEnd = false
        sleepAt = minutes.map { Date().addingTimeInterval(Double($0) * 60) }
        fade = 1
        applyVolume()
    }

    /** Stops the music when the song playing now ends. */
    func sleepAtEndOfSong() {
        sleepAt = nil
        sleepAtSongEnd = true
        fade = 1
        applyVolume()
    }

    private func sleepNow() {
        sleepAt = nil
        sleepAtSongEnd = false
        player.pause()
        fade = 1
        applyVolume()
    }

    private func observe(_ item: AVPlayerItem) {
        itemObservers = [
            item.observe(\.status) { [weak self] item, _ in
                Task { @MainActor in
                    guard let self else { return }
                    if item.status == .failed { self.error = item.error?.localizedDescription ?? "Couldn't play this song" }
                    if item.status == .readyToPlay, item.duration.isNumeric { self.duration = item.duration.seconds }
                }
            },
        ]
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                if self.sleepAtSongEnd {
                    self.sleepAtSongEnd = false
                    // Ready on the next song for the morning, but quiet.
                    if self.index + 1 < self.queue.count { self.index += 1; self.load(autoplay: false) } else { self.seek(to: 0) }
                } else if self.repeatMode == .one { self.seek(to: 0); self.player.play() } else { self.next() }
            }
        }
    }

    private func tick(_ seconds: Double) {
        guard seconds.isFinite else { return }
        position = seconds
        // A listen counts after 30 seconds, or half of a short song.
        if !countedPlay, let song = current, seconds >= min(30, max(duration, 1) / 2) {
            countedPlay = true
            library.recordPlay(song)
        }
        // The sleep timer: the last ten seconds fade out, then the music stops.
        if let sleepAt {
            let left = sleepAt.timeIntervalSinceNow
            if left <= 0 { sleepNow() } else if left < 10 { fade = Float(left / 10); applyVolume() }
        }
        // Near the end of the queue, have the radio's next songs ready.
        if autoplay, repeatMode != .all, index == queue.count - 1, duration > 0, duration - seconds < 25, radioTask == nil {
            extendWithRadio()
        }
    }

    private func extendWithRadio(thenAdvance: Bool = false) {
        guard let seed = queue.last, radioTask == nil else { return }
        radioTask = Task { [weak self] in
            defer { Task { @MainActor in self?.radioTask = nil } }
            guard let more = try? await MusicRepository.radio(after: seed), let self, !Task.isCancelled else { return }
            let known = Set(self.queue.map(\.videoId))
            let fresh = more.filter { !known.contains($0.videoId) }
            guard !fresh.isEmpty else { return }
            self.queue.append(contentsOf: fresh)
            if thenAdvance { self.next() }
        }
    }

    private func loadLyrics(for song: Song) {
        lyricsTask?.cancel()
        lyrics = nil
        lyricsLoading = true
        lyricsTask = Task { [weak self] in
            let found = try? await Lrclib.lookup(title: song.title, artist: song.artist, durationMs: song.durationMs)
            guard !Task.isCancelled, let self, self.current?.videoId == song.videoId else { return }
            self.lyrics = found ?? nil
            self.lyricsLoading = false
        }
    }

    // MARK: System

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, policy: .longFormAudio)
        try? session.setActive(true)
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, AVAudioSession.InterruptionType(rawValue: raw) == .ended,
                  let opts = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                  AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume) else { return }
            MainActor.assumeIsolated { self?.player.play() }
        }
        // Headphones out: pause, as every music app does.
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { self?.player.pause() }
        }
    }

    private func configureRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.next() }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.previous() }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.seek(to: e.positionTime) }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let song = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.artist,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(speed) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
        ]
        if let album = song.albumName { info[MPMediaItemPropertyAlbumTitle] = album }
        let existing = MPNowPlayingInfoCenter.default().nowPlayingInfo
        if artworkFor == song.videoId, let art = existing?[MPMediaItemPropertyArtwork] { info[MPMediaItemPropertyArtwork] = art }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if artworkFor != song.videoId, let url = song.thumbnailUrl.artworkAt(Art.player) {
            artworkFor = song.videoId
            Task {
                guard let (data, _) = try? await URLSession.shared.data(from: url), let image = UIImage(data: data) else { return }
                await MainActor.run {
                    guard self.current?.videoId == song.videoId else { return }
                    var now = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? info
                    now[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = now
                }
            }
        }
    }
}
