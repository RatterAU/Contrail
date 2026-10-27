import Foundation

struct Metar: Hashable, Sendable {
    struct Cloud: Hashable, Sendable {
        var cover: String
        var base: Int?
    }

    var icao: String
    var raw: String
    var observed: Millis?
    var temp: Double?
    var dewpoint: Double?
    var windDir: Double?
    var windVariable: Bool
    var windSpeed: Double?
    var gust: Double?
    /// Statute miles as reported; "6+" means 10 km or more.
    var visibility: String?
    var qnh: Double?
    var clouds: [Cloud]
    /// VFR, MVFR, IFR or LIFR.
    var category: String?

    var visibilityText: String {
        guard let v = visibility else { return Fmt.none }
        if v == "6+" || v == "10+" { return "10 km+" }
        guard let mi = Double(v) else { return v }
        let km = mi * 1.609
        return km >= 10 ? "10 km+" : String(format: "%.1f km", km)
    }

    var cloudText: String {
        guard !clouds.isEmpty else { return "Clear" }
        return clouds.map { c in
            let cover = switch c.cover {
            case "FEW": "Few"
            case "SCT": "Scattered"
            case "BKN": "Broken"
            case "OVC": "Overcast"
            case "CLR", "SKC", "NCD", "NSC", "CAVOK": "Clear"
            default: c.cover
            }
            return c.base.map { "\(cover) \($0.formatted(.number.locale(Locale(identifier: "en_US")))) ft" } ?? cover
        }.joined(separator: ", ")
    }

    init?(_ o: JSONObject) {
        guard let icao = o.string("icaoId"), let raw = o.string("rawOb") else { return nil }
        self.icao = icao
        self.raw = raw
        observed = o.double("obsTime").map { Millis($0 * 1000) }
        temp = o.double("temp")
        dewpoint = o.double("dewp")
        windDir = o.double("wdir")
        windVariable = o.string("wdir") == "VRB"
        windSpeed = o.double("wspd")
        gust = o.double("wgst")
        visibility = o.text("visib")
        qnh = o.double("altim")
        clouds = ((o["clouds"] as? [JSONObject]) ?? []).compactMap { c in c.string("cover").map { Cloud(cover: $0, base: c.int("base")) } }
        category = o.string("fltCat")
    }
}

struct RadarFrame: Hashable, Sendable {
    var time: Millis
    /// Tile URL template with {z}/{x}/{y}.
    var template: String
}

/// An international SIGMET: a hazard area (turbulence, icing, storms, ash, cyclone) between two levels.
struct Sigmet: Hashable, Sendable {
    var hazard: String
    var qualifier: String?
    /// Feet; either can be missing.
    var base: Int?
    var top: Int?
    var validTo: Millis?
    /// Usually one area; a SIGMET can cover several.
    var areas: [[Coord]]

    init(hazard: String, qualifier: String? = nil, base: Int? = nil, top: Int? = nil, validTo: Millis? = nil, areas: [[Coord]] = []) {
        self.hazard = hazard; self.qualifier = qualifier; self.base = base; self.top = top; self.validTo = validTo; self.areas = areas
    }

    /// aviationweather.gov's `coords` is a list of {lat, lon}, or a list of such lists for several areas.
    init?(_ o: JSONObject) {
        guard let hazard = o.string("hazard") else { return nil }
        func points(_ list: [JSONObject]) -> [Coord] { list.compactMap { p in p.double("lat").flatMap { lat in p.double("lon").map { Coord(lat: lat, lon: $0) } } } }
        let areas: [[Coord]] = if let one = o["coords"] as? [JSONObject] { [points(one)] }
            else { ((o["coords"] as? [[JSONObject]]) ?? []).map(points) }
        self.init(hazard: hazard, qualifier: o.string("qualifier"), base: o.int("base"), top: o.int("top"),
                  validTo: o.double("validTimeTo").map { Millis($0 * 1000) }, areas: areas)
    }

    /// "Severe turbulence" over "FL250–FL380". Flight levels stay in feet whatever the units, as pilots use them.
    var label: String {
        let sev = qualifier == "SEV" ? "Severe " : ""
        let name = switch hazard {
        case "TURB": "\(sev)turbulence"
        case "ICE": "\(sev)icing"
        case "TS": "Thunderstorms"
        case "VA": "Volcanic ash"
        case "TC": "Cyclone \((qualifier ?? "").capitalized)".trimmingCharacters(in: .whitespaces)
        case "MTW": "\(sev)mountain waves"
        default: hazard
        }
        func fl(_ ft: Int) -> String { ft <= 0 ? "SFC" : String(format: "FL%03d", ft / 100) }
        let levels: String? = switch (base, top) {
        case let (b?, t?): "\(fl(b))–\(fl(t))"
        case let (nil, t?): "Up to \(fl(t))"
        case let (b?, nil): "Above \(fl(b))"
        default: nil
        }
        return ([name.prefix(1).uppercased() + name.dropFirst()] + [levels].compactMap { $0 }).joined(separator: "\n")
    }
}

