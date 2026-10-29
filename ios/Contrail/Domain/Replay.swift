import Foundation

/// Where a plane was at a moment of a recorded flight, for replaying it: between the two fixes either side, position
/// and figures are interpolated, and the heading is the direction it was moving.
enum Replay {
    /// `gap`: no fix within `gapMs` either side, so the position is a straight-line guess across a coverage hole.
    struct Frame: Equatable, Sendable {
        var lat: Double
        var lon: Double
        var alt: Int?
        var onGround: Bool
        var gs: Double?
        var heading: Double
        var time: Millis
        var gap: Bool
    }

    static let gapMs: Millis = 5 * 60_000

    /// `path` oldest first; `t` is clamped to its span. Nil for fewer than two points.
    static func at(_ path: [TrailPoint], _ t: Millis) -> Frame? {
        guard path.count >= 2 else { return nil }
        let time = min(max(t, path[0].time), path[path.count - 1].time)
        // First fix after the moment (or the last), so path[i - 1] is at or before it.
        var lo = 0, hi = path.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if path[mid].time <= time { lo = mid + 1 } else { hi = mid }
        }
        let i = min(max(lo, 1), path.count - 1)
        let a = path[i - 1], b = path[i]
        let span = Double(b.time - a.time)
        let f: Double = span > 0 ? min(max(Double(time - a.time) / span, 0), 1) : 0
        // Across the dateline, the short way round.
        let dLon = (b.lon - a.lon + 540).truncatingRemainder(dividingBy: 360) - 180
        let lon = (a.lon + dLon * f + 540).truncatingRemainder(dividingBy: 360) - 180
        let alt: Int? = if let x = a.alt, let y = b.alt { Int(Double(x) + Double(y - x) * f) } else { f < 0.5 ? a.alt : b.alt }
        let gs: Double? = if let x = a.gs, let y = b.gs { x + (y - x) * f } else { a.gs ?? b.gs }
        return Frame(lat: a.lat + (b.lat - a.lat) * f, lon: lon, alt: alt, onGround: f < 0.5 ? a.onGround : b.onGround, gs: gs,
                     heading: heading(path, i), time: time, gap: b.time - a.time > gapMs)
    }

    /// Direction of travel into path[i]: from the nearest earlier fix at least 0.2 nm back (or forward, at the start),
    /// so a taxiing or GPS-jittering plane doesn't spin.
    private static func heading(_ path: [TrailPoint], _ i: Int) -> Double {
        let b = path[i]
        for j in stride(from: i - 1, through: max(0, i - 20), by: -1) where Geo.distanceNm(path[j].coord, b.coord) >= 0.2 {
            return Geo.bearing(path[j].coord, b.coord)
        }
        if i + 1 < path.count {
            for j in (i + 1)...min(path.count - 1, i + 20) where Geo.distanceNm(b.coord, path[j].coord) >= 0.2 {
                return Geo.bearing(b.coord, path[j].coord)
            }
        }
        return Geo.bearing(path[i - 1].coord, b.coord)
    }
}
