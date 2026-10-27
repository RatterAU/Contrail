import Foundation
import Testing
@testable import Contrail

let adl = Airport(icao: "YPAD", iata: "ADL", name: "Adelaide", city: "Adelaide", country: "AU", lat: -34.945, lon: 138.531, elevationFt: 20)
let syd = Airport(icao: "YSSY", iata: "SYD", name: "Sydney", city: "Sydney", country: "AU", lat: -33.946, lon: 151.177, elevationFt: 21)
let mel = Airport(icao: "YMML", iata: "MEL", name: "Melbourne", city: "Melbourne", country: "AU", lat: -37.673, lon: 144.843, elevationFt: 434)

func route(_ cs: String, _ fn: String, _ o: Airport, _ d: Airport) -> Route {
    Route(callsign: cs, flightNumber: fn, airline: nil, origin: o, destination: d)
}

extension Aircraft {
    func with(_ f: (inout Aircraft) -> Void) -> Aircraft {
        var a = self
        f(&a)
        return a
    }
}

struct FlightProgressTests {
    let r = route("QFA736", "QF736", adl, syd)
    let base = one(#"{"hex":"7c1234","flight":"QFA736","alt_baro":36000,"gs":450,"track":80,"lat":-34.5,"lon":144.0,"seen":0,"seen_pos":0}"#)

    func trackAt(_ lat: Double, _ lon: Double, trk: Double = 0, ground: Bool = false, gs: Double = 450) -> Track {
        let heading = trk == 0 ? Geo.bearing(lat, lon, syd.lat, syd.lon) : trk
        return Track.start(base.with {
            $0.lat = lat; $0.lon = lon; $0.track = heading; $0.onGround = ground; $0.groundSpeed = gs; $0.positionTime = 0; $0.seenTime = 0
        }, now: 0)
    }

    @Test func midRouteIsVerifiedWithEta() {
        let c = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, syd.lat, syd.lon), 300)
        let p = FlightProgress.compute(trackAt(c.lat, c.lon), r, now: 0)
        #expect(p.verified)
        #expect(abs(p.flownNm - 300) < 1)
        #expect(abs(p.fraction - 0.47) < 0.02)
        // ~333 nm at 450 kt is ~44 min, plus a 6-8 min approach buffer.
        let etaMin = Double(p.eta!) / 60_000
        #expect((48.0...54.0).contains(etaMin), "eta \(etaMin)")
        #expect(p.departedEstimated)
        #expect(p.departedAt != nil)
    }

    @Test func departureEstimateHoldsWhenThePlaneSlowsOnApproach() {
        let c = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, syd.lat, syd.lon), 600)
        let cruising = trackAt(c.lat, c.lon, gs: 450)
        // Same spot, now slowed to 150 kt, with the cruise fix still on the recorded trail.
        let slowed = cruising.updated(with: cruising.aircraft.with { $0.groundSpeed = 150; $0.positionTime = 1; $0.seenTime = 1 }, now: 1)
        #expect(FlightProgress.compute(cruising, r, now: 0).departedAt == FlightProgress.compute(slowed, r, now: 0).departedAt)
    }

    @Test func departureEstimateForAJetFirstSeenOnApproachUsesTypicalCruise() {
        // 1,400 nm flown, only ever seen at 3,000 ft and 178 kt: assume airliner cruise, not 178 kt for 8 hours.
        let far = Airport(icao: "YPDN", iata: "DRW", name: "Darwin", city: "Darwin", country: "AU", lat: -12.41, lon: 130.88, elevationFt: 100)
        let rr = route("JST689", "JQ689", far, adl)
        let c = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.lat, adl.lon, far.lat, far.lon), 10)
        let t = Track.start(base.with {
            $0.lat = c.lat; $0.lon = c.lon; $0.altBaro = 3000; $0.groundSpeed = 178; $0.category = "A3"; $0.baroRate = -900
            $0.track = Geo.bearing(c.lat, c.lon, adl.lat, adl.lon); $0.positionTime = 0; $0.seenTime = 0
        }, now: 0)
        let p = FlightProgress.compute(t, rr, now: 0)
        let hours = Double(-p.departedAt!) / 3_600_000
        #expect((3.0...3.8).contains(hours), "took off \(hours) h ago")
        // And it lands in a few minutes, not after a long cruise.
        #expect((4.0...9.0).contains(Double(p.eta!) / 60_000))
    }

    @Test func planeFarOffCorridorIsUnverified() {
        // Over Perth while "flying" ADL to SYD: a stale route.
        let p = FlightProgress.compute(trackAt(-31.9, 115.9), r, now: 0)
        #expect(!p.verified)
        #expect(p.eta == nil)
    }

    @Test func longHaulWellOffTheGreatCircleIsVerified() {
        // EY445 Sydney to Abu Dhabi crosses Adelaide, 280 nm off the great circle: still its route.
        var auh = adl
        auh.icao = "OMAA"; auh.iata = "AUH"; auh.lat = 24.433; auh.lon = 54.651
        let ey = route("ETD445", "EY445", syd, auh)
        #expect(FlightProgress.compute(trackAt(adl.lat, adl.lon, trk: 290), ey, now: 0).verified)
        // Heading back east over Adelaide isn't that flight.
        #expect(!FlightProgress.compute(trackAt(adl.lat, adl.lon, trk: 100), ey, now: 0).verified)
        // Just after takeoff, a departure procedure pointing south still counts.
        let c = Geo.destination(syd.lat, syd.lon, 180, 15)
        #expect(FlightProgress.compute(trackAt(c.lat, c.lon, trk: 160), ey, now: 0).verified)
    }

    @Test func headingAwayFromDestinationIsUnverified() {
        let c = Geo.destination(adl.lat, adl.lon, 85, 300)
        #expect(!FlightProgress.compute(trackAt(c.lat, c.lon, trk: 265), r, now: 0).verified)
    }

    @Test func observedTakeoffBeatsEstimate() {
        let t = trackAt(adl.lat, adl.lon, ground: true, gs: 15)
            .updated(with: base.with { $0.lat = adl.lat + 0.05; $0.lon = adl.lon + 0.1; $0.positionTime = 120_000; $0.seenTime = 120_000 }, now: 120_000)
        let p = FlightProgress.compute(t, r, now: 120_000)
        #expect(p.departedAt == 120_000)
        #expect(!p.departedEstimated)
    }

    @Test func landedAtDestination() {
        var t = trackAt(syd.lat + 0.3, syd.lon - 0.3)
        t = t.updated(with: base.with { $0.lat = syd.lat; $0.lon = syd.lon; $0.onGround = true; $0.groundSpeed = 20; $0.positionTime = 60_000; $0.seenTime = 60_000 }, now: 60_000)
        let p = FlightProgress.compute(t, r, now: 60_000)
        #expect(p.verified)
        #expect(p.landedAt == 60_000)
        #expect(p.fraction == 1)
    }
}

