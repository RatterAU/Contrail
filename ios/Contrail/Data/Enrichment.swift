import Foundation

/// Keyless lookups: adsbdb.com (aircraft and callsign route), adsb.lol (route legs) and airport-data.com (photos). Results are cached in
/// memory per (hex, callsign); concurrent callers share one request.
actor EnrichmentService {
    private var cache: [String: Enrichment] = [:]
    private var order: [String] = []
    private var inFlight: [String: Task<(Enrichment, complete: Bool), Never>] = [:]
    private var routeCache: [String: (Route?, Millis)] = [:]

    func cached(_ hex: String, _ callsign: String?) -> Enrichment? { cache[key(hex, callsign)] }

    /// `partial` gets adsbdb's answer as soon as it's in, before adsb.lol's slower legs.
    func lookup(_ hex: String, _ callsign: String?, partial: (@Sendable (Enrichment) async -> Void)? = nil) async -> Enrichment {
        let k = key(hex, callsign)
        if let e = cache[k] { return e }
        if let t = inFlight[k] { return await t.value.0 }
        let t = Task { () -> (Enrichment, complete: Bool) in
            async let db = EnrichmentService.retrying { try await EnrichmentService.adsbdb(hex, callsign) }
            async let photos = (try? EnrichmentService.airportDataPhotos(hex)) ?? []
            async let lol: [Route]? = callsign == nil ? [] : EnrichmentService.retrying { try await EnrichmentService.legs(callsign!) }
            async let seen: [Route] = callsign == nil ? [] : await self.seenRoutes(callsign!)
            let answer = await db
            let (info, listed, fallback) = answer ?? (nil, nil, nil)
            // adsbdb rejects the whole request when it doesn't know the airframe (VOZ092's 7cad46), route included.
            var route = listed
            var routeFailed = false
            if route == nil, let callsign {
                if let r = await EnrichmentService.retrying({ try await EnrichmentService.callsignRoute(callsign) }) { route = r } else { routeFailed = true }
            }
            let p = await photos
            if callsign != nil {
                await partial?(Enrichment(info: info, route: route, photos: p.isEmpty ? [fallback].compactMap { $0 } : p, legsPending: true))
            }
            let found = await lol
            // Routes this callsign was seen flying lately come after the listed ones: published routes go stale.
            let extra = await seen.filter { r in !(found ?? []).contains { $0.origin.icao == r.origin.icao && $0.destination.icao == r.destination.icao } }
            let legs = ((found ?? []) + extra).map { leg in
                var l = leg
                l.flightNumber = route?.flightNumber ?? leg.flightNumber
                l.airline = route?.airline ?? leg.airline
                return l
            }
            return (Enrichment(info: info, route: route, photos: p.isEmpty ? [fallback].compactMap { $0 } : p, legs: legs),
                    answer != nil && found != nil && !routeFailed)
        }
        inFlight[k] = t
        let (e, complete) = await t.value
        inFlight[k] = nil
        // A source that failed (busy, timed out, offline) isn't an answer: left uncached, the next look asks again.
        guard complete else { return e }
        cache[k] = e
        order.append(k)
        if order.count > 300 { cache[order.removeFirst()] = nil }
        return e
    }

    /// `block` up to three times, 2 s then 5 s apart; nil if every try failed. adsb.lol answers 429 when the feed
    /// polling has used its share, and adsbdb times out under load.
    private static func retrying<T: Sendable>(_ block: @Sendable () async throws -> T) async -> T? {
        for wait in [0, 2, 5] {
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return nil } }
            // do/catch, not try?: try? would flatten a T that is itself optional, turning "not known" into a failure.
            do { return try await block() } catch { continue }
        }
        return nil
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
            do {
                (data, _) = try await HTTP.get(url(base)!)
            } catch let e as HTTP.Status where e.code == 404 {
                return (nil, nil, nil) // airframe unknown too
            }
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

    private var seenCache: [String: (String, Millis)] = [:]

    /// Routes `callsign` was seen flying (both ends) at least twice in the last 14 days, most flown first, from this
    /// repo's daily history (tools/history.py). One file per first four characters, shared by nearby flight numbers.
    private func seenRoutes(_ callsign: String) async -> [Route] {
        guard callsign.count >= 4, callsign.allSatisfy({ $0.isLetter || $0.isNumber }), let data = StaticData.now() else { return [] }
        let prefix = String(callsign.prefix(4))
        let now = Millis.now
        var text = seenCache[prefix].flatMap { now - $0.1 < 3_600_000 ? $0.0 : nil }
        if text == nil {
            do {
                let (body, _) = try await HTTP.get(URL(string: "https://ratterau.github.io/Contrail/r/\(prefix).txt")!)
                text = String(decoding: body, as: UTF8.self)
            } catch let e as HTTP.Status where e.code == 404 {
                text = ""
            } catch {
                return []
            }
            seenCache[prefix] = (text!, now)
        }
        return Self.parseSeenRoutes(text!, callsign: callsign, airline: data.airlineForCallsign(callsign), airport: data.airport)
    }

    /// Lines `callsign  from  to  times-seen`, most flown first; `airport` looks up the bundled table.
    static func parseSeenRoutes(_ text: String, callsign: String, airline: Airline?, airport: (String) -> Airport?) -> [Route] {
        text.split(separator: "\n").map { $0.split(separator: "\t", omittingEmptySubsequences: false).map(String.init) }
            .filter { $0.count >= 4 && $0[0] == callsign }
            .sorted { (Int($0[3]) ?? 0) > (Int($1[3]) ?? 0) }
            .compactMap { c in
                guard let o = airport(c[1]), let d = airport(c[2]) else { return nil }
                return Route(callsign: callsign, flightNumber: nil, airline: airline, origin: o, destination: d)
            }
    }

    /// adsb.lol's legs for a callsign; none if it's unknown (404). Throws when it couldn't answer (429, 5xx, offline).
    private static func legs(_ callsign: String) async throws -> [Route] {
        do {
            let (data, _) = try await HTTP.get(url("https://api.adsb.lol/api/0/route/\(path(callsign))")!)
            return parseAdsbLolLegs(data)
        } catch let e as HTTP.Status where e.code == 404 {
            return []
        }
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
/// are takeoff and landing when seen; otherwise when the plane was first or last seen. `scheduledFrom` and
/// `scheduledTo` fill an unseen end from the callsign's published route, only where the flown path agrees with it.
struct PastFlight: Equatable, Sendable {
    var callsign: String?
    var from: String?
    var departed: Millis
    var to: String?
    var arrived: Millis
    var departedSeen = true
    var arrivedSeen = true
    /// Where this plane's trace for each day of the flight sits in adsb.lol's daily release ("YYYYMMDD.N.aa.offset.size").
    var traces: [String] = []
    var scheduledFrom: String?
    var scheduledTo: String?
}

/// Figures from a past flight's path. Top speed ignores impossible reports and the fastest 1%, where glitches show.
struct PastStats: Equatable, Sendable {
    var distanceNm: Double
    var topAltFt: Int?
    var topSpeedKt: Double?
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

    private var pathCache: [String: [TrailPoint]] = [:]

    /// The path `f` flew, fetched straight from adsb.lol's daily open-data release: an HTTP range request for just this
    /// plane's day (a few KB), not the 4 GB file. Empty if it wasn't recorded, nil if a download failed.
    func path(_ f: PastFlight) async -> [TrailPoint]? {
        var all: [TrailPoint] = []
        for ref in f.traces {
            if let hit = pathCache[ref] { all += hit; continue }
            let c = ref.split(separator: ".")
            guard let url = FlightHistory.traceURL(ref), let offset = Int(c[3]), let size = Int(c[4]) else { return [] }
            var req = URLRequest(url: url)
            req.setValue("bytes=\(offset)-\(offset + size - 1)", forHTTPHeaderField: "Range")
            // A full 4 GB answer (not 206) would be wrong; never read it.
            guard let (data, res) = try? await HTTP.shared.data(for: req), (res as? HTTPURLResponse)?.statusCode == 206,
                  let json = FlightHistory.gunzip(data) else { return nil }
            let pts = FlightHistory.parseTrace(json)
            if pathCache.count > 20 { pathCache.removeAll() }
            pathCache[ref] = pts
            all += pts
        }
        return FlightHistory.slice(all, f)
    }

    /// "YYYYMMDD.N.aa.offset.size" to that part's download URL on adsb.lol's globe_history release.
    static func traceURL(_ ref: String) -> URL? {
        let c = ref.split(separator: ".").map(String.init)
        guard c.count == 5, c[0].count == 8 else { return nil }
        let d = Array(c[0])
        let tag = "v\(String(d[0..<4])).\(String(d[4..<6])).\(String(d[6..<8]))-planes-readsb-prod-\(c[1])"
        return URL(string: "https://github.com/adsblol/globe_history_\(String(d[0..<4]))/releases/download/\(tag)/\(tag).tar.\(c[2])")
    }

    /// Foundation has raw DEFLATE (`.zlib`) but no gzip: skip the gzip header and trailer around it.
    static func gunzip(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { return nil }
        let flags = b[3]
        var i = 10
        if flags & 4 != 0, i + 2 <= b.count { i += 2 + Int(b[i]) + Int(b[i + 1]) << 8 }
        if flags & 8 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 16 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 2 != 0 { i += 2 }
        guard i < b.count - 8 else { return nil }
        return try? (Data(b[i..<(b.count - 8)]) as NSData).decompressed(using: .zlib) as Data
    }

    /// A readsb trace_full file: `timestamp` plus rows of [secs after it, lat, lon, alt ft | "ground" | null, gs, ...].
    static func parseTrace(_ data: Data) -> [TrailPoint] {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let base = (o["timestamp"] as? NSNumber)?.doubleValue, let rows = o["trace"] as? [[Any]] else { return [] }
        return rows.compactMap { r in
            guard r.count > 4, let secs = (r[0] as? NSNumber)?.doubleValue, let lat = (r[1] as? NSNumber)?.doubleValue,
                  let lon = (r[2] as? NSNumber)?.doubleValue else { return nil }
            let ground = (r[3] as? String) == "ground"
            return TrailPoint(lat: lat, lon: lon, alt: ground ? 0 : (r[3] as? NSNumber)?.intValue, onGround: ground,
                              gs: (r[4] as? NSNumber)?.doubleValue, time: Millis((base + secs) * 1000))
        }
    }

    /// The part of a day's (or two days') trace that is this flight: from 10 min before it was first seen to 10 min after.
    static func slice(_ pts: [TrailPoint], _ f: PastFlight) -> [TrailPoint] {
        smooth(pts.filter { $0.time >= f.departed - 600_000 && $0.time <= f.arrived + 600_000 }.sorted { $0.time < $1.time })
    }

    /// A single glitched report (an altitude thousands of feet off, a speed spike) takes the median of its neighbours:
    /// altitude over three airborne points, ground speed over five.
    static func smooth(_ pts: [TrailPoint]) -> [TrailPoint] {
        pts.indices.map { i in
            var p = pts[i]
            guard !p.onGround else { return p }
            let alts = (i - 1...i + 1).compactMap { j in pts.indices.contains(j) && !pts[j].onGround ? pts[j].alt : nil }
            let speeds = (i - 2...i + 2).compactMap { j in pts.indices.contains(j) ? pts[j].gs : nil }
            if p.alt != nil && alts.count == 3 { p.alt = alts.sorted()[1] }
            if p.gs != nil && speeds.count >= 3 { p.gs = speeds.sorted()[speeds.count / 2] }
            return p
        }
    }

    static func stats(_ pts: [TrailPoint]) -> PastStats {
        let air = pts.filter { !$0.onGround }
        // Over 750 kt is a glitch: an airliner riding the strongest jet stream peaks around 700.
        let speeds = air.compactMap(\.gs).filter { $0 <= 750 }.sorted()
        return PastStats(
            distanceNm: zip(pts, pts.dropFirst()).reduce(0) { $0 + Geo.distanceNm($1.0.lat, $1.0.lon, $1.1.lat, $1.1.lon) },
            topAltFt: air.compactMap(\.alt).max(),
            topSpeedKt: speeds.isEmpty ? nil : speeds[min(Int(Double(speeds.count) * 0.99), speeds.count - 1)])
    }

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

    /// Lines are `hex  callsign  from  departed  to  arrived  traces  scheduled-from  scheduled-to`, tab-separated, times
    /// in unix minutes, "~" if not seen. Older files stop after traces.
    static func parsePastFlights(_ text: String, hex: String) -> [PastFlight] {
        text.split(separator: "\n").compactMap { line -> PastFlight? in
            guard line.hasPrefix(hex) else { return nil }
            let c = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard c.count >= 6, c[0] == hex, let t0 = Millis(c[3].trimmingPrefix("~")), let t1 = Millis(c[5].trimmingPrefix("~")) else { return nil }
            return PastFlight(callsign: c[1].isEmpty ? nil : c[1], from: c[2].isEmpty ? nil : c[2], departed: t0 * 60_000,
                              to: c[4].isEmpty ? nil : c[4], arrived: t1 * 60_000, departedSeen: !c[3].hasPrefix("~"), arrivedSeen: !c[5].hasPrefix("~"),
                              traces: c.count > 6 ? c[6].split(separator: " ").map(String.init) : [],
                              scheduledFrom: c.count > 7 && !c[7].isEmpty ? c[7] : nil, scheduledTo: c.count > 8 && !c[8].isEmpty ? c[8] : nil)
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
