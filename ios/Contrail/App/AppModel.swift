import Foundation
import Observation

/// Live picture of all known aircraft, keyed by ICAO hex. An actor so merges from overlapping polls (viewport,
/// selected plane, airport, nearby) apply one at a time, off the main thread.
actor TrafficStore {
    static let fadeAfterMs: Millis = 30_000
    static let dropAfterMs: Millis = 90_000

    private var tracks: [String: Track] = [:]
    /// Hexes kept even when they leave the polled area (the selected plane).
    var pinned: Set<String> = []

    func setPinned(_ p: Set<String>) { pinned = p }

    func merge(_ reports: [Aircraft], now: Millis) -> [String: Track] {
        tracks = tracks.filter { now - $0.value.aircraft.seenTime < TrafficStore.dropAfterMs || pinned.contains($0.key) }
        for a in reports { tracks[a.hex] = tracks[a.hex]?.updated(with: a, now: now) ?? Track.start(a, now: now) }
        return tracks
    }

    func addHistory(_ hex: String, _ pts: [TrailPoint]) -> [String: Track] {
        if let t = tracks[hex] { tracks[hex] = t.withHistory(pts) }
        return tracks
    }
}

enum Selection: Hashable {
    case aircraft(String)
    case airport(Airport)
}

struct Viewport: Hashable {
    var center: Coord
    var radiusNm: Int
}

enum FeedStatus { case connecting, live, offline }

/// Panels shown over the main sheet.
enum Panel: String, Identifiable {
    case layers, filters, settings, about
    var id: String { rawValue }
}

struct AirportState {
    var airport: Airport
    var runways: [Runway]
    var metar: Metar?
    var weatherLoaded = false
    var routes: [String: Route?] = [:]
    var routesLoaded = false
}

struct SearchResults {
    var aircraft: [Aircraft]
    var airports: [Airport]
    var failed: Bool
}

@MainActor @Observable
final class AppModel {
    let settings = Settings()
    let location = LocationService()

    @ObservationIgnored let store = TrafficStore()
    @ObservationIgnored let enrichment = EnrichmentService()
    @ObservationIgnored let history = FlightHistory()
    @ObservationIgnored let weather = WeatherService()
    @ObservationIgnored private(set) var scheduler: FeedScheduler!

    private(set) var tracks: [String: Track] = [:]
    private(set) var status = FeedStatus.connecting
    private(set) var health: [String: FeedHealth] = [:]

    private(set) var selection: Selection?
    /// Route, photos and registry info for the selected plane.
    /// A past flight opened from the sheet; `path` is nil while downloading and empty if it wasn't recorded.
    struct PastView: Equatable {
        var flight: PastFlight
        var path: [TrailPoint]?
        var failed = false
    }
    /// Drawn on the map in place of the live trail and route, framed once when its path arrives.
    private(set) var pastView: PastView?
    @ObservationIgnored private var pastTask: Task<Void, Never>?

    func openPast(_ f: PastFlight) {
        pastTask?.cancel()
        following = false
        scrub = nil
        pastView = PastView(flight: f)
        map.selectionChanged()
        pastTask = Task {
            let path = await history.path(f)
            guard !Task.isCancelled, pastView?.flight == f else { return }
            pastView = PastView(flight: f, path: path ?? [], failed: path == nil)
            map.pastLoaded()
        }
    }

    func closePast() {
        guard pastView != nil else { return }
        pastTask?.cancel()
        pastView = nil
        scrub = nil
        map.selectionChanged()
    }

    private(set) var details: Enrichment?
    private(set) var detailsLoading = false

