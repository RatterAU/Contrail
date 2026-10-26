import SwiftUI

/// A rounded group on the sheet, like an inset-grouped list section.
struct Card<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            // A translucent fill reads both on the glass sheet (partial heights) and the opaque one (full height).
            .background(Color(.tertiarySystemFill), in: .rect(cornerRadius: 22, style: .continuous))
    }
}

/// Section heading above a card, in the iOS grouped-list style.
struct SectionTitle: View {
    var text: String
    var trailing: String?

    init(_ text: String, trailing: String? = nil) {
        self.text = text
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(text).font(.title3.weight(.semibold))
            Spacer()
            if let trailing { Text(trailing).font(.subheadline).foregroundStyle(.secondary) }
        }
        .padding(.horizontal, 4)
        .padding(.top, 8)
    }
}

/// Label on the left, value on the right.
struct ValueRow: View {
    var label: String
    var value: String
    var mono = false

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .monospacedDigit()
                .fontDesign(mono ? .monospaced : nil)
                .multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
    }
}

/// A small coloured capsule: flight phase, weather category, board status.
struct Pill: View {
    var text: String
    var color: Color
    var symbol: String?

    var body: some View {
        HStack(spacing: 4) {
            if let symbol { Image(systemName: symbol).imageScale(.small) }
            Text(text)
        }
        .font(.caption.weight(.semibold))
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .foregroundStyle(color)
        .background(color.opacity(0.16), in: .capsule)
    }
}

/// A grid cell: a caption above a large value.
struct Fact: View {
    var label: String
    var value: String
    var dot: Color?
    var lines = 1

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 6) {
                if let dot { Circle().fill(dot).frame(width: 8, height: 8) }
                Text(value)
                    .font(lines > 1 ? .headline : .title3.weight(.semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .lineLimit(lines)
                    .minimumScaleFactor(lines == 1 ? 0.7 : 1)
                    .fixedSize(horizontal: false, vertical: lines > 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Grey placeholder bar while something loads.
struct Skeleton: View {
    var width: CGFloat? = nil
    var height: CGFloat = 14

    var body: some View {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
            .fill(.fill.tertiary)
            .frame(width: width, height: height)
    }
}

/// Header shared by the aircraft and airport sheets: title block on the left, close button on the right.
struct SheetHeader<Leading: View, Title: View>: View {
    var close: () -> Void
    @ViewBuilder var leading: Leading
    @ViewBuilder var title: Title

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            leading
            VStack(alignment: .leading, spacing: 2) { title }
            Spacer(minLength: 8)
            CloseButton(action: close)
        }
    }
}

/// The round glass ✕ used to close sheets.
struct CloseButton: View {
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark").font(.system(size: 15, weight: .semibold)).frame(width: 30, height: 30)
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .tint(.primary)
        .accessibilityLabel("Close")
    }
}

extension Color {
    init(_ ui: UIColor) { self.init(uiColor: ui) }
}

extension Phase {
    var color: Color {
        switch self {
        case .climbing: .green
        case .descending, .approach: .orange
        case .cruising, .level: .blue
        case .takeoff: .pink
        case .landed, .taxiing, .parked: .gray
        }
    }

    var symbol: String {
        switch self {
        case .climbing, .takeoff: "airplane.departure"
        case .descending, .approach, .landed: "airplane.arrival"
        case .cruising, .level: "airplane"
        case .taxiing, .parked: "parkingsign"
        }
    }
}
