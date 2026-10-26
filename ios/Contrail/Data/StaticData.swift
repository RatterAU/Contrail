import Foundation
import Synchronization

/// Bundled reference data (OurAirports + OpenFlights), shared with the Android app's assets folder. ~18k airports,
/// held in memory, loaded once in the background at launch.
final class StaticData: Sendable {
    let airports: [Airport]
    private let runwaysByIcao: [String: [Runway]]
    private let airlinesByIcao: [String: Airline]
    private let activeAirlines: Set<String>
    private let byIcao: [String: Airport]
    private let byIata: [String: Airport]
    private let airlinesByIata: [String: [Airline]]
    private let named: [Airport]
    private let major: [Airport]

    init(airports: [Airport], runways: [Runway], airlines: [Airline], active: Set<String>) {
        self.airports = airports
        runwaysByIcao = Dictionary(grouping: runways, by: \.airportIcao)
        airlinesByIcao = Dictionary(airlines.compactMap { a in a.icao.map { ($0, a) } }, uniquingKeysWith: { a, _ in a })
        activeAirlines = active
        byIcao = Dictionary(airports.compactMap { a in a.icao.map { ($0, a) } }, uniquingKeysWith: { a, _ in a })
        byIata = Dictionary(airports.compactMap { a in a.iata.map { ($0, a) } }, uniquingKeysWith: { a, _ in a })
        // A few rows carry a description instead of a town ("Private Airfield in the Shire of Campaspe").
        named = airports.filter { c in c.city.map { !$0.isEmpty && !["Airfield", "Airport", "Shire of"].contains(where: $0.contains) } ?? false }
        major = airports.filter { $0.size == "L" || $0.size == "M" }
        airlinesByIata = Dictionary(grouping: airlines.filter { $0.iata?.isEmpty == false }, by: { $0.iata! })
    }

    func airport(_ code: String) -> Airport? { byIcao[code.uppercased()] ?? byIata[code.uppercased()] }
    func runways(_ icao: String) -> [Runway] { runwaysByIcao[icao] ?? [] }
    func airline(_ icao: String) -> Airline? { airlinesByIcao[icao.uppercased()] }

    /// The airline ICAO prefix of an airline-style callsign ("QLK12" gives QLK), known to the table or not. For logos.
    static func airlineCode(_ callsign: String?) -> String? {
        guard let cs = callsign, cs.count >= 4 else { return nil }
        let chars = Array(cs.utf8)
        guard chars[3] >= 48 && chars[3] <= 57, chars[0..<3].allSatisfy({ $0 >= 65 && $0 <= 90 }) else { return nil }
        return String(cs.prefix(3))
    }

    /// The operating airline from an airline-style callsign ("QFA123"), only for airlines still flying.
    func airlineForCallsign(_ callsign: String?) -> Airline? {
        guard let code = StaticData.airlineCode(callsign), activeAirlines.contains(code) else { return nil }
        return airlinesByIcao[code]
    }

    /// Nearest airport with a town name, for "12 km NE of Gawler" (unnamed strips would give "Private Airfield in
    /// the Shire of Campaspe"). Scans every row; call off the main actor.
    func nearestPlace(_ lat: Double, _ lon: Double) -> Airport? {
        let k = cos(Geo.rad(lat))
        return named.min { a, b in
            let da = (a.lat - lat) * (a.lat - lat) + (a.lon - lon) * k * (a.lon - lon) * k
            let db = (b.lat - lat) * (b.lat - lat) + (b.lon - lon) * k * (b.lon - lon) * k
            return da < db
        }
    }

    /// The closest large or medium airport within `nm`, for reading a route off a flown path.
    func majorAirport(near lat: Double, _ lon: Double, withinNm nm: Double) -> Airport? {
        let best = major.min { Geo.distanceNm(lat, lon, $0.lat, $0.lon) < Geo.distanceNm(lat, lon, $1.lat, $1.lon) }
        return best.flatMap { Geo.distanceNm(lat, lon, $0.lat, $0.lon) <= nm ? $0 : nil }
    }

    /// IATA airline code to candidate ICAO codes (IATA codes are reused, so there can be several).
    func icaoForIataAirline(_ iata: String) -> [String] { (airlinesByIata[iata.uppercased()] ?? []).compactMap(\.icao) }

    func searchAirports(_ q: String, limit: Int = 8) -> [Airport] {
        let u = q.trimmingCharacters(in: .whitespaces).uppercased()
        guard u.count >= 2 else { return [] }
        var out = [byIata[u], byIcao[u]].compactMap { $0 }
        let rank: (Airport) -> Int = { $0.size == "L" ? 0 : $0.size == "M" ? 1 : 2 }
        let rest = airports.filter { a in
            !out.contains(a) && (a.name.uppercased().contains(u) || a.city?.uppercased().contains(u) == true)
        }.sorted { rank($0) < rank($1) }
        for a in rest where out.count < limit && !out.contains(a) { out.append(a) }
        return Array(out.prefix(limit))
    }

    // MARK: Loading

    private static let ready = Mutex<StaticData?>(nil)
    private static let loader = Task.detached(priority: .userInitiated) { () -> StaticData in
        let d = load(Bundle.main)
        ready.withLock { $0 = d }
        return d
    }

    static func preload() { _ = loader }
    static func get() async -> StaticData { await loader.value }
    /// Loaded data, or nil while it's still loading.
    static func now() -> StaticData? { ready.withLock { $0 } }

    static func rows(_ name: String, in bundle: Bundle) -> [[Substring]] {
        guard let url = bundle.url(forResource: name, withExtension: "tsv", subdirectory: "assets") ?? bundle.url(forResource: name, withExtension: "tsv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return text.split(separator: "\n", omittingEmptySubsequences: true).map { $0.split(separator: "\t", omittingEmptySubsequences: false) }
    }

    static func load(_ bundle: Bundle) -> StaticData {
        func opt(_ s: Substring) -> String? { s.isEmpty ? nil : String(s) }
        let airports = rows("airports", in: bundle).compactMap { r -> Airport? in
            guard r.count >= 10, let lat = Double(r[5]), let lon = Double(r[6]) else { return nil }
            return Airport(icao: String(r[0]), iata: opt(r[1]), name: String(r[2]), city: opt(r[3]), country: opt(r[4]),
                           lat: lat, lon: lon, elevationFt: Int(r[7]), tz: opt(r[9]), size: r[8].first)
        }
        let runways = rows("runways", in: bundle).compactMap { r -> Runway? in
            guard r.count >= 7 else { return nil }
            return Runway(airportIcao: String(r[0]), leIdent: String(r[1]), heIdent: String(r[2]), lengthFt: Int(r[3]),
                          surface: String(r[4]), leHeading: Double(r[5]), heHeading: Double(r[6]))
        }
        let airlineRows = rows("airlines", in: bundle)
        let airlines = airlineRows.compactMap { r -> Airline? in
            guard r.count >= 5 else { return nil }
            return Airline(name: String(r[2]), icao: String(r[0]), iata: opt(r[1]), country: opt(r[4]))
        }
        let active = Set(airlineRows.filter { $0.count > 5 && $0[5] == "Y" }.map { String($0[0]) })
        return StaticData(airports: airports, runways: runways, airlines: airlines, active: active)
    }
}
