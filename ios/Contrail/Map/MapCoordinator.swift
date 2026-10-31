import MapKit
import SwiftUI
import UIKit

/// Owns the MKMapView and everything drawn on it. Planes move by Core Animation: each tick gives every visible
/// plane a linear animation to where it will be at the next tick, so they glide at the display's frame rate while
/// the main thread does work only once per tick (1-2 s), not every frame.
@MainActor
final class MapCoordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
    let mapView = MKMapView()
    private unowned let model: AppModel

    private var planes: [String: PlaneAnnotation] = [:]
    private var lastHeading = 0.0
    private var airports: [String: AirportAnnotation] = [:]
    private var endpoints: [AirportAnnotation] = []
    private var scrubPin: ScrubAnnotation?
    private var replayPlane: ReplayAnnotation?
    private var trailLines: [StyledLines] = []
    private var tailLine: StyledLines?
    private var routeLines: [StyledLines] = []
    private var radar: [RadarOverlay] = []
    private var radarRenderers: [ObjectIdentifier: MKTileOverlayRenderer] = [:]
    private var radarFrame = 0
    private var clouds: [CloudOverlay] = []
    private var cloudBucket: Int64?
    private var hazardPolygons: [HazardPolygon] = []
    private var hazardLabels: [HazardLabel] = []
    private var shownSigmets: [Sigmet] = []

    private var tickTask: Task<Void, Never>?
    private var radarTask: Task<Void, Never>?
    private var prefs = MapPrefs()
    private var appliedPrefs: MapPrefs?
    private var trailKey: Int?
    private var routeKey: Int?
    private var requestedLogos: Set<String> = []
    private var userMovedMap = false
    private var flewToUser = false
    private var pendingDeselect: Task<Void, Never>?
    private var visible = true
    private(set) var zoom: Double = 3

    init(model: AppModel) {
        self.model = model
        super.init()
        mapView.delegate = self
        mapView.showsUserLocation = true
        mapView.showsCompass = false
        mapView.showsScale = false
        mapView.pointOfInterestFilter = .excludingAll
        mapView.isPitchEnabled = false
        mapView.accessibilityIdentifier = "map"
        mapView.register(PlaneView.self, forAnnotationViewWithReuseIdentifier: PlaneView.reuseId)
        mapView.register(AirportView.self, forAnnotationViewWithReuseIdentifier: AirportView.reuseId)
        restoreCamera()

        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.delegate = self
        mapView.addGestureRecognizer(tap)
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.delegate = self
        doubleTap.cancelsTouchesInView = false
        mapView.addGestureRecognizer(doubleTap)

        model.onTraffic = { [weak self] in self?.render(animate: true) }
        startTicking()
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    // MARK: Settings and selection

    func apply(_ p: MapPrefs) {
        prefs = p
        guard p != appliedPrefs else { return }
        let old = appliedPrefs
        appliedPrefs = p
        if old?.kind != p.kind {
            mapView.preferredConfiguration = switch p.kind {
            case .muted: MKStandardMapConfiguration(elevationStyle: .flat, emphasisStyle: .muted)
            case .standard: MKStandardMapConfiguration(elevationStyle: .flat, emphasisStyle: .default)
            case .satellite: MKHybridMapConfiguration(elevationStyle: .flat)
            }
        }
        if old?.appearance != p.appearance {
            mapView.overrideUserInterfaceStyle = switch p.appearance {
            case .auto: .unspecified
            case .dark: .dark
            case .light: .light
            }
            airports.values.forEach { mapView.removeAnnotation($0) }
            airports = [:]
        }
        if old?.airports != p.airports { updateAirports() }
        trailKey = nil
        // Deferred so reading traffic here isn't tracked by SwiftUI's update of the representable.
        Task { self.render(animate: false) }
    }

    func selectionChanged() {
        trailKey = nil
        routeKey = nil
        if model.pastView == nil, let hex = model.selectedHex, let t = model.tracks[hex] { reveal(Track.extrapolate(t.aircraft, .now)) }
        if case .airport(let a) = model.selection { reveal(a.coord) }
        Task { self.render(animate: false) }
    }

    /// A past flight's path arrived: draw it and frame it above the sheet.
    func pastLoaded() {
        trailKey = nil
        routeKey = nil
        Task { self.render(animate: false) }
        guard let path = model.pastView?.path, path.count >= 2 else { return }
        let rect = path.reduce(MKMapRect.null) { r, p in
            r.union(MKMapRect(origin: MKMapPoint(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)), size: MKMapSize(width: 0, height: 0)))
        }
        let pad = UIEdgeInsets(top: mapView.safeAreaInsets.top + 70, left: 40, bottom: min(model.sheetHeight, mapView.bounds.height * 0.6) + 30, right: 60)
        mapView.setVisibleMapRect(rect, edgePadding: pad, animated: true)
    }

    func setVisible(_ v: Bool) {
        visible = v
        if v { startTicking() } else { tickTask?.cancel() }
    }

    /// Moves the map just enough to bring `c` into the part above the sheet.
    private func reveal(_ c: Coord) {
        let pt = mapView.convert(CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon), toPointTo: mapView)
        let safe = mapView.bounds.inset(by: UIEdgeInsets(top: mapView.safeAreaInsets.top + 60, left: 30, bottom: model.sheetHeight + 30, right: 30))
        guard !safe.contains(pt) else { return }
        centre(on: c, animated: true)
    }

    /// `glide`: move linearly over this many ms, in step with the planes, instead of MapKit's quick ease (a jolt each tick).
    private func centre(on c: Coord, animated: Bool, glide: Millis? = nil) {
        // Centre in the visible strip: shift the target up by half the sheet.
        let size = mapView.bounds.size
        let target = mapView.convert(CLLocationCoordinate2D(latitude: c.lat, longitude: c.lon), toPointTo: mapView)
        let shifted = CGPoint(x: target.x, y: target.y + (min(model.sheetHeight, size.height * 0.6) - mapView.safeAreaInsets.top) / 2)
        let centre = mapView.convert(shifted, toCoordinateFrom: mapView)
        guard let glide else { return mapView.setCenter(centre, animated: animated) }
        let cam = mapView.camera.copy() as! MKMapCamera
        cam.centerCoordinate = centre
        UIView.animate(withDuration: Double(glide) / 1000, delay: 0, options: [.curveLinear, .allowUserInteraction, .beginFromCurrentState]) {
            self.mapView.camera = cam
        }
    }

    func recentreOnUser() {
        guard let l = model.location.location else { return }
        let region = MKCoordinateRegion(center: l.coordinate, latitudinalMeters: 60_000, longitudinalMeters: 60_000)
        mapView.setRegion(region, animated: true)
    }

    /// The first GPS fix of each launch flies there, unless the user has already moved the map.
    func userLocated(_ c: CLLocationCoordinate2D) {
        guard !flewToUser else { return }
        flewToUser = true
        guard !userMovedMap else { return }
        let span = min(mapView.region.span.latitudeDelta, 1.5)
        mapView.setRegion(MKCoordinateRegion(center: c, span: MKCoordinateSpan(latitudeDelta: span, longitudeDelta: span)), animated: true)
    }

    // MARK: Ticks

    private var tickMs: Millis {
        // Animation smooths between ticks, so the tick only bounds the prediction error: 1 s, or 2 s zoomed far out.
        MapFeatures.frameIntervalMs(zoom: zoom, lat: mapView.region.center.latitude) >= 2000 ? 2000 : 1000
    }

    private func startTicking() {
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.render(animate: true)
                try? await Task.sleep(for: .milliseconds(self.tickMs))
            }
        }
    }

    private var cullArea: Area? {
        let r = mapView.region
        if r.span.longitudeDelta >= 180 { return nil }
        let dLat = r.span.latitudeDelta, dLon = r.span.longitudeDelta
        return Area(south: r.center.latitude - dLat, west: r.center.longitude - dLon, north: r.center.latitude + dLat, east: r.center.longitude + dLon)
    }

    /// Draws traffic: planes (moved, added, removed), trails and the route.
    func render(animate: Bool) {
        guard visible else { return }
        let now = Millis.now
        let step = tickMs
        let selected = model.selectedHex
        let filters = prefs.filters
        let area = cullArea
        let heading = mapView.camera.heading
        let showLogos = zoom >= 7.5
        let tracks = model.tracks

        var keep = Set<String>()
        var moves: [(PlaneAnnotation, CLLocationCoordinate2D)] = []
        var added: [PlaneAnnotation] = []
        for (hex, t) in tracks {
            let a = t.aircraft
            let isSel = hex == selected
            guard isSel || filters.matches(a) else { continue }
            let p = t.displayPosition(now)
            guard isSel || area?.contains(p.lat, p.lon) ?? true else { continue }
            keep.insert(hex)
            if let ann = planes[hex] {
                let target = Track.extrapolate(a, now + step)
                moves.append((ann, CLLocationCoordinate2D(latitude: target.lat, longitude: target.lon)))
            } else {
                let ann = PlaneAnnotation(hex: hex, coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon))
                planes[hex] = ann
                added.append(ann)
            }
        }
        let gone = planes.keys.filter { !keep.contains($0) }
        if !gone.isEmpty { mapView.removeAnnotations(gone.compactMap { planes.removeValue(forKey: $0) }) }
        if !added.isEmpty { mapView.addAnnotations(added) }

        if animate {
            UIView.animate(withDuration: Double(step) / 1000, delay: 0, options: [.curveLinear, .allowUserInteraction, .beginFromCurrentState]) {
                for (ann, c) in moves { ann.coordinate = c }
            }
        } else {
            for (ann, c) in moves { ann.coordinate = c }
        }

        for (hex, ann) in planes {
            guard let view = mapView.view(for: ann) as? PlaneView, let t = tracks[hex] else { continue }
            style(view, t, selected: hex == selected, now: now, heading: heading, showLogos: showLogos)
        }

        if model.following, model.pastView == nil, let hex = selected, let t = tracks[hex] {
            centre(on: Track.extrapolate(t.aircraft, now + step), animated: true, glide: animate ? step : nil)
        }
        renderTrails(tracks, selected: selected, now: now, step: step)
        renderRoute(selected.flatMap { tracks[$0] }, now: now)
        renderScrub()
    }

    private func style(_ v: PlaneView, _ t: Track, selected: Bool, now: Millis, heading: Double, showLogos: Bool) {
        let a = t.aircraft
        let shape = Shape.of(a)
        var logo: UIImage?
        if showLogos, let code = StaticData.airlineCode(a.callsign) {
            logo = AirlineLogos.shared.peekCache.object(forKey: code as NSString)
            if logo == nil, requestedLogos.insert(code).inserted { Task { _ = await AirlineLogos.shared.get(code) } }
            // Only near-square marks read at badge size.
            if let l = logo, l.size.width > l.size.height * 1.6 { logo = nil }
        }
        v.apply(PlaneView.Look(shape: shape, band: AltitudeColors.band(a), selected: selected,
                               label: prefs.labels && zoom >= 7 ? a.callsign ?? a.registration : nil,
                               stale: t.staleness(now) > TrafficStore.fadeAfterMs, logo: logo,
                               spoken: "\(a.label), \(Fmt.current.altitude(a))", dimmed: model.pastView != nil))
        v.setRotation(a.track.map { $0 - heading }, rotates: shape.rotates)
        // Higher planes draw over lower ones; the selected plane over everything.
        v.zPriority = selected ? .max : MKAnnotationViewZPriority(rawValue: Float(min(a.altBaro ?? 0, 60_000)) / 60_000 * 0.9)
    }

    private func renderTrails(_ tracks: [String: Track], selected: String?, now: Millis, step: Millis) {
        // Rebuilt only when a trail gains a point (every few seconds), not every tick.
        var hasher = Hasher()
        hasher.combine(selected)
        hasher.combine(prefs.allTrails)
        let past = model.pastView?.path
        hasher.combine(past?.count)
        hasher.combine(past?.first?.time)
        if prefs.allTrails { for t in tracks.values where planes[t.hex] != nil { hasher.combine(t.hex); hasher.combine(t.trail.count) } }
        if let s = selected.flatMap({ tracks[$0] }) { hasher.combine(s.trail.count); hasher.combine(s.trail.first?.time) }
        let key = hasher.finalize()
        if key != trailKey {
            trailKey = key
            var byStyle: [StyledLines.Style: [MKPolyline]] = [:]
            func add(_ t: Track, selected sel: Bool) {
                for run in MapFeatures.trailRuns(t.trail, current: nil) {
                    var pts = run.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                    byStyle[.trail(band: run.band, selected: sel), default: []].append(MKPolyline(coordinates: &pts, count: pts.count))
                }
            }
            if let past {
                // A past flight replaces the live trails; live planes stay on the map.
                for run in MapFeatures.trailRuns(past, current: nil) {
                    var pts = run.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                    byStyle[.trail(band: run.band, selected: true), default: []].append(MKPolyline(coordinates: &pts, count: pts.count))
                }
            } else {
                if prefs.allTrails { for t in tracks.values where planes[t.hex] != nil && t.hex != selected { add(t, selected: false) } }
                if let s = selected.flatMap({ tracks[$0] }) { add(s, selected: true) }
            }
            let lines = byStyle.map { style, polys in
                let m = StyledLines(polys)
                m.style = style
                return m
            }
            mapView.removeOverlays(trailLines)
            trailLines = lines.sorted { a, b in
                // Selected trail on top.
                if case .trail(_, let sa) = a.style, case .trail(_, let sb) = b.style { return !sa && sb }
                return false
            }
            mapView.addOverlays(trailLines, level: .aboveRoads)
        }
        // The short piece from the last fix to the plane, moved every tick so the trail stays attached.
        if let tailLine { mapView.removeOverlay(tailLine) }
        tailLine = nil
        if model.pastView == nil, let s = selected.flatMap({ tracks[$0] }), let last = s.trail.last {
            let end = Track.extrapolate(s.aircraft, now + step)
            var pts = [CLLocationCoordinate2D(latitude: last.lat, longitude: last.lon), CLLocationCoordinate2D(latitude: end.lat, longitude: end.lon)]
            let m = StyledLines([MKPolyline(coordinates: &pts, count: 2)])
            m.style = .trail(band: AltitudeColors.band(s.aircraft), selected: true)
            tailLine = m
            mapView.addOverlay(m, level: .aboveRoads)
        }
    }

    private func renderRoute(_ t: Track?, now: Millis) {
        if let past = model.pastView {
            // Just the past flight's known ends.
            guard routeKey != past.flight.departed.hashValue else { return }
            routeKey = past.flight.departed.hashValue
            mapView.removeOverlays(routeLines)
            mapView.removeAnnotations(endpoints)
            routeLines = []
            endpoints = [past.flight.from, past.flight.to].compactMap { $0.flatMap { StaticData.now()?.airport($0) } }.map { AirportAnnotation($0, endpoint: true) }
            mapView.addAnnotations(endpoints)
            return
        }
        let route = t.flatMap { model.route(for: $0, now: now) }
        let p = t.flatMap { tr in route.map { FlightProgress.compute(tr, $0, now: now) } }
        // An unverified route isn't drawn: it would put the plane on a line it isn't flying.
        let shown = p?.verified == true ? route : nil
        var hasher = Hasher()
        hasher.combine(t?.hex)
        hasher.combine(shown)
        hasher.combine(t?.trail.count)
        hasher.combine(t?.aircraft.onGround)
        let key = hasher.finalize()
        guard key != routeKey else { return }
        routeKey = key
        mapView.removeOverlays(routeLines)
        mapView.removeAnnotations(endpoints)
        routeLines = []
        endpoints = []
        guard let t else { return }
        for (kind, pts) in MapFeatures.routeLines(t, origin: shown?.origin, destination: shown?.destination, now: now) where pts.count >= 2 {
            var c = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
            let m = StyledLines([MKPolyline(coordinates: &c, count: c.count)])
            m.style = .route(kind)
            routeLines.append(m)
        }
        mapView.addOverlays(routeLines, level: .aboveRoads)
        if let shown {
            endpoints = [AirportAnnotation(shown.origin, endpoint: true), AirportAnnotation(shown.destination, endpoint: true)]
            mapView.addAnnotations(endpoints)
        }
    }

    /// A past flight's replay: the plane itself, as the selected plane's silhouette, faded across a coverage gap.
    func renderReplay() {
        guard let f = model.replay, let hex = model.selectedHex, let t = model.tracks[hex] else {
            if let p = replayPlane { mapView.removeAnnotation(p); replayPlane = nil }
            return
        }
        let c = CLLocationCoordinate2D(latitude: f.lat, longitude: f.lon)
        if let p = replayPlane {
            p.coordinate = c
            p.frame = f
        } else {
            replayPlane = ReplayAnnotation(c, frame: f, shape: Shape.of(t.aircraft))
            mapView.addAnnotation(replayPlane!)
        }
        if let v = mapView.view(for: replayPlane!) as? PlaneView { styleReplay(v, replayPlane!) }
    }

    private func styleReplay(_ v: PlaneView, _ p: ReplayAnnotation) {
        v.apply(PlaneView.Look(shape: p.shape, band: AltitudeColors.selectedBand, selected: true, label: nil, stale: false, logo: nil, spoken: "Replay"))
        v.setRotation(p.frame.heading - mapView.camera.heading, rotates: p.shape.rotates)
        v.setFade(p.frame.gap ? 0.5 : 1)
        v.zPriority = .max
    }

    private func renderScrub() {
        let c = model.scrub.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        switch (scrubPin, c) {
        case (nil, let c?):
            scrubPin = ScrubAnnotation(c)
            mapView.addAnnotation(scrubPin!)
        case (let pin?, let c?): pin.coordinate = c
        case (let pin?, nil):
            mapView.removeAnnotation(pin)
            scrubPin = nil
        case (nil, nil): break
        }
    }

    // MARK: Airports

    private func updateAirports() {
        guard prefs.airports, let data = StaticData.now() else {
            if !airports.isEmpty { mapView.removeAnnotations(Array(airports.values)); airports = [:] }
            if prefs.airports && StaticData.now() == nil { Task { _ = await StaticData.get(); self.updateAirports() } }
            return
        }
        // Large from zoom 4, medium from 7, small from 9: what's readable at each scale.
        let sizes: Set<Character> = zoom >= 9 ? ["L", "M", "S"] : zoom >= 7 ? ["L", "M"] : zoom >= 4 ? ["L"] : []
        let r = mapView.region
        let visible = Area(south: r.center.latitude - r.span.latitudeDelta / 2, west: r.center.longitude - r.span.longitudeDelta / 2,
                           north: r.center.latitude + r.span.latitudeDelta / 2, east: r.center.longitude + r.span.longitudeDelta / 2)
        var want: [String: Airport] = [:]
        for a in data.airports where a.size.map(sizes.contains) == true && visible.contains(a.lat, a.lon) {
            if let icao = a.icao { want[icao] = a }
            if want.count >= 400 { break }
        }
        let gone = airports.keys.filter { want[$0] == nil }
        mapView.removeAnnotations(gone.compactMap { airports.removeValue(forKey: $0) })
        let new = want.filter { airports[$0.key] == nil }.map { AirportAnnotation($0.value) }
        for a in new { airports[a.airport.icao!] = a }
        mapView.addAnnotations(new)
    }

    // MARK: Radar

    func setRadar(_ frames: [RadarFrame]) {
        let templates = frames.map(\.template)
        guard templates != radar.compactMap(\.urlTemplate) else { return }
        radarTask?.cancel()
        mapView.removeOverlays(radar)
        radarRenderers = [:]
        radar = templates.map { t in
            let o = RadarOverlay(urlTemplate: t)
            o.canReplaceMapContent = false
            o.maximumZ = 12
            return o
        }
        // All frames load once; the animation only flips which one is visible, so it never refetches tiles.
        // Radar draws over the clouds.
        for o in radar {
            if let top = clouds.last { mapView.insertOverlay(o, above: top) } else { mapView.insertOverlay(o, at: 0, level: .aboveRoads) }
        }
        radarFrame = radar.count - 1
        guard radar.count > 1 else { return }
        radarTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                // Hold on the latest frame, then replay the hour.
                // `try`, not `try?`: a cancelled sleep must end the loop, since the frames may be gone by then.
                do { try await Task.sleep(for: .milliseconds(self.radarFrame == self.radar.count - 1 ? 1800 : 450)) } catch { return }
                guard self.radar.count > 1 else { return }
                self.radarFrame = (self.radarFrame + 1) % self.radar.count
                self.showRadarFrame()
            }
        }
    }

    // MARK: Clouds and hazards

    /// Satellite clouds (see `CloudOverlay`) for this 10-minute `bucket`, under the radar; nil removes them.
    func setClouds(_ bucket: Int64?) {
        guard bucket != cloudBucket else { return }
        cloudBucket = bucket
        mapView.removeOverlays(clouds)
        clouds = bucket.map(CloudOverlay.all) ?? []
        for o in clouds.reversed() { mapView.insertOverlay(o, at: 0, level: .aboveRoads) }
    }

    func setHazards(_ list: [Sigmet]) {
        guard list != shownSigmets else { return }
        shownSigmets = list
        mapView.removeOverlays(hazardPolygons)
        mapView.removeAnnotations(hazardLabels)
        hazardPolygons = []
        hazardLabels = []
        let now = Millis.now
        for s in list where (s.validTo ?? .max) >= now {
            for area in s.areas where area.count >= 3 {
                var pts = area.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                let p = HazardPolygon(coordinates: &pts, count: pts.count)
                p.color = HazardLabel.color(s.hazard)
                hazardPolygons.append(p)
                // Longitudes made continuous first, so an area across the antimeridian isn't labelled on the far side of the world.
                var prev = area[0].lon
                let lons = area.map { p in var lon = p.lon; while lon - prev > 180 { lon -= 360 }; while lon - prev < -180 { lon += 360 }; prev = lon; return lon }
                let mid = lons.reduce(0, +) / Double(area.count)
                let c = CLLocationCoordinate2D(latitude: area.map(\.lat).reduce(0, +) / Double(area.count), longitude: mid > 180 ? mid - 360 : mid < -180 ? mid + 360 : mid)
                hazardLabels.append(HazardLabel(c, s.label, p.color))
            }
        }
        mapView.addOverlays(hazardPolygons, level: .aboveRoads)
        mapView.addAnnotations(hazardLabels)
    }

    private func showRadarFrame() {
        for (i, o) in radar.enumerated() {
            radarRenderers[ObjectIdentifier(o)]?.alpha = i == radarFrame ? 0.7 : 0
        }
    }

    // MARK: Taps

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let pt = g.location(in: mapView)
        // Annotation views other than planes (airports) are selected by MapKit itself.
        if var v = mapView.hitTest(pt, with: nil) {
            while let s = v.superview, !(v is MKAnnotationView) { v = s }
            if v is AirportView { return }
        }
        // Planes are hit-tested where they're drawn (mid-animation), not where their animation ends.
        var best: (String, CGFloat)?
        for (hex, ann) in planes {
            guard let view = mapView.view(for: ann) else { continue }
            let f = (view.layer.presentation() ?? view.layer).frame.insetBy(dx: -8, dy: -8)
            guard f.contains(pt) else { continue }
            let d = hypot(f.midX - pt.x, f.midY - pt.y)
            if best == nil || d < best!.1 { best = (hex, d) }
        }
        if let (hex, _) = best {
            pendingDeselect?.cancel()
            UISelectionFeedbackGenerator().selectionChanged()
            model.select(.aircraft(hex))
            return
        }
        // Tapping empty map closes the sheet, unless it was the first tap of a double-tap zoom.
        guard model.selection != nil else { return }
        pendingDeselect = Task {
            try? await Task.sleep(for: .milliseconds(300))
            if !Task.isCancelled { self.model.select(nil) }
        }
    }

    @objc private func doubleTapped() { pendingDeselect?.cancel() }

    // MARK: MKMapViewDelegate

    func mapView(_ mapView: MKMapView, viewFor annotation: any MKAnnotation) -> MKAnnotationView? {
        switch annotation {
        case let p as PlaneAnnotation:
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: PlaneView.reuseId, for: p) as! PlaneView
            let hex = p.hex
            v.onActivate = { [weak self] in self?.model.select(.aircraft(hex)) }
            if let t = model.tracks[p.hex] {
                style(v, t, selected: p.hex == model.selectedHex, now: .now, heading: mapView.camera.heading, showLogos: zoom >= 7.5)
            }
            return v
        case let a as AirportAnnotation:
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: AirportView.reuseId, for: a) as! AirportView
            v.configure(a, dark: mapView.traitCollection.userInterfaceStyle == .dark)
            return v
        case let h as HazardLabel:
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: "hazard") ?? MKAnnotationView(annotation: h, reuseIdentifier: "hazard")
            v.annotation = h
            let label = (v.subviews.first as? UILabel) ?? UILabel()
            if label.superview == nil { v.addSubview(label) }
            label.numberOfLines = 2
            label.textAlignment = .center
            label.font = .systemFont(ofSize: 11, weight: .bold)
            label.textColor = h.color
            label.text = h.text
            label.shadowColor = mapView.traitCollection.userInterfaceStyle == .dark ? .black : .white
            label.shadowOffset = CGSize(width: 0, height: 1)
            label.sizeToFit()
            v.frame = label.bounds
            v.isEnabled = false
            v.isUserInteractionEnabled = false
            v.zPriority = .min
            v.displayPriority = .defaultLow
            return v
        case let r as ReplayAnnotation:
            let v = PlaneView(annotation: r, reuseIdentifier: nil)
            styleReplay(v, r)
            return v
        case is ScrubAnnotation:
            let v = MKAnnotationView(annotation: annotation, reuseIdentifier: nil)
            v.frame = CGRect(x: 0, y: 0, width: 14, height: 14)
            v.backgroundColor = .white
            v.layer.cornerRadius = 7
            v.layer.borderWidth = 3
            v.layer.borderColor = UIColor.systemBlue.cgColor
            v.zPriority = .max
            return v
        default:
            return nil // the user's blue dot
        }
    }

    func mapView(_ mapView: MKMapView, didSelect annotation: any MKAnnotation) {
        mapView.deselectAnnotation(annotation, animated: false)
        if let a = annotation as? AirportAnnotation {
            UISelectionFeedbackGenerator().selectionChanged()
            model.select(.airport(StaticData.now()?.airport(a.airport.icao ?? a.airport.code) ?? a.airport))
        }
    }

    func mapView(_ mapView: MKMapView, rendererFor overlay: any MKOverlay) -> MKOverlayRenderer {
        if let c = overlay as? CloudOverlay {
            let renderer = MKTileOverlayRenderer(tileOverlay: c)
            renderer.alpha = 0.85
            return renderer
        }
        if let h = overlay as? HazardPolygon {
            let r = MKPolygonRenderer(polygon: h)
            r.fillColor = h.color.withAlphaComponent(0.16)
            r.strokeColor = h.color.withAlphaComponent(0.9)
            r.lineWidth = 1.5
            return r
        }
        if let r = overlay as? RadarOverlay {
            let renderer = MKTileOverlayRenderer(tileOverlay: r)
            renderer.alpha = radar.last === r ? 0.7 : 0
            radarRenderers[ObjectIdentifier(r)] = renderer
            return renderer
        }
        guard let lines = overlay as? StyledLines else { return MKOverlayRenderer(overlay: overlay) }
        let r = MKMultiPolylineRenderer(multiPolyline: lines)
        r.lineCap = .round
        r.lineJoin = .round
        switch lines.style {
        case .trail(let band, let selected):
            r.strokeColor = AltitudeColors.color(band: band)
            r.lineWidth = selected ? 3.5 : 1.5
        case .route(let kind):
            let dark = mapView.traitCollection.userInterfaceStyle == .dark || prefs.kind == .satellite
            r.strokeColor = (dark ? UIColor.white : UIColor(white: 0.1, alpha: 1)).withAlphaComponent(kind == .behind ? 0.45 : 0.8)
            r.lineWidth = 2
            r.lineDashPattern = kind == .projected ? [1, 5] : [6, 6]
        }
        return r
    }

    func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
        if !model.mapMoving { model.mapMoving = true }
        // A gesture in progress means the user moved the map; programmatic moves don't count.
        if mapView.subviews.first?.gestureRecognizers?.contains(where: { $0.state == .began || $0.state == .changed }) == true {
            userMovedMap = true
            if model.following { model.following = false }
        }
    }

    func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
        // Keep icons pointing along their track while the map rotates.
        let heading = mapView.camera.heading
        guard heading != lastHeading else { return } // fires every frame of a pan too
        lastHeading = heading
        for (hex, ann) in planes {
            guard let v = mapView.view(for: ann) as? PlaneView, let t = model.tracks[hex] else { continue }
            v.setRotation(t.aircraft.track.map { $0 - heading }, rotates: Shape.of(t.aircraft).rotates)
        }
    }

    func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
        let r = mapView.region
        zoom = MapFeatures.zoom(longitudeDelta: r.span.longitudeDelta, widthPoints: mapView.bounds.width)
        let ne = CLLocationCoordinate2D(latitude: r.center.latitude + r.span.latitudeDelta / 2, longitude: r.center.longitude + r.span.longitudeDelta / 2)
        let radius = Geo.distanceNm(r.center.latitude, r.center.longitude, ne.latitude, ne.longitude)
        // Too wide when even the narrower half of the view reaches past the feeds' 250 nm query.
        let halfWidth = Geo.distanceNm(r.center.latitude, r.center.longitude, r.center.latitude, r.center.longitude + r.span.longitudeDelta / 2)
        let halfHeight = Geo.distanceNm(r.center.latitude, r.center.longitude, r.center.latitude + r.span.latitudeDelta / 2, r.center.longitude)
        let tooWide = min(halfWidth, halfHeight) > 300
        if model.viewTooWide != tooWide { model.viewTooWide = tooWide }
        model.viewport = Viewport(center: Coord(lat: r.center.latitude, lon: r.center.longitude), radiusNm: Int(min(max(radius, 5), 250)))
        model.mapMoving = false
        model.windArea = r.span.longitudeDelta >= 180
            ? Area(south: max(r.center.latitude - r.span.latitudeDelta / 2, -80), west: -180, north: min(r.center.latitude + r.span.latitudeDelta / 2, 80), east: 180)
            : Area(south: r.center.latitude - r.span.latitudeDelta / 2, west: r.center.longitude - r.span.longitudeDelta / 2,
                   north: r.center.latitude + r.span.latitudeDelta / 2, east: r.center.longitude + r.span.longitudeDelta / 2)
        saveCamera()
        updateAirports()
        render(animate: false)
    }

    func mapView(_ mapView: MKMapView, didUpdate userLocation: MKUserLocation) {
        if let c = userLocation.location?.coordinate { userLocated(c) }
    }

    // MARK: Camera persistence

    private func saveCamera() {
        let r = mapView.region
        UserDefaults.standard.set([r.center.latitude, r.center.longitude, r.span.latitudeDelta, r.span.longitudeDelta], forKey: "camera")
    }

    /// Where the map was last left, or a world view on first launch.
    private func restoreCamera() {
        #if DEBUG
        // For performance checks: `-debugCamera "span,lat,lon"` starts anywhere (span first: a leading "-" reads as a flag).
        if let v = UserDefaults.standard.string(forKey: "debugCamera")?.split(separator: ",").compactMap({ Double($0) }), v.count == 3 {
            mapView.region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: v[1], longitude: v[2]),
                                                span: MKCoordinateSpan(latitudeDelta: v[0], longitudeDelta: v[0]))
            flewToUser = true
            return
        }
        #endif
        if let v = UserDefaults.standard.array(forKey: "camera") as? [Double], v.count == 4 {
            mapView.region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: v[0], longitude: v[1]),
                                                span: MKCoordinateSpan(latitudeDelta: v[2], longitudeDelta: v[3]))
        } else {
            mapView.region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 10, longitude: 135),
                                                span: MKCoordinateSpan(latitudeDelta: 120, longitudeDelta: 120))
        }
    }
}

/// The SwiftUI side: passes settings and data the map reacts to into the coordinator.
struct MapView: UIViewRepresentable {
    let model: AppModel

    func makeUIView(context: Context) -> MKMapView { model.map.mapView }

    func updateUIView(_ view: MKMapView, context: Context) {
        let map = model.map
        map.apply(model.settings.map)
        map.setRadar(model.radarFrames)
        map.setClouds(model.cloudBucket)
        map.setHazards(model.sigmets)
        _ = model.details?.route
        _ = model.scrub
        _ = model.following
        Task { map.render(animate: false) }
    }
}
