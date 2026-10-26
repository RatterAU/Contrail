import Foundation

/// Epoch milliseconds. Domain times are plain integers so the maths matches the feeds (and the Android tests) exactly.
typealias Millis = Int64

extension Millis {
    static var now: Millis { Millis(Date().timeIntervalSince1970 * 1000) }
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000) }
}

struct Coord: Hashable, Sendable {
    var lat: Double
    var lon: Double
}

/// Spherical-earth helpers. Distances in nautical miles, angles in degrees.
enum Geo {
    static let earthNm = 3440.065

    static func rad(_ d: Double) -> Double { d * .pi / 180 }
    static func deg(_ r: Double) -> Double { r * 180 / .pi }

    static func distanceNm(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let dLat = rad(lat2 - lat1), dLon = rad(lon2 - lon1)
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthNm * asin(sqrt(min(max(a, 0), 1)))
    }

    static func distanceNm(_ a: Coord, _ b: Coord) -> Double { distanceNm(a.lat, a.lon, b.lat, b.lon) }

    static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = rad(lat1), p2 = rad(lat2), dl = rad(lon2 - lon1)
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (deg(atan2(y, x)) + 360).truncatingRemainder(dividingBy: 360)
    }

    static func bearing(_ a: Coord, _ b: Coord) -> Double { bearing(a.lat, a.lon, b.lat, b.lon) }

    /// Point reached from (lat, lon) travelling `distNm` on initial `bearingDeg`.
    static func destination(_ lat: Double, _ lon: Double, _ bearingDeg: Double, _ distNm: Double) -> Coord {
        let d = distNm / earthNm, b = rad(bearingDeg), p1 = rad(lat), l1 = rad(lon)
        let p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        let l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Coord(lat: deg(p2), lon: (deg(l2) + 540).truncatingRemainder(dividingBy: 360) - 180)
    }

    /// Signed distance of point P from the great circle A→B (nm).
    static func crossTrackNm(_ latA: Double, _ lonA: Double, _ latB: Double, _ lonB: Double, _ latP: Double, _ lonP: Double) -> Double {
        let d13 = distanceNm(latA, lonA, latP, lonP) / earthNm
        let t13 = rad(bearing(latA, lonA, latP, lonP))
        let t12 = rad(bearing(latA, lonA, latB, lonB))
        return asin(sin(d13) * sin(t13 - t12)) * earthNm
    }

    /// Smallest absolute difference between two headings.
    static func angleDiff(_ a: Double, _ b: Double) -> Double {
        let d = ((a - b).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return d > 180 ? 360 - d : d
    }

    static func compass(_ deg: Double) -> String {
        let names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
        return names[Int(((deg.truncatingRemainder(dividingBy: 360)) + 360 + 22.5) / 45) % 8]
    }

    /// Great circle as ~20 nm segments (max 64), including both ends.
    static func greatCircle(_ from: Coord, _ to: Coord) -> [Coord] {
        var p = from
        let steps = min(max(Int(distanceNm(from, to) / 20), 1), 64)
        var pts = [p]
        pts.reserveCapacity(steps + 1)
        for i in 1...steps {
            let remaining = distanceNm(p, to)
            p = destination(p.lat, p.lon, bearing(p, to), remaining / Double(steps - i + 1))
            pts.append(p)
        }
        return pts
    }

    /// Turns from `heading` towards the destination at about a standard-rate turn's radius (5° per nm), then follows
    /// the great circle, so a plane pointing away turns round instead of the line jumping sideways.
    static func pathAhead(from start: Coord, heading: Double?, to dest: Coord) -> [Coord] {
        let direct = greatCircle(start, dest)
        // Near the airport planes fly downwind legs and vectors nobody can predict, and the turn radius would
        // orbit the field; a straight line is the honest picture there.
        guard var hdg = heading, distanceNm(start, dest) >= 40 else { return direct }
        var p = start
        var pts = [p]
        for _ in 0..<60 {
            let diff = (bearing(p, dest) - hdg + 540).truncatingRemainder(dividingBy: 360) - 180
            if abs(diff) < 3 { return pts + greatCircle(p, dest).dropFirst() }
            hdg += min(max(diff, -5), 5)
            p = destination(p.lat, p.lon, hdg, 1)
            pts.append(p)
        }
        return direct // never settled on course: don't draw a loop
    }

    /// Dead-reckoned `minutes` ahead, following the current turn (capped at standard rate, 3°/s).
    static func projected(from start: Coord, heading: Double, gs: Double, turnRate: Double?, minutes: Int = 8) -> [Coord] {
        var p = start, hdg = heading
        let r = min(max(turnRate ?? 0, -3), 3)
        let rate = abs(r) > 0.1 ? r : 0
        let stepS = 15.0
        var pts = [p]
        for i in 0..<Int(Double(minutes * 60) / stepS) {
            // A turn doesn't go on forever: stop turning after ~90°, the usual size of a course change.
            if abs(rate) * stepS * Double(i) < 90 { hdg += rate * stepS }
            p = destination(p.lat, p.lon, hdg, gs * stepS / 3600)
            pts.append(p)
        }
        return pts
    }
}
