import SwiftUI

extension PresentationDetent {
    /// Just the search bar, like Apple Maps' collapsed card.
    static let peek = PresentationDetent.height(86)
}

/// The one persistent sheet. Its content follows the selection; panels and Sky view are presented from here so
/// only one view ever presents.
struct MainSheet: View {
    @Environment(AppModel.self) private var model
    @State private var detent: PresentationDetent = .peek

    var body: some View {
        @Bindable var model = model
        content
            .onGeometryChange(for: Double.self) { $0.size.height } action: { model.sheetHeight = $0 }
            .presentationDetents([.peek, .medium, .large], selection: $detent)
            .presentationBackgroundInteraction(.enabled(upThrough: .medium))
            .presentationDragIndicator(.visible)
            .interactiveDismissDisabled()
            .sheet(item: $model.panel) { PanelView(panel: $0) }
            .fullScreenCover(isPresented: $model.showSky) { SkyView() }
            .onChange(of: model.selection) { _, s in
                // Opening something shows enough of it; closing it returns to the search bar.
                withAnimation(.snappy) { detent = s == nil && !model.showNearby ? .peek : .medium }
            }
            .onChange(of: model.showNearby) { _, on in withAnimation(.snappy) { detent = on ? .medium : .peek } }
    }

    @ViewBuilder private var content: some View {
        switch model.selection {
        case .aircraft(let hex): AircraftSheet(hex: hex).id(hex)
        case .airport(let a): AirportSheet(airport: a).id(a.id)
        case nil:
            if model.showNearby {
                NearbyView()
            } else {
                HomeSheet(expand: { withAnimation(.snappy) { detent = .large } })
            }
        }
    }
}