    func route(for t: Track, now: Millis) -> Route? {
        FlightProgress.shownRoute(t, listed: details?.route, legs: details?.legs ?? [], now: now)
    }
    private(set) var airportState: AirportState?
    var following = false
    /// Point on the selected plane's trail being inspected in the profile chart.
    var scrub: TrailPoint?
    var panel: Panel?
    var showNearby = false
    var showSky = false
    private(set) var radarFrames: [RadarFrame] = []
    /// The 10-minute slot of satellite clouds to show; nil when the layer is off.
    private(set) var cloudBucket: Int64?
    private(set) var sigmets: [Sigmet] = []
    private(set) var windGrid: WindGrid?
    /// The visible map, set when the camera stops; drives which wind points are fetched.
    var windArea: Area?
    /// True from the start of a camera move until it settles; the wind particles hide meanwhile.
    var mapMoving = false
    /// The view is wider than the feeds can serve (250 nm around the centre), so most of it has no traffic.
    var viewTooWide = false
    /// Height of the main sheet, so the map can keep the selected plane in the part of the screen still visible.
    @ObservationIgnored var sheetHeight: Double = 0

    /// The map, created once and kept for the app's life.
    @ObservationIgnored private(set) lazy var map = MapCoordinator(model: self)
    /// Called after every traffic merge so the map draws new data straight away.
    @ObservationIgnored var onTraffic: (() -> Void)?

    @ObservationIgnored var viewport: Viewport? {
        didSet { if viewport != oldValue { restartViewportPolling() } }
    }
    /// False while something covers the map (Sky view), so it isn't polled or drawn for nothing.
    @ObservationIgnored var mapVisible = true {
        didSet { if mapVisible != oldValue { restartViewportPolling(); map.setVisible(mapVisible) } }
    }
    @ObservationIgnored private var active = false
    @ObservationIgnored private var viewportTask: Task<Void, Never>?

    init() {
        scheduler = FeedScheduler([AdsbSource(endpoints: .adsbLol), AdsbSource(endpoints: .adsbFi)], onHealth: { [weak self] h in
            Task { @MainActor in self?.health = h }
        })
        StaticData.preload()
    }

    var selectedHex: String? { if case .aircraft(let h) = selection { h } else { nil } }
    var selectedTrack: Track? { selectedHex.flatMap { tracks[$0] } }

    // MARK: Lifecycle

    func setActive(_ on: Bool) {
        guard on != active else { return }
        active = on
        if on { location.start() } else { location.stop() }
        restartViewportPolling()
    }

    // MARK: Selection

    func select(_ s: Selection?) {
        guard s != selection else { return }
        selection = s
        pastTask?.cancel()
        pastView = nil
        scrub = nil
        details = nil
        following = false
        if case .airport(let a) = s {
            airportState = AirportState(airport: a, runways: a.icao.flatMap { StaticData.now()?.runways($0) } ?? [])
        } else {
            airportState = nil
        }
        let pin = selectedHex.map { Set([$0]) } ?? []
        Task { await store.setPinned(pin) }
        map.selectionChanged()
    }

    // MARK: Polling

    private func restartViewportPolling() {
        viewportTask?.cancel()
        guard active, mapVisible, let v = viewport else { return }
        viewportTask = Task {
            // Debounce: a pan fires many camera changes; poll once it settles.
            try? await Task.sleep(for: .milliseconds(350))
            while !Task.isCancelled {
                await refresh { try await $0.point(lat: v.center.lat, lon: v.center.lon, nm: v.radiusNm) }
                // A wide view returns hundreds of aircraft that barely move on screen: poll it less often.
                try? await Task.sleep(for: .seconds(v.radiusNm > 120 ? 6 : 3))
            }
        }
    }

    @discardableResult
    func refresh(_ fetch: @escaping @Sendable (any FeedSource) async throws -> [Aircraft]) async -> [Aircraft] {
        do {
            let reports = try await scheduler.run(fetch)
            apply(await store.merge(reports, now: .now))
            status = .live
            return reports
        } catch is CancellationError {
            return []
        } catch {
            if !Task.isCancelled { status = .offline }
            return []
        }
    }

    private func apply(_ snapshot: [String: Track]) {
        tracks = snapshot
        onTraffic?()
    }