struct InsightsTests {
    func track(_ json: String) -> Track { Track.start(one(json), now: 0) }

    @Test func phases() {
        #expect(FlightInsights.phase(track(#"{"hex":"a","alt_baro":36000,"baro_rate":0,"gs":450,"lat":0,"lon":0}"#), nil, now: 0) == .cruising)
        #expect(FlightInsights.phase(track(#"{"hex":"a","alt_baro":12000,"baro_rate":2000,"gs":300,"lat":0,"lon":0}"#), nil, now: 0) == .climbing)
        #expect(FlightInsights.phase(track(#"{"hex":"a","alt_baro":20000,"baro_rate":-1500,"gs":350,"lat":0,"lon":0}"#), nil, now: 0) == .descending)
        #expect(FlightInsights.phase(track(#"{"hex":"a","alt_baro":2000,"baro_rate":-700,"gs":150,"lat":0,"lon":0}"#), nil, now: 0) == .approach)
        #expect(FlightInsights.phase(track(#"{"hex":"a","alt_baro":"ground","gs":15,"lat":0,"lon":0}"#), nil, now: 0) == .taxiing)
        #expect(FlightInsights.phase(track(#"{"hex":"a","alt_baro":"ground","gs":0,"lat":0,"lon":0}"#), nil, now: 0) == .parked)
    }

    @Test func windComponents() {
        let ahead = FlightInsights.windComponents(from: 90, knots: 40, track: 90)
        #expect(abs(ahead.head - 40) < 0.01 && abs(ahead.cross) < 0.01)
        #expect(abs(FlightInsights.windComponents(from: 270, knots: 40, track: 90).head + 40) < 0.01)
        #expect(abs(FlightInsights.windComponents(from: 180, knots: 40, track: 90).cross - 40) < 0.01)
    }

