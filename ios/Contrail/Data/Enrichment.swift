import Foundation

/// Keyless lookups: adsbdb.com (aircraft and callsign route), adsb.lol (route legs) and airport-data.com (photos). Results are cached in
/// memory per (hex, callsign); concurrent callers share one request.
actor EnrichmentService {
    private var cache: [String: Enrichment] = [:]
    private var order: [String] = []
    private var inFlight: [String: Task<Enrichment, Never>] = [:]
    private var routeCache: [String: (Route?, Millis)] = [:]

    func cached(_ hex: String, _ callsign: String?) -> Enrichment? { cache[key(hex, callsign)] }

    func lookup(_ hex: String, _ callsign: String?) async -> Enrichment {
        let k = key(hex, callsign)
        if let e = cache[k] { return e }
        if let t = inFlight[k] { return await t.value }
        let t = Task {
            async let db = try? EnrichmentService.adsbdb(hex, callsign)
            async let photos = (try? EnrichmentService.airportDataPhotos(hex)) ?? []
            async let lol = EnrichmentService.legs(callsign)
            let (info, route, fallback) = await db ?? (nil, nil, nil)
            let p = await photos
            let legs = await lol.map { leg in
                var l = leg
                l.flightNumber = route?.flightNumber ?? leg.flightNumber
                l.airline = route?.airline ?? leg.airline
                return l
            }
            return Enrichment(info: info, route: route, photos: p.isEmpty ? [fallback].compactMap { $0 } : p, legs: legs)
        }
        inFlight[k] = t
        let e = await t.value
        inFlight[k] = nil
        cache[k] = e
        order.append(k)
        if order.count > 300 { cache[order.removeFirst()] = nil }
        return e
    }

    /// Route by callsign alone (for airport boards). Cached 6 h, including "unknown" answers; errors aren't cached.
    func route(for callsign: String) async -> Route? {
        let now = Millis.now
        if let (r, at) = routeCache[callsign], now - at < 6 * 3_600_000 { return r }
        do {
            let r = try await EnrichmentService.callsignRoute(callsign)
            routeCache[callsign] = (r, now)
            return r
        } catch {
            return nil
        }
    }

    func cachedRoute(_ callsign: String) -> Route?? { routeCache[callsign].map(\.0) }

    private func key(_ hex: String, _ cs: String?) -> String { "\(hex)|\(cs ?? "")" }

    private static func url(_ s: String) -> URL? { URL(string: s) }
    private static func path(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? s }

    private static func adsbdb(_ hex: String, _ callsign: String?) async throws -> (AircraftInfo?, Route?, Photo?) {
        let base = "https://api.adsbdb.com/v0/aircraft/\(hex)"
        var data: Data
        do {
            (data, _) = try await HTTP.get(url(base + (callsign.map { "?callsign=\(path($0))" } ?? ""))!)
        } catch let e as HTTP.Status where e.code == 404 && callsign != nil {
            // adsbdb 404s the whole request when only the callsign is unknown; retry for the aircraft alone.
            (data, _) = try await HTTP.get(url(base)!)
        }
        guard let response = try JSON.object(data)["response"] as? JSONObject else { return (nil, nil, nil) }
        let ac = response["aircraft"] as? JSONObject
        let info = ac.map {
            AircraftInfo(registration: $0.string("registration"), manufacturer: $0.string("manufacturer"), type: $0.string("type"),
                         icaoType: $0.string("icao_type"), owner: $0.string("registered_owner"), ownerCountry: $0.string("registered_owner_country_name"))
        }
        let photo = ac?.string("url_photo").flatMap(url).map { full in
            Photo(thumbURL: ac?.string("url_photo_thumbnail").flatMap(url) ?? full, fullURL: full, photographer: nil, pageURL: nil, site: "airport-data.com")
        }
        return (info, (response["flightroute"] as? JSONObject).flatMap(route), photo)
    }

    private static func callsignRoute(_ callsign: String) async throws -> Route? {
        do {
            let (data, _) = try await HTTP.get(url("https://api.adsbdb.com/v0/callsign/\(path(callsign))")!)
            return ((try JSON.object(data)["response"] as? JSONObject)?["flightroute"] as? JSONObject).flatMap(route)
        } catch let e as HTTP.Status where e.code == 404 {
            return nil
        }
    }

    /// adsb.lol's legs for a callsign; none if it's unknown (404) or the request fails.
    private static func legs(_ callsign: String?) async -> [Route] {
        guard let callsign, let (data, _) = try? await HTTP.get(url("https://api.adsb.lol/api/0/route/\(path(callsign))")!) else { return [] }
        return parseAdsbLolLegs(data)
    }

    /// adsb.lol `/api/0/route/{callsign}`: its `_airports` in flight order, as one route per leg.
    static func parseAdsbLolLegs(_ data: Data) -> [Route] {
        guard let o = try? JSON.object(data), let cs = o.string("callsign") else { return [] }
        let stops: [Airport] = ((o["_airports"] as? [JSONObject]) ?? []).compactMap { a in
            guard let name = a.string("name"), let lat = a.double("lat"), let lon = a.double("lon") else { return nil }
            let icao = a.string("icao")
            // The bundled table adds time zone and size; keep the API's coordinates either way.
            let s = icao.flatMap { StaticData.now()?.airport($0) }
            return Airport(icao: icao, iata: a.string("iata"), name: name, city: a.string("location") ?? s?.city, country: s?.country,
                           lat: lat, lon: lon, elevationFt: a.int("alt_feet"), tz: s?.tz, size: s?.size)
        }
        let airline = o.string("airline_code").flatMap { StaticData.now()?.airline($0) }
        let number = o.string("number").flatMap { n in airline?.iata.map { $0 + n } }
        return zip(stops, stops.dropFirst()).map { Route(callsign: cs, flightNumber: number, airline: airline, origin: $0, destination: $1) }
    }

    private static func route(_ o: JSONObject) -> Route? {
        guard let origin = (o["origin"] as? JSONObject).flatMap(airport),
              let dest = (o["destination"] as? JSONObject).flatMap(airport),
              let cs = o.string("callsign_icao") ?? o.string("callsign") else { return nil }
        let airline = (o["airline"] as? JSONObject).flatMap { a in
            a.string("name").map { Airline(name: $0, icao: a.string("icao"), iata: a.string("iata"), country: a.string("country")) }
        }
        return Route(callsign: cs, flightNumber: o.string("callsign_iata"), airline: airline, origin: origin, destination: dest)
    }

    private static func airport(_ o: JSONObject) -> Airport? {
        guard let name = o.string("name"), let lat = o.double("latitude"), let lon = o.double("longitude") else { return nil }
        let icao = o.string("icao_code")
        // Time zone and size come from the bundled table.
        let s = icao.flatMap { StaticData.now()?.airport($0) }
        return Airport(icao: icao, iata: o.string("iata_code"), name: name, city: o.string("municipality") ?? s?.city,
                       country: o.string("country_name"), lat: lat, lon: lon, elevationFt: o.int("elevation"), tz: s?.tz, size: s?.size)
    }

    private static func airportDataPhotos(_ hex: String) async throws -> [Photo] {
        let (data, _) = try await HTTP.get(url("https://www.airport-data.com/api/ac_thumb.json?m=\(hex.uppercased())&n=5")!)
        let items = (try JSON.object(data)["data"] as? [JSONObject]) ?? []
        return items.compactMap { p in
            // Thumbnails live at /images/aircraft/thumbnails/001/672/001672596.jpg; the full image shares the id.
            guard let image = p.string("image"), let thumb = url(image) else { return nil }
            let id = thumb.deletingPathExtension().lastPathComponent
            guard let full = url("https://image.airport-data.com/aircraft/\(id).jpg") else { return nil }
            return Photo(thumbURL: thumb, fullURL: full, photographer: p.string("photographer"), pageURL: p.string("link").flatMap(url), site: "airport-data.com")
        }
    }
}

