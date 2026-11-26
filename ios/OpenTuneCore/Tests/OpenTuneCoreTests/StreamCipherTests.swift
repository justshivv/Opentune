@testable import OpenTuneCore
import XCTest

final class StreamCipherTests: XCTestCase {
    func testRecipeTableKeepsGoodEntriesAndAliases() throws {
        let table = """
        {"schemaVersion":"1","players":{
          "b485e332":{"sig":"Yo(2,7910,INPUT)","nClass":"Xo","sts":20779,"aliases":["fe40e19e"]},
          "deadbeef":{"sig":"alert(1);Yo(2,7910,INPUT)","nClass":"Xo","sts":1},
          "cafebabe":{"sig":"Yo(2,7910,INPUT)","nClass":"X o","sts":1},
          "0badf00d":{"sig":"Yo(2,7910,INPUT)","nClass":"Xo","sts":0}
        }}
        """
        let recipes = try StreamCipherData.recipes(fromTable: Data(table.utf8))
        XCTAssertEqual(recipes["b485e332"], PlayerRecipe(sig: "Yo(2,7910,INPUT)", nClass: "Xo", sts: 20779))
        XCTAssertEqual(recipes["fe40e19e"], recipes["b485e332"])
        // Anything that isn't a plain call or name is left out, so the table can't run other code.
        XCTAssertNil(recipes["deadbeef"])
        XCTAssertNil(recipes["cafebabe"])
        XCTAssertNil(recipes["0badf00d"])
    }

    func testPlayerIdFromIframeApi() {
        XCTAssertEqual(StreamCipherData.playerId(fromIframeApi: #"var a='https:\/\/www.youtube.com\/s\/player\/b485e332\/www-widgetapi.vflset\/www-widgetapi.js';"#), "b485e332")
        XCTAssertNil(StreamCipherData.playerId(fromIframeApi: "nothing here"))
    }

    func testSignatureTimestampInPlayer() {
        XCTAssertEqual(StreamCipherData.signatureTimestamp(inPlayer: "x={signatureTimestamp:20779,y:1}"), 20779)
        XCTAssertNil(StreamCipherData.signatureTimestamp(inPlayer: "no timestamp"))
    }

    func testInstrumentedPlayerExportsInsideTheClosure() throws {
        let player = "var _yt_player={};(function(g){var window=this;Yo=function(a,b,c){return c};})(_yt_player);"
        let out = try StreamCipherData.instrumented(player: player, recipe: PlayerRecipe(sig: "Yo(2,7910,INPUT)", nClass: "Xo", sts: 1))
        XCTAssertTrue(out.contains("window._cipherSigFunc=function(sig){try{return Yo(2,7910,sig);}"))
        XCTAssertTrue(out.contains("new g.Xo('https://x.googlevideo.com/videoplayback?n='+n,true)"))
        XCTAssertTrue(out.hasSuffix("})(_yt_player);"))
        XCTAssertThrowsError(try StreamCipherData.instrumented(player: "no closure", recipe: PlayerRecipe(sig: "a(1,2,INPUT)", nClass: "b", sts: 1)))
    }

    func testSignatureCipherAndUrlBuilding() throws {
        let cipher = try XCTUnwrap(StreamCipherData.signatureCipher("s=AB%3DC%2BD&sp=sig&url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fn%3Dabc%26itag%3D140"))
        XCTAssertEqual(cipher.s, "AB=C+D")
        XCTAssertEqual(cipher.sp, "sig")
        XCTAssertEqual(cipher.url, "https://r1.googlevideo.com/videoplayback?n=abc&itag=140")
        XCTAssertEqual(StreamCipherData.nValue(in: cipher.url), "abc")

        let url = try XCTUnwrap(StreamCipherData.url(cipher.url, setting: [("n", "x/y"), ("sig", "a+b="), ("pot", "t_-")]))
        XCTAssertEqual(url.absoluteString, "https://r1.googlevideo.com/videoplayback?n=x%2Fy&itag=140&sig=a%2Bb%3D&pot=t_-")
        // No `sp`: the parameter is called "signature".
        XCTAssertEqual(StreamCipherData.signatureCipher("s=x&url=https%3A%2F%2Fa.googlevideo.com%2Fv")?.sp, "signature")
    }

    func testPlaybackNonce() {
        let cpn = StreamCipherData.playbackNonce()
        XCTAssertEqual(cpn.count, 16)
        XCTAssertNil(cpn.range(of: "[^A-Za-z0-9_-]", options: .regularExpression))
    }

    #if canImport(WebKit)
    @MainActor
    func testDeadlineDoesNotWaitForWorkThatNeverEnds() async {
        let start = Date()
        do {
            _ = try await withDeadline(0.2) {
                try await withCheckedThrowingContinuation { (_: CheckedContinuation<Int, Error>) in }
            }
            XCTFail("should have timed out")
        } catch PoTokenError.timedOut {
        } catch {
            XCTFail("unexpected \(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
    }

    @MainActor
    func testDeadlinePassesResultsAndErrorsThrough() async throws {
        let value = try await withDeadline(5) { 42 }
        XCTAssertEqual(value, 42)
        do {
            _ = try await withDeadline(5) { () -> Int in throw CipherError.noPlayer }
            XCTFail("should have thrown")
        } catch CipherError.noPlayer {}
    }
    #endif
}
