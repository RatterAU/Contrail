import Charts
import SwiftUI

/// Everything about one plane: where it's from and going, what it's doing right now, what it is.
struct AircraftSheet: View {
    @Environment(AppModel.self) private var model
    let hex: String

    @State private var near: Airport?

    var body: some View {
        let track = model.tracks[hex]
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let track {
                    AircraftHeader(track: track, details: model.details)
                    controls(track)
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        let now = Millis(ctx.date.timeIntervalSince1970 * 1000)
                        let route = model.route(for: track, now: now)
                        let p = route.map { FlightProgress.compute(track, $0, now: now) }
                        VStack(alignment: .leading, spacing: 14) {
                            RouteCard(track: track, route: route, progress: p, loading: model.details == nil && track.aircraft.callsign != nil, now: now)
                            Instruments(a: track.aircraft)
                            Insights(items: FlightInsights.insights(track, route, p, near: near, now: now, fmt: Fmt.current))
                        }
                    }
                    if track.trail.count >= 3 { ProfileChart(track: track) }
                    if let photos = model.details?.photos, !photos.isEmpty { PhotoPager(photos: photos) }
                    AboutAircraft(a: track.aircraft, info: model.details?.info)
                    TechnicalDetails(a: track.aircraft)
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Finding \(hex.uppercased())…").foregroundStyle(.secondary)
                        Button("Close") { model.select(nil) }.buttonStyle(.glass)
                    }
                    .frame(maxWidth: .infinity).padding(.top, 40)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 30)
        }
        .task { await model.followSelected(hex) }
        .task(id: track.map { Int($0.aircraft.lat * 20) * 10_000 + Int($0.aircraft.lon * 20) }) {
            // Nearest airport for "12 km NE of Gawler", re-found every ~3 nm of travel. Scans ~18k rows off the main actor.
            guard let c = track?.aircraft.coord, let data = await StaticData.get() as StaticData? else { return }
            near = await Task.detached { data.nearestPlace(c.lat, c.lon) }.value
        }
    }

    private func controls(_ t: Track) -> some View {
        @Bindable var model = model
        let phase = FlightInsights.phase(t, model.route(for: t, now: .now).map { FlightProgress.compute(t, $0, now: .now) }, now: .now)
        return HStack(spacing: 10) {
            Button {
                model.following.toggle()
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            } label: {
                Label(model.following ? "Following" : "Follow", systemImage: model.following ? "location.fill" : "location")
                    .font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.glass)
            .tint(model.following ? .blue : nil)
            Pill(text: phase.label, color: phase.color, symbol: phase.symbol)
            Spacer()
        }
    }
}

private struct AircraftHeader: View {
    @Environment(AppModel.self) private var model
    let track: Track
    let details: Enrichment?
    @State private var logo: UIImage?

    var body: some View {
        let a = track.aircraft
        let route = details?.route
        let airline = route?.airline?.name ?? StaticData.now()?.airlineForCallsign(a.callsign)?.name
        let code = StaticData.airlineCode(a.callsign)
        SheetHeader(close: { model.select(nil) }) {
            if let logo {
                let wide = logo.size.width > logo.size.height * 1.6
                Image(uiImage: logo).resizable().scaledToFit()
                    .frame(width: wide ? 72 : 44, height: wide ? 28 : 44)
                    .padding(wide ? 0 : 4)
                    .background(wide ? .clear : .white, in: .rect(cornerRadius: 10, style: .continuous))
            }
        } title: {
            Text(route?.flightNumber ?? a.callsign ?? a.registration ?? a.hex.uppercased())
                .font(.largeTitle.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
            let sub = [airline, a.callsign != route?.flightNumber ? a.callsign : nil].compactMap { $0 }.joined(separator: " · ")
            if !sub.isEmpty { Text(sub).font(.subheadline.weight(.medium)) }
            let type = details?.info?.fullName ?? a.description ?? a.typeCode
            HStack(spacing: 6) {
                if let type { Text(type).foregroundStyle(.secondary) }
                if a.isEmergency { Pill(text: "Emergency", color: .red, symbol: "exclamationmark.triangle.fill") }
                if a.military { Pill(text: "Military", color: .green, symbol: "shield.fill") }
            }
            .font(.subheadline)
        }
        .task(id: code) {
            logo = nil
            if let code { logo = await AirlineLogos.shared.get(code) }
        }
    }
}

// MARK: Route

private struct RouteCard: View {
    let track: Track
    let route: Route?
    let progress: FlightProgress?
    let loading: Bool
    let now: Millis

