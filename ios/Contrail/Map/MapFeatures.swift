import Foundation

/// A lat/lon box for culling. `contains` is inclusive.
struct Area: Hashable, Sendable {
    var south: Double, west: Double, north: Double, east: Double

    func contains(_ lat: Double, _ lon: Double) -> Bool {
        guard lat >= south, lat <= north else { return false }
        // A view across the antimeridian runs past ±180, where a plane at -175 also sits at 185.
        return (lon >= west && lon <= east) || (lon + 360 >= west && lon + 360 <= east) || (lon - 360 >= west && lon - 360 <= east)
    }
}

/// One line of a single colour: consecutive trail segments in the same altitude band.
struct TrailRun: Hashable, Sendable {
    var band: Int
    var points: [Coord]
}

enum RouteKind: Sendable { case behind, ahead, projected }

/// Pure map maths, kept apart from MapKit so it's unit-tested.
enum MapFeatures {
    /// Zoom in MapLibre's convention (world 512 pt wide at zoom 0), so thresholds match the Android app.
    static func zoom(longitudeDelta: Double, widthPoints: Double) -> Double {
        log2(360 * widthPoints / (max(longitudeDelta, 1e-9) * 512))
    }

    /// How often planes get a new target position: every 100 ms zoomed in (they visibly move), up to 2 s zoomed out.
    static func frameIntervalMs(zoom: Double, lat: Double) -> Millis {
        let metresPerPt = 78_271.5 * cos(Geo.rad(lat)) / pow(2, zoom)
        return min(max(Millis(metresPerPt / 250 * 1000), 100), 2000)
    }

    /// Runs of same-coloured segments: a cruising plane's trail is a handful of lines, not thousands. A run starts
    /// where the previous one ended so the line is continuous, except across coverage gaps (~50 nm), which aren't
    /// drawn as a false straight line. The last segment reaches `current`, the plane's drawn position.
    static func trailRuns(_ pts: [TrailPoint], current: Coord?) -> [TrailRun] {
        guard let first = pts.first else { return [] }
        var out: [TrailRun] = []
        var run = [first.coord]
        var runBand = Int.min
        func flush() { if run.count >= 2 { out.append(TrailRun(band: runBand, points: run)) } }
        for i in pts.indices {
            let a = pts[i]
            guard let b = i + 1 < pts.count ? pts[i + 1].coord : current else { break }
            if abs(b.lat - a.lat) > 0.8 || abs(b.lon - a.lon) * cos(Geo.rad(a.lat)) > 0.8 {
                flush()
                run = [b]
                runBand = .min
                continue
            }
            let band = AltitudeColors.band(a)
            if band != runBand && run.count >= 2 {
                flush()
                run = [run.last!]
            }
            runBand = band
            run.append(b)
        }
        flush()
        return out
    }

    /// The dashed route: from the origin to where the trail starts (if the plane was first seen far from it), and
    /// ahead to the destination, or, with no route, 8 minutes of dead reckoning.
    static func routeLines(_ track: Track, origin: Airport?, destination: Airport?, now: Millis) -> [(RouteKind, [Coord])] {
        let a = track.aircraft
        var out: [(RouteKind, [Coord])] = []
        if let o = origin, let first = track.trail.first, Geo.distanceNm(o.coord, first.coord) > 15 {
            out.append((.behind, Geo.greatCircle(o.coord, first.coord)))
        }
        guard !a.onGround else { return out }
        let p = track.displayPosition(now)
        if let d = destination {
            out.append((.ahead, Geo.pathAhead(from: p, heading: a.track, to: d.coord)))
        } else if let gs = a.groundSpeed, gs > 60, let trk = a.track {
            out.append((.projected, Geo.projected(from: p, heading: trk, gs: gs, turnRate: a.trackRate)))
        }
        return out
    }
}
