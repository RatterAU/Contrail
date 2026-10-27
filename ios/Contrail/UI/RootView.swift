import MapKit
import SwiftUI

/// The map fills the screen. Status top left, controls top right, and one persistent sheet at the bottom that
/// holds search, a plane, an airport or Nearby: Apple Maps' layout.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase

    var body: some View {
        MapView(model: model)
            .ignoresSafeArea()
            .overlay {
                if let level = model.settings.map.wind {
                    WindLayer(level: level).ignoresSafeArea().allowsHitTesting(false)
                }
            }
            .overlay(alignment: .topLeading) { StatusChip().padding(.leading, 16) }
            .overlay(alignment: .topTrailing) { MapControls().padding(.trailing, 16) }
            .sheet(isPresented: .constant(true)) { MainSheet() }
            // Only background pauses: alerts and Control Centre make the scene inactive while the map is still in view.
            .task(id: phase) { model.setActive(phase != .background) }
            .task(id: model.settings.map.radar) { await model.pollRadar() }
            .task(id: model.settings.map.clouds) { await model.pollClouds() }
            .task(id: model.settings.map.hazards) { await model.pollHazards() }

    }
}

/// Feed state and the aircraft count, in a glass capsule.
private struct StatusChip: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let (color, text): (Color, String) = switch model.status {
        case .connecting: (.yellow, "Connecting")
        case .live where model.viewTooWide: (.green, "Zoom in to see live traffic")
        case .live: (.green, "\(model.tracks.count.formatted()) aircraft")
        case .offline: (.red, "Offline")
        }
        HStack(spacing: 7) {
            Circle().fill(color).frame(width: 8, height: 8)
                .shadow(color: color.opacity(0.6), radius: 3)
            Text(text).font(.footnote.weight(.semibold)).monospacedDigit().contentTransition(.numericText())
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .glassEffect(.regular, in: .capsule)
        .animation(.default, value: text)
        .accessibilityElement(children: .combine)
    }
}

/// Layers, filters, location, compass and the "more" menu, stacked in one glass column.
private struct MapControls: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        GlassEffectContainer(spacing: 10) {
            VStack(spacing: 10) {
                VStack(spacing: 0) {
                    controlButton("square.2.layers.3d", "Map layers") { model.panel = .layers }
                    Divider().frame(width: 28)
                    controlButton(model.settings.map.filters.active ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease",
                                  "Filters") { model.panel = .filters }
                    Divider().frame(width: 28)
                    UIKitControl { MKUserTrackingButton(mapView: model.map.mapView) }
                        .frame(width: 44, height: 44)
                }
                .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 22))

                Menu {
                    Button("Planes near me", systemImage: "location.circle") { model.showNearby = true }
                    Button("Sky view", systemImage: "camera.viewfinder") { model.showSky = true }
                    Divider()
                    Button("Settings", systemImage: "gearshape") { model.panel = .settings }
                    Button("About", systemImage: "info.circle") { model.panel = .about }
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44)
                }
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityLabel("More")

                UIKitControl {
                    let c = MKCompassButton(mapView: model.map.mapView)
                    c.compassVisibility = .adaptive
                    return c
                }
                .frame(width: 40, height: 40)
            }
        }
        .tint(.primary)
    }

    private func controlButton(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 17, weight: .medium)).frame(width: 44, height: 44)
        }
        .accessibilityLabel(label)
    }
}

/// Hosts a MapKit UIKit control (tracking button, compass) that only exists as a UIView.
private struct UIKitControl<V: UIView>: UIViewRepresentable {
    var make: () -> V
    func makeUIView(context: Context) -> V { make() }
    func updateUIView(_ uiView: V, context: Context) {}
}
