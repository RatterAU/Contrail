import Foundation

/// Keyless "times": everything here is derived from the live position and the route's airports. There is no
/// schedule, so nothing claims to be scheduled; estimates are flagged as such.
struct FlightProgress: Hashable, Sendable {
    /// False when the plane's position doesn't fit the route; the route may be stale or wrong.
    var verified: Bool
    var totalNm: Double
    var flownNm: Double
    var remainingNm: Double
    var fraction: Double
    var departedAt: Millis?
    var departedEstimated: Bool
    var eta: Millis?
    var landedAt: Millis?

    /// Ground speed at cruise: the fastest seen above FL200 if we watched it cruise, else typical for its size class.
    /// Never the current speed alone: on approach it's ~150 kt, which would make a 3 h flight look like 9 h.
    static func cruiseSpeedKt(_ track: Track) -> Double {
        if let fastest = track.trail.filter({ ($0.alt ?? 0) > 20_000 }).compactMap(\.gs).max(), fastest > 150 { return fastest }
        let a = track.aircraft
        let typical: Double = switch a.category {
        case "A1": 140 // light
        case "A2": 280 // small: turboprops and small regional jets
        case "A3", "A4", "A5", "A6": 430 // airliners
        default: 400
        }
        // Over 150 kt only: 0 here would mean an infinite flight, and converting that to Millis traps.
        if (a.altBaro ?? 0) > 20_000, let gs = a.groundSpeed, gs > 150 { return gs }
        return max(typical, a.groundSpeed ?? 0)
    }

    /// Time to touchdown. The plane holds its ground speed (which already includes the wind) until ~100 nm out,
    /// then slows through descent and approach: about 300 kt average from cruise, less if already slow. Plus 2 min
    /// for vectoring and the final turn.
    static func flyingTimeMs(_ toDestNm: Double, _ gs: Double) -> Millis {
        let final = min(toDestNm, 100)
        let finalAvg = (min(gs, 460) + 140) / 2
        let hours = (toDestNm - final) / gs + final / finalAvg
        return Millis(hours * 3_600_000) + 2 * 60_000
    }

    /// The route read off where the plane has been, for when the published one is wrong (airlines reuse
    /// callsigns): a path that starts low near one big airport, and the plane now on the ground at another or on
    /// final approach to it.
    /// The route to show: the first of `legs` (adsb.lol, one per leg of a multi-stop flight) or the published one that
    /// fits the position, else one read off the flown path, else the published one (shown as not matching).
    static func shownRoute(_ t: Track, listed: Route?, legs: [Route], now: Millis) -> Route? {
        if let r = (legs + [listed].compactMap { $0 }).first(where: { compute(t, $0, now: now).verified }) { return r }
        return StaticData.now().flatMap { flownRoute(t, listed: listed, data: $0) } ?? listed ?? (legs.count == 1 ? legs[0] : nil)
    }

    static func flownRoute(_ t: Track, listed: Route?, data: StaticData) -> Route? {
        let a = t.aircraft
        guard let start = t.trail.first, start.onGround || (start.alt ?? .max) < 4000,
              let origin = data.majorAirport(near: start.lat, start.lon, withinNm: 10),
              let dest = a.onGround ? data.majorAirport(near: a.lat, a.lon, withinNm: 5) : approaching(a, data),
              origin != dest else { return nil }
        return Route(callsign: a.callsign ?? "", flightNumber: listed?.flightNumber, airline: listed?.airline, origin: origin, destination: dest)
    }

    /// The big airport a plane is on final approach to: low, slowing, close, and pointed at it.
    static func approaching(_ a: Aircraft, _ data: StaticData) -> Airport? {
        guard let alt = a.altBaro, alt < 8000, (a.groundSpeed ?? 0) < 250, alt < 3000 || (a.verticalRate ?? 0) < -300,
              let ap = data.majorAirport(near: a.lat, a.lon, withinNm: 25), let trk = a.track else { return nil }
        let close = Geo.distanceNm(a.lat, a.lon, ap.lat, ap.lon) < 3
        return close || Geo.angleDiff(trk, Geo.bearing(a.lat, a.lon, ap.lat, ap.lon)) <= 30 ? ap : nil
    }

    static func compute(_ track: Track, _ route: Route, now: Millis) -> FlightProgress {
        let a = track.aircraft
        let o = route.origin, d = route.destination
        // The true dead-reckoned position, not the eased on-screen one.
        let p = Track.extrapolate(a, now)
        let total = Geo.distanceNm(o.lat, o.lon, d.lat, d.lon)
        let fromOrigin = Geo.distanceNm(o.lat, o.lon, p.lat, p.lon)
        let toDest = Geo.distanceNm(p.lat, p.lon, d.lat, d.lon)

        let verified: Bool
        if total < 5 {
            verified = false
        } else if a.onGround {
            verified = fromOrigin < 5 || toDest < 5
        } else {
            let crossTrack = abs(Geo.crossTrackNm(o.lat, o.lon, d.lat, d.lon, p.lat, p.lon))
            // Long flights follow airways and winds hundreds of nm off the great circle (Sydney-Abu Dhabi passes
            // Adelaide, 280 nm off), so the corridor grows with the distance.
            let onCorridor = crossTrack <= max(60, total * 0.15)
            let betweenEnds = fromOrigin + toDest <= total * 1.3 + 30
            let vr = a.verticalRate ?? 0
            let towardsDest = a.track.map { Geo.angleDiff($0, Geo.bearing(p.lat, p.lon, d.lat, d.lon)) <= 75 } ?? false
            let towardsOrigin = a.track.map { Geo.angleDiff($0, Geo.bearing(p.lat, p.lon, o.lat, o.lon)) <= 75 } ?? false
            // Departure and arrival procedures point planes anywhere near the airports, so there only the opposite of
            // the route fails: climbing away from the destination (it just left that stop) or descending into the
            // origin (it's arriving there). Further out it must be pointing at the destination.
            let headingOk = if toDest < 60 && fromOrigin < 60 { true }
                else if toDest < 60 { vr <= 300 || towardsDest }
                else if fromOrigin < 60 { vr >= -300 || !towardsOrigin }
                else { towardsDest }
            verified = onCorridor && betweenEnds && headingOk
        }

        let landedHere = a.onGround && toDest < 5
        // Parked at the origin, a takeoff on record belongs to the flight before.
        let departed = a.onGround && fromOrigin < 5 ? nil : track.takeoffAt
        let cruise = cruiseSpeedKt(track)
        // Climb-out mirrors the descent: the same speed profile, flown in reverse.
        let estDeparture = departed == nil && verified && !a.onGround ? now - flyingTimeMs(fromOrigin, cruise) : nil

        var eta: Millis?
        if verified, !a.onGround, let gs = a.groundSpeed, gs > 60 {
            // Still climbing far from the destination: it will speed up, so plan on cruise speed.
            eta = now + flyingTimeMs(toDest, (a.altBaro ?? 0) < 20_000 && toDest > 150 ? max(gs, cruise) : gs)
        }

        return FlightProgress(
            verified: verified,
            totalNm: total,
            flownNm: fromOrigin,
            remainingNm: toDest,
            fraction: landedHere ? 1 : min(max(fromOrigin / (fromOrigin + toDest), 0), 1),
            departedAt: departed ?? estDeparture,
            departedEstimated: departed == nil,
            eta: eta,
            landedAt: landedHere ? track.landedAt : nil
        )
    }
}
