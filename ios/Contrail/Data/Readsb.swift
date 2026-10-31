import Foundation

/// A loosely typed JSON object. The feeds mix numbers and strings in one field ("alt_baro": "ground" | 36000), so
/// JSONSerialization is simpler (and faster) here than Codable.
typealias JSONObject = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func double(_ k: String) -> Double? { (self[k] as? NSNumber)?.doubleValue }
    func int(_ k: String) -> Int? { (self[k] as? NSNumber).map { Int($0.doubleValue) } }
    func string(_ k: String) -> String? {
        guard let s = self[k] as? String else { return nil }
        let t = s.trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? nil : t
    }
    /// A number or a string, as text ("year" comes both ways).
    func text(_ k: String) -> String? { string(k) ?? (self[k] as? NSNumber).map { "\($0.intValue)" } }
}

enum JSON {
    static func object(_ data: Data) throws -> JSONObject {
        guard let o = try JSONSerialization.jsonObject(with: data) as? JSONObject else { throw URLError(.cannotParseResponse) }
        return o
    }
}

/// readsb v2 JSON, shared by adsb.lol and adsb.fi. Point queries name the list "aircraft" on adsb.fi, "ac" elsewhere.
func parseReadsb(_ data: Data, receivedAt: Millis) throws -> [Aircraft] {
    let res = try JSON.object(data)
    // The phone's clock, not the feed's "now": ages and dead-reckoning compare against it, so a phone a minute or two
    // out would otherwise see every plane as long gone.
    let list = (res["ac"] as? [JSONObject]) ?? (res["aircraft"] as? [JSONObject]) ?? []
    return list.compactMap { aircraft(from: $0, now: receivedAt) }
}

func parseReadsb(_ json: String, receivedAt: Millis) throws -> [Aircraft] {
    try parseReadsb(Data(json.utf8), receivedAt: receivedAt)
}

private func aircraft(from o: JSONObject, now: Millis) -> Aircraft? {
    guard let hexRaw = o["hex"] as? String,
          let lat = o.double("lat") ?? o.double("rr_lat"),
          let lon = o.double("lon") ?? o.double("rr_lon") else { return nil }
    let ground = (o["alt_baro"] as? String) == "ground"
    let seen = o.double("seen") ?? 0
    let seenPos = o.double("seen_pos") ?? seen
    return Aircraft(
        hex: hexRaw.lowercased().replacingOccurrences(of: "~", with: ""),
        callsign: o.string("flight"),
        registration: o.string("r"),
        typeCode: o.string("t"),
        description: o.string("desc"),
        operator: o.string("ownOp"),
        year: o.text("year"),
        lat: lat,
        lon: lon,
        altBaro: ground ? 0 : o.int("alt_baro"),
        onGround: ground,
        altGeom: o.int("alt_geom"),
        groundSpeed: o.double("gs"),
        ias: o.double("ias"), tas: o.double("tas"), mach: o.double("mach"),
        track: o.double("track") ?? o.double("true_heading"),
        trackRate: o.double("track_rate"), roll: o.double("roll"),
        magHeading: o.double("mag_heading"), trueHeading: o.double("true_heading"),
        baroRate: o.double("baro_rate"), geomRate: o.double("geom_rate"),
        squawk: o.string("squawk"),
        emergency: o.string("emergency").flatMap { $0 == "none" ? nil : $0 },
        category: o.string("category"),
        navQnh: o.double("nav_qnh"), navAltMcp: o.int("nav_altitude_mcp"), navAltFms: o.int("nav_altitude_fms"),
        navHeading: o.double("nav_heading"), navModes: (o["nav_modes"] as? [String]) ?? [],
        windDir: o.double("wd"), windSpeed: o.double("ws"), oat: o.double("oat"), tat: o.double("tat"),
        source: PositionSource.from(o["type"] as? String, rough: o.double("lat") == nil),
        nic: o.int("nic"), nacP: o.int("nac_p"), sil: o.int("sil"), adsbVersion: o.int("version"),
        rssi: o.double("rssi"), messages: o.double("messages").map { Int64($0) },
        dbFlags: o.int("dbFlags") ?? 0,
        positionTime: now - Millis(seenPos * 1000),
        seenTime: now - Millis(seen * 1000)
    )
}
