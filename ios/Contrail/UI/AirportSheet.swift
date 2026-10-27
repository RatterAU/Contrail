import SwiftUI

/// An airport: local time, live weather, the runway in use, and arrivals/departures built from live traffic.
struct AirportSheet: View {
    @Environment(AppModel.self) private var model
    let airport: Airport
    @State private var board = 0

    var body: some View {
        let state = model.airportState
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                WeatherCard(metar: state?.metar, loaded: state?.weatherLoaded ?? false)
                if let state, !state.runways.isEmpty { RunwaysCard(state: state, tracks: model.tracks) }
                boards(state)
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 30)
        }
        .task { await model.followAirport(airport) }
    }

    private var header: some View {
        SheetHeader(close: { model.select(nil) }) {
            EmptyView()
        } title: {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(airport.code).font(.largeTitle.weight(.bold))
                if let icao = airport.icao, icao != airport.code { Text(icao).font(.title3).foregroundStyle(.secondary) }
            }
            Text(airport.name).font(.subheadline.weight(.medium))
            TimelineView(.everyMinute) { ctx in
                let ms = Millis(ctx.date.timeIntervalSince1970 * 1000)
                let c = Fmt.current.clockParts(ms, tz: airport.tz, place: airport.shortName)
                Text([c.zone == nil ? "Local time \(c.time)" : "\(c.time) local", airport.country].compactMap { $0 }.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(.secondary).monospacedDigit()
            }
        }
    }

    @ViewBuilder private func boards(_ state: AirportState?) -> some View {
        let b = state.map { AirportBoards.build(airport, model.tracks.values, routes: $0.routes, now: .now, runways: $0.runways) }
        Picker("Board", selection: $board) {
            Text("Arrivals").tag(0)
            Text("Departures").tag(1)
        }
        .pickerStyle(.segmented)
        .padding(.top, 6)
        let rows = board == 0 ? b?.arrivals ?? [] : b?.departures ?? []
        Card(padding: 0) {
            if state?.routesLoaded != true && rows.isEmpty {
                HStack { ProgressView(); Text("Looking at nearby traffic…").foregroundStyle(.secondary) }.padding(16)
            } else if rows.isEmpty {
                Text(board == 0 ? "No arrivals in range right now." : "No departures in range right now.")
                    .foregroundStyle(.secondary).padding(16)
            } else {
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, r in
                    if i > 0 { Divider().padding(.leading, 16) }
                    BoardRowView(row: r, arrival: board == 0, airport: airport)
                }
            }
        }
        Text("Built from live positions within \(Fmt.current.nm(Double(AppModel.boardRadiusNm))). There's no published schedule, so flights further out aren't listed yet.")
            .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 4)
    }
}

private struct BoardRowView: View {
    @Environment(AppModel.self) private var model
    let row: BoardRow
    let arrival: Bool
    let airport: Airport

    var body: some View {
        let a = row.track.aircraft
        let other = arrival ? row.route?.origin : row.route?.destination
        Button {
            model.select(.aircraft(row.track.hex))
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.route?.flightNumber ?? a.label).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    Text(other.map { "\(arrival ? "From" : "To") \($0.shortName)" } ?? (arrival ? "Arriving" : "Departing"))
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 3) {
                    if let t = row.time {
                        let c = Fmt.current.clockParts(t, tz: airport.tz, place: airport.shortName)
                        Text((row.timeEstimated ? "~" : "") + c.time).font(.body.weight(.medium)).monospacedDigit().foregroundStyle(.primary)
                    }
                    status
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private var status: Pill {
        switch row.status {
        case .landed: Pill(text: "Landed", color: .green)
        case .approach: Pill(text: "On approach", color: .orange)
        case .enRoute: Pill(text: "En route", color: .blue)
        case .taxiing: Pill(text: "Taxiing", color: .gray)
        case .onGround: Pill(text: "At gate", color: .gray)
        case .departed: Pill(text: "Departed", color: .blue)
        case .justDeparted: Pill(text: "Just departed", color: .green)
        }
    }
}

private struct WeatherCard: View {
    let metar: Metar?
    let loaded: Bool