/// aviationweather.gov's public data API, RainViewer's public radar and Open-Meteo wind (CC BY 4.0); none needs a key.
actor WeatherService {
    private var metars: [String: (Metar?, Millis)] = [:]
    private var sigmetCache: ([Sigmet], Millis)?
    private var windCache: [Int64: (WindSample, Millis)] = [:]
    private var windStep = 0.0
    private var windBlockedUntil: Millis = 0

    /// Worldwide international SIGMETs in force, cached 10 min.
    func sigmets() async throws -> [Sigmet] {
        let now = Millis.now
        if let (s, at) = sigmetCache, now - at < 10 * 60_000 { return s }
        let (data, code) = try await HTTP.get(URL(string: "https://aviationweather.gov/api/data/isigmet?format=json")!)
        let list = code == 204 ? [] : ((try JSONSerialization.jsonObject(with: data) as? [JSONObject]) ?? []).compactMap(Sigmet.init)
        sigmetCache = (list, now)
        return list
    }

    /// Wind at every level for the grid `points` (indices at `step`°). Points fetched in the last hour are reused;
    /// the rest come in one Open-Meteo request. Returns what it has even if the request fails.
    func wind(_ points: [(Int, Int)], step: Double) async -> WindGrid {
        let now = Millis.now
        if step != windStep { windCache = [:]; windStep = step }
        windCache = windCache.filter { now - $0.value.1 < 3_600_000 }
        let missing = Array(points.filter { windCache[WindGrid.key($0.0, $0.1, step)] == nil }.prefix(WindGrid.maxPoints))
        if !missing.isEmpty, now >= windBlockedUntil {
            let coords = missing.map { WindGrid.latLon($0.0, $0.1, step) }
            let vars = WindLevel.allCases.flatMap { [$0.speedVar, $0.dirVar] }.joined(separator: ",")
            let url = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(coords.map { String(format: "%.2f", $0.lat) }.joined(separator: ","))"
                + "&longitude=\(coords.map { String(format: "%.2f", $0.lon) }.joined(separator: ","))&current=\(vars)&wind_speed_unit=kn")!
            do {
                let (data, _) = try await HTTP.get(url)
                let json = try JSONSerialization.jsonObject(with: data)
                let rows = (json as? [JSONObject]) ?? [json as? JSONObject].compactMap { $0 }
                for (row, p) in zip(rows, missing) {
                    guard let cur = row["current"] as? JSONObject else { continue }
                    var s = WindSample(speedKt: [:], fromDeg: [:])
                    for l in WindLevel.allCases { s.speedKt[l] = cur.double(l.speedVar); s.fromDeg[l] = cur.double(l.dirVar) }
                    windCache[WindGrid.key(p.0, p.1, step)] = (s, now)
                }
            } catch let e as HTTP.Status where e.code == 429 {
                // Over the daily limit: stop asking for a while rather than hammering it.
                windBlockedUntil = now + 15 * 60_000
            } catch {}
        }
        return WindGrid(step: step, samples: windCache.mapValues(\.0))
    }

    func metar(_ icao: String) async throws -> Metar? {
        let now = Millis.now
        if let (m, at) = metars[icao], now - at < 10 * 60_000 { return m }
        let (data, code) = try await HTTP.get(URL(string: "https://aviationweather.gov/api/data/metar?ids=\(icao)&format=json")!)
        // 204: no report for this station.
        let m = code == 204 || data.isEmpty ? nil : ((try JSONSerialization.jsonObject(with: data) as? [JSONObject])?.first).flatMap(Metar.init)
        metars[icao] = (m, now)
        return m
    }

    /// Past radar frames, oldest first.
    func radarFrames() async throws -> [RadarFrame] {
        let (data, _) = try await HTTP.get(URL(string: "https://api.rainviewer.com/public/weather-maps.json")!)
        let o = try JSON.object(data)
        guard let host = o.string("host"), let past = (o["radar"] as? JSONObject)?["past"] as? [JSONObject] else { return [] }
        return past.compactMap { f in
            guard let t = f.double("time"), let path = f.string("path") else { return nil }
            return RadarFrame(time: Millis(t * 1000), template: "\(host)\(path)/256/{z}/{x}/{y}/2/1_1.png")
        }
    }
}
