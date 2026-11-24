@testable import OpenTuneCore
import XCTest

final class PoTokenTests: XCTestCase {
    private let fields: [Any] = ["msg", ["interp-js", 1], NSNull(), "hash", "program", "globalVm", NSNull(), "blob"]

    func testPlainChallenge() throws {
        let raw = String(decoding: try JSONSerialization.data(withJSONObject: [fields]), as: UTF8.self)
        let parsed = try JSONSerialization.jsonObject(with: Data(try BotGuardData.challenge(fromCreateResponse: raw).utf8)) as! [String: Any]
        XCTAssertEqual(parsed["messageId"] as? String, "msg")
        XCTAssertEqual(parsed["program"] as? String, "program")
        XCTAssertEqual(parsed["globalName"] as? String, "globalVm")
        XCTAssertEqual(parsed["clientExperimentsStateBlob"] as? String, "blob")
        let interpreter = parsed["interpreterJavascript"] as! [String: Any]
        XCTAssertEqual(interpreter["privateDoNotAccessOrElseSafeScriptWrappedValue"] as? String, "interp-js")
        XCTAssertTrue(interpreter["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"] is NSNull)
    }

    func testScrambledChallenge() throws {
        // The real data, with 97 taken off every byte, in YouTube's URL-safe base64.
        let inner = try JSONSerialization.data(withJSONObject: fields)
        let scrambled = Data(inner.map { $0 &- 97 }).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: ".")
        let raw = "[null,\"\(scrambled)\"]"
        let parsed = try JSONSerialization.jsonObject(with: Data(try BotGuardData.challenge(fromCreateResponse: raw).utf8)) as! [String: Any]
        XCTAssertEqual(parsed["globalName"] as? String, "globalVm")
    }

    func testBadChallengeThrows() {
        XCTAssertThrowsError(try BotGuardData.challenge(fromCreateResponse: "[[\"too\",\"short\"]]"))
        XCTAssertThrowsError(try BotGuardData.challenge(fromCreateResponse: "not json"))
    }

    func testIntegrityToken() throws {
        let (js, lifetime) = try BotGuardData.integrityToken(fromGenerateITResponse: "[\"AQID_-8.\",43200]")
        XCTAssertEqual(js, "new Uint8Array([1,2,3,255,239])")
        XCTAssertEqual(lifetime, 43200)
    }

    func testIdentifierBytes() {
        XCTAssertEqual(BotGuardData.uint8Array("ab"), "new Uint8Array([97,98])")
    }

    func testMintedBytesBecomeUrlSafeBase64() throws {
        // 251,255,191 is "+/+/" in plain base64.
        XCTAssertEqual(try BotGuardData.token(fromJoinedBytes: "251,255,191,251,255,191"), "-_-_-_-_")
        XCTAssertThrowsError(try BotGuardData.token(fromJoinedBytes: "1,300"))
        XCTAssertThrowsError(try BotGuardData.token(fromJoinedBytes: ""))
    }
}
