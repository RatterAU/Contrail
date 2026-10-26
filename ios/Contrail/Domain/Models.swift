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
        let s = [manufacturer, type].compactMap { $0 }.joined(separator: " ")
        return s.isEmpty ? nil : s
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
}
