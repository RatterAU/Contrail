import SwiftUI

/// The collapsed sheet: a search field. Typing searches live traffic, the feeds (flight number, registration, hex,
/// type, squawk) and airports.
struct HomeSheet: View {
    @Environment(AppModel.self) private var model
    var expand: () -> Void

    @State private var query = ""
    @State private var results: SearchResults?
    @State private var searching = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Flight, registration or airport", text: $query)
                        .focused($focused)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                    if searching { ProgressView().controlSize(.small) }
                    if !query.isEmpty {
                        Button("Clear", systemImage: "xmark.circle.fill") { query = "" }
                            .labelStyle(.iconOnly).foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 14)
                .frame(height: 48)
                .glassEffect(.regular.interactive(), in: .capsule)

                if focused {
                    Button("Cancel") {
                        query = ""
                        focused = false
                    }
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 18)
            .animation(.snappy, value: focused)

            ScrollView {
                if let results {
                    SearchResultsList(results: results) {
                        query = ""
                        focused = false
                    }
                } else if query.trimmingCharacters(in: .whitespaces).count < 2 {
                    suggestions
                }
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .onChange(of: focused) { _, f in if f { expand() } }
        .task(id: query) {
            // Debounce typing: search once the user pauses.
            guard query.trimmingCharacters(in: .whitespaces).count >= 2 else {
                results = nil
                return
            }
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            searching = true
            let r = await model.search(query)
            if !Task.isCancelled { results = r }
            searching = false
        }
    }

    private var suggestions: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle("Explore")
            Card(padding: 0) {
                VStack(spacing: 0) {
                    suggestion("Planes near me", "location.circle.fill", .blue) { model.showNearby = true }
                    Divider().padding(.leading, 56)
                    suggestion("Sky view", "camera.viewfinder", .indigo) { model.showSky = true }
                }
            }
            Text("Try a flight number like QF1, a registration like VH-OQA, or an airport code.")
                .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 4)
        }
        .padding(16)
    }

    private func suggestion(_ title: String, _ symbol: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.title3).foregroundStyle(.white)
                    .frame(width: 30, height: 30).background(color.gradient, in: .rect(cornerRadius: 8, style: .continuous))
                Text(title).foregroundStyle(.primary)
                Spacer()
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14).padding(.vertical, 11)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

private struct SearchResultsList: View {
    @Environment(AppModel.self) private var model
    var results: SearchResults
    var chosen: () -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 10) {
            if results.aircraft.isEmpty && results.airports.isEmpty {
                ContentUnavailableView(results.failed ? "Feeds unavailable" : "No matches",
                                       systemImage: results.failed ? "wifi.exclamationmark" : "airplane",
                                       description: Text(results.failed ? "Check your connection and try again." : "Only aircraft transmitting right now can be found."))
                    .padding(.top, 30)
            }
            if !results.aircraft.isEmpty {
                SectionTitle("Aircraft")
                Card(padding: 0) {
                    ForEach(Array(results.aircraft.enumerated()), id: \.element.hex) { i, a in
                        if i > 0 { Divider().padding(.leading, 56) }
                        row(symbol: "airplane", title: a.callsign ?? a.registration ?? a.hex.uppercased(),
                            subtitle: [a.registration, a.typeCode, a.onGround ? "On the ground" : Fmt.current.feet(a.altBaro)].compactMap { $0 }.joined(separator: " · ")) {
                            model.select(.aircraft(a.hex))
                        }
                    }
                }
            }
            if !results.airports.isEmpty {
                SectionTitle("Airports")
                Card(padding: 0) {
                    ForEach(Array(results.airports.enumerated()), id: \.element.id) { i, a in
                        if i > 0 { Divider().padding(.leading, 56) }
                        row(symbol: "building.2", title: "\(a.code) · \(a.name)", subtitle: [a.city, a.country].compactMap { $0 }.joined(separator: ", ")) {
                            model.select(.airport(a))
                        }
                    }
                }
            }
        }
        .padding(16)
    }

    private func row(symbol: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button {
            chosen()
            action()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: symbol).foregroundStyle(.tint).frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
                    if !subtitle.isEmpty { Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer()
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
