import Foundation

enum BoardStatus: Sendable { case enRoute, approach, landed, taxiing, onGround, departed, justDeparted }

struct BoardRow: Hashable, Sendable, Identifiable {
    var track: Track
    var route: Route?
    var status: BoardStatus
    /// ETA for arrivals, takeoff time for departures, landing time for landed.
    var time: Millis?
    var timeEstimated: Bool
    var distanceNm: Double

    var id: String { track.hex }
}

struct Boards: Hashable, Sendable {
    var arrivals: [BoardRow]
    var departures: [BoardRow]
}

/// Live arrivals/departures built from aircraft in the air or on the ground nearby. Without a schedule this only
/// covers flights within feed range (~250 nm), which the UI states.
enum AirportBoards {
    private static let atAirportNm = 4.0
    private static let circuitNm = 15.0

    static func build(_ airport: Airport, _ tracks: some Collection<Track>, routes: [String: Route?], now: Millis) -> Boards {
        var arrivals: [BoardRow] = [], departures: [BoardRow] = []
        for t in tracks {
            let a = t.aircraft
            let dist = Geo.distanceNm(airport.lat, airport.lon, a.lat, a.lon)
            let route = a.callsign.flatMap { routes[$0] ?? nil }
            let toHere = route?.destination.matches(airport) == true
            let fromHere = route?.origin.matches(airport) == true
            let p = route.map { FlightProgress.compute(t, $0, now: now) }
            let vr = a.verticalRate ?? 0
            let low = (a.altBaro ?? .max) < 6000

            if toHere && a.onGround && dist < atAirportNm {
                arrivals.append(BoardRow(track: t, route: route, status: .landed, time: t.landedAt, timeEstimated: false, distanceNm: dist))
            } else if toHere && !a.onGround && p?.verified == true {
                arrivals.append(BoardRow(track: t, route: route, status: dist < circuitNm && low ? .approach : .enRoute, time: p?.eta, timeEstimated: true, distanceNm: dist))
            } else if fromHere && a.onGround && dist < atAirportNm {
                departures.append(BoardRow(track: t, route: route, status: (a.groundSpeed ?? 0) > 3 ? .taxiing : .onGround, time: nil, timeEstimated: false, distanceNm: dist))
            } else if fromHere && !a.onGround && p?.verified == true {
                departures.append(BoardRow(track: t, route: route, status: .departed, time: p?.departedAt, timeEstimated: p?.departedEstimated ?? false, distanceNm: dist))
            } else if route == nil || p?.verified == false, !a.onGround && dist < circuitNm && low {
                // No usable route: infer from behaviour close to the field.
                if vr < -300 { arrivals.append(BoardRow(track: t, route: nil, status: .approach, time: nil, timeEstimated: false, distanceNm: dist)) }
                if vr > 300 { departures.append(BoardRow(track: t, route: nil, status: .justDeparted, time: t.takeoffAt, timeEstimated: false, distanceNm: dist)) }
            }
        }
        arrivals.sort { l, r in
            let lk = (l.status == .landed ? 0 : 1, l.time ?? .max, l.distanceNm)
            let rk = (r.status == .landed ? 0 : 1, r.time ?? .max, r.distanceNm)
            return lk < rk
        }
        departures.sort { l, r in
            let onGround: (BoardRow) -> Int = { $0.status == .taxiing || $0.status == .onGround ? 0 : 1 }
            return (onGround(l), -(l.time ?? 0)) < (onGround(r), -(r.time ?? 0))
        }
        return Boards(arrivals: arrivals, departures: departures)
    }

    struct ActiveRunway: Hashable, Sendable {
        var ident: String
        var fromTraffic: Bool
    }

    /// Runway in use: the end most low, aligned aircraft are using. Falls back to the end facing most directly into the wind.
    static func activeRunway(_ airport: Airport, _ runways: [Runway], _ tracks: some Collection<Track>, windDir: Double?) -> ActiveRunway? {
        let ends: [(String, Double)] = runways.flatMap { r in
            [r.leHeading.map { (r.leIdent, $0) }, r.heHeading.map { (r.heIdent, $0) }].compactMap { $0 }
        }
        guard !ends.isEmpty else { return nil }
        var votes: [String: Int] = [:]
        for t in tracks {
            let a = t.aircraft
            guard let trk = a.track, !a.onGround, (a.altBaro ?? .max) <= 3000,
                  Geo.distanceNm(airport.lat, airport.lon, a.lat, a.lon) <= 8 else { continue }
            for end in ends where Geo.angleDiff(trk, end.1) < 20 { votes[end.0, default: 0] += 1 }
        }
        if let best = votes.max(by: { $0.value < $1.value }) { return ActiveRunway(ident: best.key, fromTraffic: true) }
        guard let windDir else { return nil }
        return ends.min(by: { Geo.angleDiff($0.1, windDir) < Geo.angleDiff($1.1, windDir) }).map { ActiveRunway(ident: $0.0, fromTraffic: false) }
    }
}