    var body: some View {
        Card {
            if let route, progress?.verified != true {
                // A route that fails the position check is shown only as a note: big airport codes read as fact.
                Label("Listed as \(route.origin.shortName) to \(route.destination.shortName), but this plane isn't on that route. Airlines reuse flight numbers, so the listing is likely out of date.", systemImage: "questionmark.circle")
                    .font(.subheadline).foregroundStyle(.secondary)
            } else if let route {
                HStack(alignment: .top) {
                    end(route.origin, alignment: .leading)
                    Spacer()
                    end(route.destination, alignment: .trailing)
                }
                ProgressLine(fraction: progress?.fraction)
                if let p = progress {
                    HStack(alignment: .top) {
                        departed(p, route.origin)
                        Spacer()
                        arrives(p, route.destination)
                    }
                    let done = p.landedAt != nil
                    Text(done ? "Landed after \(Fmt.current.nm(p.totalNm))"
                              : "\(Fmt.current.nm(p.flownNm)) flown · \(Fmt.current.nm(p.remainingNm)) to go · \(Int(p.fraction * 100))%")
                        .font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                }
            } else if loading {
                HStack { Skeleton(width: 70, height: 28); Spacer(); Skeleton(width: 70, height: 28) }
                Skeleton(height: 4)
                Text("Looking up route…").font(.footnote).foregroundStyle(.secondary)
            } else {
                Label(track.aircraft.callsign == nil ? "No flight number is being broadcast, so the route is unknown." : "No published route for this flight.",
                      systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private func end(_ a: Airport, alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 1) {
            Text(a.code).font(.system(size: 30, weight: .bold, design: .rounded))
            Text(a.shortName).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
        }
    }

    private func departed(_ p: FlightProgress, _ origin: Airport) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(p.departedEstimated ? "Took off (est.)" : "Took off").font(.caption).foregroundStyle(.secondary)
            if let d = p.departedAt {
                let c = Fmt.current.clockParts(d, tz: origin.tz, place: origin.shortName)
                Text(c.time).font(.headline).monospacedDigit()
                Text("\(Fmt.duration(now - d)) ago").font(.caption).foregroundStyle(.secondary).monospacedDigit()
                if let z = c.zone { Text(z).font(.caption2).foregroundStyle(.tertiary) }
            } else {
                Text(Fmt.none).font(.headline)
            }
        }
    }

    private func arrives(_ p: FlightProgress, _ dest: Airport) -> some View {
        VStack(alignment: .trailing, spacing: 2) {
            if let landed = p.landedAt {
                Text("Landed").font(.caption).foregroundStyle(.secondary)
                Text(Fmt.current.clockParts(landed, tz: dest.tz, place: dest.shortName).time).font(.headline).monospacedDigit()
                Text("\(Fmt.duration(now - landed)) ago").font(.caption).foregroundStyle(.secondary)
            } else if let eta = p.eta {
                let c = Fmt.current.clockParts(eta, tz: dest.tz, place: dest.shortName)
                Text("Lands (est.)").font(.caption).foregroundStyle(.secondary)
                Text(c.time).font(.headline).monospacedDigit()
                Text(eta - now < 60_000 ? "any moment" : "in \(Fmt.duration(eta - now))").font(.caption).foregroundStyle(.secondary).monospacedDigit()
                if let z = c.zone { Text(z).font(.caption2).foregroundStyle(.tertiary) }
            } else {
                Text("Lands").font(.caption).foregroundStyle(.secondary)
                Text(Fmt.none).font(.headline)
            }
        }
    }
}

/// Dashed line between the two airports, solid up to the plane.
private struct ProgressLine: View {
    let fraction: Double?

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, f = fraction ?? 0
            ZStack(alignment: .leading) {
                Path { p in p.move(to: CGPoint(x: 0, y: 10)); p.addLine(to: CGPoint(x: w, y: 10)) }
                    .stroke(.tertiary, style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [3, 5]))
                if fraction != nil {
                    Capsule().fill(.tint).frame(width: max(w * f, 2), height: 3).offset(y: 0)
                    Image(systemName: "airplane").font(.system(size: 17, weight: .semibold)).foregroundStyle(.tint)
                        .offset(x: min(max(w * f - 9, 0), w - 18))
                }
            }
            .frame(height: 20)
        }
        .frame(height: 20)
        .animation(.smooth, value: fraction)
    }
}

