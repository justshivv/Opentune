import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/**
 * Proof-of-origin tokens. Without one, googlevideo serves the first megabyte of a song and
 * refuses the rest with a 403. A token comes from running YouTube's BotGuard challenge in a
 * web view, as the Android app does (`core/.../innertube/potoken`, after NewPipe): the
 * challenge program is fetched, run, its answer traded for an integrity token, and that
 * token makes a minter that turns an identifier into a poToken.
 *
 * A minter makes two: one bound to the session (visitor data), minted first and once per
 * web view, and one bound to the video. The WEB_REMIX client's stream URLs take the video
 * one as `pot`; tried live, the session one isn't accepted there.
 */
public struct PoTokens: Sendable, Equatable {
    /** Bound to the video id: goes on WEB_REMIX stream URLs as `pot`. */
    public let video: String
    /** Bound to the session's visitor data. */
    public let session: String

    public init(video: String, session: String) {
        self.video = video
        self.session = session
    }
}

/**
 * What the WEB_REMIX stream path needs from a browser engine: tokens, and YouTube's player to
 * unscramble URLs with. The app's runs in web views; see [BrowserSolver].
 */
public protocol WebClientSolver: Sendable {
    /** The video-bound proof-of-origin token for [videoId] in [visitorData]'s session. */
    func videoToken(videoId: String, visitorData: String) async throws -> String
    /** The current player's signature timestamp, which the player request has to carry. */
    func signatureTimestamp() async throws -> Int
    func signature(_ scrambled: String) async throws -> String
    func n(_ value: String) async throws -> String
    /** Drops the loaded player and token web views, after a stream they made was refused. */
    func reset() async
}

public enum PoTokenError: Error, CustomStringConvertible, Sendable {
    case badData(String)
    case javascript(String)
    case request(Int)
    case timedOut

    public var description: String {
        switch self {
        case let .badData(what): return "PO token: unexpected \(what)"
        case let .javascript(message): return "PO token: \(message)"
        case let .request(code): return "PO token: BotGuard answered \(code)"
        case .timedOut: return "PO token: timed out"
        }
    }
}

/** The data conversions around BotGuard, in the shapes its JavaScript wants. */
public enum BotGuardData {
    /**
     * The Create endpoint's answer as a JavaScript object literal for `runBotGuard`.
     * The answer is an array; when its second element is a string, that string is the
     * real data, base64-encoded with 97 subtracted from every byte.
     */
    public static func challenge(fromCreateResponse raw: String) throws -> String {
        guard let outer = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [Any] else {
            throw PoTokenError.badData("challenge")
        }
        let data: [Any]
        if outer.count > 1, let scrambled = outer[1] as? String {
            let bytes = try decode(scrambled).map { $0 &+ 97 }
            guard let inner = try JSONSerialization.jsonObject(with: Data(bytes)) as? [Any] else {
                throw PoTokenError.badData("descrambled challenge")
            }
            data = inner
        } else if let first = outer.first as? [Any] {
            data = first
        } else {
            throw PoTokenError.badData("challenge")
        }
        guard data.count > 7,
              let messageId = data[0] as? String, let interpreterHash = data[3] as? String,
              let program = data[4] as? String, let globalName = data[5] as? String,
              let experiments = data[7] as? String
        else { throw PoTokenError.badData("challenge fields") }
        func firstString(_ value: Any) -> Any { (value as? [Any])?.first { $0 is String } ?? NSNull() }
        let object: [String: Any] = [
            "messageId": messageId,
            "interpreterJavascript": [
                "privateDoNotAccessOrElseSafeScriptWrappedValue": firstString(data[1]),
                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue": firstString(data[2]),
            ],
            "interpreterHash": interpreterHash,
            "program": program,
            "globalName": globalName,
            "clientExperimentsStateBlob": experiments,
        ]
        return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }

    /** The GenerateIT answer: the integrity token as a `Uint8Array` literal, and how many seconds it lasts. */
    public static func integrityToken(fromGenerateITResponse raw: String) throws -> (javascript: String, lifetime: TimeInterval) {
        guard let array = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [Any],
              array.count > 1, let token = array[0] as? String, let seconds = array[1] as? NSNumber
        else { throw PoTokenError.badData("integrity token") }
        return (uint8Array(try decode(token)), seconds.doubleValue)
    }

