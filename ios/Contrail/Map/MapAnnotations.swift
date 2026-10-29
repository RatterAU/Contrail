import MapKit
import UIKit

final class PlaneAnnotation: NSObject, MKAnnotation {
    let hex: String
    @objc dynamic var coordinate: CLLocationCoordinate2D

    init(hex: String, coordinate: CLLocationCoordinate2D) {
        self.hex = hex
        self.coordinate = coordinate
    }
}

/// A plane: its silhouette rotated to its track, with an optional callsign label and airline badge.
final class PlaneView: MKAnnotationView {
    static let reuseId = "plane"

    private let icon = UIImageView()
    private let label = UILabel()
    private let badge = UIImageView()
    private var rotation: CGFloat = .nan
    /// VoiceOver activation (the map's own selection is off for planes).
    var onActivate: (() -> Void)?

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 44, height: 44)
        // The map's own selection would fight the taps handled by the coordinator (which hit-test the animated position).
        isEnabled = false
        collisionMode = .circle
        displayPriority = .required
        icon.frame = bounds
        icon.contentMode = .center
        addSubview(icon)
        label.font = .systemFont(ofSize: 11, weight: .semibold)
        // Dynamic colours resolve against the map's own appearance, so labels read on light and dark maps.
        label.textColor = .label
        label.layer.shadowRadius = 1.5
        label.layer.shadowOpacity = 1
        label.layer.shadowOffset = .zero
        label.textAlignment = .center
        addSubview(label)
        badge.contentMode = .scaleAspectFit
        badge.backgroundColor = .white
        badge.layer.cornerRadius = 11
        badge.layer.cornerCurve = .continuous
        badge.clipsToBounds = true
        badge.isHidden = true
        addSubview(badge)
        updateHalo()
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (v: PlaneView, _) in v.updateHalo() }
        isAccessibilityElement = true
        accessibilityIdentifier = "plane"
        accessibilityTraits = .button
    }

    private func updateHalo() {
        label.layer.shadowColor = UIColor.systemBackground.resolvedColor(with: traitCollection).cgColor
    }

    override func accessibilityActivate() -> Bool {
        onActivate?()
        return onActivate != nil
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    struct Look: Equatable {
        var shape: Shape
        var band: Int
        var selected: Bool
        var label: String?
        var stale: Bool
        var logo: UIImage?
        var spoken: String
        /// Faded back while a past flight is shown, so its path and replayed plane stand out.
        var dimmed = false
    }

    private var look: Look?

    func apply(_ l: Look) {
        guard l != look else { return }
        look = l
        icon.image = Sprites.image(l.shape, band: l.selected ? AltitudeColors.selectedBand : l.band)
        icon.transform = CGAffineTransform(scaleX: l.selected ? 1.3 : 1, y: l.selected ? 1.3 : 1).rotated(by: rotation.isNaN ? 0 : rotation)
        setFade(l.dimmed ? 0.3 : l.stale && !l.selected ? 0.35 : 1)
        accessibilityLabel = l.spoken
        label.text = l.label
        label.isHidden = l.label == nil
        if l.label != nil {
            label.sizeToFit()
            label.center = CGPoint(x: bounds.midX, y: bounds.maxY + 2)
        }
        if let logo = l.logo {
            badge.image = logo
            badge.isHidden = false
            badge.frame = CGRect(x: bounds.maxX - 8, y: -6, width: 22, height: 22)
            badge.layer.borderColor = UIColor.white.cgColor
            badge.layer.borderWidth = 1.5
        } else {
            badge.isHidden = true
        }
    }

    /// On the subviews: MapKit manages the annotation view's own alpha (fading views in and out) and overrides it.
    func setFade(_ a: CGFloat) {
        for v in [icon, label, badge] { v.alpha = a }
    }

    /// Track relative to the map's own rotation.
    func setRotation(_ degrees: Double?, rotates: Bool) {
        let r = rotates ? CGFloat(Geo.rad(degrees ?? 0)) : 0
        guard r != rotation else { return }
        rotation = r
        let s: CGFloat = look?.selected == true ? 1.3 : 1
        icon.transform = CGAffineTransform(scaleX: s, y: s).rotated(by: r)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        look = nil
        rotation = .nan
    }
}

