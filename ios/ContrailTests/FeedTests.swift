import Foundation
import Testing
@testable import Contrail

let NOW: Millis = 1_792_918_779_500

// Trimmed from a real adsb.lol point response over Adelaide, 2026-10-25.
let LOL = """
{"ac":[
{"hex":"7c5304","type":"adsb_icao","flight":"QLK589D ","r":"VH-QOM","t":"DH8D","alt_baro":16000,"alt_geom":16175,"gs":312.9,"ias":277,"tas":354,"mach":0.56,"wd":263,"ws":40,"oat":-10,"tat":7,"track":274.4,"baro_rate":64,"squawk":"7250","emergency":"none","category":"A2","nav_qnh":1012.8,"nav_altitude_mcp":2816,"nav_heading":267.89,"nav_modes":["autopilot","vnav"],"lat":-34.789837,"lon":136.84782,"nic":8,"nac_p":9,"sil":3,"version":2,"messages":18333,"seen":0,"seen_pos":0.5,"rssi":-18.5,"dst":83.176,"dir":275.7},
{"hex":"7c6de6","type":"adsb_icao","flight":"QFA777  ","r":"VH-VZS","t":"B738","alt_baro":"ground","gs":12.1,"track":90,"lat":-34.94,"lon":138.53,"seen":2,"seen_pos":2},
{"hex":"~2a1b3c","type":"tisb_other","lat":-35.0,"lon":138.6,"seen":1},
{"hex":"7c0000","type":"mode_s","alt_baro":3000,"seen":1}
],"msg":"No error","now":\(NOW),"total":4,"ctime":0,"ptime":1}
"""

let FI = """
{"now":\(NOW),"aircraft":[{"hex":"ae10e9","type":"mlat","flight":"C2003","t":"C130","dbFlags":1,"alt_baro":24000,"lat":-34.5,"lon":138.2,"seen":1,"seen_pos":1}],"resultCount":1,"ptime":1}
"""

/// Fixtures are built by parsing JSON, like the feeds, rather than by hand.
func parse(_ json: String, _ now: Millis = 0) -> [Aircraft] { try! parseReadsb(json, receivedAt: now) }
func one(_ acJson: String, _ now: Millis = 0) -> Aircraft { parse("{\"ac\":[\(acJson)]}", now)[0] }

struct ParserTests {
    @Test func parsesAdsbLolPointResponse() {
        let list = parse(LOL, NOW)
        #expect(list.count == 3, "aircraft without a position are dropped")
        let q = list.first { $0.hex == "7c5304" }!
        #expect(q.callsign == "QLK589D")
        #expect(q.registration == "VH-QOM")
        #expect(q.altBaro == 16000)
        #expect(!q.onGround)
        #expect(abs(q.groundSpeed! - 312.9) < 0.001)
        #expect(q.navAltMcp == 2816)
        #expect(q.navModes == ["autopilot", "vnav"])
        #expect(q.emergency == nil, "'none' is not an emergency")
        #expect(q.source == .adsb)
        #expect(q.positionTime == NOW - 500)
    }

    @Test func groundAltitudeAndHexPrefix() {
        let list = parse(LOL, NOW)
        let g = list.first { $0.hex == "7c6de6" }!
        #expect(g.onGround)
        #expect(g.altBaro == 0)
        #expect(list.first { $0.source == .tisb }?.hex == "2a1b3c", "non-ICAO '~' prefix stripped")
    }

    @Test func parsesAdsbFiAircraftKeyAndFlags() {
        let a = parse(FI, NOW).first!
        #expect(a.source == .mlat)
        #expect(a.military)
        #expect(a.callsign == "C2003")
    }

    @Test func implausibleServerClockFallsBackToPhoneClock() {
        let a = parse(FI.replacingOccurrences(of: "\(NOW)", with: "1000"), NOW).first!
        #expect(a.positionTime == NOW - 1000)
    }
}

struct GeoTests {
    @Test func adelaideToSydneyDistance() {
        // YPAD to YSSY is ~633 nm great-circle.
        #expect(abs(Geo.distanceNm(-34.945, 138.531, -33.946, 151.177) - 633) < 5)
    }

    @Test func destinationRoundTrips() {
        let d = Geo.destination(-34.9, 138.5, 45, 100)
        #expect(abs(Geo.distanceNm(-34.9, 138.5, d.lat, d.lon) - 100) < 0.01)
        #expect(abs(Geo.bearing(-34.9, 138.5, d.lat, d.lon) - 45) < 0.5)
    }

