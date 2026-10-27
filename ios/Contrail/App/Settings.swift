import Foundation
import Observation

enum MapAppearance: String, CaseIterable, Codable, Sendable { case auto, dark, light }
enum MapKind: String, CaseIterable, Codable, Sendable { case muted, standard, satellite }

struct MapPrefs: Hashable, Codable, Sendable {
    var appearance = MapAppearance.auto
    var kind = MapKind.muted
    var radar = false
    /// Satellite cloud imagery (infrared, so it works at night).
    var clouds = false
    /// SIGMET hazard areas: turbulence, icing, storms, volcanic ash, cyclones.
    var hazards = false
    /// Animated wind at this level; nil = off.
    var wind: WindLevel?
    var airports = true
    var labels = true
    var allTrails = false
    var filters = Filters()
}

extension MapPrefs {
    /// Keys missing from older saved settings take their defaults instead of failing the whole decode.
    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = MapPrefs()
        appearance = try c.decodeIfPresent(MapAppearance.self, forKey: .appearance) ?? d.appearance
        kind = try c.decodeIfPresent(MapKind.self, forKey: .kind) ?? d.kind
        radar = try c.decodeIfPresent(Bool.self, forKey: .radar) ?? d.radar
        clouds = try c.decodeIfPresent(Bool.self, forKey: .clouds) ?? d.clouds
        hazards = try c.decodeIfPresent(Bool.self, forKey: .hazards) ?? d.hazards
        wind = try c.decodeIfPresent(WindLevel.self, forKey: .wind)
        airports = try c.decodeIfPresent(Bool.self, forKey: .airports) ?? d.airports
        labels = try c.decodeIfPresent(Bool.self, forKey: .labels) ?? d.labels
        allTrails = try c.decodeIfPresent(Bool.self, forKey: .allTrails) ?? d.allTrails
        filters = try c.decodeIfPresent(Filters.self, forKey: .filters) ?? d.filters
    }
}

/// Preferences, saved to UserDefaults as JSON on every change. Each group is its own property so a view that reads
/// only `units` isn't re-rendered by a filter change.
@MainActor @Observable
final class Settings {
    var map: MapPrefs { didSet { save(map, "map") } }
    var units: Units { didSet { save(units, "units"); Fmt.current = Fmt(units: units) } }

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        map = Settings.load("map", defaults) ?? MapPrefs()
        units = Settings.load("units", defaults) ?? Units()
        Fmt.current = Fmt(units: units)
    }

    private static func load<T: Decodable>(_ key: String, _ d: UserDefaults) -> T? {
        d.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private func save<T: Encodable>(_ v: T, _ key: String) {
        defaults.set(try? JSONEncoder().encode(v), forKey: key)
    }
}

extension Fmt {
    /// The formatter in the user's units. Set by Settings on the main actor; read anywhere it's needed for display.
    @MainActor static var current = Fmt()
}
