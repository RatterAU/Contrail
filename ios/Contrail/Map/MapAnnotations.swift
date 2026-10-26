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
    }

    private var look: Look?

    func apply(_ l: Look) {
        guard l != look else { return }
        look = l
        icon.image = Sprites.image(l.shape, band: l.selected ? AltitudeColors.selectedBand : l.band)
        icon.transform = CGAffineTransform(scaleX: l.selected ? 1.3 : 1, y: l.selected ? 1.3 : 1).rotated(by: rotation.isNaN ? 0 : rotation)
        alpha = l.stale && !l.selected ? 0.35 : 1
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
final class RadarOverlay: MKTileOverlay, @unchecked Sendable {
    static let maxZoom = 7

    override func loadTile(at path: MKTileOverlayPath, result: @escaping @Sendable (Data?, (any Error)?) -> Void) {
        guard path.z > RadarOverlay.maxZoom else { return super.loadTile(at: path, result: result) }
        let shift = path.z - RadarOverlay.maxZoom
        let parent = MKTileOverlayPath(x: path.x >> shift, y: path.y >> shift, z: RadarOverlay.maxZoom, contentScaleFactor: path.contentScaleFactor)
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
