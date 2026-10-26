import Foundation
import Observation

enum HTTP {
    /// One shared session. A URLCache is attached so photos, logos and radar tiles revisit cheaply.
    static let shared: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 8
        c.waitsForConnectivity = false
        c.httpAdditionalHeaders = ["User-Agent": "Contrail/0.1 (iOS)"]
        c.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 128 << 20)
        return URLSession(configuration: c)
    }()

    struct Status: Error, CustomStringConvertible {
        var code: Int
        var description: String { "HTTP \(code)" }
    }

    /// GET returning the body, throwing for non-2xx. 204 comes back as empty data.
    static func get(_ url: URL, session: URLSession = shared) async throws -> (Data, Int) {
        let (data, res) = try await session.data(from: url)
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw Status(code: code) }
        return (data, code)
    }
}

/// adsb.lol and adsb.fi serve the same readsb v2 JSON from different URL layouts. Both are keyless.
struct FeedEndpoints: Sendable {
    var name: String
    var base: String
    var point: @Sendable (Double, Double, Int) -> String
    var reg = "reg"

    static let adsbLol = FeedEndpoints(name: "adsb.lol", base: "https://api.adsb.lol/v2/") { lat, lon, nm in
        String(format: "point/%.4f/%.4f/%d", lat, lon, nm)
    }
    static let adsbFi = FeedEndpoints(name: "adsb.fi", base: "https://opendata.adsb.fi/api/v2/", point: { lat, lon, nm in
        String(format: "lat/%.4f/lon/%.4f/dist/%d", lat, lon, nm)
    }, reg: "registration")
}

protocol FeedSource: Sendable {
    var name: String { get }
    func point(lat: Double, lon: Double, nm: Int) async throws -> [Aircraft]
    func query(_ q: RemoteQuery) async throws -> [Aircraft]
}

struct AdsbSource: FeedSource {
    var endpoints: FeedEndpoints
    var name: String { endpoints.name }

    private func fetch(_ path: String) async throws -> [Aircraft] {
        guard let url = URL(string: endpoints.base + path) else { throw URLError(.badURL) }
        let (data, _) = try await HTTP.get(url)
        let received = Millis.now
        return try await Task.detached(priority: .userInitiated) { try parseReadsb(data, receivedAt: received) }.value
    }

    func point(lat: Double, lon: Double, nm: Int) async throws -> [Aircraft] {
        try await fetch(endpoints.point(lat, lon, min(max(nm, 1), 250)))
    }

    func query(_ q: RemoteQuery) async throws -> [Aircraft] {
        let path: String = switch q {
        case .hex(let h): "hex/\(h.lowercased())"
        case .callsign(let c): "callsign/\(c)"
        case .registration(let r): "\(endpoints.reg)/\(r)"
        case .type(let t): "type/\(t)"
        case .squawk(let s): "sqk/\(s)"
        }
        return try await fetch(path)
    }
}

struct FeedHealth: Hashable, Sendable {
    var ok: Bool
    var latencyMs: Int?
    var lastError: String?
    var checkedAt: Millis
}

struct FeedsUnavailable: Error {}

/// Round-robins the feeds, at most one call per source every 1.1 s (their published limit). A source that fails is
/// benched for a minute and tried last; if every source fails the call throws. Always go through `run`.
actor FeedScheduler {
    private let sources: [any FeedSource]
    private let clock: @Sendable () -> Millis
    private let minIntervalMs: Millis
    private var lastCall: [String: Millis] = [:]
    private var benchedUntil: [String: Millis] = [:]
    private var next = 0
    private let onHealth: @Sendable ([String: FeedHealth]) -> Void
    private(set) var health: [String: FeedHealth] = [:]

    static let benchMs: Millis = 60_000

    init(_ sources: [any FeedSource], clock: @escaping @Sendable () -> Millis = { .now }, minIntervalMs: Millis = 1100,
         onHealth: @escaping @Sendable ([String: FeedHealth]) -> Void = { _ in }) {
        self.sources = sources
        self.clock = clock
        self.minIntervalMs = minIntervalMs
        self.onHealth = onHealth
    }

    func run<T: Sendable>(_ call: @Sendable (any FeedSource) async throws -> T) async throws -> T {
        let now = clock()
        let start = next % sources.count
        next += 1
        let rotated = Array(sources[start...] + sources[..<start])
        let benched = { (s: any FeedSource) in (self.benchedUntil[s.name] ?? 0) > now }
        let ordered = rotated.filter { !benched($0) } + rotated.filter(benched)
        for s in ordered {
            try Task.checkCancellation()
            if let last = lastCall[s.name], minIntervalMs > 0 {
                let wait = last + minIntervalMs - clock()
                if wait > 0 { try await Task.sleep(for: .milliseconds(wait)) }
            }
            let t0 = clock()
            lastCall[s.name] = t0
            do {
                let r = try await call(s)
                benchedUntil[s.name] = nil
                report(s.name, FeedHealth(ok: true, latencyMs: Int(clock() - t0), lastError: nil, checkedAt: clock()))
                return r
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if (error as? URLError)?.code == .cancelled { throw CancellationError() }
                benchedUntil[s.name] = clock() + FeedScheduler.benchMs
                report(s.name, FeedHealth(ok: false, latencyMs: nil, lastError: "\(error)", checkedAt: clock()))
            }
        }
        throw FeedsUnavailable()
    }

    private func report(_ name: String, _ h: FeedHealth) {
        health[name] = h
        onHealth(health)
    }
}
