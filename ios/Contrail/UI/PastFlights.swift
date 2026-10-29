import Charts
import SwiftUI

// MARK: Past flights

/// Earlier flights from the daily history (last 14 days, up to yesterday), newest first, grouped by day, under a short
/// summary. Each end shows only what was seen: a scheduled airport says so, and an unseen takeoff or landing gives the
/// time it must have been before (or after).
struct PastFlights: View {
    @Environment(AppModel.self) private var model
    let hex: String
    /// nil while loading; a failed download says so rather than hiding the card.
    @State private var flights: [PastFlight]?
    @State private var failed = false
    @State private var all = false
    private static let preview = 6

    var body: some View {
        // A VStack, not a Group: an empty Group drops .task, so the flights would never load.
        VStack(alignment: .leading, spacing: 14) {
            if let flights {
                SectionTitle("Past flights", trailing: "Last 14 days")
                Card {
                    if failed {
                        Text("Couldn't load past flights. Check your connection.").foregroundStyle(.secondary)
                    } else if flights.isEmpty {
                        Text("Not seen flying in the last 14 days.").foregroundStyle(.secondary)
                    } else {
                        Summary(flights: flights)
                    }
                    let shown = all ? flights : Array(flights.prefix(Self.preview))
                    ForEach(Array(shown.enumerated()), id: \.offset) { i, f in
                        let day = PastFormat.day(f)
                        if i == 0 || PastFormat.day(shown[i - 1]) != day {
                            Text(PastFormat.dayName(day).uppercased())
                                .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                                .padding(.top, i == 0 ? 4 : 10)
                        } else {
                            Divider()
                        }
                        Button { model.openPast(f, all: flights) } label: { PastRow(f: f).contentShape(.rect) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("pastFlight")
                    }
                    if flights.count > Self.preview && !all {
                        Button("Show all \(flights.count) flights") { withAnimation(.snappy) { all = true } }
                            .padding(.top, 4)
                    }
                }
                Text("From adsb.lol's open data. Times are local to each airport.")
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 4)
            }
        }
        .task(id: hex) {
            flights = nil
            let result = await model.history.past(hex)
            failed = result == nil
            flights = result ?? []
        }
    }
}

/// Flights, airports and the most-flown route over the fortnight, from seen ends only.
private struct Summary: View {
    let flights: [PastFlight]

    var body: some View {
        let airports = Set(flights.flatMap { [$0.from, $0.to].compactMap { $0 } })
        let pairs = flights.compactMap { f -> [String]? in
            guard let a = f.from, let b = f.to, a != b else { return nil }
            return [a, b].sorted()
        }
        let top = Dictionary(grouping: pairs, by: { $0 }).max { $0.value.count < $1.value.count }.flatMap { $0.value.count >= 2 ? $0 : nil }
        HStack(spacing: 8) {
            tile("\(flights.count)", "Flights")
            tile(airports.isEmpty ? Fmt.none : "\(airports.count)", "Airports")
            if let top {
                tile("\(top.key.map(PastFormat.code).joined(separator: "–")) ×\(top.value.count)", "Most flown")
            }
        }
    }

    private func tile(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.headline).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.quaternarySystemFill), in: .rect(cornerRadius: 12, style: .continuous))
    }
}

private struct PastRow: View {
    let f: PastFlight

