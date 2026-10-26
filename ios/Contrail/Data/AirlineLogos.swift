import UIKit

/// Airline tail/symbol marks from two open logo sets on GitHub (keyless, keyed by ICAO code): Jxck-S's
/// FlightAware-style tails first (square, best coverage of regionals), then sexym0nk3y's as a fallback. Only logos on
/// a transparent background are used; boxed or full-bleed ones are rejected. Downloads are cached on disk, so each
/// airline is fetched once per install.
actor AirlineLogos {
    static let shared = AirlineLogos()

    private static let sources = [
        "https://raw.githubusercontent.com/Jxck-S/airline-logos/main/flightaware_logos/{ICAO}.png",
        "https://raw.githubusercontent.com/sexym0nk3y/airline-logos/main/logos/{ICAO}.png",
    ]
    /// Operators that fly in a parent's colours but have no logo of their own in either set.
    private static let aliases = ["NWK": "QLK"]

    private let dir: URL
    private var memory: [String: UIImage?] = [:]
    private var inFlight: [String: Task<UIImage??, Never>] = [:]
    /// Synchronous lookup for the map's annotation views. NSCache is thread-safe.
    nonisolated(unsafe) let peekCache = NSCache<NSString, UIImage>()

    init() {
        dir = URL.cachesDirectory.appending(path: "airline-logos-v2", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// The trimmed logo, or nil if this airline has no clean logo. Concurrent calls share one download.
    func get(_ icao: String) async -> UIImage? {
        if let m = memory[icao] { return m }
        let dir = dir
        let t = inFlight[icao] ?? Task.detached(priority: .utility) { () -> UIImage?? in
            do { return .some(try await AirlineLogos.load(icao, dir: dir)) } catch { return .none }
        }
        inFlight[icao] = t
        let result = await t.value
        inFlight[icao] = nil
        // A network error (.none) isn't remembered, so the next sheet retries.
        if case .some(let img) = result {
            memory[icao] = img
            if let img { peekCache.setObject(img, forKey: icao as NSString) }
        }
        return result ?? nil
    }

    private static func load(_ icao: String, dir: URL) async throws -> UIImage? {
        let file = dir.appending(path: "\(icao).png"), none = dir.appending(path: "\(icao).none")
        let fm = FileManager.default
        if fm.fileExists(atPath: none.path) { return nil }
        if let d = try? Data(contentsOf: file) { return decodeClean(d) }
        // First set with a clean (transparent) logo wins; the chosen bytes are cached.
        for template in sources {
            let url = URL(string: template.replacingOccurrences(of: "{ICAO}", with: aliases[icao] ?? icao))!
            let bytes: Data
            do {
                (bytes, _) = try await HTTP.get(url)
            } catch let e as HTTP.Status where e.code == 404 {
                continue
            }
            if let img = decodeClean(bytes) {
                try? bytes.write(to: file)
                return img
            }
        }
        fm.createFile(atPath: none.path, contents: nil)
        return nil
    }

    private static func decodeClean(_ bytes: Data) -> UIImage? {
        guard let cg = UIImage(data: bytes)?.cgImage else { return nil }
        let w = cg.width, h = cg.height
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let drawn = px.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        let alpha = stride(from: 3, to: px.count, by: 4).map { px[$0] }
        guard isCleanLogo(alpha: alpha, w, h), let b = opaqueBounds(alpha: alpha, w, h),
              let cropped = cg.cropping(to: CGRect(x: b.minX, y: b.minY, width: b.width, height: b.height)) else { return nil }
        return UIImage(cgImage: cropped, scale: 3, orientation: .up)
    }

    /// A usable mark sits on transparency: clear corners, and well under the whole canvas is solid. A square badge
    /// or photo-style logo fails this and is skipped. `alpha` is row-major.
    static func isCleanLogo(alpha: [UInt8], _ w: Int, _ h: Int) -> Bool {
        guard w >= 16, h >= 16 else { return false }
        let corners = [alpha[0], alpha[w - 1], alpha[(h - 1) * w], alpha[h * w - 1]]
        if corners.contains(where: { $0 > 24 }) { return false }
        let solid = alpha.reduce(0) { $0 + ($1 > 200 ? 1 : 0) }
        return solid > alpha.count / 50 && Double(solid) < Double(alpha.count) * 0.6
    }

    /// Bounds of the visible pixels, or nil if the image is empty.
    static func opaqueBounds(alpha: [UInt8], _ w: Int, _ h: Int) -> CGRect? {
        var l = w, t = h, r = -1, b = -1
        for y in 0..<h {
            for x in 0..<w where alpha[y * w + x] > 16 {
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
        }
        return r < 0 ? nil : CGRect(x: l, y: t, width: r - l + 1, height: b - t + 1)
    }
}
