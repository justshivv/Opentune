@testable import OpenTuneCore
import XCTest

final class PlaylistImportTests: XCTestCase {
    func testExportifyCsv() {
        let csv = "\u{FEFF}\"Track URI\",\"Track Name\",\"Album Name\",\"Artist Name(s)\",\"Duration (ms)\"\r\n"
            + "\"spotify:track:1\",\"Blinding Lights\",\"After Hours\",\"The Weeknd\",\"200040\"\r\n"
            + "\"spotify:track:2\",\"Say \"\"Hi\"\", now\",\"\",\"A;B, C\",\"\"\r\n"
            + "\"spotify:track:3\",\"Two\nlines\",\"X\",\"D\",\"1000\"\r\n"
            + "\"spotify:track:4\",\"\",\"X\",\"E\",\"1\"\r\n"
            + "\r\n"
        let tracks = PlaylistCsv.parse(csv)
        XCTAssertEqual(tracks, [
            ImportTrack(title: "Blinding Lights", artists: ["The Weeknd"], album: "After Hours", durationMs: 200_040),
            ImportTrack(title: "Say \"Hi\", now", artists: ["A", "B", "C"]),
            ImportTrack(title: "Two\nlines", artists: ["D"], album: "X", durationMs: 1000),
        ])
    }

    func testCsvWithoutKnownColumnsIsEmpty() {
        XCTAssertEqual(PlaylistCsv.parse("foo,bar\n1,2\n"), [])
        XCTAssertEqual(PlaylistCsv.parse("Title,Artist\n"), [])
        // A last row without a line break still counts.
        XCTAssertEqual(PlaylistCsv.parse("Title,Artist\nSong,Someone").count, 1)
        // Quoted fields may end in a line break; it's trimmed like spaces.
        XCTAssertEqual(PlaylistCsv.parse("\"Track Name\n\",Artist,\"Duration (ms)\"\n\"Song\n\",X,\"1000\n\"\n"),
                       [ImportTrack(title: "Song", artists: ["X"], durationMs: 1000)])
    }

    func testKeyDropsFeaturesAndRemasters() {
        XCTAssertEqual(PlaylistMatch.key("Stay (feat. Justin Bieber)"), "stay")
        XCTAssertEqual(PlaylistMatch.key("Let It Be - Remastered 2009"), "letitbe")
        XCTAssertEqual(PlaylistMatch.key("Café del Mar"), "cafédelmar")
    }

    func testBestPrefersTitleArtistAndLength() {
        let track = ImportTrack(title: "Blinding Lights", artists: ["The Weeknd"], durationMs: 200_040)
        let cover = Song(videoId: "c", title: "Blinding Lights", artist: "Cover Band", durationText: "3:20")
        let live = Song(videoId: "l", title: "Blinding Lights (Live)", artist: "The Weeknd", durationText: "4:10")
        let real = Song(videoId: "r", title: "Blinding Lights", artist: "The Weeknd", durationText: "3:20")
        XCTAssertEqual(PlaylistMatch.best(track, [live, real])?.videoId, "r")
        // Same title and length by someone else is accepted only as a last resort.
        XCTAssertEqual(PlaylistMatch.best(track, [cover])?.videoId, "c")
        XCTAssertNil(PlaylistMatch.best(track, [Song(videoId: "x", title: "Something Else", artist: "The Weeknd")]))
        XCTAssertNil(PlaylistMatch.best(track, [Song(videoId: "x", title: "Blinding Lights", artist: "Cover Band", durationText: "2:00")]))
    }

    func testPlaceholderArtistTakesTheTracksCredit() {
        let track = ImportTrack(title: "Song", artists: ["Real Singer"], durationMs: 180_000)
        let upload = Song(videoId: "u", title: "Song", artist: "Various Artists", durationText: "3:00", artistId: "UCx")
        let song = PlaylistMatch.best(track, [upload])
        XCTAssertEqual(song?.artist, "Real Singer")
        XCTAssertNil(song?.artistId)
    }

    func testAddAllToPlaylistSkipsDuplicates() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LibraryStore(directory: dir)
        let id = store.createPlaylist("Imported")
        let a = Song(videoId: "a", title: "A", artist: "X"), b = Song(videoId: "b", title: "B", artist: "X")
        store.addAllToPlaylist(id, [a, b, a])
        store.addAllToPlaylist(id, [b])
        XCTAssertEqual(store.playlists.first { $0.id == id }?.songs.map(\.videoId), ["a", "b"])
    }
}
