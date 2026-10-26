import Foundation

enum AircraftKind: String, CaseIterable, Codable, Sendable, Identifiable {
    case airliner, light, helicopter, military, glider, ground

    var id: Self { self }

    var label: String {
        switch self {
        case .airliner: "Airliners"
        case .light: "Light & GA"
        case .helicopter: "Helicopters"
        case .military: "Military"
        case .glider: "Gliders & balloons"
        case .ground: "Ground vehicles"
        }
    }

    var symbol: String {
        switch self {
        case .airliner: "airplane"
        case .light: "airplane.circle"
        case .helicopter: "fanblades"
        case .military: "shield.lefthalf.filled"
        case .glider: "wind"
        case .ground: "car"
        }
    }

    static func of(_ a: Aircraft) -> AircraftKind {
        if a.military { return .military }
        switch a.category {
        case "A1", "B4": return .light
        case "A7": return .helicopter
        case "B1", "B2", "B3": return .glider
        case "C1", "C2", "C3", "C4", "C5": return .ground
        default: return .airliner
        }
    }
}

struct Filters: Hashable, Codable, Sendable {
    static let maxAlt = 50_000

    var altMinFt = 0
    var altMaxFt = Filters.maxAlt
    var hideGround = false
    var kinds = Set(AircraftKind.allCases)
    /// Only military, emergency or "interesting" (database-flagged) aircraft.
    var specialOnly = false

    var active: Bool { self != Filters() }

    func matches(_ a: Aircraft) -> Bool {
        if a.isEmergency { return true } // never hide an emergency
        if hideGround && a.onGround { return false }
        if !kinds.contains(AircraftKind.of(a)) { return false }
        if specialOnly && !(a.military || a.interesting) { return false }
        if !a.onGround {
            guard let alt = a.altBaro else { return altMinFt == 0 && altMaxFt == Filters.maxAlt }
            if alt < altMinFt { return false }
            if altMaxFt < Filters.maxAlt && alt > altMaxFt { return false }
        }
        return true
    }
}

/// Plain-English meanings for transponder codes and emitter categories.
enum Squawks {
    static func meaning(_ sq: String) -> String? {
        switch sq {
        case "7500": "Hijack"
        case "7600": "Radio failure"
        case "7700": "General emergency"
        case "7000": "VFR (Europe/Australia)"
        case "1200": "VFR (US/Canada)"
        case "2000": "Entering controlled airspace"
        case "7400": "Lost datalink (drone)"
        case "1000": "IFR, Mode S conspicuity"
        default: nil
        }
    }

    static func category(_ c: String?) -> String {
        switch c {
        case "A1": "Light (< 7 t)"
        case "A2": "Small (7–34 t)"
        case "A3": "Large (34–136 t)"
        case "A4": "High-vortex large (B757)"
        case "A5": "Heavy (> 136 t)"
        case "A6": "High performance"
        case "A7": "Rotorcraft"
        case "B1": "Glider"
        case "B2": "Lighter than air"
        case "B3": "Parachutist"
        case "B4": "Ultralight"
        case "B6": "Drone (UAV)"
        case "B7": "Space vehicle"
        case "C1": "Emergency vehicle"
        case "C2": "Service vehicle"
        case "C3": "Ground obstacle"
        case nil, "A0", "B0", "C0", "D0": "Not reported"
        case let other?: other
        }
    }
}