// MARK: Now

private struct Instruments: View {
    let a: Aircraft

    var body: some View {
        let fmt = Fmt.current
        Card {
            Grid(horizontalSpacing: 16, verticalSpacing: 14) {
                GridRow {
                    Fact(label: "Altitude", value: fmt.altitude(a), dot: Color(AltitudeColors.color(band: AltitudeColors.band(a))))
                    Fact(label: "Speed", value: fmt.knots(a.groundSpeed))
                }
                GridRow {
                    Fact(label: "Climb", value: fmt.verticalRate(a.verticalRate))
                    Fact(label: "Heading", value: a.track.map { "\(fmt.degrees($0)) \(Geo.compass($0))" } ?? Fmt.none)
                }
            }
            .animation(.default, value: a)
        }
    }
}

private struct Insights: View {
    let items: [Insight]

    var body: some View {
        if !items.isEmpty {
            SectionTitle("Right now")
            Card {
                ForEach(items, id: \.self) { i in
                    Label {
                        Text(i.text).font(.subheadline)
                    } icon: {
                        Image(systemName: symbol(i.kind)).foregroundStyle(i.kind == .emergency ? .red : .secondary)
                    }
                }
            }
        }
    }

    private func symbol(_ k: Insight.Kind) -> String {
        switch k {
        case .phase: "airplane"
        case .target: "scope"
        case .wind: "wind"
        case .temperature: "thermometer.medium"
        case .place: "mappin.and.ellipse"
        case .emergency: "exclamationmark.triangle.fill"
        }
    }
}

// MARK: Profile

/// Altitude (filled) and ground speed (line) over the recorded trail. Dragging marks the point on the map.
private struct ProfileChart: View {
    @Environment(AppModel.self) private var model
    let track: Track
    @State private var selected: Date?