    /// While an aircraft sheet is open: keep the plane fresh if it's outside the polled area, load its details
    /// (again if the callsign changes) and, once, the path it flew before we saw it.
    func followSelected(_ hex: String) async {
        await withTaskGroup(of: Void.self) { g in
            g.addTask { await self.loadHistory(hex) }
            g.addTask {
                var lastCallsign: String?? = .none
                while !Task.isCancelled {
                    let t = await self.tracks[hex]
                    if t == nil || t!.staleness(.now) > 8_000 { await self.refresh { try await $0.query(.hex(hex)) } }
                    let cs = await self.tracks[hex]?.aircraft.callsign
                    if lastCallsign != .some(cs) {
                        lastCallsign = .some(cs)
                        await self.loadDetails(hex, cs)
                    }
                    try? await Task.sleep(for: .seconds(3))
                }
            }
        }
    }

    private func loadDetails(_ hex: String, _ callsign: String?) async {
        if let c = await enrichment.cached(hex, callsign) {
            details = c
            return
        }
        detailsLoading = true
        let e = await enrichment.lookup(hex, callsign) { [weak self] p in await self?.showPartial(hex, p) }
        guard selectedHex == hex else { return }
        details = e
        detailsLoading = false
        map.selectionChanged()
    }

    private func showPartial(_ hex: String, _ e: Enrichment) {
        guard selectedHex == hex, details == nil else { return }
        details = e
        map.selectionChanged()
    }

    private func loadHistory(_ hex: String) async {
        // Wait for the first fix if it was opened from search before the plane arrived.
        for _ in 0..<20 where tracks[hex] == nil { try? await Task.sleep(for: .milliseconds(250)) }
        guard tracks[hex]?.aircraft.onGround == false, let pts = await history.fetch(hex), selectedHex == hex else { return }
        apply(await store.addHistory(hex, pts))
    }

    nonisolated static let boardRadiusNm = 250

    /// While an airport sheet is open: its weather, the traffic around it, and routes for the nearest flights.
    func followAirport(_ ap: Airport) async {
        await withTaskGroup(of: Void.self) { g in
            g.addTask { await self.loadWeather(ap) }
            g.addTask {
                while !Task.isCancelled {
                    await self.refresh { try await $0.point(lat: ap.lat, lon: ap.lon, nm: AppModel.boardRadiusNm) }
                    let near = await self.tracks.values
                        .compactMap { t -> (String, Double)? in
                            guard let cs = t.aircraft.callsign else { return nil }
                            let d = Geo.distanceNm(ap.coord, t.aircraft.coord)
                            return d < Double(AppModel.boardRadiusNm) ? (cs, d) : nil
                        }
                        .sorted { $0.1 < $1.1 }.prefix(60).map(\.0)
                    let routes = await self.routes(for: Array(near))
                    await MainActor.run {
                        guard self.airportState?.airport == ap else { return }
                        self.airportState?.routes.merge(routes) { _, new in new }
                        self.airportState?.routesLoaded = true
                    }
                    try? await Task.sleep(for: .seconds(20))
                }
            }
        }
    }

    private func routes(for callsigns: [String]) async -> [String: Route?] {
        let service = enrichment
        return await withTaskGroup(of: (String, Route?).self) { g in
            var out: [String: Route?] = [:]
            var it = callsigns.makeIterator()
            // Four at a time: adsbdb is free; don't hammer it.
            for _ in 0..<4 { if let cs = it.next() { g.addTask { (cs, await service.route(for: cs)) } } }
            while let (cs, r) = await g.next() {
                out[cs] = r
                if let next = it.next() { g.addTask { (next, await service.route(for: next)) } }
            }
            return out
        }
    }