    @Test func crossTrack() {
        #expect(abs(Geo.crossTrackNm(0, 0, 0, 10, 0, 5)) < 0.01)
        #expect(abs(abs(Geo.crossTrackNm(0, 0, 0, 10, 1, 5)) - 60) < 0.5)
    }

    @Test func compassAndAngles() {
        #expect(Geo.compass(359) == "N")
        #expect(Geo.compass(40) == "NE")
        #expect(abs(Geo.angleDiff(350, 10) - 20) < 1e-9)
    }
}

struct TrackTests {
    func plane(_ t: Millis, _ lat: Double, ground: Bool = false, gs: Double = 240) -> Aircraft {
        var a = parse(LOL, NOW).first { $0.hex == "7c5304" }!
        a.lat = lat
        a.positionTime = t
        a.seenTime = t
        a.onGround = ground
        a.groundSpeed = gs
        a.track = 0
        return a
    }

    @Test func extrapolatesAlongTrack() {
        // 240 kt for 15 s = 1 nm north.
        #expect(abs(Track.extrapolate(plane(0, 0), 15_000).lat - 1.0 / 60) < 1e-4)
    }

    @Test func extrapolationIsCapped() {
        let a = plane(0, 0)
        #expect(Track.extrapolate(a, Track.maxExtrapolateMs) == Track.extrapolate(a, 10 * 60_000))
    }

    @Test func recordsTakeoffAndLanding() {
        var t = Track.start(plane(0, 0, ground: true, gs: 20), now: 0)
        t = t.updated(with: plane(60_000, 0.1), now: 60_000)
        #expect(t.takeoffAt == 60_000)
        t = t.updated(with: plane(3_600_000, 5, ground: true, gs: 30), now: 3_600_000)
        #expect(t.landedAt == 3_600_000)
        #expect(t.trail.count == 3)
    }

    @Test func ignoresOlderReports() {
        let t = Track.start(plane(10_000, 1), now: 10_000)
        #expect(t.updated(with: plane(5_000, 0.5), now: 11_000) == t)
    }

    @Test func registryFieldsSurviveAFeedThatOmitsThem() {
        let t = Track.start(plane(0, 0), now: 0)
        let bare = plane(1_000, 0.1).with { $0.registration = nil; $0.typeCode = nil; $0.year = nil }
        let u = t.updated(with: bare, now: 1_000)
        #expect(u.aircraft.registration == "VH-QOM")
        #expect(u.aircraft.typeCode == "DH8D")
    }

    @Test func blendEasesFromOldDrawnPosition() {
        var t = Track.start(plane(0, 0, gs: 0), now: 0)
        t = t.updated(with: plane(1_000, 0.01, gs: 0), now: 1_000)
        #expect(abs(t.displayPosition(1_000).lat) < 1e-9)
        #expect(abs(t.displayPosition(1_000 + Track.blendMs).lat - 0.01) < 1e-9)
    }
}

struct SchedulerTests {
    final class FakeSource: FeedSource, @unchecked Sendable {
        let name: String
        let fail: Bool
        private let lock = NSLock()
        private var _calls = 0
        var calls: Int { lock.withLock { _calls } }

        init(_ name: String, fail: Bool) {
            self.name = name
            self.fail = fail
        }

        func point(lat: Double, lon: Double, nm: Int) async throws -> [Aircraft] {
            lock.withLock { _calls += 1 }
            if fail { throw HTTP.Status(code: 429) }
            return []
        }

        func query(_ q: RemoteQuery) async throws -> [Aircraft] { [] }
    }

    final class Clock: @unchecked Sendable {
        var now: Millis = 0
    }

    @Test func failsOverAndBenchesBrokenSource() async throws {
        let bad = FakeSource("a", fail: true), good = FakeSource("b", fail: false)
        let clock = Clock()
        let s = FeedScheduler([bad, good], clock: { clock.now }, minIntervalMs: 0)
        _ = try await s.run { try await $0.point(lat: 0, lon: 0, nm: 10) }
        #expect(bad.calls == 1)
        #expect(good.calls == 1)
        #expect(await s.health["a"]?.ok == false)
        // While benched, the bad source is tried last, so the good one answers first.
        for _ in 0..<4 {
            clock.now += 1_000
            _ = try await s.run { try await $0.point(lat: 0, lon: 0, nm: 10) }
        }
        #expect(bad.calls == 1)
        #expect(await s.health["b"]?.latencyMs != nil)
    }

    @Test func allDownThrows() async {
        let s = FeedScheduler([FakeSource("a", fail: true), FakeSource("b", fail: true)], clock: { 0 }, minIntervalMs: 0)
        await #expect(throws: FeedsUnavailable.self) { try await s.run { try await $0.point(lat: 0, lon: 0, nm: 10) } }
    }
}
