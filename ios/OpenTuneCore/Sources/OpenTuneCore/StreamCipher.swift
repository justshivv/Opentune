import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/**
 * Web clients' stream URLs are scrambled: the signature has to be put through a function in
 * YouTube's player script, and so does the `n` parameter, or the CDN refuses the file. Which
 * functions, and the player's signature timestamp, come from a table the community keeps per
 * player version (ZemerTeam/zemer-cipher's `player_configs.json`, which the Android app's
 * InnerTubeX also reads). The player script itself is loaded into a web view with two small
 * functions added inside it, so they can reach the player's own names.
 */
public struct PlayerRecipe: Equatable, Sendable {
    /** A call like `Yo(2,7910,INPUT)`, with INPUT standing for the scrambled signature. */
    public let sig: String
    /** The player's URL class: built on a URL with `n=…`, its `get('n')` gives the transformed value. */
    public let nClass: String
    /** The player's signature timestamp, which the player request has to carry. */
    public let sts: Int
}

public enum CipherError: Error, CustomStringConvertible, Sendable {
    case noPlayer
    case noRecipe(String)
    case badPlayer(String)
    case unsolved(String)

    public var description: String {
        switch self {
        case .noPlayer: return "Couldn't find YouTube's current player"
        case let .noRecipe(id): return "No signature recipe yet for YouTube's player \(id)"
        case let .badPlayer(why): return "YouTube's player script: \(why)"
        case let .unsolved(what): return "Couldn't unscramble the stream's \(what)"
        }
    }
}

/** Parsing and URL building around the cipher; no JavaScript here. */
public enum StreamCipherData {
    static let sigPattern = #"^[A-Za-z0-9$_]{1,8}\(\d+,\d+,INPUT\)$"#
    static let namePattern = #"^[A-Za-z0-9$_]{1,8}$"#

    /** The recipes in the table, by player id and alias; entries that don't look right are left out. */
    public static func recipes(fromTable data: Data) throws -> [String: PlayerRecipe] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let players = root["players"] as? [String: Any] else { throw CipherError.badPlayer("unreadable recipe table") }
        var out: [String: PlayerRecipe] = [:]
        for (id, value) in players {
            guard let entry = value as? [String: Any],
                  let sig = entry["sig"] as? String, sig.range(of: sigPattern, options: .regularExpression) != nil,
                  let nClass = entry["nClass"] as? String, nClass.range(of: namePattern, options: .regularExpression) != nil,
                  let sts = (entry["sts"] as? NSNumber)?.intValue, sts > 0
            else { continue }
            let recipe = PlayerRecipe(sig: sig, nClass: nClass, sts: sts)
            out[id] = recipe
            for alias in entry["aliases"] as? [String] ?? [] { out[alias] = recipe }
        }
        return out
    }

    /** The current player's id from `iframe_api`: the part after `/s/player/`. */
    public static func playerId(fromIframeApi text: String) -> String? {
        let unescaped = text.replacingOccurrences(of: "\\/", with: "/")
        guard let range = unescaped.range(of: #"/s/player/([a-zA-Z0-9_-]+)/"#, options: .regularExpression) else { return nil }
        return String(unescaped[range].dropFirst("/s/player/".count).dropLast())
    }

    /** The signature timestamp written in the player script, which beats the table's when both exist. */
    public static func signatureTimestamp(inPlayer script: String) -> Int? {
        guard let range = script.range(of: #"signatureTimestamp['":\s]+(\d+)"#, options: .regularExpression) else { return nil }
        return Int(script[range].filter(\.isNumber))
    }

    static let playerTrailer = "})(_yt_player);"

    /**
     * The player script with `_cipherSigFunc` and `_nTransformFunc` added just inside its
     * closing line, where the player's own functions are in scope.
     */
    public static func instrumented(player script: String, recipe: PlayerRecipe) throws -> String {
        guard let trailer = script.range(of: playerTrailer, options: .backwards) else {
            throw CipherError.badPlayer("unexpected layout")
        }
        let sig = recipe.sig.replacingOccurrences(of: "INPUT", with: "sig")
        let n = "(function(n){try{var u=new g.\(recipe.nClass)('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(n)"
        let exports = "; window._cipherSigFunc=function(sig){try{return \(sig);}catch(e){return null;}};"
            + "window._nTransformFunc=function(n){try{return \(n);}catch(e){return n;}}; "
        return script.replacingCharacters(in: trailer, with: exports + playerTrailer)
    }

    /** The parts of a `signatureCipher`: the scrambled signature, the parameter it goes in, and the URL. */
    public static func signatureCipher(_ text: String) -> (s: String, sp: String, url: String)? {
        var parts: [String: String] = [:]
        for pair in text.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            guard kv.count == 2 else { continue }
            parts[kv[0]] = kv[1].replacingOccurrences(of: "+", with: " ").removingPercentEncoding
        }
        guard let s = parts["s"], let url = parts["url"] else { return nil }
        return (s, parts["sp"] ?? "signature", url)
    }

    /** The `n` value in [url], if it has one. */
    public static func nValue(in url: String) -> String? {
        URLComponents(string: url)?.queryItems?.first { $0.name == "n" }?.value
    }

    /** [url] with each of [replacing] set (replaced in place when already there) and [appending] added. */
    public static func url(_ url: String, setting values: [(String, String)]) -> URL? {
        guard var parts = URLComponents(string: url) else { return nil }
        var items = parts.queryItems ?? []
        for (name, value) in values {
            if let i = items.firstIndex(where: { $0.name == name }) { items[i].value = value } else { items.append(URLQueryItem(name: name, value: value)) }
        }
        parts.percentEncodedQuery = items.map { "\(escape($0.name))=\(escape($0.value ?? ""))" }.joined(separator: "&")
        return parts.url
    }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    private static func escape(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? s }

    /** A client playback nonce: 16 characters from YouTube's URL-safe alphabet. */
    public static func playbackNonce() -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        return String((0..<16).map { _ in alphabet.randomElement()! })
    }
}