final class AirportAnnotation: NSObject, MKAnnotation {
    let airport: Airport
    let isEndpoint: Bool
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: airport.lat, longitude: airport.lon) }

    init(_ a: Airport, endpoint: Bool = false) {
        airport = a
        isEndpoint = endpoint
    }
}

/// An airport: a small ring with its code. Larger airports win label collisions, and MapKit hides the losers.
final class AirportView: MKAnnotationView {
    static let reuseId = "airport"
    private let dot = UIView()
    private let label = UILabel()

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 12, height: 12)
        canShowCallout = false
        isAccessibilityElement = true
        accessibilityTraits = .button
        dot.frame = bounds
        dot.layer.cornerRadius = 6
        dot.layer.borderWidth = 2.5
        addSubview(dot)
        label.font = .systemFont(ofSize: 11, weight: .bold)
        label.shadowOffset = CGSize(width: 0, height: 1)
        addSubview(label)
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    func configure(_ a: AirportAnnotation, dark: Bool) {
        let ap = a.airport
        dot.backgroundColor = dark ? UIColor(white: 0.1, alpha: 1) : .white
        dot.layer.borderColor = (a.isEndpoint ? UIColor.systemBlue : UIColor(white: dark ? 0.85 : 0.35, alpha: 1)).cgColor
        label.text = ap.code
        label.textColor = dark ? UIColor(white: 0.92, alpha: 1) : UIColor(white: 0.15, alpha: 1)
        label.shadowColor = dark ? UIColor(white: 0, alpha: 0.8) : UIColor(white: 1, alpha: 0.9)
        label.sizeToFit()
        label.center = CGPoint(x: bounds.midX, y: bounds.maxY + label.bounds.height / 2 + 1)
        accessibilityLabel = "\(ap.name) airport"
        zPriority = a.isEndpoint ? .max : .min
        displayPriority = a.isEndpoint ? .required : ap.size == "L" ? .defaultHigh : ap.size == "M" ? MKFeatureDisplayPriority(500) : .defaultLow
        collisionMode = .rectangle
    }
}

/// A past flight's plane where its replay has got to.
final class ReplayAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var frame: Replay.Frame
    let shape: Shape
    init(_ c: CLLocationCoordinate2D, frame: Replay.Frame, shape: Shape) {
        coordinate = c
        self.frame = frame
        self.shape = shape
    }
}

/// Where the profile chart's finger is, on the trail.
final class ScrubAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    init(_ c: CLLocationCoordinate2D) { coordinate = c }
}

/// A multi-line in one colour: every trail segment in one altitude band, or one kind of route line.
final class StyledLines: MKMultiPolyline {
    enum Style: Hashable { case trail(band: Int, selected: Bool), route(RouteKind) }
    var style: Style = .route(.ahead)
}

/// RainViewer serves radar only to zoom 7. Deeper tiles are cut from their zoom-7 parent and scaled up, so the
/// radar stays on screen when zoomed in instead of vanishing.
class RadarOverlay: MKTileOverlay, @unchecked Sendable {
    class var maxZoom: Int { 7 }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping @Sendable (Data?, (any Error)?) -> Void) {
        let maxZoom = Self.maxZoom
        guard path.z > maxZoom else { return super.loadTile(at: path, result: result) }
        let shift = path.z - maxZoom
        let parent = MKTileOverlayPath(x: path.x >> shift, y: path.y >> shift, z: maxZoom, contentScaleFactor: path.contentScaleFactor)
        let n = 1 << shift
        let sx = path.x - (parent.x << shift), sy = path.y - (parent.y << shift)
        super.loadTile(at: parent) { data, error in
            guard let data, let img = UIImage(data: data)?.cgImage else { return result(nil, error) }
            let w = img.width / n, h = img.height / n
            guard let part = img.cropping(to: CGRect(x: sx * w, y: sy * h, width: w, height: h)) else { return result(nil, nil) }
            let size = CGSize(width: 256, height: 256)
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let scaled = UIGraphicsImageRenderer(size: size, format: format).pngData { ctx in
                ctx.cgContext.interpolationQuality = .medium
                UIImage(cgImage: part).draw(in: CGRect(origin: .zero, size: size))
            }
            result(scaled, nil)
        }
    }
}

