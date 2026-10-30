import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/**
 * Saves a song's audio file for listening offline. YouTube slows a stream
 * fetched in one request to the speed it plays at, so the file is fetched in
 * pieces with its `range` parameter, as the Android app does.
 */
public enum SongDownloader {
    public enum Failure: Error, CustomStringConvertible, Sendable {
        /** Only an HLS manifest was offered: a list of pieces, not a file to keep. */
        case notAFile
        case badResponse(Int)
        case empty

        public var description: String {
            switch self {
            case .notAFile: return "This song can't be saved on this phone."
            case let .badResponse(code): return "The download stopped (HTTP \(code))."
            case .empty: return "The download came back empty."
            }
        }
    }

    /** How much each request fetches. */
    public static let pieceSize: Int64 = 2 << 20

    /** The byte ranges, first and last byte included, a file of [total] bytes is fetched in. */
    public static func ranges(total: Int64, piece: Int64 = pieceSize) -> [ClosedRange<Int64>] {
        guard total > 0, piece > 0 else { return [] }
        return stride(from: Int64(0), to: total, by: Int(piece)).map { $0...min($0 + piece, total) - 1 }
    }

    /** [base] with its `range` parameter set to [range], replacing one already there. */
    public static func url(_ base: URL, range: ClosedRange<Int64>) -> URL {
        guard var parts = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return base }
        var items = (parts.queryItems ?? []).filter { $0.name != "range" }
        items.append(URLQueryItem(name: "range", value: "\(range.lowerBound)-\(range.upperBound)"))
        parts.queryItems = items
        return parts.url ?? base
    }

    /**
     * Fetches [stream] into [file], replacing what was there, and returns its
     * size. [progress] goes from 0 to 1 as the pieces come in. A half-fetched
     * file is deleted when it fails or is cancelled.
     */
    @discardableResult
    public static func fetch(
        _ stream: Innertube.Stream, to file: URL, session: URLSession = .shared,
        progress: @Sendable (Double) -> Void = { _ in }
    ) async throws -> Int64 {
        guard !stream.isHLS else { throw Failure.notAFile }
        let partial = file.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: partial)
        guard FileManager.default.createFile(atPath: partial.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        do {
            let handle = try FileHandle(forWritingTo: partial)
            defer { try? handle.close() }
            var written: Int64 = 0
            if let total = stream.contentLength, total > 0 {
                for range in ranges(total: total) {
                    try Task.checkCancellation()
                    let data = try await get(url(stream.url, range: range), stream.headers, session)
                    try handle.write(contentsOf: data)
                    written += Int64(data.count)
                    progress(min(1, Double(written) / Double(total)))
                    // A short piece is the end, whatever the size said.
                    if Int64(data.count) < Int64(range.count) { break }
                }
            } else {
                let data = try await get(stream.url, stream.headers, session)
                try handle.write(contentsOf: data)
                written = Int64(data.count)
                progress(1)
            }
            guard written > 0 else { throw Failure.empty }
            try? handle.close()
            try? FileManager.default.removeItem(at: file)
            try FileManager.default.moveItem(at: partial, to: file)
            return written
        } catch {
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
    }

    private static func get(_ url: URL, _ headers: [String: String], _ session: URLSession) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: 30)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        let (data, response) = try await session.data(for: request)
        if let code = (response as? HTTPURLResponse)?.statusCode, !(200..<300).contains(code) { throw Failure.badResponse(code) }
        return data
    }
}
