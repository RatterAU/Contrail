import MapKit
import SwiftUI

/// Wind as drifting streaks, Windy-style. The field is sampled once per camera stop on a coarse screen grid (one
/// coordinate conversion per cell) and particles move in screen space, so a frame costs no map calls. Drawn by Metal
/// (`drawingGroup`) at 20 fps. Particles hide while the camera moves, while following a plane (the map moves every
/// tick), while the app isn't active and while the phone is running hot. The wind fetch lives here, not in RootView, so camera stops don't
/// re-evaluate the whole screen.
struct WindLayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var phase
    let level: WindLevel
    @State private var field: ScreenWind?
    @State private var particles = WindParticles()
    @State private var hot = ProcessInfo.processInfo.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue

    private struct FieldKey: Equatable {
        var grid: WindGrid?
        var level: WindLevel
        var moving: Bool
        var size: CGSize
    }

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation(minimumInterval: 1.0 / 20, paused: field == nil || phase != .active || model.following || hot)) { tl in
                Canvas { ctx, _ in
                    guard let field, !model.following, !hot else { return }
                    particles.step(field, at: tl.date)
                    for (bin, path) in particles.paths().enumerated() {
                        ctx.stroke(path, with: .color(colour.opacity(Double(bin + 1) / Double(WindParticles.bins) * 0.8)),
                                   style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                    }
                }
                .drawingGroup()
            }
            .task(id: model.windArea) { await model.loadWind() }
            .onReceive(NotificationCenter.default.publisher(for: ProcessInfo.thermalStateDidChangeNotification)) { _ in
                hot = ProcessInfo.processInfo.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
            }
            .task(id: FieldKey(grid: model.windGrid, level: level, moving: model.mapMoving, size: geo.size)) {
                field = model.windGrid.flatMap { model.mapMoving ? nil : ScreenWind.sample(model.map.mapView, $0, level, geo.size) }
                if let field { particles.reset(field) }
            }
        }
    }

    private var colour: Color {
        let s = model.settings.map
        let dark = s.kind == .satellite || s.appearance == .dark || (s.appearance == .auto && scheme == .dark)
        return dark ? .white : Color(white: 0.1)
    }
}

/// Screen-space wind velocity (pt/s) on a grid of `cell` points, bilinear between cells. NaN where there's no data.
struct ScreenWind {
    let cols: Int, rows: Int
    let cell: Double
    let vx: [Double], vy: [Double]
    let size: CGSize

    func at(_ x: Double, _ y: Double) -> (Double, Double)? {
        let gx = min(max(x / cell, 0), Double(cols) - 1.001), gy = min(max(y / cell, 0), Double(rows) - 1.001)
        let i = Int(gx), j = Int(gy)
        let fx = gx - Double(i), fy = gy - Double(j)
        // Written out, not looped over an array of tuples: this runs for every particle at 20 fps.
        let a = j * cols + i, b = a + 1, c = a + cols, d = c + 1
        if vx[a].isNaN || vx[b].isNaN || vx[c].isNaN || vx[d].isNaN { return nil }
        let wa = (1 - fx) * (1 - fy), wb = fx * (1 - fy), wc = (1 - fx) * fy, wd = fx * fy
        return (vx[a] * wa + vx[b] * wb + vx[c] * wc + vx[d] * wd, vy[a] * wa + vy[b] * wb + vy[c] * wc + vy[d] * wd)
    }

    @MainActor static func sample(_ map: MKMapView, _ grid: WindGrid, _ level: WindLevel, _ size: CGSize) -> ScreenWind? {
        guard size.width > 0, size.height > 0 else { return nil }
        let cell = 40.0
        let cols = Int(size.width / cell) + 2, rows = Int(size.height / cell) + 2
        let bearing = map.camera.heading * .pi / 180
        var vx = [Double](repeating: .nan, count: cols * rows), vy = vx
        for j in 0..<rows {
            for i in 0..<cols {
                let c = map.convert(CGPoint(x: Double(i) * cell, y: Double(j) * cell), toCoordinateFrom: map)
                guard let (u, v) = grid.sample(lat: c.latitude, lon: c.longitude, level: level) else { continue }
                let kt = (u * u + v * v).squareRoot()
                // Screen speed grows with the square root of wind speed: a 10 kt breeze still drifts, a 150 kt jet doesn't streak off screen.
                let scale = kt < 0.1 ? 0 : 8 * kt.squareRoot() / kt
                vx[j * cols + i] = (u * cos(bearing) - v * sin(bearing)) * scale
                vy[j * cols + i] = -(u * sin(bearing) + v * cos(bearing)) * scale
            }
        }
        return ScreenWind(cols: cols, rows: rows, cell: cell, vx: vx, vy: vy, size: size)
    }
}

/// Particle positions, mutated in place each frame by the Canvas.
final class WindParticles {
    static let bins = 4
    private static let tail = 0.35
    private var x: [Double] = [], y: [Double] = [], age: [Double] = [], life: [Double] = []
    private var last: Date?
    private var segments = Array(repeating: Path(), count: bins)

    func reset(_ f: ScreenWind) {
        let n = min(max(Int(f.size.width * f.size.height / (34 * 34)), 50), 1000)
        x = .init(repeating: 0, count: n); y = x; age = x; life = x
        for i in 0..<n { spawn(f, i, Double.random(in: 0...1)) }
        last = nil
    }

    private func spawn(_ f: ScreenWind, _ i: Int, _ startAge: Double) {
        x[i] = Double.random(in: 0...f.size.width)
        y[i] = Double.random(in: 0...f.size.height)
        life[i] = Double.random(in: 2...4)
        age[i] = startAge * life[i]
    }

    func step(_ f: ScreenWind, at date: Date) {
        let dt = last.map { min(date.timeIntervalSince($0), 0.1) } ?? 0
        last = date
        var paths = Array(repeating: Path(), count: Self.bins)
        for i in x.indices {
            age[i] += dt
            guard let (vx, vy) = f.at(x[i], y[i]), age[i] <= life[i], (0...f.size.width).contains(x[i]), (0...f.size.height).contains(y[i]) else {
                spawn(f, i, 0)
                continue
            }
            x[i] += vx * dt
            y[i] += vy * dt
            // Fade in and out over each particle's life, so they don't pop.
            let bin = min(max(Int(sin(.pi * age[i] / life[i]) * Double(Self.bins)), 0), Self.bins - 1)
            paths[bin].move(to: CGPoint(x: x[i], y: y[i]))
            paths[bin].addLine(to: CGPoint(x: x[i] - vx * Self.tail, y: y[i] - vy * Self.tail))
        }
        segments = paths
    }

    func paths() -> [Path] { segments }
}
