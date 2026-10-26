import Foundation

/// Keyless lookups: adsbdb.com (aircraft and callsign route) and airport-data.com (photos). Results are cached in
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
            let (info, route, fallback) = await db ?? (nil, nil, nil)
            let p = await photos
            return Enrichment(info: info, route: route, photos: p.isEmpty ? [fallback].compactMap { $0 } : p)
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

/// The path a plane flew before the app saw it, from OpenSky's anonymous tracks endpoint (keyless; ~400 requests a
/// day, so it's fetched once per tapped plane and cached). The readsb feeds' own trace files are bot-protected.
actor FlightHistory {
    private var cache: [String: ([TrailPoint]?, Millis)] = [:]
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
