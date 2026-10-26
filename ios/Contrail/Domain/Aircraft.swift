import Foundation

enum PositionSource: String, Sendable {
    case adsb = "ADS-B", adsr = "ADS-R", mlat = "MLAT", tisb = "TIS-B", modeS = "Mode S", adsc = "ADS-C"
    case rough = "Approximate", other = "Other"

    static func from(_ type: String?, rough: Bool) -> PositionSource {
        if rough { return .rough }
        guard let type else { return .other }
        if type.hasPrefix("adsb") { return .adsb }
        if type.hasPrefix("adsr") { return .adsr }
        if type.hasPrefix("tisb") { return .tisb }
        switch type {
        case "mlat": return .mlat
        case "mode_s": return .modeS
        case "adsc": return .adsc
        default: return .other
        }
    }
}

/// One aircraft as last reported by a feed. Units are aviation-native: ft, kt, ft/min, degrees.
struct Aircraft: Hashable, Sendable {
    var hex: String
    var callsign: String?
    var registration: String?
    var typeCode: String?
    var description: String?
    var `operator`: String?
    var year: String?
    var lat: Double
    var lon: Double
    var altBaro: Int?
    var onGround: Bool
    var altGeom: Int?
    var groundSpeed: Double?
    var ias: Double?
    var tas: Double?
    var mach: Double?
    var track: Double?
    var trackRate: Double?
    var roll: Double?
    var magHeading: Double?
    var trueHeading: Double?
    var baroRate: Double?
    var geomRate: Double?
    var squawk: String?
    var emergency: String?
    var category: String?
    var navQnh: Double?
    var navAltMcp: Int?
    var navAltFms: Int?
    var navHeading: Double?
    var navModes: [String] = []
    var windDir: Double?
    var windSpeed: Double?
    var oat: Double?
    var tat: Double?
    var source: PositionSource = .other
    var nic: Int?
    var nacP: Int?
    var sil: Int?
    var adsbVersion: Int?
    var rssi: Double?
    var messages: Int64?
    var dbFlags: Int = 0
    /// When lat/lon was measured.
    var positionTime: Millis
    /// The last message of any kind.
    var seenTime: Millis

    var coord: Coord { Coord(lat: lat, lon: lon) }
    var military: Bool { dbFlags & 1 != 0 }
    var interesting: Bool { dbFlags & 2 != 0 }
    var verticalRate: Double? { baroRate ?? geomRate }
    var label: String { callsign ?? registration ?? hex.uppercased() }
    var isEmergency: Bool { emergency != nil || squawk.map(Aircraft.emergencySquawks.contains) == true }

    static let emergencySquawks: Set<String> = ["7500", "7600", "7700"]
}
