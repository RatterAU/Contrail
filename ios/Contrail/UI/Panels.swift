import SwiftUI

/// Panels presented over the main sheet: layers, filters, settings, about.
struct PanelView: View {
    let panel: Panel

    var body: some View {
        NavigationStack {
            switch panel {
            case .layers: LayersPanel()
            case .filters: FiltersPanel()
            case .settings: SettingsPanel()
            case .about: AboutPanel()
            }
        }
        .presentationDetents(panel == .layers || panel == .filters ? [.medium, .large] : [.large])
        .presentationDragIndicator(.visible)
    }
}

private struct DoneButton: ToolbarContent {
    @Environment(\.dismiss) private var dismiss
    var body: some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button("Done", systemImage: "checkmark") { dismiss() }
        }
    }
}

private struct LayersPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var s = model.settings
        Form {
            Section {
                Picker("Map", selection: $s.map.kind) {
                    Text("Muted").tag(MapKind.muted)
                    Text("Streets").tag(MapKind.standard)
                    Text("Satellite").tag(MapKind.satellite)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            Section {
                Picker("Appearance", selection: $s.map.appearance) {
                    Text("Automatic").tag(MapAppearance.auto)
                    Text("Dark").tag(MapAppearance.dark)
                    Text("Light").tag(MapAppearance.light)
                }
            }
            Section("Weather") {
                Toggle(isOn: $s.map.radar) { Label { row("Rain radar", "Animated, last hour") } icon: { Image(systemName: "cloud.rain") } }
                Toggle(isOn: $s.map.clouds) { Label { row("Clouds", "Satellite, day and night, every 10 min") } icon: { Image(systemName: "cloud") } }
                Toggle(isOn: $s.map.hazards) { Label { row("Hazards", "Turbulence, icing, storms, ash and cyclones") } icon: { Image(systemName: "exclamationmark.triangle") } }
                Picker(selection: $s.map.wind) {
                    Text("Off").tag(WindLevel?.none)
                    ForEach(WindLevel.allCases, id: \.self) { Text($0.label).tag(WindLevel?.some($0)) }
                } label: {
                    Label { row("Wind", "Animated, from Open-Meteo") } icon: { Image(systemName: "wind") }
                }
            }
            Section {
                Toggle(isOn: $s.map.airports) { Label { row("Airports", "Smaller airfields appear as you zoom in") } icon: { Image(systemName: "building.2") } }
                Toggle(isOn: $s.map.labels) { Label { row("Callsign labels", nil) } icon: { Image(systemName: "textformat") } }
                Toggle(isOn: $s.map.allTrails) { Label { row("Trails for every aircraft", "Uses more battery") } icon: { Image(systemName: "scribble") } }
            }
            Section("Altitude colours") { AltitudeKey() }
        }
        .navigationTitle("Map")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { DoneButton() }
    }

    private func row(_ title: String, _ subtitle: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let subtitle { Text(subtitle).font(.footnote).foregroundStyle(.secondary) }
        }
    }
}

/// The altitude ramp as a bar with labels.
struct AltitudeKey: View {
    var body: some View {
        let stops = stride(from: 0, through: 40_000, by: 2_000).map { Color(AltitudeColors.color(altitude: $0)) }
        VStack(spacing: 6) {
            Capsule().fill(LinearGradient(colors: [Color(AltitudeColors.color(band: AltitudeColors.groundBand))] + stops, startPoint: .leading, endPoint: .trailing))
                .frame(height: 10)
            HStack {
                Text("Ground")
                Spacer()
                Text(Fmt.current.feet(10_000))
                Spacer()
                Text(Fmt.current.feet(40_000))
            }
            .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct FiltersPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var s = model.settings
        let f = s.map.filters
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Altitude")
                        Spacer()
                        Text(rangeText(f)).foregroundStyle(.secondary).monospacedDigit()
                    }
                    RangeSlider(low: $s.map.filters.altMinFt, high: $s.map.filters.altMaxFt, bounds: 0...Filters.maxAlt, step: 1000)
                }
                Toggle("Hide aircraft on the ground", isOn: $s.map.filters.hideGround)
                Toggle("Only military and notable", isOn: $s.map.filters.specialOnly)
            } footer: {
                Text("Emergencies always show.")
            }
            Section("Show") {
                ForEach(AircraftKind.allCases) { k in
                    Toggle(isOn: Binding(
                        get: { s.map.filters.kinds.contains(k) },
                        set: { on in if on { s.map.filters.kinds.insert(k) } else { s.map.filters.kinds.remove(k) } }
                    )) { Label(k.label, systemImage: k.symbol) }
                }
            }
            if f.active {
                Section {
                    Button("Reset filters", role: .destructive) { s.map.filters = Filters() }
                }
            }
        }
        .navigationTitle("Filters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { DoneButton() }
    }

    private func rangeText(_ f: Filters) -> String {
        let hi = f.altMaxFt >= Filters.maxAlt ? "any" : Fmt.current.feet(f.altMaxFt)
        return f.altMinFt == 0 && f.altMaxFt >= Filters.maxAlt ? "All" : "\(Fmt.current.feet(f.altMinFt)) to \(hi)"
    }
}

/// Two-thumb slider (SwiftUI has none). Snaps to `step`, with a selection haptic per step.
struct RangeSlider: View {
    @Binding var low: Int
    @Binding var high: Int
    let bounds: ClosedRange<Int>
    let step: Int

