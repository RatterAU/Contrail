import SwiftUI

struct NearbyItem: Identifiable {
    var track: Track
    var sky: SkyPosition
    var id: String { track.hex }
}

/// Planes near the user, closest first, with which way to look.
struct NearbyView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SheetHeader(close: { model.showNearby = false }) {
                    EmptyView()
                } title: {
                    Text("Near you").font(.largeTitle.weight(.bold))
                    Text("Within \(Fmt.current.nm(60))").font(.subheadline).foregroundStyle(.secondary)
                }
                content
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 30)
        }
        .task { await model.pollNearby() }
        .onAppear { model.location.startHeading() }
        .onDisappear { model.location.stopHeading() }
    }

    @ViewBuilder private var content: some View {
        if model.location.denied {
            ContentUnavailableView("Location is off", systemImage: "location.slash",
                                   description: Text("Allow location for Contrail in Settings to see the planes around you."))
        } else if let here = model.location.location {
            let items = nearby(here.coordinate.latitude, here.coordinate.longitude, here.altitude * 3.28084)
            if let first = items.first {
                ClosestCard(item: first, heading: model.location.heading)
                if items.count > 1 {
                    SectionTitle("Also nearby")
                    Card(padding: 0) {
                        ForEach(Array(items.dropFirst().prefix(30).enumerated()), id: \.element.id) { i, item in
                            if i > 0 { Divider().padding(.leading, 60) }
                            NearbyRow(item: item, heading: model.location.heading)
                        }
                    }
                }
            } else {
                ContentUnavailableView("Quiet skies", systemImage: "airplane", description: Text("No aircraft within \(Fmt.current.nm(60)) right now."))
            }
        } else {
            HStack { ProgressView(); Text("Finding you…").foregroundStyle(.secondary) }.frame(maxWidth: .infinity).padding(.top, 30)
        }
    }

    private func nearby(_ lat: Double, _ lon: Double, _ altFt: Double) -> [NearbyItem] {
        let now = Millis.now
        return model.tracks.values.compactMap { t in
            let p = Track.extrapolate(t.aircraft, now)
            let s = SkyGeometry.locate(obsLat: lat, obsLon: lon, obsAltFt: altFt, t.aircraft, at: p)
            return s.groundNm <= 60 ? NearbyItem(track: t, sky: s) : nil
        }
        // Planes in the air first: parked ones at the local airport aren't what anyone looks up for.
        .sorted { ($0.track.aircraft.onGround ? 1 : 0, $0.sky.slantNm) < ($1.track.aircraft.onGround ? 1 : 0, $1.sky.slantNm) }
    }
}

/// Arrow pointing to the plane relative to where the phone faces (or north-up without a compass).
private struct LookArrow: View {
    let bearing: Double
    let heading: Double?
    var size: CGFloat = 22

    var body: some View {
        Image(systemName: "location.north.fill")
            .font(.system(size: size, weight: .semibold))
            .rotationEffect(.degrees(bearing - (heading ?? 0)))
            .animation(.smooth, value: bearing - (heading ?? 0))
            .foregroundStyle(.tint)
    }
}

private func lookText(_ s: SkyPosition) -> String {
    s.elevation < 1 ? "Low on the horizon, \(Geo.compass(s.bearing))" : "Look \(Geo.compass(s.bearing)), \(Int(s.elevation.rounded()))° up"
}

private struct ClosestCard: View {
    @Environment(AppModel.self) private var model
    let item: NearbyItem
    let heading: Double?

    var body: some View {
        let a = item.track.aircraft
        Button { model.select(.aircraft(a.hex)) } label: {
            Card {
                Text("CLOSEST NOW").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                HStack(spacing: 16) {
                    LookArrow(bearing: item.sky.bearing, heading: heading, size: 40)
                        .frame(width: 64, height: 64)
                        .background(.tint.opacity(0.12), in: .circle)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(a.label).font(.title2.weight(.bold)).foregroundStyle(.primary)
                        Text(lookText(item.sky)).font(.headline).foregroundStyle(.tint)
                        Text("\(Fmt.current.nm(item.sky.slantNm)) away · \(Fmt.current.altitude(a))")
                            .font(.subheadline).foregroundStyle(.secondary).monospacedDigit()
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

private struct NearbyRow: View {
    @Environment(AppModel.self) private var model
    let item: NearbyItem
    let heading: Double?

    var body: some View {
        let a = item.track.aircraft
        Button { model.select(.aircraft(a.hex)) } label: {
            HStack(spacing: 14) {
                LookArrow(bearing: item.sky.bearing, heading: heading).frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(a.label).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    Text([a.typeCode, Fmt.current.altitude(a)].compactMap { $0 }.joined(separator: " · "))
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(Fmt.current.nm(item.sky.slantNm)).font(.body.weight(.medium)).monospacedDigit().foregroundStyle(.primary)
                    Text(item.sky.elevation < 1 ? "Horizon" : "\(Int(item.sky.elevation.rounded()))° up").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