    /** [text]'s UTF-8 bytes as a `Uint8Array` literal: what the minter is given. */
    public static func uint8Array(_ text: String) -> String { uint8Array(Array(text.utf8)) }

    static func uint8Array(_ bytes: [UInt8]) -> String {
        "new Uint8Array([" + bytes.map(String.init).joined(separator: ",") + "])"
    }

    /** The minter's bytes, as `Uint8Array.join(",")` gives them, in YouTube's URL-safe base64. */
    public static func token(fromJoinedBytes joined: String) throws -> String {
        let bytes = try joined.split(separator: ",").map { part -> UInt8 in
            guard let b = UInt8(part.trimmingCharacters(in: .whitespaces)) else { throw PoTokenError.badData("token bytes") }
            return b
        }
        guard !bytes.isEmpty else { throw PoTokenError.badData("empty token") }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
    }

    /** YouTube's base64: `-` and `_` for `+` and `/`, and `.` for padding. */
    static func decode(_ text: String) throws -> [UInt8] {
        var s = text.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: ".", with: "=")
        while s.count % 4 != 0 { s += "=" }
        guard let data = Data(base64Encoded: s) else { throw PoTokenError.badData("base64") }
        return Array(data)
    }
}

#if canImport(WebKit)
import WebKit

/**
 * Runs BotGuard in an off-screen web view and mints tokens with it. One web view per
 * session, rebuilt when the session (visitor data) changes, when its integrity token
 * expires, or after a failure; the streaming token is minted once per web view, first.
 */
@MainActor
public final class BotGuardTokenMinter {
    private static let apiKey = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private static let requestKey = "O43z0dpjhgX20SCx4KAo"
    /**
     * BotGuard looks at the browser it runs in: its tokens are only accepted when the user agent
     * is an honest one for the engine, which here is Safari's WebKit. (Android's WebView is
     * Chrome, hence its Chrome agent.) WebKit's own default, with no Safari part, isn't accepted either.
     */
    #if os(iOS)
    nonisolated public static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    #else
    nonisolated public static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15"
    #endif

    private var webView: WKWebView?
    private var sessionId: String?
    private var streamingToken: String?
    private var expiresAt = Date.distantPast
    /** One build or mint at a time: BotGuard isn't made for overlapping calls. */
    private var busy: Task<Void, Never>?
    private let session: URLSession
    /** The web view's user agent; nil keeps WebKit's own. */
    private let userAgent: String?

    public init(session: URLSession = .shared, userAgent: String? = BotGuardTokenMinter.userAgent) {
        self.session = session
        self.userAgent = userAgent
    }

    /** Tokens for [videoId] in [visitorData]'s session; a failed mint is retried once on a fresh web view. */
    public func tokens(videoId: String, visitorData: String) async throws -> PoTokens {
        while let busy { await busy.value }
        let work = Task<PoTokens, Error> { try await mint(videoId: videoId, visitorData: visitorData, retry: true) }
        busy = Task { _ = try? await work.value }
        defer { busy = nil }
        return try await work.value
    }

    private func mint(videoId: String, visitorData: String, retry: Bool) async throws -> PoTokens {
        var fresh = false
        if webView == nil || sessionId != visitorData || Date() >= expiresAt || streamingToken == nil {
            try await rebuild(visitorData: visitorData)
            fresh = true
        }
        do {
            let player = try await obtain(videoId)
            guard let streamingToken else { throw PoTokenError.badData("missing streaming token") }
            return PoTokens(video: player, session: streamingToken)
        } catch where retry && !fresh {
            // The page can be lost while the app is in the background: start over once.
            reset()
            return try await mint(videoId: videoId, visitorData: visitorData, retry: false)
        }
    }

    public func reset() {
        webView?.stopLoading()
        webView = nil
        sessionId = nil
        streamingToken = nil
        expiresAt = .distantPast
    }