    @Test func tailwindAndAutopilotTargetAreReported() {
        let t = track(#"{"hex":"a","alt_baro":20000,"baro_rate":-1500,"gs":420,"track":90,"wd":270,"ws":60,"nav_altitude_mcp":10008,"lat":0,"lon":0}"#)
        let texts = FlightInsights.insights(t, nil, nil, near: nil, now: 0).map(\.text)
        #expect(texts.contains { $0.contains("tailwind") }, "\(texts)")
        #expect(texts.contains { $0.hasPrefix("Descending to") }, "\(texts)")
    }

    @Test func missedApproachAltitudeIsNotReportedAsAClimb() {
        // On final with 3,008 ft set for the go-around: the sheet must not say it's climbing.
        let t = track(#"{"hex":"a","alt_baro":1500,"baro_rate":-700,"gs":140,"nav_altitude_mcp":3008,"lat":0,"lon":0}"#)
        #expect(!FlightInsights.insights(t, nil, nil, near: nil, now: 0).contains { $0.text.contains("Climbing") })
    }

    @Test func ageNeedsAPlausibleYear() {
        #expect(FlightInsights.ageYears("2019", nowYear: 2025) == 6)
        #expect(FlightInsights.ageYears("0000", nowYear: 2025) == nil)
        #expect(FlightInsights.ageYears(nil, nowYear: 2025) == nil)
    }

    @Test func logosOnTransparencyPassBoxedLogosFail() {
        let mark = (0..<64 * 64).map { i -> UInt8 in (16...47).contains(i % 64) && (16...47).contains(i / 64) ? 255 : 0 }
        #expect(AirlineLogos.isCleanLogo(alpha: mark, 64, 64))
        #expect(!AirlineLogos.isCleanLogo(alpha: [UInt8](repeating: 255, count: 64 * 64), 64, 64))
        #expect(AirlineLogos.opaqueBounds(alpha: mark, 64, 64) == CGRect(x: 16, y: 16, width: 32, height: 32))
    }

    @Test func pathAheadEndsAtTheDestinationEvenWhenPointingAway() {
        let dest = Coord(lat: -33.946, lon: 151.177)
        // Heading due west, away from Sydney.
        let pts = Geo.pathAhead(from: Coord(lat: -34.9, lon: 138.5), heading: 270, to: dest)
        #expect(Geo.distanceNm(pts.last!, dest) < 3.5)
        // It starts by continuing west (the turn), not by jumping onto the great circle.
        #expect(pts[1].lon < 138.5)
    }

    @Test func projectionCoversEightMinutesAtCurrentSpeed() {
        let pts = Geo.projected(from: Coord(lat: 0, lon: 0), heading: 90, gs: 480, turnRate: nil)
        #expect(abs(Geo.distanceNm(Coord(lat: 0, lon: 0), pts.last!) - 64) < 1)
    }
}

struct AirportBoardsTests {
    let proto = one(#"{"hex":"7c0001","flight":"X","alt_baro":10000,"gs":300,"track":0,"lat":0,"lon":0,"seen":0}"#)

    func t(_ hex: String, _ cs: String, _ c: Coord, _ alt: Int, _ trk: Double, ground: Bool = false, gs: Double = 300, vr: Double = 0) -> Track {
        Track.start(proto.with {
            $0.hex = hex; $0.callsign = cs; $0.lat = c.lat; $0.lon = c.lon; $0.altBaro = alt; $0.track = trk
            $0.onGround = ground; $0.groundSpeed = gs; $0.baroRate = vr; $0.positionTime = 0; $0.seenTime = 0
        }, now: 0)
    }

    @Test func classifiesArrivalsAndDepartures() {
        let inbound = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.coord, mel.coord), 120)
        let outbound = Geo.destination(adl.lat, adl.lon, Geo.bearing(adl.coord, syd.coord), 60)
        let tracks = [
            t("a1", "QFA680", inbound, 20000, Geo.bearing(inbound, adl.coord)),
            t("a2", "QFA736", outbound, 25000, Geo.bearing(outbound, syd.coord)),
            t("a3", "VOZ221", Coord(lat: adl.lat, lon: adl.lon + 0.01), 0, 90, ground: true, gs: 12),
            // No route: on final to runway 23 counts; a trainer descending over Parafield, 9 nm off, doesn't.
            t("a4", "UNKNOWN1", Geo.destination(adl.lat, adl.lon, 50, 6), 2000, 230, vr: -700),
            t("a5", "UNKNOWN2", Coord(lat: -34.793, lon: 138.633), 1500, 180, vr: -500),
        ]
        let routes: [String: Route?] = [
            "QFA680": route("QFA680", "QF680", mel, adl),
            "QFA736": route("QFA736", "QF736", adl, syd),
            "VOZ221": route("VOZ221", "VA221", adl, mel),
        ]
        let b = AirportBoards.build(adl, tracks, routes: routes, now: 0,
                                    runways: [Runway(airportIcao: "YPAD", leIdent: "05", heIdent: "23", lengthFt: 10171, surface: "ASP", leHeading: 50, heHeading: 230)])
        #expect(b.arrivals.map(\.track.hex).sorted().reversed() == ["a4", "a1"])
        #expect(b.arrivals.first { $0.track.hex == "a1" }?.status == .enRoute)
        #expect(b.arrivals.first { $0.track.hex == "a4" }?.status == .approach)
        #expect(b.departures.first?.status == .taxiing)
        #expect(b.departures.first { $0.track.hex == "a2" }?.status == .departed)
        #expect(b.arrivals.first { $0.track.hex == "a1" }?.time != nil)
    }

    @Test func activeRunwayFromTrafficThenWind() {
        let rwy = Runway(airportIcao: "YPAD", leIdent: "05", heIdent: "23", lengthFt: 10171, surface: "ASP", leHeading: 50, heHeading: 230)
        let landing23 = t("b1", "QFA1", Coord(lat: adl.lat + 0.05, lon: adl.lon + 0.05), 1500, 228)
        #expect(AirportBoards.activeRunway(adl, [rwy], [landing23], windDir: 40) == .init(ident: "23", fromTraffic: true))
        #expect(AirportBoards.activeRunway(adl, [rwy], [Track](), windDir: 40) == .init(ident: "05", fromTraffic: false))
    }
}

struct HistoryTests {
    // Sydney gate, climb-out, then a 30 min coverage gap to over the Bight (as seen on QFA655).
    let json = Data("""
    {"icao24":"7c6dde","path":[
        [1000,-33.9374,151.1737,0,348,false],
        [1020,-33.9280,151.1713,304,347,false],
        [1600,-33.5000,149.0000,10972,260,false],
        [3400,-34.8000,138.0000,10972,265,false]]}
    """.utf8)