/// One earlier flight: airports are ICAO idents from the bundled table, nil where the plane wasn't seen there. Times
/// are takeoff and landing when seen; otherwise only when the plane was first or last seen, good for the date alone.
struct PastFlight: Equatable, Sendable {
    var callsign: String?
    var from: String?
    var departed: Millis
    var to: String?
    var arrived: Millis
    var departedSeen = true
    var arrivedSeen = true
}

/// The path a plane flew before the app saw it, from OpenSky's anonymous tracks endpoint (keyless; ~400 requests a
/// day, so it's fetched once per tapped plane and cached). The readsb feeds' own trace files are bot-protected.
///
/// Earlier flights (up to yesterday, 14 days) come from this repo's own GitHub Pages: tools/history.py turns
/// adsb.lol's daily open-data dump into one small file per first four hex digits.
actor FlightHistory {
    private var cache: [String: ([TrailPoint]?, Millis)] = [:]
    /// By shard, not plane: nearby planes often share one (Australian hexes all start 7c).
    private var pastCache: [String: (String, Millis)] = [:]

    /// Earlier flights, newest first; empty if none are known, nil if the request failed.
    func past(_ hex: String) async -> [PastFlight]? {
        let now = Millis.now, h = hex.lowercased(), shard = String(h.prefix(4))
        if let (text, at) = pastCache[shard], now - at < 3_600_000 { return FlightHistory.parsePastFlights(text, hex: h) } // files change daily
        guard let url = URL(string: "https://ratterau.github.io/Contrail/h/\(shard).txt") else { return nil }
        let text: String
        do {
            let (data, _) = try await HTTP.get(url)
            text = String(decoding: data, as: UTF8.self)
        } catch let e as HTTP.Status where e.code == 404 {
            text = "" // nothing in this shard
        } catch {
            return nil
        }
        pastCache[shard] = (text, now)
        return FlightHistory.parsePastFlights(text, hex: h)
    }

    /// Lines are `hex  callsign  from  departed  to  arrived`, tab-separated, times in unix minutes, "~" if not seen.
    static func parsePastFlights(_ text: String, hex: String) -> [PastFlight] {
        text.split(separator: "\n").compactMap { line -> PastFlight? in
            guard line.hasPrefix(hex) else { return nil }
            let c = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard c.count >= 6, c[0] == hex, let t0 = Millis(c[3].trimmingPrefix("~")), let t1 = Millis(c[5].trimmingPrefix("~")) else { return nil }
            return PastFlight(callsign: c[1].isEmpty ? nil : c[1], from: c[2].isEmpty ? nil : c[2], departed: t0 * 60_000,
                              to: c[4].isEmpty ? nil : c[4], arrived: t1 * 60_000, departedSeen: !c[3].hasPrefix("~"), arrivedSeen: !c[5].hasPrefix("~"))
        }
        .sorted { $0.departed > $1.departed }
    }
    private static let cacheMs: Millis = 10 * 60_000
    private static let mToFt = 3.28084

    /// The current flight's path, oldest first, or nil if unknown, stale or the request failed.
    func fetch(_ hex: String) async -> [TrailPoint]? {
        let now = Millis.now
        if let (pts, at) = cache[hex], now - at < FlightHistory.cacheMs { return pts }
        guard let url = URL(string: "https://opensky-network.org/api/tracks/all?icao24=\(hex.lowercased())&time=0"),
              let (data, _) = try? await HTTP.get(url) else { return nil } // offline or rate-limited: retry next tap
        let pts = FlightHistory.parseOpenSkyTrack(data, now: now)
        cache[hex] = (pts, now)
        return pts
    }

    /// OpenSky path rows are [time s, lat, lon, baro alt m, track, onGround]. Coverage gaps (oceans, outback) are
    /// bridged along the great circle so the line stays whole. A track that ended over 15 min ago belongs to an
    /// earlier flight and is ignored.
    static func parseOpenSkyTrack(_ data: Data, now: Millis) -> [TrailPoint]? {
        guard let path = (try? JSON.object(data))?["path"] as? [[Any]] else { return nil }
        let raw: [TrailPoint] = path.compactMap { r in
            guard r.count >= 6, let t = (r[0] as? NSNumber)?.int64Value, let lat = (r[1] as? NSNumber)?.doubleValue,
                  let lon = (r[2] as? NSNumber)?.doubleValue else { return nil }
            let alt = (r[3] as? NSNumber).map { Int($0.doubleValue * mToFt) }
            return TrailPoint(lat: lat, lon: lon, alt: alt, onGround: (r[5] as? Bool) ?? false, gs: nil, time: t * 1000)
        }
        guard raw.count >= 2, let last = raw.last, now - last.time <= 15 * 60_000 else { return nil }
        // OpenSky's fixes jitter: altitude by a few hundred feet, timestamps by seconds. A median of three removes
        // altitude spikes; speed is measured over at least 2 minutes so it doesn't saw up and down.
        let clean = raw.indices.map { i -> TrailPoint in
            var p = raw[i]
            if i > 0, i < raw.count - 1, let a = raw[i - 1].alt, let b = p.alt, let c = raw[i + 1].alt { p.alt = [a, b, c].sorted()[1] }
            return p
        }
        var out: [TrailPoint] = []
        out.reserveCapacity(raw.count)
        var from = 0
        for (i, p) in clean.enumerated() {
            while from < i - 1 && p.time - clean[from + 1].time >= 120_000 { from += 1 }
            let gs = i > 0 ? speedKt(clean[from], p) : nil
            if i > 0 {
                let prev = clean[i - 1]
                if abs(p.lat - prev.lat) > 0.5 || abs(p.lon - prev.lon) * cos(Geo.rad(p.lat)) > 0.5 {
                    let dist = Geo.distanceNm(prev.lat, prev.lon, p.lat, p.lon)
                    let steps = min(max(Int(dist / 20), 1), 100)
                    let brg = Geo.bearing(prev.lat, prev.lon, p.lat, p.lon)
                    for s in 1..<max(steps, 1) where steps > 1 {
                        let c = Geo.destination(prev.lat, prev.lon, brg, dist * Double(s) / Double(steps))
                        let alt = prev.alt.flatMap { a in p.alt.map { a + ($0 - a) * s / steps } } ?? p.alt
                        out.append(TrailPoint(lat: c.lat, lon: c.lon, alt: alt, onGround: false, gs: gs,
                                              time: prev.time + (p.time - prev.time) * Millis(s) / Millis(steps)))
                    }
                }
            }
            var q = p
            q.gs = gs ?? (clean.count > 1 ? speedKt(p, clean[1]) : nil)
            out.append(q)
        }
        return out
    }

    private static func speedKt(_ a: TrailPoint, _ b: TrailPoint) -> Double? {
        let dt = Double(b.time - a.time) / 3_600_000
        return dt > 0 ? Geo.distanceNm(a.lat, a.lon, b.lat, b.lon) / dt : nil
    }
}
