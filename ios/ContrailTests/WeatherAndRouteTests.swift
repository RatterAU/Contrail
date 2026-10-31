import Foundation
import Testing
import UIKit
@testable import Contrail

struct RouteLegTests {
    // Trimmed from adsb.lol /api/0/route/QFA1, 2026-10-27.
    let qfa1 = EnrichmentService.parseAdsbLolLegs(Data(#"""
        {"callsign":"QFA1","number":"1","airline_code":"QFA","airport_codes":"YSSY-WSSS-EGLL","_airports":[
          {"name":"Sydney Kingsford Smith International Airport","icao":"YSSY","iata":"SYD","location":"Sydney","countryiso2":"AU","lat":-33.946098,"lon":151.177002,"alt_feet":21},
          {"name":"Singapore Changi Airport","icao":"WSSS","iata":"SIN","location":"Singapore","countryiso2":"SG","lat":1.35019,"lon":103.994003,"alt_feet":22},
          {"name":"London Heathrow Airport","icao":"EGLL","iata":"LHR","location":"London","countryiso2":"GB","lat":51.4706,"lon":-0.461941,"alt_feet":83}]}
        """#.utf8))

    func at(_ lat: Double, _ lon: Double, _ track: Int) -> Track {
        Track.start(one(#"{"hex":"7c4ee8","flight":"QFA1","alt_baro":38000,"gs":480,"track":\#(track),"lat":\#(lat),"lon":\#(lon),"seen":0,"seen_pos":0}"#), now: 0)
    }

    @Test func eachStopPairIsALeg() {
        #expect(qfa1.map { "\($0.origin.iata!)-\($0.destination.iata!)" } == ["SYD-SIN", "SIN-LHR"])
        #expect(qfa1[0].destination.city == "Singapore")
    }

    @Test func theLegThePlaneIsOnIsShown() {
        // Over the Kimberley heading north-west: Sydney to Singapore. Over the Bay of Bengal: Singapore to London.
        #expect(FlightProgress.shownRoute(at(-17, 125, 300), listed: nil, legs: qfa1, now: 0)?.destination.iata == "SIN")
        #expect(FlightProgress.shownRoute(at(15, 88, 300), listed: nil, legs: qfa1, now: 0)?.destination.iata == "LHR")
    }
}

struct PastFlightTests {
    @Test func pathFromAdsbLolTrace() throws {
        let f = try #require(FlightHistory.parsePastFlights("7c6d97\tQFA683\tYMML\t29884000\tYPAD\t29884051\t20261026.0.ab.1500.120 20261027.0.aa.9.8\n", hex: "7c6d97").first)
        #expect(f.traces == ["20261026.0.ab.1500.120", "20261027.0.aa.9.8"])
        #expect(FlightHistory.traceURL(f.traces[0])?.absoluteString ==
                "https://github.com/adsblol/globe_history_2026/releases/download/v2026.10.26-planes-readsb-prod-0/v2026.10.26-planes-readsb-prod-0.tar.ab")
        #expect(FlightHistory.traceURL("garbage") == nil)
        // A real gzip file, as it sits in the release: Foundation has no gzip, so the header is stripped by hand.
        let json = try #require(FlightHistory.gunzip(Data(base64Encoded: "H4sIAAAAAAACE22OzQqDMBCEXyXkHEO2SYzbVxEPolIEf4rVU+m7d900aGlDCOzsfJN5yr6pZ3kVMjR5i0EqIdd+7B5rPd5JhoDWOENnXyx105FYljRlNug8KAHO6cLR9rbM29RSAFyUMHynbRjiWylR5omKECphORcKo31CwBtzILRiiBEgwDPhvEn+LzOw2WtE9uuDQNy1HyQWyCy1oQZgC+1tqg3u3ycxiIkPcDKdrNXrDT1jdHZbAQAA")!))
        let pts = FlightHistory.parseTrace(json)
        #expect(pts.count == 6)
        #expect(pts[0].onGround && pts[0].alt == 0)
        #expect(pts[1].alt == 3000 && pts[4].alt == nil)
        #expect(pts[1].time == (29884000 * 60 + 60) * 1000)
        // The flight's window drops the row from the next flight hours later; the 9999 kt glitch is ignored.
        let path = FlightHistory.slice(pts, f)
        #expect(path.count == 5)
        let st = FlightHistory.stats(path)
        #expect(st.topAltFt == 35000 && st.topSpeedKt == 450)
        #expect(st.distanceNm > 300 && st.distanceNm < 600)
    }

    @Test func onlyThisPlaneNewestFirst() {
        // A shard holds every aircraft whose hex starts 7c4e; blank airports were out of coverage.
        let shard = "7c4ee8\tQFA1\tYSSY\t100\tWSSS\t580\n7c4ee8\tQFA2\tWSSS\t700\t\t~1200\n7c4ee9\tVOZ1\tYMML\t50\tYSSY\t140\n"
        let list = FlightHistory.parsePastFlights(shard, hex: "7c4ee8")
        #expect(list.map(\.callsign) == ["QFA2", "QFA1"])
        #expect(list[0].to == nil)
        #expect(list[0].arrived == 1200 * 60_000 && !list[0].arrivedSeen) // "~": when it was last seen, not when it landed
        #expect(list[1].arrivedSeen)
        #expect(list[1].departed == 100 * 60_000)
    }

    @Test func scheduledEndsAreKeptApartFromSeenOnes() throws {
        let f = try #require(FlightHistory.parsePastFlights("7c6d97\tVOZ1361\t\t~29884000\tYMML\t29884051\tref\tYMLT\t\n", hex: "7c6d97").first)
        #expect(f.from == nil && f.scheduledFrom == "YMLT" && f.scheduledTo == nil && !f.departedSeen)
    }
}

struct SeenRouteTests {
    @Test func mostFlownFirstUnknownAirportsSkipped() {
        let table = ["YPAD": adl, "YSSY": syd, "YMML": mel]
        let text = "QFA1\tYPAD\tYMML\t3\nQFA1\tYSSY\tYPAD\t9\nQFA1\tYSSY\tXXXX\t4\nQFA10\tYMML\tYSSY\t5\n"
        let seen = EnrichmentService.parseSeenRoutes(text, callsign: "QFA1", airline: nil) { table[$0] }
        #expect(seen.map { "\($0.origin.code)-\($0.destination.code)" } == ["SYD-ADL", "ADL-MEL"])
    }
}

struct MakerTests {
    @Test func makersAreNamedAsPeopleSayThem() {
        #expect(AircraftInfo.shortMaker("Embraer - Empresa Brasileira De Aeronautica S.a.") == "Embraer")
        #expect(AircraftInfo.shortMaker("Piper Aircraft, Inc.") == "Piper")
        #expect(AircraftInfo.shortMaker("Diamond Aircraft Industries Inc.") == "Diamond")
        #expect(AircraftInfo.shortMaker("Boeing Company") == "Boeing")
        #expect(AircraftInfo.shortMaker("Airbus Helicopters") == "Airbus Helicopters")
        #expect(AircraftInfo(registration: nil, manufacturer: "Avions de Transport Regional", type: "ATR 72 500", icaoType: nil, owner: nil, ownerCountry: nil).fullName == "ATR 72 500")
    }
}

struct ReplayTests {
    func p(_ t: Millis, _ lat: Double, _ lon: Double, alt: Int? = 10_000, gs: Double? = 300, ground: Bool = false) -> TrailPoint {
        TrailPoint(lat: lat, lon: lon, alt: alt, onGround: ground, gs: gs, time: t)
    }

    @Test func interpolatesBetweenFixes() throws {
        let f = try #require(Replay.at([p(0, 0, 0, alt: 0, gs: 100), p(60_000, 0, 1, alt: 6_000, gs: 300)], 30_000))
        #expect(abs(f.lon - 0.5) < 1e-9 && f.alt == 3_000 && abs(f.gs! - 200) < 1e-9)
        #expect(abs(f.heading - 90) < 0.5 && !f.gap)
    }

    @Test func clampsAndNeedsTwoPoints() {
        let path = [p(1_000, 0, 0), p(2_000, 1, 0)]
        #expect(Replay.at(path, 0)?.time == 1_000)
        #expect(Replay.at(path, 9_999)?.lat == 1)
        #expect(Replay.at(Array(path.prefix(1)), 1_000) == nil)
    }

    @Test func crossesTheDatelineAndFlagsGaps() throws {
        let f = try #require(Replay.at([p(0, -20, 179), p(60_000, -20, -179)], 30_000))
        #expect(abs(abs(f.lon) - 180) < 0.1 && abs(f.heading - 90) < 1)
        #expect(Replay.at([p(0, -34, 138), p(Replay.gapMs + 60_000, -30, 140)], 120_000)?.gap == true)
    }

    @Test func headingIgnoresJitterOnTheGround() throws {
        let path = [p(0, -35, 138, ground: true), p(30_000, -34.99, 138, ground: true), p(40_000, -34.98999, 137.99999, ground: true)]
        let h = try #require(Replay.at(path, 35_000)).heading
        #expect(h < 1 || h > 359)
    }
}

struct WeatherLayerTests {
    func sample(_ speed: Double) -> WindSample {
        WindSample(speedKt: Dictionary(uniqueKeysWithValues: WindLevel.allCases.map { ($0, speed) }),
                   fromDeg: Dictionary(uniqueKeysWithValues: WindLevel.allCases.map { ($0, 270) }))
    }

    @Test func westerlyBlowsEast() {
        let v = WindGrid.vector(speed: 100, fromDeg: 270)
        #expect(abs(v.u - 100) < 1e-9 && abs(v.v) < 1e-9)
    }

    @Test func bilinearBetweenPointsAndNilOutsideTheGrid() {
        let g = WindGrid(step: 1, samples: [WindGrid.key(0, 0, 1): sample(0), WindGrid.key(0, 1, 1): sample(100),
                                            WindGrid.key(1, 0, 1): sample(0), WindGrid.key(1, 1, 1): sample(100)])
        #expect(abs(g.sample(lat: 0.5, lon: 0.5, level: .fl340)!.u - 50) < 1e-6)
        #expect(g.sample(lat: 5, lon: 5, level: .fl340) == nil)
    }

    @Test func antimeridianPointsShareKeys() {
        #expect(WindGrid.key(0, 190, 1) == WindGrid.key(0, -170, 1))
        #expect(abs(WindGrid.latLon(0, 190, 1).lon + 170) < 1e-9)
    }

    @Test func gridNeverAsksForALatitudePastThePole() {
        for (south, north) in [(-80.0, 80.0), (-85.0, 85.0)] {
            for step in [20.0, 30.0] {
                let lats = WindGrid.points(south: south, west: -180, north: north, east: 180, step: step).map { WindGrid.latLon($0.0, $0.1, step).lat }
                #expect(lats.allSatisfy { abs($0) <= 90 }, "\(south)...\(north) at \(step)")
            }
        }
    }

    @Test func decimalVisibilityIsKept() throws {
        let m = try #require(Metar(JSON.object(Data(#"{"icaoId":"EGLL","rawOb":"x","visib":2.61}"#.utf8))))
        #expect(m.visibilityText == "4.2 km")
        let plus = try #require(Metar(JSON.object(Data(#"{"icaoId":"EGLL","rawOb":"x","visib":"6+"}"#.utf8))))
        #expect(plus.visibilityText == "10 km+")
    }

    @Test func gridStaysWithinTheRequestBudget() {
        for (lat, lon) in [(0.5, 0.8), (4.0, 6.0), (30.0, 60.0), (160.0, 360.0)] {
            let step = WindGrid.step(latSpan: lat, lonSpan: lon)
            #expect(Double(WindGrid.points(south: -lat / 2, west: -lon / 2, north: lat / 2, east: lon / 2, step: step).count) <= Double(WindGrid.maxPoints) * 1.2)
        }
    }

    @Test func sigmetsWithOneOrSeveralAreasBothParse() throws {
        // Shapes from aviationweather.gov isigmet, 2026-10-27: a multi-area SIGMET nests its coordinate lists.
        let json = #"""
            [{"hazard":"TURB","qualifier":"SEV","base":25000,"top":38000,"validTimeTo":1793094480,"coords":[{"lon":14.9,"lat":-40.8},{"lon":23.5,"lat":-49.3},{"lon":20.2,"lat":-50.9}]},
             {"hazard":"TS","qualifier":"EMBD","base":null,"top":45000,"validTimeTo":1793094480,"coords":[[{"lon":1,"lat":1},{"lon":2,"lat":1},{"lon":2,"lat":2}],[{"lon":5,"lat":5},{"lon":6,"lat":5},{"lon":6,"lat":6}]]}]
            """#
        let list = try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [JSONObject]).compactMap(Sigmet.init)
        #expect(list.map(\.areas.count) == [1, 2])
        #expect(list[0].label == "Severe turbulence\nFL250–FL380")
        #expect(list[1].label == "Thunderstorms\nUp to FL450")
    }

    @Test func cloudRecolourMakesClearSkyTransparentAndCloudWhite() throws {
        // Two grey pixels: clear ground (110) and a cold cloud top (200).
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let png = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 1), format: format).pngData { ctx in
            UIColor(white: 110 / 255, alpha: 1).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
            UIColor(white: 200 / 255, alpha: 1).setFill(); ctx.fill(CGRect(x: 1, y: 0, width: 1, height: 1))
        }
        let out = try #require(CloudOverlay.recolour(png).flatMap { UIImage(data: $0)?.cgImage })
        let ctx = try #require(CGContext(data: nil, width: 2, height: 1, bitsPerComponent: 8, bytesPerRow: 8, space: CGColorSpaceCreateDeviceRGB(),
                                         bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.draw(out, in: CGRect(x: 0, y: 0, width: 2, height: 1))
        let px = try #require(ctx.data?.bindMemory(to: UInt8.self, capacity: 8))
        #expect(px[3] == 0)
        #expect(abs(Int(px[7]) - 230) <= 2)
        #expect(px[4] == px[7]) // white, premultiplied
    }
}
