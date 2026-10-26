import Foundation

enum AltitudeUnit: String, CaseIterable, Codable, Sendable { case feet, metres }
enum SpeedUnit: String, CaseIterable, Codable, Sendable { case knots, kmh, mph }
enum DistanceUnit: String, CaseIterable, Codable, Sendable { case nm, km, mi }
enum ClockStyle: String, CaseIterable, Codable, Sendable { case system, h12, h24 }

struct Units: Hashable, Codable, Sendable {
    var altitude: AltitudeUnit = .feet
    var speed: SpeedUnit = .knots
    var distance: DistanceUnit = .km
    var clock: ClockStyle = .system
}

/// Display formatting in the chosen units. Inputs are always aviation units (ft, kt, nm, ft/min).
struct Fmt: Sendable {
    static let none = "—"
    var units = Units()

    private static let grouped: IntegerFormatStyle<Int> = .number.locale(Locale(identifier: "en_US"))

    func altitude(_ a: Aircraft) -> String {
        if a.onGround { return "Ground" }
        return a.altBaro.map { feet($0) } ?? Fmt.none
    }

    func feet(_ ft: Int?) -> String {
        guard let ft else { return Fmt.none }
        if units.altitude == .metres { return "\(Int((Double(ft) * 0.3048).rounded()).formatted(Fmt.grouped)) m" }
        return "\(ft.formatted(Fmt.grouped)) ft"
    }

    func knots(_ kt: Double?) -> String {
        guard let kt else { return Fmt.none }
        switch units.speed {
        case .knots: return "\(Int(kt.rounded())) kt"
        case .kmh: return "\(Int((kt * 1.852).rounded())) km/h"
        case .mph: return "\(Int((kt * 1.15078).rounded())) mph"
        }
    }

    func nm(_ d: Double?) -> String {
        guard let d else { return Fmt.none }
        let (v, u): (Double, String) = switch units.distance {
        case .nm: (d, "nm")
        case .km: (d * 1.852, "km")
        case .mi: (d * 1.15078, "mi")
        }
        return v < 10 ? String(format: "%.1f %@", v, u) : "\(Int(v.rounded())) \(u)"
    }

    func mach(_ m: Double?) -> String { m.map { String(format: "M%.2f", $0) } ?? Fmt.none }
    func degrees(_ d: Double?) -> String { d.map { "\((Int($0.rounded()) % 360 + 360) % 360)°" } ?? Fmt.none }
    func celsius(_ c: Double?) -> String { c.map { "\(Int($0.rounded())) °C" } ?? Fmt.none }
    func hpa(_ h: Double?) -> String { h.map { String(format: "%.1f hPa", $0) } ?? Fmt.none }

    func verticalRate(_ fpm: Double?) -> String {
        guard let fpm else { return Fmt.none }
        if abs(fpm) < 64 { return "Level" }
        let sign = fpm > 0 ? "+" : "−"
        if units.altitude == .metres { return String(format: "%@%.1f m/s", sign, abs(fpm) * 0.00508) }
        let r = Int((fpm / 64).rounded()) * 64
        return "\(sign)\(abs(r).formatted(Fmt.grouped)) ft/min"
    }

    /// Clock time in `zone` (an airport's), honouring the 12/24-hour setting.
    func time(_ ms: Millis, zone: TimeZone = .current) -> String {
        var style = Date.FormatStyle(date: .omitted, time: .shortened)
        style.timeZone = zone
        switch units.clock {
        case .system: break
        case .h12: style = Date.FormatStyle(timeZone: zone).hour(.defaultDigits(amPM: .abbreviated)).minute(.twoDigits)
        case .h24:
            style = Date.FormatStyle(timeZone: zone).hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)
            style.locale = Locale(identifier: "en_GB")
        }
        return ms.date.formatted(style)
    }

    /// The time, plus "Melbourne time" when that zone's offset differs from the phone's (else nil). `place` (the
    /// airport's city) names it: the bundled zone ids can name a neighbouring city (MEL is Australia/Hobart).
    func clockParts(_ ms: Millis, tz: String?, place: String?) -> (time: String, zone: String?) {
        let zone = tz.flatMap(TimeZone.init(identifier:)) ?? .current
        let date = ms.date
        let differs = zone.secondsFromGMT(for: date) != TimeZone.current.secondsFromGMT(for: date)
        let name = place ?? zone.identifier.split(separator: "/").last.map { $0.replacingOccurrences(of: "_", with: " ") } ?? zone.identifier
        return (time(ms, zone: zone), differs ? "\(name) time" : nil)
    }

    static func ago(_ ms: Millis) -> String {
        switch ms {
        case ..<2_000: "now"
        case ..<60_000: "\(ms / 1000)s ago"
        case ..<3_600_000: "\(ms / 60_000)m ago"
        default: "\(ms / 3_600_000)h ago"
        }
    }

    static func duration(_ ms: Millis) -> String {
        let m = max(ms / 60_000, 0)
        return m < 60 ? "\(m)m" : "\(m / 60)h \(m % 60)m"
    }
}
