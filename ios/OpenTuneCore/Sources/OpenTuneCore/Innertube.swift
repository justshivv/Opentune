import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/**
 * A small InnerTube (youtubei) client, the same calls the Android app makes:
 * pages and search as the music.youtube.com web client, streams from the
 * device clients that answer with plain URLs (no player JavaScript, no
 * signature to solve).
 */
public actor Innertube {
    public static let shared = Innertube()

    public struct PlayerClient: Sendable {
        let name: String
        let version: String
        let id: String
        let userAgent: String
        var osName: String?
        var osVersion: String?
        var deviceMake: String?
        var deviceModel: String?
        var androidSdkVersion: Int?

        /** iPhone YouTube: answered without a login, a token or a signature, and it serves AAC. */
        static let ios = PlayerClient(
            name: "IOS", version: "21.26.4", id: "5",
            userAgent: "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osName: "iPhone", osVersion: "18.3.2.22D82", deviceMake: "Apple", deviceModel: "iPhone16,2"
        )
        static let iosRecent = PlayerClient(
            name: "IOS", version: "21.29.1", id: "5",
            userAgent: "com.google.ios.youtube/21.29.1 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)",
            osName: "iPhone", osVersion: "18.5.22F70", deviceMake: "Apple", deviceModel: "iPhone16,2"
        )
        /** The Quest's YouTube app: unciphered too. Must stay at 1.65.x or older (newer ones are SABR-only). */
        static let androidVR = PlayerClient(
            name: "ANDROID_VR", version: "1.65.10", id: "28",
            userAgent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName: "Android", osVersion: "12L", deviceMake: "Oculus", deviceModel: "Quest 3", androidSdkVersion: 32
        )
    }

    /** A stream ready for AVPlayer: where it is and the headers its fetch must repeat. */
    public struct Stream: Sendable {
        public let url: URL
        public let headers: [String: String]
        public let isHLS: Bool
        public let mimeType: String?
        public let bitrate: Int?
        /** YouTube's loudness measure, for normalisation. */
        public let loudnessDb: Double?
        public let durationMs: Int64?
        /** The file's size in bytes, where YouTube says; downloads fetch it in pieces. */
        public var contentLength: Int64? = nil
    }

    public enum Failure: Error, CustomStringConvertible, Sendable {
        case unplayable(String)
        case badResponse(Int)
        case noStream

        public var description: String {
            switch self {
            case let .unplayable(r): return r
            case let .badResponse(code): return "YouTube answered \(code)"
            case .noStream: return "No stream this phone can play"
            }
        }
    }

    static let webRemixVersion = "1.20260707.12.00"
    static let webUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    static let musicBase = "https://music.youtube.com/youtubei/v1"
    static let youtubeBase = "https://www.youtube.com/youtubei/v1"

    private let session: URLSession
    private var visitorData: String?
    public var language = "en"
    public var region = "US"

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func setLocale(language: String, region: String) {
        self.language = language
        self.region = region
    }

    // MARK: Pages

    public func home() async throws -> JSON { try await postMusic("browse", ["browseId": "FEmusic_home"]) }

    public func browse(_ browseId: String, params: String? = nil) async throws -> JSON {
        var body: [String: Any] = ["browseId": browseId]
        if let params { body["params"] = params }
        return try await postMusic("browse", body)
    }

    public func continuation(_ token: String) async throws -> JSON {
        try await postMusic("browse", ["continuation": token], query: ["ctoken": token, "continuation": token, "type": "next"])
    }

    public func search(_ query: String, params: String? = nil) async throws -> JSON {
        var body: [String: Any] = ["query": query]
        if let params { body["params"] = params }
        return try await postMusic("search", body)
    }

    public func searchSuggestions(_ input: String) async throws -> JSON {
        try await postMusic("music/get_search_suggestions", ["input": input])
    }

    /** The radio mix YouTube Music would play after [videoId]. */
    public func next(_ videoId: String) async throws -> JSON {
        try await postMusic("next", ["videoId": videoId, "playlistId": "RDAMVM\(videoId)", "isAudioOnly": true])
    }

    // MARK: Streams

    /**
     * A stream for [videoId], walking the device clients until one answers.
     * AVPlayer can't play WebM/Opus, so an AAC (`audio/mp4`) format is taken,
     * the best one offered; failing that, the HLS manifest the iOS client sends.
     */
    public func stream(_ videoId: String) async throws -> Stream {
        var lastError: Error = Failure.noStream
        for client in [PlayerClient.ios, .iosRecent, .androidVR] {
            do {
                let response = try await postPlayer(videoId, client)
                let status = response.o("playabilityStatus").s("status")
                if let status, status != "OK" {
                    throw Failure.unplayable(response.o("playabilityStatus").s("reason") ?? status)
                }
                if let stream = Self.pickStream(response, client: client) { return stream }
                lastError = Failure.noStream
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    /** The best AAC audio format with a plain URL, or the HLS manifest. */
    static func pickStream(_ response: JSON, client: PlayerClient) -> Stream? {
        let data = response.o("streamingData")
        let loudness = response.o("playerConfig").o("audioConfig")?["loudnessDb"]?.doubleValue
        let durationMs = response.o("videoDetails").s("lengthSeconds").flatMap { Int64($0) }.map { $0 * 1000 }
        let headers = ["User-Agent": client.userAgent]
        let audio = (data.a("adaptiveFormats") ?? []).filter { f in
            let mime = Optional(f).s("mimeType") ?? ""
            return mime.hasPrefix("audio/mp4") && Optional(f).s("url") != nil
        }
        if let best = audio.max(by: { ($0["bitrate"]?.doubleValue ?? 0) < ($1["bitrate"]?.doubleValue ?? 0) }),
           let s = Optional(best).s("url"), let url = URL(string: s) {
            return Stream(url: url, headers: headers, isHLS: false, mimeType: Optional(best).s("mimeType"),
                          bitrate: best["bitrate"]?.doubleValue.map { Int($0) }, loudnessDb: loudness, durationMs: durationMs,
                          contentLength: Optional(best).s("contentLength").flatMap { Int64($0) })
        }
        if let hls = data.s("hlsManifestUrl"), let url = URL(string: hls) {
            return Stream(url: url, headers: headers, isHLS: true, mimeType: "application/x-mpegURL", bitrate: nil, loudnessDb: loudness, durationMs: durationMs)
        }
        return nil
    }

    // MARK: Requests

    private func postMusic(_ endpoint: String, _ extras: [String: Any], query: [String: String] = [:]) async throws -> JSON {
        var client: [String: Any] = ["clientName": "WEB_REMIX", "clientVersion": Self.webRemixVersion, "hl": language, "gl": region]
        if let visitorData { client["visitorData"] = visitorData }
        var body = extras
        body["context"] = ["client": client, "user": ["lockedSafetyMode": false]]
        var headers = [
            "Origin": "https://music.youtube.com",
            "Referer": "https://music.youtube.com/",
            "X-Origin": "https://music.youtube.com",
            "User-Agent": Self.webUserAgent,
            "X-YouTube-Client-Name": "67",
            "X-YouTube-Client-Version": Self.webRemixVersion,
        ]
        if let visitorData { headers["X-Goog-Visitor-Id"] = visitorData }
        let json = try await post("\(Self.musicBase)/\(endpoint)", body: body, headers: headers, query: query)
        if visitorData == nil { visitorData = json.o("responseContext").s("visitorData") }
        return json
    }

    private func postPlayer(_ videoId: String, _ c: PlayerClient) async throws -> JSON {
        var client: [String: Any] = ["clientName": c.name, "clientVersion": c.version, "hl": language, "gl": region]
        if let v = c.osName { client["osName"] = v }
        if let v = c.osVersion { client["osVersion"] = v }
        if let v = c.deviceMake { client["deviceMake"] = v }
        if let v = c.deviceModel { client["deviceModel"] = v }
        if let v = c.androidSdkVersion { client["androidSdkVersion"] = v }
        if let visitorData { client["visitorData"] = visitorData }
        let body: [String: Any] = ["context": ["client": client], "videoId": videoId, "contentCheckOk": true, "racyCheckOk": true]
        var headers = ["User-Agent": c.userAgent, "X-YouTube-Client-Name": c.id, "X-YouTube-Client-Version": c.version]
        if let visitorData { headers["X-Goog-Visitor-Id"] = visitorData }
        return try await post("\(Self.youtubeBase)/player", body: body, headers: headers, query: [:], timeout: 8)
    }

    private func post(_ url: String, body: [String: Any], headers: [String: String], query: [String: String], timeout: TimeInterval = 15) async throws -> JSON {
        var components = URLComponents(string: url)!
        components.queryItems = [URLQueryItem(name: "prettyPrint", value: "false")] + query.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: components.url!, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("\(language)-\(region),\(language);q=0.9", forHTTPHeaderField: "Accept-Language")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        // A busy moment (429/5xx) usually passes; a short wait and two more tries.
        var attempt = 0
        while true {
            let (data, response) = try await session.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            if (code == 429 || code >= 500), attempt < 2 {
                attempt += 1
                try await Task.sleep(nanoseconds: UInt64(attempt) * 700_000_000)
                continue
            }
            guard (200..<300).contains(code) else { throw Failure.badResponse(code) }
            guard let json = JSON.parse(data) else { throw Failure.badResponse(code) }
            return json
        }
    }
}

/** The calls the screens make, already parsed. */
public enum MusicRepository {
    public static func home() async throws -> (shelves: [HomeShelf], continuation: String?) {
        let json = try await Innertube.shared.home()
        return (InnertubeParser.parseHome(json), InnertubeParser.sectionListContinuation(json))
    }

    public static func moreHome(_ token: String) async throws -> (shelves: [HomeShelf], continuation: String?) {
        let json = try await Innertube.shared.continuation(token)
        return (InnertubeParser.parseHomeContinuation(json), InnertubeParser.sectionListContinuation(json))
    }

    public static func search(_ query: String) async throws -> [SearchResult] {
        InnertubeParser.parseSearch(try await Innertube.shared.search(query))
    }

    public static func suggestions(_ input: String) async throws -> [String] {
        InnertubeParser.parseSearchSuggestions(try await Innertube.shared.searchSuggestions(input))
    }

    public static func collection(_ browseId: String) async throws -> CollectionPage {
        InnertubeParser.parseCollection(try await Innertube.shared.browse(browseId))
    }

    public static func artist(_ browseId: String) async throws -> ArtistPage {
        InnertubeParser.parseArtist(try await Innertube.shared.browse(browseId))
    }

    /** More songs like [song], for the queue once it runs out. */
    public static func radio(after song: Song) async throws -> [Song] {
        let queue = InnertubeParser.parseWatchQueue(try await Innertube.shared.next(song.videoId))
        // Catalogue tracks after a catalogue track; a mix of only uploads is still better than silence.
        let music = queue.filter { !$0.isVideo || song.isVideo }
        return music.isEmpty ? queue : music
    }
}