    var body: some View {
        let pts = sampled
        let maxAlt = Double(max(pts.compactMap(\.alt).max() ?? 1000, 1000))
        let maxGs = max(pts.compactMap(\.gs).max() ?? 100, 100)
        SectionTitle("Flight profile", trailing: scrubText)
        Card {
            Chart {
                ForEach(pts, id: \.time) { p in
                    AreaMark(x: .value("Time", p.time.date), y: .value("Altitude", Double(p.alt ?? 0)))
                        .foregroundStyle(LinearGradient(colors: [.purple.opacity(0.5), .orange.opacity(0.15)], startPoint: .top, endPoint: .bottom))
                        .interpolationMethod(.monotone)
                }
                ForEach(pts, id: \.time) { p in
                    if let gs = p.gs {
                        LineMark(x: .value("Time", p.time.date), y: .value("Speed", gs / maxGs * maxAlt), series: .value("Series", "Speed"))
                            .foregroundStyle(.blue)
                            .lineStyle(StrokeStyle(lineWidth: 1.5))
                            .interpolationMethod(.monotone)
                    }
                }
                if let selected {
                    RuleMark(x: .value("Time", selected)).foregroundStyle(.secondary)
                }
            }
            .chartYScale(domain: 0...maxAlt * 1.1)
            .chartXSelection(value: $selected)
            .chartYAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { v in
                    AxisGridLine()
                    AxisValueLabel { if let d = v.as(Double.self) { Text(Fmt.current.feet(Int(d))) } }
                }
            }
            .chartXAxis { AxisMarks(values: .automatic(desiredCount: 3)) { _ in AxisGridLine(); AxisValueLabel(format: .dateTime.hour().minute()) } }
            .frame(height: 150)
            HStack(spacing: 14) {
                Label("Altitude", systemImage: "square.fill").foregroundStyle(.purple)
                Label("Ground speed", systemImage: "line.diagonal").foregroundStyle(.blue)
            }
            .font(.caption)
        }
        .onChange(of: selected) { _, d in
            model.scrub = d.flatMap { d in
                let ms = Millis(d.timeIntervalSince1970 * 1000)
                return track.trail.min { abs($0.time - ms) < abs($1.time - ms) }
            }
            if d != nil { UISelectionFeedbackGenerator().selectionChanged() }
        }
    }

    private var scrubText: String? {
        guard let p = model.scrub else { return nil }
        return "\(Fmt.current.feet(p.onGround ? 0 : p.alt)) · \(Fmt.current.knots(p.gs)) · \(Fmt.current.time(p.time))"
    }

    /// At most ~200 points (Charts on an iPhone 11 stays smooth, and the shape doesn't change), with ground speed
    /// averaged over neighbours: speeds derived from fix-to-fix distances are noisy.
    private var sampled: [TrailPoint] {
        let t = track.trail
        let pts = t.count <= 200 ? t : (0..<200).map { t[Int(Double($0) * Double(t.count) / 200)] } + [t.last!]
        return pts.indices.map { i in
            var p = pts[i]
            let window = pts[max(i - 3, 0)...min(i + 3, pts.count - 1)].compactMap(\.gs)
            if p.gs != nil, !window.isEmpty { p.gs = window.reduce(0, +) / Double(window.count) }
            return p
        }
    }
}

// MARK: Photos

private struct PhotoPager: View {
    let photos: [Photo]

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 10) {
                ForEach(photos) { p in
                    PhotoCell(photo: p).containerRelativeFrame(.horizontal)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
        .aspectRatio(3 / 2, contentMode: .fit)
    }
}

private struct PhotoCell: View {
    let photo: Photo
    @Environment(\.openURL) private var openURL

    var body: some View {
        AsyncImage(url: photo.fullURL) { phase in
            switch phase {
            case .success(let img):
                // Bottom-aligned: airport-data burns the photographer's strip into the bottom edge; keep it whole.
                img.resizable().scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            case .failure:
                AsyncImage(url: photo.thumbURL) { $0.resizable().scaledToFill() } placeholder: { Color(.tertiarySystemFill) }
            default:
                Color(.tertiarySystemFill).overlay { ProgressView() }
            }
        }
        .aspectRatio(3 / 2, contentMode: .fit)
        .clipShape(.rect(cornerRadius: 22, style: .continuous))
        .overlay(alignment: .topLeading) {
            Text("© \(photo.photographer ?? photo.site)")
                .font(.caption2.weight(.semibold)).foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(.black.opacity(0.45), in: .capsule)
                .padding(10)
        }
        .onTapGesture { if let u = photo.pageURL { openURL(u) } }
        .accessibilityLabel("Photo by \(photo.photographer ?? photo.site)")
    }
}

// MARK: About

private struct AboutAircraft: View {
    let a: Aircraft
    let info: AircraftInfo?

