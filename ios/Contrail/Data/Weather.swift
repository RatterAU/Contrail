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

/// aviationweather.gov's public data API and RainViewer's public radar; neither needs a key.
actor WeatherService {
    private var metars: [String: (Metar?, Millis)] = [:]

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
