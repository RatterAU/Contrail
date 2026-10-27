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
    @Test func onlyThisPlaneNewestFirst() {
        // A shard holds every aircraft whose hex starts 7c4e; blank airports were out of coverage.
        let shard = "7c4ee8\tQFA1\tYSSY\t100\tWSSS\t580\n7c4ee8\tQFA2\tWSSS\t700\t\t1200\n7c4ee9\tVOZ1\tYMML\t50\tYSSY\t140\n"
        let list = FlightHistory.parsePastFlights(shard, hex: "7c4ee8")
        #expect(list.map(\.callsign) == ["QFA2", "QFA1"])
        #expect(list[0].to == nil)
        #expect(list[1].departed == 100 * 60_000)
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