    private func rebuild(visitorData: String) async throws {
        reset()
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        if let userAgent { view.customUserAgent = userAgent }
        try await load(BotGuardPage.html, in: view)

        let challenge = try BotGuardData.challenge(fromCreateResponse: try await post("Create", "[\"\(Self.requestKey)\"]"))
        let answer = try await run(
            """
            const result = await runBotGuard(\(challenge));
            window.webPoSignalOutput = result.webPoSignalOutput;
            return result.botguardResponse;
            """, in: view)
        guard let botguardResponse = answer as? String else { throw PoTokenError.badData("BotGuard response") }

        let generated = try await post("GenerateIT", "[\"\(Self.requestKey)\",\"\(botguardResponse)\"]")
        let (integrity, lifetime) = try BotGuardData.integrityToken(fromGenerateITResponse: generated)
        _ = try await run("await createPoTokenMinter(window.webPoSignalOutput, \(integrity)); return true;", in: view)

        webView = view
        sessionId = visitorData
        // Ten minutes' margin, as on Android.
        expiresAt = Date().addingTimeInterval(lifetime - 600)
        // The streaming token has to be minted before any player token.
        streamingToken = try await obtain(visitorData)
    }

    private func obtain(_ identifier: String) async throws -> String {
        guard let webView else { throw PoTokenError.badData("no web view") }
        let joined = try await run(
            "const token = await obtainPoToken(\(BotGuardData.uint8Array(identifier))); return Array.from(token).join(',');",
            in: webView)
        guard let joined = joined as? String else { throw PoTokenError.badData("minted token") }
        return try BotGuardData.token(fromJoinedBytes: joined)
    }

    private func run(_ body: String, in view: WKWebView) async throws -> Any? {
        do {
            return try await withDeadline(20) {
                try await view.callAsyncJavaScript(body, arguments: [:], in: nil, contentWorld: .page)
            }
        } catch let error as PoTokenError {
            throw error
        } catch {
            let info = (error as NSError).userInfo
            throw PoTokenError.javascript(info["WKJavaScriptExceptionMessage"] as? String ?? "\(error)")
        }
    }

    private func load(_ html: String, in view: WKWebView) async throws {
        try await PageLoad.load(html, in: view)
    }

    private func post(_ endpoint: String, _ body: String) async throws -> String {
        var request = URLRequest(url: URL(string: "https://www.youtube.com/api/jnn/v1/\(endpoint)")!, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.httpBody = Data(body.utf8)
        request.setValue(userAgent ?? Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json+protobuf", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.apiKey, forHTTPHeaderField: "x-goog-api-key")
        request.setValue("grpc-web-javascript/0.1", forHTTPHeaderField: "x-user-agent")
        let (data, response) = try await session.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard code == 200, !data.isEmpty else { throw PoTokenError.request(code) }
        return String(decoding: data, as: UTF8.self)
    }
}

/** Tokens and the unscrambling player, each in its own web view. */
@MainActor
public final class BrowserSolver: WebClientSolver {
    private let minter: BotGuardTokenMinter
    private let cipher: StreamCipher

    public init(session: URLSession = .shared) {
        minter = BotGuardTokenMinter(session: session)
        cipher = StreamCipher(session: session)
    }

    public func videoToken(videoId: String, visitorData: String) async throws -> String {
        try await minter.tokens(videoId: videoId, visitorData: visitorData).video
    }

    public func signatureTimestamp() async throws -> Int {
        try await cipher.prepare()
        guard let sts = cipher.signatureTimestamp else { throw CipherError.noPlayer }
        return sts
    }

    public func signature(_ scrambled: String) async throws -> String { try await cipher.signature(scrambled) }
    public func n(_ value: String) async throws -> String { try await cipher.n(value) }

    public func reset() {
        cipher.reset()
        minter.reset()
    }

    /** Loads the player and makes the first tokens ahead of the first song. */
    public func warmUp(visitorData: String) async {
        _ = try? await cipher.prepare()
        _ = try? await minter.tokens(videoId: "dQw4w9WgXcQ", visitorData: visitorData)
    }
}

/** Loads a page into a web view and waits for it, failing if the page or its process dies. */
@MainActor
final class PageLoad: NSObject, WKNavigationDelegate {
    private var result: Result<Void, Error>?
    private var waiting: CheckedContinuation<Void, Error>?

    static func load(_ html: String, in view: WKWebView) async throws {
        let waiter = PageLoad()
        view.navigationDelegate = waiter
        defer { view.navigationDelegate = nil }
        view.loadHTMLString(html, baseURL: URL(string: "https://www.youtube.com"))
        try await withDeadline(15) { try await waiter.finished() }
    }

    private func finished() async throws {
        if let result { return try result.get() }
        try await withCheckedThrowingContinuation { waiting = $0 }
    }

    private func finish(_ r: Result<Void, Error>) {
        guard result == nil else { return }
        result = r
        waiting?.resume(with: r)
        waiting = nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finish(.success(())) }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish(.failure(error)) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finish(.failure(error)) }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { finish(.failure(PoTokenError.javascript("the web view's process ended"))) }
}

/** Whether a deadline's race is decided; only touched on the main actor. */
@MainActor
final class DeadlineFlag { var done = false }

/**
 * [work], or `timedOut` after [seconds], whichever comes first. Unlike a task group, this
 * doesn't wait for the work once time is up: a WebKit call that never answers can't be
 * cancelled, and would otherwise hold everything behind it.
 */
@MainActor
func withDeadline<T>(_ seconds: Double, _ work: @escaping @MainActor () async throws -> T) async throws -> T {
    let once = DeadlineFlag()
    return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T, Error>) in
        let task = Task { @MainActor in
            let result: Result<T, Error>
            do { result = .success(try await work()) } catch { result = .failure(error) }
            guard !once.done else { return }
            once.done = true
            continuation.resume(with: result)
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !once.done else { return }
            once.done = true
            task.cancel()
            continuation.resume(throwing: PoTokenError.timedOut)
        }
    }
}

