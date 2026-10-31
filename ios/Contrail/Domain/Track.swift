import Foundation

struct TrailPoint: Hashable, Sendable {
    var lat: Double
    var lon: Double
    var alt: Int?
    var onGround: Bool
    var gs: Double?
    var time: Millis

    var coord: Coord { Coord(lat: lat, lon: lon) }
}

/// Where the plane was drawn when a fresh report arrived; the icon eases from here instead of jumping.
struct Blend: Hashable, Sendable {
    var lat: Double
    var lon: Double
    var start: Millis
}

extension Aircraft {
    var trailPoint: TrailPoint { TrailPoint(lat: lat, lon: lon, alt: altBaro, onGround: onGround, gs: groundSpeed, time: positionTime) }
}

/// An aircraft plus what this app has observed about it over time. The feeds' trace files are bot-protected, so
/// the trail and takeoff/landing times are recorded locally (and topped up from OpenSky, see FlightHistory).
struct Track: Hashable, Sendable {
    var aircraft: Aircraft
    var trail: [TrailPoint]
    var firstSeen: Millis
    var takeoffAt: Millis?
    var landedAt: Millis?
    var blend: Blend?

    static let blendMs: Millis = 900
    static let maxTrail = 3000
    /// Don't extrapolate further than this past the last position fix.
    static let maxExtrapolateMs: Millis = 30_000

    var hex: String { aircraft.hex }

    func staleness(_ now: Millis) -> Millis { now - aircraft.seenTime }

    static func start(_ a: Aircraft, now: Millis) -> Track {
        Track(aircraft: a, trail: [a.trailPoint], firstSeen: now)
    }

    /// Dead-reckoned from ground speed and track, capped at `maxExtrapolateMs`. Use this, not `displayPosition`,
    /// for anything that calculates.
    static func extrapolate(_ a: Aircraft, _ now: Millis) -> Coord {
        guard let gs = a.groundSpeed, let track = a.track, gs >= 3 else { return a.coord }
        let dt = min(max(now - a.positionTime, 0), maxExtrapolateMs)
        return Geo.destination(a.lat, a.lon, track, gs * Double(dt) / 3_600_000)
    }

    /// Dead-reckoned position at `now`, eased from `blend` for the first `blendMs`.
    func displayPosition(_ now: Millis) -> Coord {
        let p = Track.extrapolate(aircraft, now)
        guard let b = blend else { return p }
        let f = Double(now - b.start) / Double(Track.blendMs)
        if f >= 1 || f < 0 || abs(b.lon - p.lon) > 180 { return p }
        let e = 1 - (1 - f) * (1 - f) * (1 - f) // ease-out cubic
        return Coord(lat: b.lat + (p.lat - b.lat) * e, lon: b.lon + (p.lon - b.lon) * e)
    }

    /// Merge a newer report into this track. Older or duplicate reports are ignored.
    func updated(with next: Aircraft, now: Millis) -> Track {
        // A rough position is the receiver's, up to hundreds of nm off: it never replaces a real fix.
        if next.source == .rough && aircraft.source != .rough { return self }
        if next.positionTime <= aircraft.positionTime {
            guard next.seenTime > aircraft.seenTime else { return self }
            var t = self
            t.aircraft.seenTime = next.seenTime
            return t
        }
        let d = displayPosition(now)
        let moved: Bool = {
            guard let last = trail.last else { return true }
            return Geo.distanceNm(last.lat, last.lon, next.lat, next.lon) > 0.05 || last.onGround != next.onGround
                || next.positionTime - last.time > 30_000
        }()
        var t = self
        t.aircraft = next
        // adsb.fi omits registry fields that adsb.lol sends; don't let alternating feeds blank them.
        let old = aircraft
        t.aircraft.registration = next.registration ?? old.registration
        t.aircraft.typeCode = next.typeCode ?? old.typeCode
        t.aircraft.description = next.description ?? old.description
        t.aircraft.operator = next.operator ?? old.operator
        t.aircraft.year = next.year ?? old.year
        t.aircraft.category = next.category ?? old.category
        t.aircraft.callsign = next.callsign ?? old.callsign
        if moved {
            t.trail.append(next.trailPoint)
            if t.trail.count > Track.maxTrail { t.trail.removeFirst(t.trail.count - Track.maxTrail) }
        }
        if aircraft.onGround && !next.onGround { t.takeoffAt = next.positionTime }
        if !aircraft.onGround && next.onGround { t.landedAt = next.positionTime } else if !next.onGround { t.landedAt = nil }
        t.blend = Blend(lat: d.lat, lon: d.lon, start: now)
        return t
    }

    /// Prepend the path flown before the app saw the plane. If that path starts on the ground, its first airborne
    /// point is the real takeoff time.
    func withHistory(_ history: [TrailPoint]) -> Track {
        let first = trail.first?.time ?? .max
        let older = history.filter { $0.time < first - 1000 }
        guard let start = older.first else { return self }
        let fromGround = start.onGround || (start.alt ?? .max) < 1500
        let takeoff = fromGround ? older.first(where: { !$0.onGround && ($0.alt ?? 0) > 200 })?.time : nil
        var t = self
        t.trail = Array((older + trail).suffix(Track.maxTrail))
        t.takeoffAt = takeoffAt ?? takeoff
        // Landed before we started watching: the flown path ends at touchdown.
        if aircraft.onGround, t.landedAt == nil, t.takeoffAt != nil { t.landedAt = older.last?.time }
        return t
    }
}
