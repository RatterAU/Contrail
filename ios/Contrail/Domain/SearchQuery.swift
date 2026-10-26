import Foundation

/// A feed lookup that a free-text query could mean.
enum RemoteQuery: Hashable, Sendable {
    case hex(String)
    case callsign(String)
    case registration(String)
    case type(String)
    case squawk(String)
}

enum SearchQuery {
    nonisolated(unsafe) private static let hex = /^[0-9A-F]{6}$/
    nonisolated(unsafe) private static let icaoFlight = /^[A-Z]{3}[0-9]{1,4}[A-Z]{0,2}$/
    nonisolated(unsafe) private static let iataFlight = /^([A-Z0-9]{2})([0-9]{1,4}[A-Z]?)$/
    nonisolated(unsafe) private static let reg = /^[A-Z0-9]{1,3}-[A-Z0-9]{1,5}$|^N[0-9][0-9A-Z]{1,4}$|^[A-Z]{1,2}[0-9]{4,6}$/
    nonisolated(unsafe) private static let type = /^[A-Z][A-Z0-9]{1,3}$/
    nonisolated(unsafe) private static let squawk = /^[0-7]{4}$/

    /// Everything `raw` might be. "QF1" becomes callsign QFA1 via the airline table; "A388" is both a possible type
    /// and hex-less text, so several lookups can run for one query.
    static func classify(_ raw: String, icaoForIata: (String) -> [String]) -> [RemoteQuery] {
        let q = raw.trimmingCharacters(in: .whitespaces).uppercased().replacingOccurrences(of: " ", with: "")
        guard q.count >= 2 else { return [] }
        var out: [RemoteQuery] = []
        func add(_ r: RemoteQuery) { if !out.contains(r) { out.append(r) } }
        let isSquawk = q.wholeMatch(of: squawk) != nil
        if isSquawk { add(.squawk(q)) }
        if q.wholeMatch(of: hex) != nil { add(.hex(q)) }
        if let m = q.wholeMatch(of: iataFlight) {
            let number = String(m.2).drop(while: { $0 == "0" })
            for icao in icaoForIata(String(m.1)).prefix(3) { add(.callsign(icao + number)) }
        }
        if q.wholeMatch(of: icaoFlight) != nil { add(.callsign(q)) }
        if q.wholeMatch(of: reg) != nil { add(.registration(q)) }
        if q.wholeMatch(of: type) != nil && !isSquawk { add(.type(q)) }
        return out
    }
}
