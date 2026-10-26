import AVFoundation
import CoreMotion
import SwiftUI

/// Point the phone at the sky: the camera, with labels on the planes it's looking at.
struct SkyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var camera = SkyCamera()
    @State private var motion = SkyMotion()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if camera.available {
                CameraPreview(session: camera.session).ignoresSafeArea()
            }
            GeometryReader { g in
                SkyLabels(motion: motion, size: g.size, hFov: camera.horizontalFov(for: g.size))
            }
            .ignoresSafeArea()
            VStack {
                HStack {
                    Spacer()
                    CloseButton { dismiss() }
                }
                Spacer()
                status
            }
            .padding()
        }
        .task { await camera.start() }
        .task { await model.pollNearby() }
        .onAppear {
            motion.start()
            model.mapVisible = false
        }
        .onDisappear {
            motion.stop()
            camera.stop()
            model.mapVisible = true
        }
        .statusBarHidden()
    }

    @ViewBuilder private var status: some View {
        let text: String? = !camera.available ? (camera.denied ? "Allow camera access in Settings to see the sky." : "No camera available. Labels still follow the compass.")
            : !motion.available ? "This device has no compass." : nil
        if let text {
            Text(text).font(.subheadline).padding(.horizontal, 14).padding(.vertical, 10).glassEffect(.regular, in: .capsule)
        } else {
            Text("Point at the sky").font(.subheadline.weight(.medium)).padding(.horizontal, 14).padding(.vertical, 10).glassEffect(.regular, in: .capsule)
        }
    }
}

/// Labels projected from each plane's bearing/elevation into the camera image. Only this view re-renders with the
/// 30 Hz attitude updates.
private struct SkyLabels: View {
    @Environment(AppModel.self) private var model
    let motion: SkyMotion
    let size: CGSize
    let hFov: Double

    var body: some View {
        if let r = motion.matrix, let here = model.location.location {
            let now = Millis.now
            let lat = here.coordinate.latitude, lon = here.coordinate.longitude, alt = here.altitude * 3.28084
            ForEach(Array(model.tracks.values), id: \.hex) { t in
                let p = Track.extrapolate(t.aircraft, now)
                let s = SkyGeometry.locate(obsLat: lat, obsLon: lon, obsAltFt: alt, t.aircraft, at: p)
                if s.groundNm < 60, s.elevation > -2,
                   let pt = SkyGeometry.project(r, bearing: s.bearing, elevation: s.elevation, hFovDeg: hFov, aspect: size.width / size.height),
                   (-0.1...1.1).contains(pt.x), (-0.1...1.1).contains(pt.y) {
                    SkyTag(a: t.aircraft, sky: s)
                        .position(x: pt.x * size.width, y: pt.y * size.height)
                        .onTapGesture { model.showSky = false; model.select(.aircraft(t.hex)) }
                }
            }
        }
    }
}

private struct SkyTag: View {
    let a: Aircraft
    let sky: SkyPosition

    var body: some View {
        VStack(spacing: 4) {
            Circle().strokeBorder(.white, lineWidth: 2).frame(width: 22, height: 22)
                .background(Circle().fill(Color(AltitudeColors.color(band: AltitudeColors.band(a))).opacity(0.5)))
            VStack(spacing: 1) {
                Text(a.label).font(.caption.weight(.bold))
                Text("\(Fmt.current.altitude(a)) · \(Fmt.current.nm(sky.slantNm))").font(.caption2).monospacedDigit()
            }
            .padding(.horizontal, 8).padding(.vertical, 4)
            .glassEffect(.regular, in: .rect(cornerRadius: 10))
        }
        .foregroundStyle(.white)
    }
}

/// Device attitude against true north, as the ENU matrix SkyGeometry expects.
@MainActor @Observable
final class SkyMotion {
    private(set) var matrix: [Double]?
    @ObservationIgnored private let manager = CMMotionManager()

    var available: Bool { CMMotionManager.availableAttitudeReferenceFrames().contains(.xTrueNorthZVertical) }

    func start() {
        guard available else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 30
        manager.startDeviceMotionUpdates(using: .xTrueNorthZVertical, to: .main) { [weak self] m, _ in
            guard let r = m?.attitude.rotationMatrix else { return }
            MainActor.assumeIsolated {
                self?.matrix = SkyGeometry.fromCoreMotion(m11: r.m11, m12: r.m12, m13: r.m13, m21: r.m21, m22: r.m22, m23: r.m23,
                                                          m31: r.m31, m32: r.m32, m33: r.m33)
            }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }
}

@MainActor @Observable
final class SkyCamera {
    let session = AVCaptureSession()
    private(set) var available = false
    private(set) var denied = false
    @ObservationIgnored private var fovDeg = 65.0

    func start() async {
        guard await AVCaptureDevice.requestAccess(for: .video) else {
            denied = true
            return
        }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
        session.beginConfiguration()
        session.sessionPreset = .high
        session.addInput(input)
        session.commitConfiguration()
        fovDeg = Double(device.activeFormat.videoFieldOfView)
        available = true
        // startRunning blocks for a moment; AVCaptureSession is documented as safe to drive from another thread.
        nonisolated(unsafe) let s = session
        await Task.detached { s.startRunning() }.value
    }

    func stop() {
        nonisolated(unsafe) let s = session
        Task.detached { s.stopRunning() }
    }

    /// The sensor's field of view is across its long side, which is the screen's height in portrait. The preview
    /// fills the screen, so the screen's width sees proportionally less.
    func horizontalFov(for size: CGSize) -> Double {
        guard size.height > 0 else { return 60 }
        let long = Geo.rad(fovDeg) / 2
        return Geo.deg(2 * atan(tan(long) * Double(size.width / size.height)))
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.preview.session = session
        v.preview.videoGravity = .resizeAspectFill
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