    var body: some View {
        HStack(spacing: 6) {
            PastEnd(f: f, departing: true)
            VStack(spacing: 3) {
                Text(f.callsign ?? " ").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                FlightLine()
                Text(PastFormat.duration(f) ?? " ").font(.caption2).monospacedDigit().foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            PastEnd(f: f, departing: false)
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
    }
}

/// Code, city and time for one end of a past flight, the way the route card shows a live one.
private struct PastEnd: View {
    let f: PastFlight
    let departing: Bool
    var large = false

    var body: some View {
        let seen = departing ? f.from : f.to
        let scheduled = departing ? f.scheduledFrom : f.scheduledTo
        let ap = PastFormat.airport(seen ?? scheduled)
        VStack(alignment: departing ? .leading : .trailing, spacing: 1) {
            Text((seen ?? scheduled).map(PastFormat.code) ?? "—")
                .font(large ? .title.weight(.bold) : .title3.weight(.semibold))
                .foregroundStyle(seen != nil ? .primary : .secondary)
            Text(ap?.shortName ?? seen ?? scheduled ?? "Not seen").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(PastFormat.endTime(f, departing: departing, long: large))
                .font(.caption2).monospacedDigit()
                .foregroundStyle(seen == nil && scheduled != nil ? Color.accentColor : .secondary)
                .lineLimit(large ? 2 : 1)
                .multilineTextAlignment(departing ? .leading : .trailing)
        }
        .frame(minWidth: large ? 100 : 76, alignment: departing ? .leading : .trailing)
    }
}

/// A short line with a plane on it, between the two ends.
private struct FlightLine: View {
    var body: some View {
        HStack(spacing: 4) {
            Capsule().fill(.quaternary).frame(height: 1.5)
            Image(systemName: "airplane").font(.caption).foregroundStyle(.secondary)
            Capsule().fill(.quaternary).frame(height: 1.5)
        }
    }
}

@MainActor enum PastFormat {
    static func airport(_ code: String?) -> Airport? { code.flatMap { StaticData.now()?.airport($0) } }
    static func code(_ icao: String) -> String { airport(icao)?.code ?? icao }
    static func zone(_ ap: Airport?) -> TimeZone { ap?.tz.flatMap(TimeZone.init(identifier:)) ?? .current }

    /// The day it took off, in the departure airport's time.
    static func day(_ f: PastFlight) -> DateComponents {
        var cal = Calendar.current
        cal.timeZone = zone(airport(f.from ?? f.scheduledFrom))
        return cal.dateComponents([.year, .month, .day], from: f.departed.date)
    }

    static func dayName(_ d: DateComponents) -> String {
        guard let date = Calendar.current.date(from: d) else { return "" }
        if Calendar.current.isDateInYesterday(date) { return "Yesterday" }
        if Calendar.current.isDateInToday(date) { return "Today" }
        return date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
    }

    /// Each clock is in its own airport's zone, so the duration says how long it really took.
    static func duration(_ f: PastFlight) -> String? { f.departedSeen && f.arrivedSeen ? Fmt.duration(f.arrived - f.departed) : nil }

    /// "10:05", or for an unseen takeoff the time it must have been before (first seen), and for an unseen landing the
    /// time it must have been after (last seen). A scheduled end says so instead.
    static func endTime(_ f: PastFlight, departing: Bool, long: Bool) -> String {
        let seen = departing ? f.departedSeen : f.arrivedSeen
        let code = departing ? f.from ?? f.scheduledFrom : f.to ?? f.scheduledTo
        let t = Fmt.current.time(departing ? f.departed : f.arrived, zone: zone(airport(code)))
        let scheduled = (departing ? f.from : f.to) == nil && (departing ? f.scheduledFrom : f.scheduledTo) != nil
        if seen { return long ? "\(departing ? "Took off" : "Landed") \(t)" : t }
        if scheduled { return long ? "Scheduled, not seen" : "Scheduled" }
        if departing { return long ? "Took off before \(t)" : "before \(t)" }
        return long ? "Landed after \(t)" : "after \(t)"
    }
}

// MARK: Past flight

/// One earlier flight, opened from Past flights. The map draws its path in place of the live trail and the plane where
/// the replay is; this shows the two ends in their own local times, the replay, and what the path says.
struct PastFlightSheet: View {
    @Environment(AppModel.self) private var model
    let view: AppModel.PastView
    @State private var logo: UIImage?

    var body: some View {
        let f = view.flight
        let from = PastFormat.airport(f.from ?? f.scheduledFrom)
        let code = StaticData.airlineCode(f.callsign)
        let airline = StaticData.now()?.airlineForCallsign(f.callsign)?.name
        let index = view.all.firstIndex(of: f)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    if let logo {
                        let wide = logo.size.width > logo.size.height * 1.6
                        Image(uiImage: logo).resizable().scaledToFit()
                            .frame(width: wide ? 72 : 44, height: wide ? 28 : 44)
                            .padding(wide ? 0 : 4)
                            .background(wide ? .clear : .white, in: .rect(cornerRadius: 10, style: .continuous))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(f.callsign ?? "Past flight").font(.largeTitle.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
                        Text([airline, PastFormat.dayName(PastFormat.day(f))].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Button { model.closePast() } label: { Label("Back", systemImage: "chevron.left").font(.subheadline.weight(.semibold)) }
                        .buttonStyle(.glass)
                }
                Card {
                    HStack(alignment: .center) {
                        PastEnd(f: f, departing: true, large: true)
                        VStack(spacing: 3) {
                            FlightLine()
                            if let d = PastFormat.duration(f) { Text(d).font(.footnote).monospacedDigit().foregroundStyle(.secondary) }
                        }
                        .frame(maxWidth: .infinity)
                        PastEnd(f: f, departing: false, large: true)
                    }
                    if (f.from == nil && f.scheduledFrom != nil) || (f.to == nil && f.scheduledTo != nil) {
                        Text("Scheduled: from the flight's published route, which its path matches. It wasn't seen there.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if let path = view.path, !view.failed, path.count >= 2 {
                    if path.filter({ $0.alt != nil || $0.onGround }).count >= 3 {
                        ReplayCard(path: path, from: from).id(f.departed)
                    }
                    let st = FlightHistory.stats(path)
                    Card {
                        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 14) {
                            GridRow {
                                Fact(label: "Distance flown", value: Fmt.current.nm(st.distanceNm))
                                Fact(label: "Highest", value: Fmt.current.feet(st.topAltFt))
                            }
                            GridRow {
                                Fact(label: "Top speed", value: Fmt.current.knots(st.topSpeedKt))
                                Fact(label: "Tracked for", value: Fmt.duration(path[path.count - 1].time - path[0].time))
                            }
                        }
                    }
                } else {
                    Card {
                        HStack(spacing: 10) {
                            if view.path == nil { ProgressView() }
                            Text(view.path == nil ? "Loading the flown path…"
                                 : view.failed ? "Couldn't load the flown path. Check your connection."
                                 : "The path for this flight wasn't recorded.").foregroundStyle(.secondary)
                        }
                    }
                }
                if let index, view.all.count > 1 {
                    // The list is newest first: "Earlier" steps down it.
                    HStack {
                        if index + 1 < view.all.count {
                            Button { model.openPast(view.all[index + 1]) } label: { Label("Earlier", systemImage: "chevron.left") }
                                .buttonStyle(.bordered)
                        }
                        Spacer()
                        Text("\(view.all.count - index) of \(view.all.count)").font(.footnote).monospacedDigit().foregroundStyle(.secondary)
                        Spacer()
                        if index > 0 {
                            Button { model.openPast(view.all[index - 1]) } label: { Text("Later") }
                                .buttonStyle(.bordered)
                        }
                    }
                }
                Text("Path from adsb.lol's open data, as its receivers heard it: gaps are where none did.")
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 4)
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 30)
        }
        .task(id: code) {
            logo = nil
            if let code { logo = await AirlineLogos.shared.get(code) }
        }
    }
}

// MARK: Replay

/// The replay's moment and state. Only the views that show the moment read it, so playing redraws those, not the chart.
@MainActor @Observable
private final class ReplayClock {
    var t: Millis
    var playing = false
    init(_ t: Millis) { self.t = t }
}

/// Plays the flight back: the plane moves along its path on the map, the clock shows the moment in the departure
/// airport's time, and the profile underneath is the timeline, dragged to jump anywhere.
private struct ReplayCard: View {
    @Environment(AppModel.self) private var model
    let path: [TrailPoint]
    let from: Airport?
    @State private var clock: ReplayClock
    @State private var speed = 1
    @State private var picked: Date?
    /// Replay speeds: a one-hour flight takes a minute at 60×, a long-haul about a minute at 1000×.
    private static let speeds: [Millis] = [10, 60, 300, 1000]

    init(path: [TrailPoint], from: Airport?) {
        self.path = path
        self.from = from
        _clock = State(initialValue: ReplayClock(path[0].time))
    }

    var body: some View {
        let start = path[0].time, end = path[path.count - 1].time
        SectionTitle("Replay")
        Card {
            ReplayReadout(clock: clock, path: path, from: from)
            ReplayChart(path: path, clock: clock, picked: $picked)
            HStack(spacing: 12) {
                PlayButton(clock: clock, end: end)
                Picker("Speed", selection: $speed) {
                    ForEach(Self.speeds.indices, id: \.self) { i in Text(verbatim: "\(Self.speeds[i])×").tag(i) }
                }
                .pickerStyle(.segmented)
            }
            Text("Drag along the profile to jump to any moment.").font(.footnote).foregroundStyle(.secondary)
        }
        .onAppear { model.replay = Replay.at(path, clock.t) }
        .onDisappear { model.replay = nil }
        .onChange(of: picked) { _, d in
            guard let d else { return }
            clock.playing = false
            clock.t = min(max(Millis(d.timeIntervalSince1970 * 1000), start), end)
            model.replay = Replay.at(path, clock.t)
        }
        .task(id: clock.playing) {
            guard clock.playing else { return }
            if clock.t >= end { clock.t = start }
            var last = Date()
            while clock.playing {
                // The map follows at 30 frames a second; a cancelled sleep ends the loop.
                do { try await Task.sleep(for: .milliseconds(33)) } catch { return }
                let now = Date()
                clock.t = min(clock.t + Millis(now.timeIntervalSince(last) * 1000) * Self.speeds[speed], end)
                last = now
                model.replay = Replay.at(path, clock.t)
                if clock.t >= end { clock.playing = false }
            }
        }
    }
}

private struct PlayButton: View {
    let clock: ReplayClock
    let end: Millis

    var body: some View {
        Button { clock.playing.toggle() } label: {
            Image(systemName: clock.playing ? "pause.fill" : clock.t >= end ? "arrow.counterclockwise" : "play.fill")
                .font(.title3).frame(width: 44, height: 44)
                .foregroundStyle(.white)
                .background(Color.accentColor, in: .circle)
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(clock.playing ? "Pause" : "Play")
    }
}

/// The moment: clock in the departure airport's time, time since the start, height and speed.
private struct ReplayReadout: View {
    let clock: ReplayClock
    let path: [TrailPoint]
    let from: Airport?

    var body: some View {
        let f = Replay.at(path, clock.t)
        let (time, zone) = Fmt.current.clockParts(clock.t, tz: from?.tz, place: from?.shortName)
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 1) {
                Text(time).font(.largeTitle.weight(.semibold)).monospacedDigit()
                Text([zone, "+\(Fmt.duration(clock.t - path[0].time))"].compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote).monospacedDigit().foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text(f.map { $0.onGround ? "On the ground" : Fmt.current.feet($0.alt) } ?? Fmt.none).font(.headline).monospacedDigit()
                Text(Fmt.current.knots(f?.gs)).font(.footnote).monospacedDigit().foregroundStyle(.secondary)
                if f?.gap == true { Text("No coverage here").font(.caption).foregroundStyle(Color.accentColor) }
            }
        }
    }
}

/// Altitude and speed against time, with the replay's moment as a line. The chart itself doesn't read the clock, so
/// playing only moves the line.
private struct ReplayChart: View {
    let path: [TrailPoint]
    let clock: ReplayClock
    @Binding var picked: Date?

