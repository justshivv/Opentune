import XCTest
@testable import OpenTuneCore

final class ParserTests: XCTestCase {
    func fixture(_ name: String) throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try XCTUnwrap(JSON.parse(Data(contentsOf: url)))
    }

    func testHomeHasShelvesOfCards() throws {
        // A signed-out first visit gets one carousel and a taste picker, which isn't a shelf.
        let shelves = InnertubeParser.parseHome(try fixture("home"))
        XCTAssertEqual(shelves.count, 1)
        XCTAssertGreaterThanOrEqual(shelves[0].items.count, 8)
        for shelf in shelves {
            XCTAssertFalse(shelf.title.isEmpty)
            XCTAssertFalse(shelf.items.isEmpty)
            for item in shelf.items { XCTAssertTrue(item.videoId != nil || item.browseId != nil, "\(item.title) has nowhere to go") }
        }
    }

    func testSearchKeepsVideosOffTheMixedResults() throws {
        // This anonymous answer holds the artist and fan lyric-video uploads only.
        let results = InnertubeParser.parseSearch(try fixture("search-blinding"))
        let artist = results.compactMap { r -> BrowseItem? in if case let .browse(b) = r { return b }; return nil }.first
        XCTAssertEqual(artist?.title, "The Weeknd")
        XCTAssertEqual(artist?.type, .artist)
        XCTAssertEqual(artist?.browseId, "UClYV6hHlupm_S_ObS1W-DYw")
        let songs: [Song] = results.compactMap {
            switch $0 {
            case let .topTrack(s), let .track(s): return s
            case .browse: return nil
            }
        }
        XCTAssertFalse(songs.contains { $0.isVideo })
        XCTAssertEqual(Set(results.map(\.id)).count, results.count, "no duplicates")
    }

    func testSearchRowParsing() throws {
        // A catalogue song row: square art, artist and album linked in the subtitle, the E mark.
        let row = try XCTUnwrap(JSON.parse(Data("""
        {"contents":[{"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"4NRXx6U8ABQ"},
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/x=w120-h120","width":120,"height":120}]}}},
          "badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}],
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Blinding Lights"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
              {"text":"Song"},{"text":" • "},
              {"text":"The Weeknd","navigationEndpoint":{"browseEndpoint":{"browseId":"UClYV6hHlupm_S_ObS1W-DYw","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
              {"text":" • "},
              {"text":"After Hours","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_xyz","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},
              {"text":" • "},{"text":"3:20"}]}}}]}}]}
        """.utf8)))
        let results = InnertubeParser.parseSearch(row)
        guard case let .track(song)? = results.first else { return XCTFail("no track: \(results)") }
        XCTAssertEqual(song.title, "Blinding Lights")
        XCTAssertEqual(song.artist, "The Weeknd")
        XCTAssertEqual(song.artistId, "UClYV6hHlupm_S_ObS1W-DYw")
        XCTAssertEqual(song.albumName, "After Hours")
        XCTAssertEqual(song.durationText, "3:20")
        XCTAssertEqual(song.isExplicit, true)
        XCTAssertFalse(song.isVideo)
    }

    func testAlbumRowsBorrowTheArtistFromTheHeader() throws {
        let album = InnertubeParser.parseCollection(try fixture("album-olak"))
        XCTAssertFalse(album.title.isEmpty)
        XCTAssertGreaterThan(album.songs.count, 5)
        XCTAssertFalse(album.songs.contains { $0.artist == "Unknown artist" }, album.songs.map(\.artist).joined(separator: ", "))
        XCTAssertTrue(album.songs.allSatisfy { $0.durationMs > 0 }, "album rows carry their runtime in a fixed column")
    }

    func testPlaylistKeepsItsOwnTracks() throws {
        let playlist = InnertubeParser.parseCollection(try fixture("playlist"))
        XCTAssertFalse(playlist.title.isEmpty)
        XCTAssertGreaterThan(playlist.songs.count, 10)
        XCTAssertEqual(Set(playlist.songs.map(\.videoId)).count, playlist.songs.count)
    }

    func testArtistPage() throws {
        let page = InnertubeParser.parseArtist(try fixture("artist-weeknd"))
        XCTAssertEqual(page.name, "The Weeknd")
        XCTAssertNotNil(page.thumbnailUrl)
        XCTAssertNotNil(page.description)
        // This answer is carousels only: two of videos, dropped as on Android, and playlists.
        XCTAssertEqual(page.shelves.map(\.title), ["Playlists by The Weeknd"])
        XCTAssertTrue(page.shelves.allSatisfy { $0.items.allSatisfy { $0.browseId != nil } })
    }

    func testWatchQueueIsARadioMix() throws {
        let queue = InnertubeParser.parseWatchQueue(try fixture("next-blinding"))
        XCTAssertGreaterThan(queue.count, 10)
        XCTAssertEqual(queue.first?.videoId, "4NRXx6U8ABQ")
        XCTAssertTrue(queue.allSatisfy { !$0.artist.isEmpty })
    }

    func testArtistFromSubtitle() {
        XCTAssertEqual(InnertubeParser.artistFromSubtitle("Song • Chelsea Wolfe"), "Chelsea Wolfe")
        XCTAssertEqual(InnertubeParser.artistFromSubtitle("21 Savage • 3:40"), "21 Savage")
        XCTAssertEqual(InnertubeParser.artistFromSubtitle("Album • 12 songs"), "Album • 12 songs")
    }

    func testBrowseTypes() {
        XCTAssertEqual(BrowseType.of("MPREb_abc"), .album)
        XCTAssertEqual(BrowseType.of("VLOLAK5uy_n"), .album)
        XCTAssertEqual(BrowseType.of("UClYV6hHlupm"), .artist)
        XCTAssertEqual(BrowseType.of("VLPLK2zhq9"), .playlist)
    }

    func testArtworkResize() {
        let url: String? = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        XCTAssertEqual(url.artworkAt(480)?.absoluteString, "https://lh3.googleusercontent.com/abc=w480-h480-l90-rj")
    }

    func testStreamPrefersTheBestAACThenHLS() throws {
        let response = try XCTUnwrap(JSON.parse(Data("""
        {"streamingData":{"hlsManifestUrl":"https://m.example/hls.m3u8","adaptiveFormats":[
          {"itag":251,"mimeType":"audio/webm; codecs=\\"opus\\"","bitrate":160000,"url":"https://g.example/opus"},
          {"itag":139,"mimeType":"audio/mp4; codecs=\\"mp4a.40.5\\"","bitrate":48000,"url":"https://g.example/low"},
          {"itag":140,"mimeType":"audio/mp4; codecs=\\"mp4a.40.2\\"","bitrate":130000,"contentLength":"3245678","url":"https://g.example/aac"}]},
         "playerConfig":{"audioConfig":{"loudnessDb":-3.2}},"videoDetails":{"lengthSeconds":"200"}}
        """.utf8)))
        let stream = try XCTUnwrap(Innertube.pickStream(response, client: .ios))
        XCTAssertEqual(stream.url.absoluteString, "https://g.example/aac")
        XCTAssertFalse(stream.isHLS)
        XCTAssertEqual(stream.loudnessDb, -3.2)
        XCTAssertEqual(stream.durationMs, 200_000)
        XCTAssertEqual(stream.contentLength, 3_245_678)
        XCTAssertEqual(stream.headers["User-Agent"], Innertube.PlayerClient.ios.userAgent)

        let hlsOnly = try XCTUnwrap(JSON.parse(Data(#"{"streamingData":{"hlsManifestUrl":"https://m.example/hls.m3u8","adaptiveFormats":[]}}"#.utf8)))
        XCTAssertTrue(try XCTUnwrap(Innertube.pickStream(hlsOnly, client: .ios)).isHLS)
        XCTAssertNil(Innertube.pickStream(try XCTUnwrap(JSON.parse(Data("{}".utf8))), client: .ios))
    }
}

final class LyricsTests: XCTestCase {
    func testLrc() {
        let lines = LrcParser.parse("[offset:+100]\n[00:01.50]First\n[00:04.00][00:20.00]Chorus\n[00:06.0]\n[00:08.123]<00:08.12>Word <00:09.00>by word", durationMs: 30_000)
        XCTAssertEqual(lines.map(\.text), ["First", "Chorus", "Word by word", "Chorus"])
        XCTAssertEqual(lines[0].startMs, 1_400)
        XCTAssertEqual(lines[0].endMs, 3_900)
        XCTAssertEqual(lines.activeIndex(0), -1)
        XCTAssertEqual(lines.activeIndex(5_000), 1)
        XCTAssertEqual(lines.last?.endMs, 29_900)
    }

    func testCleaner() {
        let a = TrackNameCleaner.clean(title: "The Weeknd - Blinding Lights (Official Video)", artist: "TheWeekndVEVO")
        XCTAssertEqual(a.title, "Blinding Lights")
        XCTAssertEqual(a.artist, "The Weeknd")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Stay [Official Audio] (feat. Justin Bieber)", artist: "The Kid LAROI").title, "Stay")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Bohemian Rhapsody (Live Aid)", artist: "Queen").title, "Bohemian Rhapsody (Live Aid)")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Let It Be - Remastered 2009", artist: "The Beatles").title, "Let It Be")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Song (Deluxe Edition)", artist: "Band").title, "Song")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Intro - Reprise", artist: "Some Band").title, "Intro - Reprise")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Song", artist: "Alpha, Beta & Gamma").artist, "Alpha")
        XCTAssertEqual(TrackNameCleaner.clean(title: "Tum Hi Ho", artist: "Arijit Singh - Topic").artist, "Arijit Singh")
    }

    func testPickTakesTheClosestSyncedRecording() throws {
        let results = try XCTUnwrap(JSON.parse(Data("""
        [{"id":1,"duration":207.0,"syncedLyrics":"[00:05.00]music video cut"},
         {"id":2,"duration":200.5,"syncedLyrics":"[00:01.00]album cut"},
         {"id":3,"duration":200.0,"plainLyrics":"plain only"},
         {"id":4,"duration":260.0,"syncedLyrics":"[00:01.00]extended mix"}]
        """.utf8)))
        XCTAssertEqual(Lrclib.pick(results, durationS: 200)?["id"]?.doubleValue, 2)
        XCTAssertEqual(Lrclib.pick(results, durationS: 207.3)?["id"]?.doubleValue, 1)
    }

    func testTitleOnlyHitsMustBeTheSameArtist() throws {
        let hits = try XCTUnwrap(JSON.parse(Data(#"[{"id":1,"artistName":"Beyonce"},{"id":2,"artistName":"A Cover Band"},{"id":3,"artistName":"Beyoncé feat. JAY-Z"}]"#.utf8)))
        XCTAssertEqual(Lrclib.sameArtist(hits, "Beyoncé").arrayValue?.compactMap { $0["id"]?.doubleValue }, [1, 3])
        XCTAssertEqual(Lrclib.sameArtist(hits, "").arrayValue?.count, 0)
    }
}

final class LibraryTests: XCTestCase {
    func testLikesAndHistorySurviveARestart() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let song = Song(videoId: "abc", title: "Song", artist: "Artist", isExplicit: true)
        let store = LibraryStore(directory: dir)
        XCTAssertTrue(store.toggleLike(song))
        store.recordPlay(song, at: Date(timeIntervalSince1970: 1_000))
        store.recordPlay(song, at: Date(timeIntervalSince1970: 1_100)) // same listen, moved up
        store.recordPlay(Song(videoId: "def", title: "Other", artist: "B"), at: Date(timeIntervalSince1970: 5_000))

        let again = LibraryStore(directory: dir)
        XCTAssertTrue(again.isLiked("abc"))
        XCTAssertEqual(again.liked.first?.isExplicit, true)
        XCTAssertEqual(again.history.count, 2)
        XCTAssertEqual(again.recentSongs().map(\.videoId), ["def", "abc"])
        XCTAssertFalse(again.toggleLike(song))
        XCTAssertFalse(LibraryStore(directory: dir).isLiked("abc"))
    }

    func testPlaylistsSavedAlbumsAndSearchesSurviveARestart() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let a = Song(videoId: "a", title: "A", artist: "X", thumbnailUrl: "https://i/a")
        let b = Song(videoId: "b", title: "B", artist: "Y", thumbnailUrl: "https://i/a")
        let c = Song(videoId: "c", title: "C", artist: "Z", thumbnailUrl: "https://i/c")
        let store = LibraryStore(directory: dir)
        let road = store.createPlaylist("  Road trip ")
        let blank = store.createPlaylist("   ")
        XCTAssertTrue(store.addToPlaylist(road, a))
        XCTAssertTrue(store.addToPlaylist(road, b))
        XCTAssertFalse(store.addToPlaylist(road, a), "a song goes in once")
        XCTAssertTrue(store.addToPlaylist(road, c))
        store.reorderPlaylist(road, [c, a, b])
        store.reorderPlaylist(road, [c, a]) // a song lost on the way: ignored
        store.removeFromPlaylist(road, videoId: "a")
        store.renamePlaylist(blank, to: "Gym")
        store.renamePlaylist(blank, to: "  ") // ignored
        XCTAssertTrue(store.toggleSaved(SavedCollection(browseId: "MPREb_1", title: "Album", subtitle: "Artist", thumbnailUrl: nil)))
        for q in ["one", "two", "ONE"] { store.recordSearch(q) }

        let again = LibraryStore(directory: dir)
        XCTAssertEqual(again.playlists.map(\.name), ["Gym", "Road trip"])
        let trip = try XCTUnwrap(again.playlists.first { $0.id == road })
        XCTAssertEqual(trip.songs.map(\.videoId), ["c", "b"])
        XCTAssertEqual(trip.covers, ["https://i/c", "https://i/a"])
        XCTAssertTrue(again.isSaved("MPREb_1"))
        XCTAssertEqual(again.searches, ["ONE", "two"])
        again.deletePlaylist(blank)
        XCTAssertFalse(again.toggleSaved(SavedCollection(browseId: "MPREb_1", title: "", subtitle: "", thumbnailUrl: nil)))
        again.clearSearches()
        let third = LibraryStore(directory: dir)
        XCTAssertEqual(third.playlists.map(\.id), [road])
        XCTAssertTrue(third.saved.isEmpty)
        XCTAssertTrue(third.searches.isEmpty)
    }

    func testALibraryFromBeforePlaylistsStillOpens() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let old = #"{"liked":[{"videoId":"abc","title":"Song","artist":"Artist","isVideo":false}],"history":[]}"#
        try Data(old.utf8).write(to: dir.appendingPathComponent("library.json"))
        let store = LibraryStore(directory: dir)
        XCTAssertTrue(store.isLiked("abc"))
        XCTAssertTrue(store.playlists.isEmpty)
        XCTAssertTrue(store.downloads.isEmpty)
    }

    func testDownloadsKeepTheirFileAndForgetIt() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LibraryStore(directory: dir)
        let song = Song(videoId: "abc", title: "Song", artist: "Artist")
        let file = store.downloadsFolder.appendingPathComponent("abc.m4a")
        try Data([1, 2, 3]).write(to: file)
        store.addDownload(DownloadedSong(song: song, fileName: "abc.m4a", bytes: 3, loudnessDb: -2))
        XCTAssertEqual(LibraryStore(directory: dir).downloadedFile("abc"), file)
        XCTAssertEqual(store.download("abc")?.loudnessDb, -2)
        store.removeDownload("abc")
        XCTAssertNil(store.downloadedFile("abc"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testDownloadsComeInPieces() {
        XCTAssertEqual(SongDownloader.ranges(total: 10, piece: 4), [0...3, 4...7, 8...9])
        XCTAssertEqual(SongDownloader.ranges(total: 8, piece: 4), [0...3, 4...7])
        XCTAssertEqual(SongDownloader.ranges(total: 0, piece: 4), [])
        let url = SongDownloader.url(URL(string: "https://g.example/videoplayback?id=1&range=0-5")!, range: 10...19)
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.filter { $0.name == "range" }.map(\.value), ["10-19"])
        XCTAssertEqual(items.first { $0.name == "id" }?.value, "1")
    }

    func testDurationFromText() {
        XCTAssertEqual(Song(videoId: "a", title: "t", artist: "a", durationText: "3:20").durationMs, 200_000)
        XCTAssertEqual(Song(videoId: "a", title: "t", artist: "a", durationText: "1:02:03").durationMs, 3_723_000)
    }
}