    var body: some View {
        SectionTitle("Weather", trailing: metar?.observed.map { "Observed \(Fmt.ago(Millis.now - $0))" })
        Card {
            if let m = metar {
                HStack(alignment: .firstTextBaseline) {
                    Text(m.temp.map { "\(Int($0.rounded()))°" } ?? Fmt.none).font(.system(size: 44, weight: .semibold, design: .rounded))
                    Spacer()
                    if let cat = m.category { Pill(text: cat, color: color(cat)) }
                }
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 12) {
                    GridRow {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Wind").font(.caption).foregroundStyle(.secondary)
                            HStack(spacing: 5) {
                                if let d = m.windDir, (m.windSpeed ?? 0) > 0 {
                                    // Arrow points where the wind blows to.
                                    Image(systemName: "location.north.fill").rotationEffect(.degrees(d + 180)).foregroundStyle(.tint)
                                }
                                Text(windText(m)).font(.headline).monospacedDigit()
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Fact(label: "Visibility", value: m.visibilityText)
                    }
                    GridRow {
                        Fact(label: "Cloud", value: m.cloudText, lines: 3)
                        Fact(label: "Dew point · QNH", value: "\(m.dewpoint.map { "\(Int($0.rounded()))°" } ?? Fmt.none) · \(m.qnh.map { "\(Int($0.rounded()))" } ?? Fmt.none)")
                    }
                }
                Text(m.raw).font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
            } else if loaded {
                Label("No weather report for this airport.", systemImage: "cloud.slash").foregroundStyle(.secondary)
            } else {
                Skeleton(width: 90, height: 36)
                Skeleton(height: 14)
            }
        }
    }

    private func windText(_ m: Metar) -> String {
        guard let s = m.windSpeed, s > 0 else { return "Calm" }
        let dir = m.windVariable ? "Variable" : m.windDir.map { Geo.compass($0) } ?? ""
        let gust = m.gust.map { " G\(Int($0))" } ?? ""
        return "\(dir) \(Fmt.current.knots(s))\(gust)"
    }

    private func color(_ cat: String) -> Color {
        switch cat {
        case "VFR": .green
        case "MVFR": .blue
        case "IFR": .red
        default: .purple
        }
    }
}

private struct RunwaysCard: View {
    let state: AirportState
    let tracks: [String: Track]

    var body: some View {
        let active = AirportBoards.activeRunway(state.airport, state.runways, tracks.values, windDir: state.metar?.windDir)
        SectionTitle("Runways")
        Card {
            ForEach(state.runways, id: \.self) { r in
                HStack {
                    Text("\(r.leIdent)/\(r.heIdent)").font(.body.weight(.semibold).monospacedDigit())
                    if let a = active, a.ident == r.leIdent || a.ident == r.heIdent {
                        Pill(text: "\(a.ident) in use", color: .green)
                    }
                    Spacer()
                    Text([r.lengthFt.map { Fmt.current.feet($0) }, surface(r.surface)].compactMap { $0 }.joined(separator: " · "))
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if let a = active, !a.fromTraffic {
                Text("Runway in use is likely, from the wind.").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func surface(_ s: String) -> String? {
        let u = s.uppercased()
        if u.isEmpty { return nil }
        if u.hasPrefix("ASP") || u.hasPrefix("BIT") || u.contains("ASPH") { return "Asphalt" }
        if u.hasPrefix("CON") { return "Concrete" }
        if u.hasPrefix("GRS") || u.hasPrefix("GRASS") || u.hasPrefix("TURF") { return "Grass" }
        if u.hasPrefix("GRV") || u.hasPrefix("GRAVEL") { return "Gravel" }
        if u.hasPrefix("DIRT") || u.hasPrefix("CLA") || u.hasPrefix("SAN") { return "Dirt" }
        if u.hasPrefix("WAT") { return "Water" }
        return s.capitalized
    }
}
