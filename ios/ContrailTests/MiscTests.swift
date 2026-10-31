import Foundation
import Testing
@testable import Contrail

struct UnitsTests {
    @Test func aviationDefaults() {
        let f = Fmt(units: Units(distance: .nm))
        #expect(f.feet(35_000) == "35,000 ft")
        #expect(f.knots(450) == "450 kt")
        #expect(f.nm(5.5) == "5.5 nm")
        #expect(f.verticalRate(1_300) == "+1,280 ft/min")
    }

    @Test func metricConversions() {
        let f = Fmt(units: Units(altitude: .metres, speed: .kmh, distance: .km))
        #expect(f.feet(35_000) == "10,668 m")
        #expect(f.knots(450) == "833 km/h")
        #expect(f.nm(10) == "19 km") // decimals only below 10 of the shown unit
        #expect(f.nm(5) == "9.3 km")
        #expect(f.verticalRate(-1_000) == "−5.1 m/s")
        #expect(f.verticalRate(30) == "Level")
    }

    @Test func imperialSpeedAndDistance() {
        let f = Fmt(units: Units(speed: .mph, distance: .mi))
        #expect(f.knots(450) == "518 mph")
        #expect(f.nm(100) == "115 mi")
    }

    @Test func clockHonoursTheSetting() {
        let utc = TimeZone(identifier: "UTC")!
        let ms: Millis = 1_792_918_779_500 // 08:59:39 UTC
        #expect(Fmt(units: Units(clock: .h24)).time(ms, zone: utc) == "08:59")
        #expect(Fmt(units: Units(clock: .h12)).time(ms, zone: utc).hasPrefix("8:59"))
    }

    @Test func clockNamesTheAirportsCityWhenTheZoneDiffers() {
        let ms: Millis = 1_792_918_779_500
        let c = Fmt().clockParts(ms, tz: "Pacific/Kiritimati", place: "Kiritimati")
        // +14 is no device's zone in practice.
        #expect(c.zone == "Kiritimati time")
    }
}

struct SearchQueryTests {
    let airlines = ["QF": ["QFA"], "VA": ["VOZ"]]
    func c(_ q: String) -> [RemoteQuery] { SearchQuery.classify(q) { airlines[$0] ?? [] } }

    @Test func iataFlightNumberMapsToIcaoCallsign() {
        #expect(c("QF1").contains(.callsign("QFA1")))
        #expect(c("qf 746").contains(.callsign("QFA746")))
        #expect(c("VA007").contains(.callsign("VOZ7")), "leading zeros dropped")
    }

