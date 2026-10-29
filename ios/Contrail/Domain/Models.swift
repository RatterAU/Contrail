import Foundation

struct Airport: Hashable, Sendable, Identifiable {
    var icao: String?
    var iata: String?
    var name: String
    var city: String?
    var country: String?
    var lat: Double
    var lon: Double
    var elevationFt: Int?
    /// IANA zone, e.g. "Australia/Adelaide"; from the bundled airport table.
    var tz: String?
    /// "L"arge, "M"edium or "S"mall, from OurAirports.
    var size: Character?

    var id: String { icao ?? iata ?? "\(lat),\(lon)" }
    var coord: Coord { Coord(lat: lat, lon: lon) }
    var code: String { (iata?.isEmpty == false ? iata : nil) ?? icao ?? "?" }
    var shortName: String { (city?.isEmpty == false ? city : nil) ?? name }

    func matches(_ other: Airport) -> Bool {
        (icao != nil && icao == other.icao) || (iata?.isEmpty == false && iata == other.iata)
    }
}

struct Airline: Hashable, Sendable {
    var name: String
    var icao: String?
    var iata: String?
    var country: String?
}

struct Route: Hashable, Sendable {
    var callsign: String
    var flightNumber: String?
    var airline: Airline?
    var origin: Airport
    var destination: Airport
}

struct Runway: Hashable, Sendable {
    var airportIcao: String
    var leIdent: String
    var heIdent: String
    var lengthFt: Int?
    var surface: String
    var leHeading: Double?
    var heHeading: Double?
}

struct AircraftInfo: Hashable, Sendable {
    var registration: String?
    var manufacturer: String?
    var type: String?
    var icaoType: String?
    var owner: String?
    var ownerCountry: String?

    var fullName: String? {
        let maker = manufacturer.map(Self.shortMaker).flatMap { m in type?.lowercased().hasPrefix(m.lowercased()) == true ? nil : m }
        let s = [maker, type].compactMap { $0 }.joined(separator: " ")
        return s.isEmpty ? nil : s
    }

    /// The registry's manufacturer as people say it: "Embraer - Empresa Brasileira De Aeronautica S.a." is Embraer,
    /// "Piper Aircraft, Inc." is Piper, "Avions de Transport Regional" is ATR.
    static func shortMaker(_ name: String) -> String {
        var s = String(name.components(separatedBy: " - ")[0]).trimmingCharacters(in: .whitespaces)
        if s.caseInsensitiveCompare("Avions de Transport Regional") == .orderedSame { return "ATR" }
        let suffix = /(?i),?\s+(Inc\.?|Corp\.?|Corporation|Company|Industries|Aircraft|S\.a\.|GmbH|Ltd\.?)$/
        while let m = s.firstMatch(of: suffix), m.range.lowerBound > s.startIndex {
            s = String(s[..<m.range.lowerBound])
        }
        return s
    }
}

struct Photo: Hashable, Sendable, Identifiable {
    var thumbURL: URL
    var fullURL: URL
    var photographer: String?
    var pageURL: URL?
    var site: String

    var id: URL { fullURL }
}

struct Enrichment: Hashable, Sendable {
    var info: AircraftInfo?
    var route: Route?
    var photos: [Photo]
    /// Each leg of the callsign's route from adsb.lol (SYD-SIN-LHR is two), for when `route` doesn't fit or is missing.
    var legs: [Route] = []
    /// adsbdb answered (about 1 s) but adsb.lol's legs haven't (it can take several seconds).
    var legsPending = false
}