    private func loadWeather(_ ap: Airport) async {
        guard let icao = ap.icao else {
            airportState?.weatherLoaded = true
            return
        }
        // One retry: a cold first request sometimes fails, and that would read as "no weather report".
        var m = try? await weather.metar(icao)
        if m == nil { m = try? await weather.metar(icao) }
        guard airportState?.airport == ap else { return }
        airportState?.metar = m ?? nil
        airportState?.weatherLoaded = true
    }

    /// While Nearby or Sky view is open, keep the area around the user fresh.
    func pollNearby() async {
        while !Task.isCancelled {
            if let l = location.location?.coordinate {
                await refresh { try await $0.point(lat: l.latitude, lon: l.longitude, nm: 60) }
            }
            try? await Task.sleep(for: .seconds(5))
        }
    }

    /// The last hour of radar, refreshed every 5 minutes while the layer is on.
    func pollRadar() async {
        guard settings.map.radar else {
            radarFrames = []
            return
        }
        while !Task.isCancelled {
            if let f = try? await weather.radarFrames() { radarFrames = Array(f.suffix(8)) }
            try? await Task.sleep(for: .seconds(300))
        }
    }

    /// Satellite clouds, moved on to the newest image every 10 minutes while the layer is on.
    func pollClouds() async {
        guard settings.map.clouds else { cloudBucket = nil; return }
        while !Task.isCancelled {
            cloudBucket = Millis.now / 600_000
            do { try await Task.sleep(for: .seconds(600)) } catch { return }
        }
    }

    /// SIGMET areas, refreshed every 10 minutes while the layer is on.
    func pollHazards() async {
        guard settings.map.hazards else { sigmets = []; return }
        while !Task.isCancelled {
            if let s = try? await weather.sigmets() { sigmets = s }
            do { try await Task.sleep(for: .seconds(600)) } catch { return }
        }
    }

    /// Wind for the visible map while the layer is on; called again whenever the view settles somewhere new.
    /// Points are cached an hour, so panning back and forth costs nothing.
    func loadWind() async {
        guard settings.map.wind != nil, let a = windArea else { windGrid = nil; return }
        // Let a fling settle before asking for anything.
        do { try await Task.sleep(for: .milliseconds(600)) } catch { return }
        let lonSpan = a.east < a.west ? a.east + 360 - a.west : a.east - a.west
        let step = WindGrid.step(latSpan: a.north - a.south, lonSpan: lonSpan)
        let points = WindGrid.points(south: a.south, west: a.west, north: a.north, east: a.east, step: step)
        while !Task.isCancelled {
            windGrid = await weather.wind(points, step: step)
            do { try await Task.sleep(for: .seconds(3600)) } catch { return }
        }
    }

    // MARK: Search

    func search(_ raw: String) async -> SearchResults? {
        let q = raw.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return nil }
        let data = await StaticData.get()
        let u = q.uppercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "")
        let local = tracks.values.map(\.aircraft).filter { a in
            [a.callsign, a.registration?.replacingOccurrences(of: "-", with: ""), a.hex.uppercased(), a.typeCode]
                .contains { $0?.uppercased().hasPrefix(u) == true }
        }
        let queries = SearchQuery.classify(q, icaoForIata: data.icaoForIataAirline)
        var failed = false
        var remote: [Aircraft] = []
        await withTaskGroup(of: [Aircraft]?.self) { g in
            for rq in queries {
                g.addTask {
                    do { return try await self.scheduler.run { try await $0.query(rq) } } catch { return nil }
                }
            }
            for await r in g {
                if let r { remote += r } else { failed = true }
            }
        }
        // Feed results join the live picture so choosing one opens its sheet straight away.
        if !remote.isEmpty { apply(await store.merge(remote, now: .now)) }
        var seen = Set<String>()
        let aircraft = (local + remote).filter { seen.insert($0.hex).inserted }.sorted { $0.label < $1.label }.prefix(60)
        let airports = await Task.detached { data.searchAirports(q) }.value
        return SearchResults(aircraft: Array(aircraft), airports: airports, failed: failed && remote.isEmpty)
    }
}