    var body: some View {
        GeometryReader { g in
            let w = g.size.width - 28
            let span = Double(bounds.upperBound - bounds.lowerBound)
            let x = { (v: Int) in CGFloat(Double(v - bounds.lowerBound) / span) * w }
            ZStack(alignment: .leading) {
                Capsule().fill(.fill.tertiary).frame(height: 4).padding(.horizontal, 14)
                Capsule().fill(.tint).frame(width: max(x(high) - x(low), 0), height: 4).offset(x: x(low) + 14)
                thumb.offset(x: x(low)).gesture(drag(w) { v in low = min(v, high - step) })
                thumb.offset(x: x(high)).gesture(drag(w) { v in high = max(v, low + step) })
            }
            .frame(maxHeight: .infinity)
        }
        .frame(height: 32)
        .sensoryFeedback(.selection, trigger: low)
        .sensoryFeedback(.selection, trigger: high)
        .accessibilityElement()
        .accessibilityLabel("Altitude range")
        .accessibilityValue("\(low) to \(high) feet")
        .accessibilityAdjustableAction { d in
            switch d {
            case .increment: high = min(high + step, bounds.upperBound)
            case .decrement: high = max(high - step, low + step)
            default: break
            }
        }
    }

    private var thumb: some View {
        Circle().fill(.white).frame(width: 28, height: 28).shadow(color: .black.opacity(0.18), radius: 4, y: 2)
    }

    private func drag(_ w: CGFloat, _ set: @escaping (Int) -> Void) -> some Gesture {
        DragGesture(minimumDistance: 0).onChanged { g in
            let f = min(max((g.location.x - 14) / w, 0), 1)
            let raw = Double(bounds.lowerBound) + Double(f) * Double(bounds.upperBound - bounds.lowerBound)
            set(min(max(Int((raw / Double(step)).rounded()) * step, bounds.lowerBound), bounds.upperBound))
        }
    }
}

private struct SettingsPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var s = model.settings
        Form {
            Section("Units") {
                Picker("Altitude", selection: $s.units.altitude) {
                    Text("Feet").tag(AltitudeUnit.feet)
                    Text("Metres").tag(AltitudeUnit.metres)
                }
                Picker("Speed", selection: $s.units.speed) {
                    Text("Knots").tag(SpeedUnit.knots)
                    Text("km/h").tag(SpeedUnit.kmh)
                    Text("mph").tag(SpeedUnit.mph)
                }
                Picker("Distance", selection: $s.units.distance) {
                    Text("Kilometres").tag(DistanceUnit.km)
                    Text("Nautical miles").tag(DistanceUnit.nm)
                    Text("Miles").tag(DistanceUnit.mi)
                }
            }
            Section {
                Picker("Clock", selection: $s.units.clock) {
                    Text("System").tag(ClockStyle.system)
                    Text("12-hour").tag(ClockStyle.h12)
                    Text("24-hour").tag(ClockStyle.h24)
                }
            } header: {
                Text("Time")
            } footer: {
                Text("Flight times always show in the airport's own time zone.")
            }
        }
        .navigationTitle("Settings")
        .toolbar { DoneButton() }
    }
}

private struct AboutPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        Form {
            Section {
                VStack(spacing: 8) {
                    Image(systemName: "airplane").font(.system(size: 34, weight: .semibold)).foregroundStyle(.white)
                        .frame(width: 72, height: 72)
                        .background(LinearGradient(colors: [.blue, .indigo], startPoint: .topLeading, endPoint: .bottomTrailing), in: .rect(cornerRadius: 16, style: .continuous))
                    Text("Contrail").font(.title2.weight(.bold))
                    Text("Live flights overhead. No ads, no accounts.").font(.subheadline).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                LabeledContent("Version", value: version)
                LabeledContent("Accounts or tracking", value: "None")
            }
            Section("Live feeds") {
                ForEach(["adsb.lol", "adsb.fi"], id: \.self) { name in
                    let h = model.health[name]
                    LabeledContent {
                        Text(h.map { $0.ok ? "\($0.latencyMs ?? 0) ms" : "Busy, retrying in a minute" } ?? "Not used yet").monospacedDigit()
                    } label: {
                        Label {
                            Text(name)
                        } icon: {
                            Circle().fill(h == nil ? .gray : h!.ok ? .green : .orange).frame(width: 9, height: 9)
                        }
                    }
                }
            }
            Section {
                credit("Aircraft positions", "adsb.lol and adsb.fi, from volunteer receivers")
                credit("Flown path", "OpenSky Network")
                credit("Aircraft and routes", "adsbdb.com")
                credit("Photos", "airport-data.com and its photographers")
                credit("Weather", "aviationweather.gov")
                credit("Rain radar", "RainViewer")
                credit("Wind", "Open-Meteo (CC BY 4.0)")
                credit("Satellite clouds", "NASA GIBS: Himawari and GOES")
                credit("Hazard areas", "aviationweather.gov SIGMETs")
                credit("Route legs", "adsb.lol")
                credit("Past flights", "adsb.lol daily open data (ODbL)")
                credit("Airports and airlines", "OurAirports, OpenFlights")
                credit("Airline logos", "Jxck-S and sexym0nk3y on GitHub")
            } header: {
                Text("Data")
            } footer: {
                Text("Routes come from crowd-sourced data and are only shown as fact when the plane's position matches them. Times marked est. are estimates.")
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { DoneButton() }
    }

    private func credit(_ what: String, _ who: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(what)
            Text(who).font(.footnote).foregroundStyle(.secondary)
        }
    }
}
