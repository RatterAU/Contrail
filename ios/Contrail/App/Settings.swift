import Foundation
import Observation

enum MapAppearance: String, CaseIterable, Codable, Sendable { case auto, dark, light }
enum MapKind: String, CaseIterable, Codable, Sendable { case muted, standard, satellite }

struct MapPrefs: Hashable, Codable, Sendable {
    var appearance = MapAppearance.auto
    var kind = MapKind.muted
    var radar = false
    var airports = true
    var labels = true
    var allTrails = false
    var filters = Filters()
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