    @Test func parsesConvertsAndBridgesGaps() {
        let pts = FlightHistory.parseOpenSkyTrack(json, now: 3_500_000)!
        #expect(pts.first?.time == 1_000_000)
        #expect(pts[1].alt == 997) // 304 m in feet
        // No step bigger than the trail's 0.8° gap rule, so the line is drawn whole.
        for (a, b) in zip(pts, pts.dropFirst()) {
            #expect(abs(a.lon - b.lon) < 0.8 && abs(a.lat - b.lat) < 0.8)
            #expect(b.time >= a.time)
        }
        // Ground speed derived from the fixes: ~560 nm in 30 min.
        #expect(abs(pts.last!.gs! - 1120) < 120)
    }

    @Test func anEarlierFlightIsIgnored() {
        #expect(FlightHistory.parseOpenSkyTrack(json, now: 3_400_000 + 16 * 60_000) == nil)
    }

    @Test func historyPrependsAndGivesTheRealTakeoffTime() {
        let live = Track.start(one(#"{"hex":"7c6dde","lat":-34.81,"lon":137.9,"alt_baro":36000,"gs":460,"seen_pos":0}"#, 3_410_000), now: 3_410_000)
        let t = live.withHistory(FlightHistory.parseOpenSkyTrack(json, now: 3_500_000)!)
        #expect(t.takeoffAt == 1_020_000)
        #expect(t.trail.last == live.trail.last)
        #expect(t.trail.count > 4)
        // Merging the same history again changes nothing.
        #expect(t.withHistory(FlightHistory.parseOpenSkyTrack(json, now: 3_500_000)!).trail.count == t.trail.count)
    }
}