    var body: some View {
        let pts = sampled
        let maxAlt = Double(max(pts.compactMap(\.alt).max() ?? 1000, 1000))
        let maxGs = max(pts.compactMap(\.gs).max() ?? 100, 100)
        VStack(alignment: .leading, spacing: 8) {
            Chart {
                ForEach(pts, id: \.time) { p in
                    AreaMark(x: .value("Time", p.time.date), y: .value("Altitude", Double(p.onGround ? 0 : p.alt ?? 0)))
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
            }
            .chartYScale(domain: 0...maxAlt * 1.1)
            .chartXScale(domain: path[0].time.date...path[path.count - 1].time.date)
            .chartXSelection(value: $picked)
            .chartYAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { v in
                    AxisGridLine()
                    AxisValueLabel { if let d = v.as(Double.self) { Text(Fmt.current.feet(Int(d))) } }
                }
            }
            .chartXAxis(.hidden)
            .chartOverlay { proxy in
                GeometryReader { geo in ReplayCursor(clock: clock, proxy: proxy, geo: geo) }
            }
            .frame(height: 130)
            HStack(spacing: 14) {
                Label("Altitude", systemImage: "square.fill").foregroundStyle(.purple)
                Label("Ground speed", systemImage: "line.diagonal").foregroundStyle(.blue)
            }
            .font(.caption)
        }
    }

    /// At most ~200 points: Charts stays smooth and the shape doesn't change.
    private var sampled: [TrailPoint] {
        let t = path.filter { $0.alt != nil || $0.onGround }
        return t.count <= 200 ? t : (0..<200).map { t[Int(Double($0) * Double(t.count) / 200)] } + [t[t.count - 1]]
    }
}

private struct ReplayCursor: View {
    let clock: ReplayClock
    let proxy: ChartProxy
    let geo: GeometryProxy

    var body: some View {
        if let plot = proxy.plotFrame, let x = proxy.position(forX: clock.t.date) {
            let origin = geo[plot].origin
            Rectangle().fill(.primary).frame(width: 1.5, height: geo[plot].height)
                .position(x: origin.x + x, y: origin.y + geo[plot].height / 2)
                .allowsHitTesting(false)
        }
    }
}