/// NASA GIBS infrared satellite tiles (Himawari, GOES-West, GOES-East) as a cloud overlay. The tiles are opaque grey,
/// which would hide the map, so each is recoloured as it loads: warm (clear) sky transparent, cloud white, more solid
/// the colder (higher) its top. Each satellite is clipped to the longitudes it sees well; near the edge of its disk
/// the image is smeared. Africa and Europe aren't covered.
final class CloudOverlay: RadarOverlay, @unchecked Sendable {
    override class var maxZoom: Int { 6 }
    private var west = -180.0, east = 180.0

    private static let base = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best"
    private static let layers: [(String, Double, Double)] = [
        ("Himawari_AHI_Band13_Clean_Infrared", 70, 180),
        ("GOES-West_ABI_Band13_Clean_Infrared", -180, -105),
        ("GOES-East_ABI_Band13_Clean_Infrared", -105, -20),
    ]

    /// GIBS serves the newest image at "default"; `bucket` changes the URL so tiles cached earlier aren't reused.
    static func all(bucket: Int64) -> [CloudOverlay] {
        layers.map { name, w, e in
            let o = CloudOverlay(urlTemplate: "\(base)/\(name)/default/default/GoogleMapsCompatible_Level6/{z}/{y}/{x}.png?t=\(bucket)")
            o.west = w
            o.east = e
            o.canReplaceMapContent = false
            return o
        }
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping @Sendable (Data?, (any Error)?) -> Void) {
        let n = Double(1 << path.z)
        let tileWest = Double(path.x) / n * 360 - 180, tileEast = Double(path.x + 1) / n * 360 - 180
        guard tileEast > west, tileWest < east else { return result(CloudOverlay.empty, nil) }
        super.loadTile(at: path) { data, error in
            result(data.flatMap(CloudOverlay.recolour), error)
        }
    }

    /// A transparent tile, for tiles outside this satellite's longitudes. Not nil: MapKit treats a nil tile as never
    /// loaded and redraws the whole map every frame (measured: 23% CPU on a still map, 0.2% with this).
    private static let empty: Data = {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1), format: format).pngData { _ in }
    }()

    /// Grey is brightness temperature: clear ground and sea are about 90–130, cloud tops brighten as they get colder.
    /// Coloured pixels are the coldest tops (storms): solid white.
    static func recolour(_ data: Data) -> Data? {
        guard let img = UIImage(data: data)?.cgImage else { return nil }
        let w = img.width, h = img.height
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let buf = ctx.data?.bindMemory(to: UInt8.self, capacity: w * h * 4) else { return nil }
        for i in stride(from: 0, to: w * h * 4, by: 4) {
            let a = Int(buf[i + 3])
            guard a > 0 else { continue }
            let r = Int(buf[i]) * 255 / a, g = Int(buf[i + 1]) * 255 / a, b = Int(buf[i + 2]) * 255 / a
            let alpha = max(r, g, b) - min(r, g, b) > 30 ? 230 : min(max((r - 135) * 230 / 65, 0), 230)
            // Premultiplied white at that alpha.
            buf[i] = UInt8(alpha); buf[i + 1] = UInt8(alpha); buf[i + 2] = UInt8(alpha); buf[i + 3] = UInt8(alpha)
        }
        return ctx.makeImage().flatMap { UIImage(cgImage: $0).pngData() }
    }
}

/// A SIGMET area, filled and outlined in its hazard's colour.
final class HazardPolygon: MKPolygon {
    var color: UIColor = .systemGray
}

/// The hazard and its levels, written in the middle of its area.
final class HazardLabel: NSObject, MKAnnotation {
    let coordinate: CLLocationCoordinate2D
    let text: String
    let color: UIColor

    init(_ c: CLLocationCoordinate2D, _ text: String, _ color: UIColor) {
        coordinate = c
        self.text = text
        self.color = color
    }

    static func color(_ hazard: String) -> UIColor {
        switch hazard {
        case "TURB": .systemOrange
        case "ICE": .systemCyan
        case "TS": .systemRed
        case "VA": .systemPurple
        case "TC": .systemPink
        case "MTW": .systemYellow
        default: .systemGray
        }
    }
}
