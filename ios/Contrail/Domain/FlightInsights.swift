import Foundation

enum Phase: String, Sendable {
    case parked, taxiing, takeoff, climbing, cruising, level, descending, approach, landed

    var label: String {
        switch self {
        case .parked: "Parked"
        case .taxiing: "Taxiing"
        case .takeoff: "Taking off"
        case .climbing: "Climbing"
        case .cruising: "Cruising"
        case .level: "Level flight"
        case .descending: "Descending"
        case .approach: "On approach"
        case .landed: "Landed"
        }
    }
}

/// A short, plain-language observation for the aircraft sheet.
struct Insight: Hashable, Sendable {
    enum Kind: Sendable { case phase, target, wind, temperature, place, emergency }
    var kind: Kind
    var text: String
}

/// Turns the raw feed fields into what a person actually wants to know. Pure; unit-tested.
enum FlightInsights {
    private static let climbFpm = 400.0

    static func phase(_ t: Track, _ p: FlightProgress?, now: Millis) -> Phase {
        let a = t.aircraft
        let gs = a.groundSpeed ?? 0
        if a.onGround {
            if let landed = t.landedAt, now - landed < 15 * 60_000 { return gs > 40 ? .landed : gs > 3 ? .taxiing : .landed }
            return gs > 40 ? .takeoff : gs > 3 ? .taxiing : .parked
        }
        let vr = a.verticalRate ?? 0
        let alt = a.altBaro ?? 0
        if vr > climbFpm { return .climbing }
        if vr < -climbFpm {
            return alt < 3000 || (p?.verified == true && p!.remainingNm < 30 && alt < 8000) ? .approach : .descending
        }
        return alt >= 18_000 ? .cruising : .level
    }

    /// Headwind (+) / tailwind (−) and crosswind (+ from the right) components, in knots.
    static func windComponents(from windFrom: Double, knots: Double, track: Double) -> (head: Double, cross: Double) {
        let rel = Geo.rad(windFrom - track)
        return (knots * cos(rel), knots * sin(rel))
    }

    /// One line describing what the flight is doing, naming the airports when the route checks out.
    static func phaseLine(_ phase: Phase, _ a: Aircraft, _ route: Route?, _ p: FlightProgress?, _ fmt: Fmt) -> String {
        let r = p?.verified == true ? route : nil
        switch phase {
        case .climbing: return r.map { "Climbing out of \($0.origin.shortName)" } ?? "Climbing through \(fmt.altitude(a))"
        case .cruising: return "Cruising at \(fmt.altitude(a))"
        case .level: return "Level at \(fmt.altitude(a))"
        case .descending: return r.map { "Descending towards \($0.destination.shortName)" } ?? "Descending through \(fmt.altitude(a))"
        case .approach: return r.map { "On final approach into \($0.destination.shortName)" } ?? "Low and descending, likely landing"
        case .takeoff: return "Accelerating on the runway"
        case .landed: return r.map { "Landed in \($0.destination.shortName)" } ?? "Just landed"
        case .taxiing: return "Taxiing"
        case .parked: return "Parked"
        }
    }

    static func insights(_ t: Track, _ route: Route?, _ p: FlightProgress?, near: Airport?, now: Millis, fmt: Fmt = Fmt()) -> [Insight] {
        let a = t.aircraft
        var out: [Insight] = []
        if a.isEmergency {
            let what = a.squawk.flatMap(Squawks.meaning) ?? a.emergency.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "Emergency"
            out.append(Insight(kind: .emergency, text: a.squawk.map { "Squawking \($0): \(what)" } ?? what))
        }
        let ph = phase(t, p, now: now)
        out.append(Insight(kind: .phase, text: phaseLine(ph, a, route, p, fmt)))
        if !a.onGround {
            // Only when the plane is actually heading there: on approach the selected altitude is the missed-approach
            // altitude, above the plane, and "climbing to 3,000 ft" would contradict "landing".
            let target = (a.navAltMcp ?? a.navAltFms).map { ($0 + 50) / 100 * 100 } // feed values come in 4 ft steps (3008)
            let vr = a.verticalRate ?? 0
            if let alt = a.altBaro, let target, target > 0, abs(target - alt) > 400, ph != .approach {
                if target > alt && vr > climbFpm { out.append(Insight(kind: .target, text: "Climbing to \(fmt.feet(target)) on autopilot")) }
                if target < alt && vr < -climbFpm { out.append(Insight(kind: .target, text: "Descending to \(fmt.feet(target)) on autopilot")) }
            }
            if let wd = a.windDir, let ws = a.windSpeed, let trk = a.track, ws >= 5 {
                let (head, cross) = windComponents(from: wd, knots: ws, track: trk)
                let text = head <= -8 ? "\(fmt.knots(-head)) tailwind, so it's moving faster over the ground"
                    : head >= 8 ? "\(fmt.knots(head)) headwind slowing it down"
                    : "\(fmt.knots(abs(cross))) crosswind from the \(cross > 0 ? "right" : "left")"
                out.append(Insight(kind: .wind, text: text))
            }
            if let oat = a.oat { out.append(Insight(kind: .temperature, text: "\(fmt.celsius(oat)) outside the cabin")) }
        }
        if let ap = near {
            let pos = Track.extrapolate(a, now)
            let nm = Geo.distanceNm(ap.lat, ap.lon, pos.lat, pos.lon)
            out.append(Insight(kind: .place, text: nm < 3
                ? "\(a.onGround ? "At" : "Over") \(ap.name)"
                : "\(fmt.nm(nm)) \(Geo.compass(Geo.bearing(ap.lat, ap.lon, pos.lat, pos.lon))) of \(ap.shortName)"))
        }
        return out
    }

    /// Whole years since the build year the feed reports, or nil if it's not a plausible year.
    static func ageYears(_ year: String?, nowYear: Int) -> Int? {
        guard let y = year.flatMap({ Int($0.prefix(4)) }), (1900...nowYear).contains(y) else { return nil }
        return nowYear - y
    }
}
