import Foundation

/// Open-Meteo levels: 250 hPa is about FL340 (airliner cruise), 500 hPa about FL180.
enum WindLevel: String, CaseIterable, Codable, Sendable {
    case surface, fl180, fl340

    var label: String {
        switch self {
        case .surface: "Surface"
        case .fl180: "FL180"
        case .fl340: "FL340"
        }
    }

    var speedVar: String {
        switch self {
        case .surface: "wind_speed_10m"
        case .fl180: "wind_speed_500hPa"
        case .fl340: "wind_speed_250hPa"
        }
    }

    var dirVar: String { speedVar.replacingOccurrences(of: "speed", with: "direction") }
}

/// Wind at one grid point for every level: speed (kt) and the direction it blows from (°).
struct WindSample: Equatable, Sendable {
    var speedKt: [WindLevel: Double]
    var fromDeg: [WindLevel: Double]
}

/// Wind on a regular lat/lon grid with `step`° spacing, keyed by grid index. Points come from Open-Meteo, which
/// counts every point as a call (10,000 a day per IP), so the grid is coarse and aligned to fixed multiples of
/// `step`: panning reuses points already fetched.
struct WindGrid: Equatable, Sendable {
    var step: Double
    var samples: [Int64: WindSample]

    static let maxPoints = 200
    private static let steps: [Double] = [0.25, 0.5, 1, 2, 3, 5, 10, 15, 20, 30]

    /// Wind vector (east, north) in kt, bilinear between the four grid points around it; nil if any is missing.
    func sample(lat: Double, lon: Double, level: WindLevel) -> (u: Double, v: Double)? {
        let y = lat / step, x = lon / step
        let i = Int(floor(y)), j = Int(floor(x))
        let fy = y - Double(i), fx = x - Double(j)
        var u = 0.0, v = 0.0
        for (di, dj, w) in [(0, 0, (1 - fy) * (1 - fx)), (0, 1, (1 - fy) * fx), (1, 0, fy * (1 - fx)), (1, 1, fy * fx)] {
            guard let s = samples[WindGrid.key(i + di, j + dj, step)], let speed = s.speedKt[level], let from = s.fromDeg[level] else { return nil }
            let p = WindGrid.vector(speed: speed, fromDeg: from)
            u += p.u * w
            v += p.v * w
        }
        return (u, v)
    }

    /// Grid spacing for a view: about ten cells across, coarser if that would need more than `maxPoints` points.
    static func step(latSpan: Double, lonSpan: Double) -> Double {
        let want = max(latSpan, lonSpan) / 10
        return steps.first { $0 >= want && (latSpan / $0 + 3) * (min(lonSpan, 360) / $0 + 3) <= Double(maxPoints) } ?? steps.last!
    }

    /// Grid indices (lat, lon) covering the box plus one cell all round.
    static func points(south: Double, west: Double, north: Double, east: Double, step: Double) -> [(Int, Int)] {
        // Clamped to the poles: Open-Meteo answers 400 for the whole request if one latitude is past ±90.
        let pole = Int(90 / step)
        let i0 = max(Int(floor(max(south, -89) / step)) - 1, -pole), i1 = min(Int(ceil(min(north, 89) / step)) + 1, pole)
        let j0 = Int(floor(west / step)) - 1, j1 = Int(ceil((east < west ? east + 360 : east) / step)) + 1
        var seen = Set<Int64>()
        return (i0...i1).flatMap { i in (j0...j1).map { (i, $0) } }.filter { seen.insert(key($0.0, $0.1, step)).inserted }
    }

    /// Index to coordinates, longitude wrapped into -180..180.
    static func latLon(_ i: Int, _ j: Int, _ step: Double) -> (lat: Double, lon: Double) {
        (Double(i) * step, ((Double(j) * step + 180).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) - 180)
    }

    /// One key per point on the globe, so 190° and -170° are the same point.
    static func key(_ i: Int, _ j: Int, _ step: Double) -> Int64 {
        let perTurn = Int((360 / step).rounded())
        let jj = ((j + perTurn / 2) % perTurn + perTurn) % perTurn
        return Int64(i) << 32 | Int64(jj)
    }

    /// Speed and the direction it blows *from* (meteorological) to an (east, north) vector of where the air goes.
    static func vector(speed: Double, fromDeg: Double) -> (u: Double, v: Double) {
        let r = fromDeg * .pi / 180
        return (-speed * sin(r), -speed * cos(r))
    }
}