#if canImport(WebKit)
import WebKit

/**
 * Keeps YouTube's current player loaded in a web view and unscrambles signatures and `n`
 * values with it. The player is fetched once per version; the recipe table at most every six
 * hours, or straight away when the player is newer than the table.
 */
@MainActor
public final class StreamCipher {
    static let tableURL = URL(string: "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json")!
    static let iframeApiURL = URL(string: "https://www.youtube.com/iframe_api")!
    static func playerURL(_ id: String) -> URL { URL(string: "https://www.youtube.com/s/player/\(id)/player_ias.vflset/en_GB/base.js")! }

    private let session: URLSession
    private var table: [String: PlayerRecipe] = [:]
    private var tableFetchedAt = Date.distantPast
    private var webView: WKWebView?
    private var loadedPlayer: String?
    private var checkedPlayerAt = Date.distantPast
    /** The signature timestamp of the loaded player. */
    public private(set) var signatureTimestamp: Int?
    private var loading: Task<Void, Error>?

    public init(session: URLSession = .shared) {
        self.session = session
    }

    /** Makes sure today's player is loaded; cheap when it already is. */
    public func prepare() async throws {
        if let loading { return try await loading.value }
        let task = Task { try await load() }
        loading = task
        defer { loading = nil }
        try await task.value
    }

    private func load() async throws {
        // The player changes every few days; look at most every half hour.
        if loadedPlayer != nil, Date().timeIntervalSince(checkedPlayerAt) < 1800 { return }
        let iframe = try await text(Self.iframeApiURL)
        guard let id = StreamCipherData.playerId(fromIframeApi: iframe) else { throw CipherError.noPlayer }
        checkedPlayerAt = Date()
        if id == loadedPlayer, webView != nil { return }

        if table[id] == nil || Date().timeIntervalSince(tableFetchedAt) > 6 * 3600 {
            let (data, _) = try await session.data(for: URLRequest(url: Self.tableURL, timeoutInterval: 15))
            table = try StreamCipherData.recipes(fromTable: data)
            tableFetchedAt = Date()
        }
        guard let recipe = table[id] else {
            // A new player the table doesn't know yet: the one already loaded still works.
            if webView != nil { return }
            throw CipherError.noRecipe(id)
        }
        let script = try await text(Self.playerURL(id))
        let source = try StreamCipherData.instrumented(player: script, recipe: recipe)

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        try await PageLoad.load("<!DOCTYPE html><html><head></head><body></body></html>", in: view)
        do {
            _ = try await withDeadline(20) { try await view.evaluateJavaScript("window._yt_player = window._yt_player || {};\n" + source + "\n;true") }
        } catch let error as PoTokenError {
            throw CipherError.badPlayer(error.description)
        } catch {
            throw CipherError.badPlayer((error as NSError).userInfo["WKJavaScriptExceptionMessage"] as? String ?? "\(error)")
        }
        // The same probe InnerTubeX makes: a working transform changes the value.
        let probe = "KdrqFlzJXl9EcCwlmEy"
        guard let out = try await call("_nTransformFunc", probe, in: view), out != probe, out.count >= 5 else {
            throw CipherError.badPlayer("the n transform didn't work on player \(id)")
        }
        webView = view
        loadedPlayer = id
        signatureTimestamp = StreamCipherData.signatureTimestamp(inPlayer: script) ?? recipe.sts
    }

    public func signature(_ scrambled: String) async throws -> String {
        try await solve("_cipherSigFunc", scrambled, what: "signature")
    }

    public func n(_ value: String) async throws -> String {
        try await solve("_nTransformFunc", value, what: "n parameter")
    }

    /** One call into the player; if it fails, the player is loaded afresh and asked once more. */
    private func solve(_ function: String, _ input: String, what: String, retry: Bool = true) async throws -> String {
        do {
            try await prepare()
            guard let webView, let out = try await call(function, input, in: webView), !out.isEmpty, out != input else {
                throw CipherError.unsolved(what)
            }
            return out
        } catch where retry {
            // The page goes when iOS ends the web view's process (in the background, say).
            reset()
            return try await solve(function, input, what: what, retry: false)
        }
    }

    /** Forgets the loaded player, so the next use loads it again (after a refused stream, say). */
    public func reset() {
        loading = nil
        webView = nil
        loadedPlayer = nil
        checkedPlayerAt = .distantPast
    }

    private func call(_ function: String, _ input: String, in view: WKWebView) async throws -> String? {
        try await withDeadline(10) {
            try await view.callAsyncJavaScript("return window.\(function)(input);", arguments: ["input": input], in: nil, contentWorld: .page) as? String
        }
    }

    private func text(_ url: URL) async throws -> String {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com/", forHTTPHeaderField: "Referer")
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw CipherError.noPlayer }
        return String(decoding: data, as: UTF8.self)
    }
}

#endif
