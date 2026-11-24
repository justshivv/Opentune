import Foundation

/** A song from another service's playlist, to be found on YouTube Music. */
public struct ImportTrack: Hashable, Sendable {
    public var title: String
    public var artists: [String]
    public var album: String?
    /** 0 when the source doesn't say. */
    public var durationMs: Int64

    public init(title: String, artists: [String], album: String? = nil, durationMs: Int64 = 0) {
        self.title = title
        self.artists = artists
        self.album = album
        self.durationMs = durationMs
    }
}

/**
 * A playlist exported as CSV: Exportify (every song of a Spotify playlist,
 * Liked Songs included), TuneMyMusic, Soundiiz and similar. Columns are
 * found by their header, so the exact layout doesn't matter. Same rules as
 * the Android app's PlaylistCsv.
 */
public enum PlaylistCsv {
    static let titleColumns = ["track name", "track", "title", "song name", "song", "name"]
    static let artistColumns = ["artist name(s)", "artist names", "artist name", "artists", "artist"]
    static let albumColumns = ["album name", "album"]
    static let durationColumns = ["duration (ms)", "duration_ms", "duration ms", "track duration (ms)"]

    /** The songs in [text]; empty when it has no recognisable title and artist columns. */
    public static func parse(_ text: String) -> [ImportTrack] {
        let rows = rows(text.hasPrefix("\u{FEFF}") ? String(text.dropFirst()) : text)
        guard rows.count >= 2 else { return [] }
        let header = rows[0].map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        func column(_ names: [String]) -> Int? { names.lazy.compactMap { header.firstIndex(of: $0) }.first }
        guard let title = column(titleColumns), let artist = column(artistColumns) else { return [] }
        let album = column(albumColumns)
        let duration = column(durationColumns)
        func cell(_ r: [String], _ i: Int?) -> String? {
            guard let i, i < r.count else { return nil }
            let v = r[i].trimmingCharacters(in: .whitespacesAndNewlines)
            return v.isEmpty ? nil : v
        }
        return rows.dropFirst().compactMap { r in
            guard let name = cell(r, title) else { return nil }
            let artists = (cell(r, artist) ?? "").replacingOccurrences(of: ";", with: ",")
                .split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            return ImportTrack(title: name, artists: artists, album: cell(r, album), durationMs: cell(r, duration).flatMap { Int64($0) } ?? 0)
        }
    }

    /** RFC 4180 rows: quoted fields may hold commas, doubled quotes and line breaks. */
    static func rows(_ text: String) -> [[String]] {
        // Unicode scalars, not Characters: Swift folds "\r\n" into one Character.
        let chars = Array(text.unicodeScalars)
        var out: [[String]] = []
        var row: [String] = []
        var field = String.UnicodeScalarView()
        var quoted = false
        func endRow() {
            row.append(String(field)); field = .init()
            if row.contains(where: { !$0.isEmpty }) { out.append(row) }
            row = []
        }
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if quoted && c == "\"" && i + 1 < chars.count && chars[i + 1] == "\"" {
                field.append("\""); i += 1
            } else if c == "\"" {
                quoted.toggle()
            } else if !quoted && c == "," {
                row.append(String(field)); field = .init()
            } else if !quoted && (c == "\n" || c == "\r") {
                if c == "\r" && i + 1 < chars.count && chars[i + 1] == "\n" { i += 1 }
                endRow()
            } else {
                field.append(c)
            }
            i += 1
        }
        if !field.isEmpty || !row.isEmpty { endRow() }
        return out
    }
}

/** Finds an imported song on YouTube Music. Same rules as the Android app's PlaylistImport. */
public enum PlaylistMatch {
    /** The YouTube Music song for [track], or nil when nothing is close enough. */
    public static func match(_ track: ImportTrack) async throws -> Song? {
        let query = "\(track.title) \(track.artists.first ?? "")".trimmingCharacters(in: .whitespacesAndNewlines)
        let candidates = try await MusicRepository.searchSongs(query).compactMap { r -> Song? in
            switch r {
            case let .track(s), let .topTrack(s): return s
            case .browse: return nil
            }
        }
        return best(track, candidates)
    }

    /**
     * The candidate whose title and artist match [track], preferring one
     * whose length is within a few seconds. Nil when no title matches.
     */
    static func best(_ track: ImportTrack, _ candidates: [Song]) -> Song? {
        let wantTitle = key(track.title)
        let wantArtists = track.artists.map(key).filter { !$0.isEmpty }
        var pick: (song: Song, score: Int)?
        for song in candidates {
            let title = key(song.title)
            let artist = key(song.artist)
            var score = 0
            if title == wantTitle { score += 4 } else if title.contains(wantTitle) || wantTitle.contains(title) { score += 2 } else { continue }
            let sameArtist = wantArtists.contains { artist.contains($0) || $0.contains(artist) }
            if sameArtist { score += 3 }
            let off: Int64? = song.durationMs > 0 && track.durationMs > 0 ? abs(song.durationMs - track.durationMs) : nil
            // Credited to someone else: only the very same title and length will do.
            // Whole seconds on YouTube Music, so half a second more for the rounding.
            if !sameArtist && !isPlaceholderArtist(artist) && (title != wantTitle || off == nil || off! > 3_500) { continue }
            if let off, off <= 7_000 { score += 2 }
            // A title and the artist, or a title and the length.
            if score >= 5 && score > (pick?.score ?? -1) { pick = (song, score) }
        }
        guard var song = pick?.song else { return nil }
        // An upload credited to "Various Artists" or the like is still the song; show who sings it.
        if isPlaceholderArtist(key(song.artist)) && !track.artists.isEmpty {
            song.artist = track.artists.joined(separator: ", ")
            song.artistId = nil
        }
        return song
    }

    /** Lower-case letters and digits, with "(feat. …)", "- Remastered 2011" and similar taken off. */
    static func key(_ s: String) -> String {
        s.lowercased()
            .replacingOccurrences(of: #"\s*[(\[](?:feat|ft|with|from|remaster|live|radio edit|mono|stereo)[^)\]]*[)\]]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\s+-\s+.*(?:remaster|version|edit|mix|live|mono|stereo).*$"#, with: "", options: .regularExpression)
            .filter { $0.isLetter || $0.isNumber }
    }

    /** "Various Artists", "VA", "Unknown Artist": an upload's stand-in for whoever sings. */
    static func isPlaceholderArtist(_ name: String) -> Bool {
        let key = name.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s*-\s*Topic$"#, with: "", options: [.regularExpression, .caseInsensitive])
            .lowercased().filter { $0.isLetter || $0.isNumber }
        return ["", "variousartist", "variousartists", "va", "unknownartist", "unknown", "artist"].contains(key)
    }
}