    var body: some View {
        let age = FlightInsights.ageYears(a.year, nowYear: Calendar.current.component(.year, from: .now))
        SectionTitle("About this aircraft")
        Card {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 14) {
                GridRow {
                    Fact(label: "Registration", value: info?.registration ?? a.registration ?? Fmt.none)
                    Fact(label: "Age", value: age.map { $0 == 0 ? "New" : "\($0) yr" } ?? Fmt.none)
                }
                GridRow {
                    Fact(label: "Registered in", value: info?.ownerCountry ?? Fmt.none)
                    Fact(label: "Type code", value: info?.icaoType ?? a.typeCode ?? Fmt.none)
                }
                GridRow {
                    Fact(label: "Size", value: Squawks.category(a.category))
                    Fact(label: "Transponder", value: a.hex.uppercased())
                }
            }
            if let owner = info?.owner ?? a.operator {
                Divider()
                ValueRow(label: "Owner", value: owner)
            }
        }
    }
}

private struct TechnicalDetails: View {
    let a: Aircraft
    @State private var open = false

    var body: some View {
        let f = Fmt.current
        Card {
            DisclosureGroup(isExpanded: $open.animation(.snappy)) {
                VStack(alignment: .leading, spacing: 18) {
                    group("Speed and altitude", [
                        ("Barometric altitude", f.feet(a.altBaro)), ("GPS altitude", f.feet(a.altGeom)),
                        ("Ground speed", f.knots(a.groundSpeed)), ("Indicated airspeed", f.knots(a.ias)),
                        ("True airspeed", f.knots(a.tas)), ("Mach", f.mach(a.mach)),
                        ("Vertical rate", f.verticalRate(a.verticalRate)),
                    ])
                    group("Direction", [
                        ("Track", f.degrees(a.track)), ("Magnetic heading", f.degrees(a.magHeading)),
                        ("True heading", f.degrees(a.trueHeading)), ("Bank angle", a.roll.map { String(format: "%.0f°", $0) } ?? Fmt.none),
                        ("Turn rate", a.trackRate.map { String(format: "%.1f°/s", $0) } ?? Fmt.none),
                    ])
                    group("Autopilot", [
                        ("Selected altitude", f.feet(a.navAltMcp)), ("FMS altitude", f.feet(a.navAltFms)),
                        ("Selected heading", f.degrees(a.navHeading)), ("Pressure setting", f.hpa(a.navQnh)),
                        ("Modes", a.navModes.isEmpty ? Fmt.none : a.navModes.joined(separator: ", ")),
                    ])
                    group("Air outside", [
                        ("Wind", a.windDir.map { d in "\(f.degrees(d)) at \(f.knots(a.windSpeed))" } ?? Fmt.none),
                        ("Outside air", f.celsius(a.oat)), ("Total air", f.celsius(a.tat)),
                    ])
                    group("Transponder and signal", [
                        ("Squawk", a.squawk.map { s in Squawks.meaning(s).map { "\(s) · \($0)" } ?? s } ?? Fmt.none),
                        ("Position from", a.source.rawValue), ("ADS-B version", a.adsbVersion.map(String.init) ?? Fmt.none),
                        ("Navigation accuracy", a.nacP.map { "NACp \($0)" } ?? Fmt.none), ("Integrity", a.nic.map { "NIC \($0)" } ?? Fmt.none),
                        ("Signal", a.rssi.map { String(format: "%.1f dBFS", $0) } ?? Fmt.none),
                        ("Messages", a.messages.map { $0.formatted() } ?? Fmt.none),
                    ])
                }
                .padding(.top, 12)
            } label: {
                Text("Technical details").font(.headline).foregroundStyle(.primary)
            }
        }
    }

    private func group(_ title: String, _ rows: [(String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(rows, id: \.0) { r in ValueRow(label: r.0, value: r.1) }
        }
    }
}