/** Android's `po_token.html`: loads the BotGuard VM, runs it, makes and keeps the minter. */
enum BotGuardPage {
    static let html = """
    <!DOCTYPE html><html lang="en"><head><title></title><script>
    var bgVmFunctions = null;
    var poTokenMinter = null;

    function loadBotGuard(globalName, program) {
      var vm = window[globalName];
      bgVmFunctions = null;
      if (!vm) throw new Error('BotGuard VM not found');
      if (!vm.a) throw new Error('Could not load the BotGuard program');
      vm.a(program, function (asyncSnapshot, shutdown, passEvent, checkCamera) {
        bgVmFunctions = { asyncSnapshotFunction: asyncSnapshot };
      }, true, undefined, function () {}, [[], []]);
      return new Promise(function (resolve, reject) {
        var waited = 0;
        var timer = setInterval(function () {
          if (bgVmFunctions && bgVmFunctions.asyncSnapshotFunction) {
            clearInterval(timer);
            resolve(bgVmFunctions);
          } else if (++waited > 1000) {
            clearInterval(timer);
            reject(new Error('Timed out waiting for the BotGuard snapshot function'));
          }
        }, 10);
      });
    }

    async function runBotGuard(challenge) {
      var interpreter = challenge.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
      if (!interpreter) throw new Error('No BotGuard interpreter in the challenge');
      new Function(interpreter)();
      var webPoSignalOutput = [];
      var vmFunctions = await loadBotGuard(challenge.globalName, challenge.program);
      var botguardResponse = await new Promise(function (resolve) {
        vmFunctions.asyncSnapshotFunction(resolve, [undefined, undefined, webPoSignalOutput, undefined]);
      });
      return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse };
    }

    async function createPoTokenMinter(webPoSignalOutput, integrityToken) {
      var getMinter = webPoSignalOutput[0];
      if (typeof getMinter !== 'function') throw new Error('BotGuard gave no minter factory');
      var minter = await getMinter(integrityToken);
      if (typeof minter !== 'function') throw new Error('BotGuard gave no minter');
      poTokenMinter = minter;
    }

    async function obtainPoToken(identifier) {
      if (!poTokenMinter) throw new Error('The minter was never made');
      var token = await poTokenMinter(identifier);
      if (!(token instanceof Uint8Array)) throw new Error('The minter gave no bytes');
      return token;
    }
    </script></head><body></body></html>
    """
}
#endif