    @Test func icaoCallsign() { #expect(c("QFA746").contains(.callsign("QFA746"))) }

    @Test func registrations() {
        #expect(c("vh-vzc").contains(.registration("VH-VZC")))
        #expect(c("N12345").contains(.registration("N12345")))
    }

    @Test func hexAndSquawk() {
        #expect(c("7c6dd6").contains(.hex("7C6DD6")))
        #expect(c("7700") == [.squawk("7700")])
    }

    @Test func typeCode() { #expect(c("A388").contains(.type("A388"))) }

    @Test func tooShortIsNothing() { #expect(c("Q").isEmpty) }
}

struct SkyGeometryTests {
    let plane = one(#"{"hex":"7c0001","alt_baro":10000,"alt_geom":10000,"lat":0,"lon":0,"seen":0}"#)

    @Test func overheadIsNinetyDegrees() {
        let s = SkyGeometry.locate(obsLat: 0, obsLon: 0, obsAltFt: 0, plane)
        #expect(abs(s.elevation - 90) < 0.01)
        #expect(abs(s.slantNm - 10000 / 6076.12) < 0.01)
    }

    @Test func tenThousandFeetTenMilesAway() {
        let c = Geo.destination(0, 0, 90, 10)
        let s = SkyGeometry.locate(obsLat: 0, obsLon: 0, obsAltFt: 0, plane.with { $0.lat = c.lat; $0.lon = c.lon })
        #expect(abs(s.bearing - 90) < 0.1)
        // atan(10000 ft / 60761 ft) ≈ 9.3°, minus a hair for curvature.
        #expect(abs(s.elevation - 9.3) < 0.15)
    }

    @Test func farAwayLowPlaneIsBelowHorizon() {
        let c = Geo.destination(0, 0, 0, 200)
        let s = SkyGeometry.locate(obsLat: 0, obsLon: 0, obsAltFt: 0, plane.with { $0.lat = c.lat; $0.lon = c.lon; $0.altBaro = 1000; $0.altGeom = 1000 })
        #expect(s.elevation < 0)
    }

    // Phone upright in portrait, camera facing north: device x = east, y = up, z = south.
    let facingNorth: [Double] = [1, 0, 0,
                                 0, 0, -1,
                                 0, 1, 0]

    @Test func cameraPointingNorthLevel() {
        let p = SkyGeometry.cameraPointing(facingNorth)
        #expect(abs(p.heading) < 0.01)
        #expect(abs(p.pitch) < 0.01)
    }

    @Test func projectionCentreRightAndBehind() {
        let centre = SkyGeometry.project(facingNorth, bearing: 0, elevation: 0, hFovDeg: 60, aspect: 2)!
        #expect(abs(centre.x - 0.5) < 1e-6 && abs(centre.y - 0.5) < 1e-6)
        #expect(SkyGeometry.project(facingNorth, bearing: 20, elevation: 0, hFovDeg: 60, aspect: 2)!.x > 0.5, "east of north appears right")
        #expect(SkyGeometry.project(facingNorth, bearing: 0, elevation: 10, hFovDeg: 60, aspect: 2)!.y < 0.5, "higher appears above")
        #expect(SkyGeometry.project(facingNorth, bearing: 180, elevation: 0, hFovDeg: 60, aspect: 2) == nil, "south is behind")
    }

    @Test func coreMotionUprightFacingNorthMatchesTheEnuLayout() {
        // CoreMotion maps world (north, west, up) into device axes. Upright facing north: device x = east = -west,
        // y = up, z = south = -north. So rows of R (device axes in world terms): x = (0,-1,0), y = (0,0,1), z = (-1,0,0).
        let r = SkyGeometry.fromCoreMotion(m11: 0, m12: -1, m13: 0, m21: 0, m22: 0, m23: 1, m31: -1, m32: 0, m33: 0)
        #expect(r == facingNorth)
    }
}

struct FiltersTests {
    let base = one(#"{"hex":"7c0001","alt_baro":10000,"category":"A3","lat":0,"lon":0,"seen":0}"#)

    @Test func defaultShowsEverything() { #expect(Filters().matches(base)) }

    @Test func altitudeRange() {
        #expect(!Filters(altMinFt: 20000).matches(base))
        #expect(!Filters(altMaxFt: 5000).matches(base))
        #expect(Filters(altMinFt: 5000, altMaxFt: 15000).matches(base))
    }

    @Test func kindsAndGround() {
        #expect(!Filters(kinds: [.helicopter]).matches(base))
        #expect(!Filters(hideGround: true).matches(base.with { $0.onGround = true }))
    }

    @Test func emergenciesAlwaysShow() {
        #expect(Filters(altMinFt: 40000, kinds: []).matches(base.with { $0.squawk = "7700" }))
    }
}

struct MapFeatureTests {
    let now: Millis = 1_000_000

    @Test func frameRateFollowsZoom() {
        #expect(MapFeatures.frameIntervalMs(zoom: 14, lat: -35) == 100)
        #expect(MapFeatures.frameIntervalMs(zoom: 4, lat: -35) == 2_000)
        #expect((500...1_500).contains(MapFeatures.frameIntervalMs(zoom: 8, lat: -35)))
        var last = Millis.max
        for z in 3...16 {
            let v = MapFeatures.frameIntervalMs(zoom: Double(z), lat: 0)
            #expect(v <= last)
            last = v
        }
    }

    @Test func areaContainsIsInclusive() {
        let adelaide = Area(south: -35.5, west: 138, north: -34.5, east: 139)
        #expect(adelaide.contains(-35.5, 138))
        #expect(!adelaide.contains(-35.6, 138.5))
    }

    @Test func areaSeesAcrossTheAntimeridian() {
        let fiji = Area(south: -20, west: 170, north: 0, east: 190)
        #expect(fiji.contains(-10, 175))
        #expect(fiji.contains(-10, -175))
        #expect(!fiji.contains(-10, -160))
    }

    func trail(_ fixes: [(Double, Double, Int)]) -> [TrailPoint] {
        var t = Track.start(one(#"{"hex":"aaaaaa","alt_baro":10000,"gs":0,"lat":0,"lon":0,"seen":0}"#).with {
            $0.lat = fixes[0].0; $0.lon = fixes[0].1; $0.altBaro = fixes[0].2; $0.positionTime = 0; $0.seenTime = 0
        }, now: 0)
        for (i, f) in fixes.dropFirst().enumerated() {
            let ms = Millis(i + 1) * 1_000
            t = t.updated(with: t.aircraft.with { $0.lat = f.0; $0.lon = f.1; $0.altBaro = f.2; $0.positionTime = ms; $0.seenTime = ms }, now: ms)
        }
        return t.trail
    }

    @Test func trailMergesSameColourSegmentsAndSplitsAtGaps() {
        // Level at 35,000 ft: one line however many fixes.
        #expect(MapFeatures.trailRuns(trail([(-35, 138, 35000), (-35, 138.1, 35000), (-35, 138.2, 35000), (-35, 138.3, 35000)]), current: nil).count == 1)
        // Descending through bands: a new line per colour, joined end to end.
        let descent = MapFeatures.trailRuns(trail([(-35, 138, 35000), (-35, 138.1, 35000), (-35, 138.2, 3000), (-35, 138.3, 3000)]), current: nil)
        #expect(descent.count == 2)
        #expect(descent[0].points.last == descent[1].points.first)
        // A 2° jump (no coverage in between) is not drawn as a straight line.
        #expect(MapFeatures.trailRuns(trail([(-35, 138, 35000), (-35, 138.1, 35000), (-35, 140.1, 35000), (-35, 140.2, 35000)]), current: nil).count == 2)
    }

    @Test func svgPathsParseToTheBox() {
        for s in Shape.allCases {
            let b = SVGPath.parse(s.path).boundingBox
            #expect(b.minX >= 0 && b.maxX <= 64 && b.minY >= 0 && b.maxY <= 64 && b.width > 10, "\(s)")
        }
    }

    @Test func altitudeBands() {
        #expect(AltitudeColors.bandFor(0) == 0)
        #expect(AltitudeColors.bandFor(36_000) == 13)
        #expect(AltitudeColors.bandFor(60_000) == AltitudeColors.bandTops.count - 1)
    }
}

struct StaticDataTests {
    // Tests run hosted in the app, so the bundled data is in the main bundle.
    let data = StaticData.load(.main)

    @Test func routeIsReadOffTheFlownPathWhenTheListingIsWrong() {
        // QTR65Q, 2026-10-26: adsbdb listed Doha to Colombo; it flew Doha to Adelaide.
        let a = one(#"{"hex":"06a10e","flight":"QTR65Q","alt_baro":"ground","gs":16,"track":53,"lat":-34.946,"lon":138.535,"seen":0,"seen_pos":0}"#, 1_000_000)
        let history = [TrailPoint(lat: 25.3035, lon: 51.5844, alt: 997, onGround: false, gs: nil, time: 1_000),
                       TrailPoint(lat: -34.9364, lon: 138.5494, alt: 0, onGround: false, gs: nil, time: 900_000)]
        let t = Track.start(a, now: 1_000_000).withHistory(history)
        let r = FlightProgress.flownRoute(t, listed: nil, data: data)
        #expect(r?.origin.iata == "DOH")
        #expect(r?.destination.iata == "ADL")
        #expect(t.takeoffAt != nil && t.landedAt == 900_000)
        #expect(FlightProgress.compute(t, r!, now: 1_000_000).verified)
    }

    @Test func destinationIsReadOffTheFinalApproach() {
        // The same flight 10 nm out on final for runway 23, descending through 3,000 ft.
        let a = one(#"{"hex":"06a10e","flight":"QTR65Q","alt_baro":3000,"baro_rate":-700,"gs":160,"track":229,"lat":-34.85,"lon":138.67,"seen":0,"seen_pos":0}"#, 1_000_000)
        let t = Track.start(a, now: 1_000_000).withHistory([TrailPoint(lat: 25.3035, lon: 51.5844, alt: 997, onGround: false, gs: nil, time: 1_000)])
        #expect(FlightProgress.flownRoute(t, listed: nil, data: data)?.destination.iata == "ADL")
        // Overflying at cruise is no evidence of where it's going.
        let high = Track.start(a.with { $0.altBaro = 37_000; $0.groundSpeed = 480 }, now: 1_000_000).withHistory([TrailPoint(lat: 25.3035, lon: 51.5844, alt: 997, onGround: false, gs: nil, time: 1_000)])
        #expect(FlightProgress.flownRoute(high, listed: nil, data: data) == nil)
    }

    @Test func airportsLoadAndHomeRegionIsPresent() {
        #expect(data.airports.count > 15_000)
        for icao in ["YPAD", "YPED", "YPPF"] { #expect(data.airport(icao)?.tz == "Australia/Adelaide", "\(icao)") }
        #expect(data.airport("ADL")?.icao == "YPAD")
    }

    @Test func timeZonesAreValid() {
        let bad = Set(data.airports.compactMap(\.tz)).filter { TimeZone(identifier: $0) == nil }
        #expect(bad.isEmpty, "unknown zones: \(bad.prefix(5))")
    }

    @Test func runwaysAndAirlines() {
        #expect(data.runways("YPPF").count == 4)
        #expect(data.airlineForCallsign("QFA1")?.name.contains("Qantas") == true)
        #expect(data.icaoForIataAirline("QF").contains("QFA"))
        #expect(data.searchAirports("adelaide").first?.icao == "YPAD")
        #expect(data.nearestPlace(-34.95, 138.53)?.city == "Adelaide")
        #expect(data.nearestPlace(-36.33, 144.97)?.city?.contains("Shire") == false)
    }
}

