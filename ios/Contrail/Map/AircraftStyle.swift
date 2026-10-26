import UIKit

/// Silhouettes drawn pointing north in a 64×64 box. `size` scales the icon on the map.
enum Shape: String, CaseIterable, Sendable {
    case airliner, heavy, light, helicopter, glider, fighter, balloon, ground

    var size: CGFloat {
        switch self {
        case .airliner: 0.9
        case .heavy: 1.15
        case .light, .balloon: 0.7
        case .helicopter, .glider, .fighter: 0.8
        case .ground: 0.5
        }
    }

    var path: String {
        switch self {
        case .airliner: "M32,4C34,4 35,6 35,9L35,24L58,36L58,41L35,35L35,50L42,56L42,60L32,57L22,60L22,56L29,50L29,35L6,41L6,36L29,24L29,9C29,6 30,4 32,4Z"
        case .heavy: "M32,2C34.5,2 36,5 36,9L36,22L48,28L48,25L51,25L51,30L60,35L60,40L36,34L36,49L43,55L43,59L32,56L21,59L21,55L28,49L28,34L4,40L4,35L13,30L13,25L16,25L16,28L28,22L28,9C28,5 29.5,2 32,2Z"
        case .light: "M32,8C34,8 35,10 35,14L35,22L60,24L60,30L35,31L34,46L44,48L44,53L32,52L20,53L20,48L30,46L29,31L4,30L4,24L29,22L29,14C29,10 30,8 32,8Z"
        case .helicopter: "M32,14C37,14 40,19 40,26C40,33 37,37 34,38L33,58L38,60L38,62L26,62L26,60L31,58L30,38C27,37 24,33 24,26C24,19 27,14 32,14ZM8,24L56,24L56,27L8,27ZM30.5,2L33.5,2L33.5,50L30.5,50Z"
        case .glider: "M32,16C33.5,16 34,18 34,21L34,26L62,28L62,31L34,31L33,52L40,53L40,56L24,56L24,53L31,52L30,31L2,31L2,28L30,26L30,21C30,18 30.5,16 32,16Z"
        case .fighter: "M32,4L35,18L35,28L54,44L54,49L35,44L35,50L42,56L42,59L32,57L22,59L22,56L29,50L29,44L10,49L10,44L29,28L29,18Z"
        case .balloon: "M32,6C42,6 50,14 50,24C50,34 40,42 35,48L35,54L29,54L29,48C24,42 14,34 14,24C14,14 22,6 32,6Z"
        case .ground: "M32,18L46,32L32,46L18,32Z"
        }
    }

    /// Balloons and ground vehicles have no meaningful heading to rotate to.
    var rotates: Bool { self != .balloon && self != .ground }

    private static let heavyTypes: Set<String> = ["A388", "B748", "B744", "B74S", "A346", "A345", "A343", "A342", "B77W", "B77L", "B772", "B773", "B779", "B778", "A359", "A35K", "B788", "B789", "B78X", "A332", "A333", "A338", "A339", "B763", "B764", "MD11", "C5M", "AN25", "A124", "IL96"]
    private static let fighters: Set<String> = ["F16", "F18", "F18S", "F35", "F15", "F22", "EUFI", "RFAL", "GRIF", "HAWK", "PC21", "A4", "MIRA", "TORN", "SU27", "SU30", "MG29", "T38", "F5"]

    static func of(_ a: Aircraft) -> Shape {
        if let t = a.typeCode {
            if heavyTypes.contains(t) { return .heavy }
            if fighters.contains(t) { return .fighter }
        }
        switch a.category {
        case "A1": return .light
        case "A5": return .heavy
        case "A6": return .fighter
        case "A7": return .helicopter
        case "B1", "B4": return .glider
        case "B2": return .balloon
        case "C0", "C1", "C2", "C3", "C4", "C5": return .ground
        default: return .airliner
        }
    }
}

/// Altitude colour ramp, tar1090-style: warm near the ground, through green, to violet at cruise. Bucketed into bands
/// so each (shape, band) pair is one pre-rendered sprite, and trails merge into one line per colour.
enum AltitudeColors {
    static let bandTops = [500, 1000, 2000, 4000, 6000, 8000, 10000, 14000, 18000, 22000, 26000, 30000, 34000, 38000, 42000, Int.max]
    static let groundBand = -1
    static let unknownBand = -2
    static let selectedBand = -3

    static func band(_ a: Aircraft) -> Int { a.onGround ? groundBand : a.altBaro.map(bandFor) ?? unknownBand }
    static func band(_ p: TrailPoint) -> Int { p.onGround ? groundBand : p.alt.map(bandFor) ?? unknownBand }
    static func bandFor(_ alt: Int) -> Int { bandTops.firstIndex { alt < $0 } ?? bandTops.count - 1 }

    private static let hueStops: [(Int, Double)] = [(0, 20), (2000, 32), (4000, 43), (6000, 54), (8000, 72), (9000, 85), (11000, 140), (40000, 300)]

    static func hue(_ alt: Int) -> Double {
        if alt <= hueStops[0].0 { return hueStops[0].1 }
        if alt >= hueStops.last!.0 { return hueStops.last!.1 }
        let i = hueStops.firstIndex { $0.0 > alt }!
        let (a0, h0) = hueStops[i - 1], (a1, h1) = hueStops[i]
        return h0 + (h1 - h0) * Double(alt - a0) / Double(a1 - a0)
    }

    static func color(altitude: Int) -> UIColor {
        // HSL(h, 0.85, 0.6) as HSB.
        let s = 0.85, l = 0.6
        let v = l + s * min(l, 1 - l)
        return UIColor(hue: hue(altitude) / 360, saturation: v == 0 ? 0 : 2 * (1 - l / v), brightness: v, alpha: 1)
    }

    private static let cache: [Int: UIColor] = Dictionary(uniqueKeysWithValues: (Array(bandTops.indices) + [groundBand, unknownBand, selectedBand]).map { ($0, make($0)) })

    static func color(band: Int) -> UIColor { cache[band] ?? .white }

    private static func make(_ band: Int) -> UIColor {
        switch band {
        case groundBand: return UIColor(red: 0x8C / 255, green: 0x93 / 255, blue: 0xA1 / 255, alpha: 1)
        case unknownBand: return UIColor(red: 0xD6 / 255, green: 0xDA / 255, blue: 0xE3 / 255, alpha: 1)
        case selectedBand: return .white
        default:
            let lo = band == 0 ? 0 : bandTops[band - 1]
            let hi = bandTops[band] == .max ? 46000 : bandTops[band]
            return color(altitude: (lo + hi) / 2)
        }
    }
}

/// Pre-rendered plane sprites, one per (shape, band), outlined in near-black so they read on any map.
@MainActor
enum Sprites {
    static let basePoints: CGFloat = 34
    private static var cache: [String: UIImage] = [:]
    private static var paths: [Shape: CGPath] = [:]

    static func image(_ shape: Shape, band: Int) -> UIImage {
        let key = "\(shape.rawValue)\(band)"
        if let i = cache[key] { return i }
        let side = basePoints * shape.size
        let path = paths[shape] ?? SVGPath.parse(shape.path)
        paths[shape] = path
        let img = UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { ctx in
            let c = ctx.cgContext
            c.scaleBy(x: side / 64, y: side / 64)
            c.addPath(path)
            c.setLineJoin(.round)
            c.setLineWidth(3.5)
            c.setStrokeColor(UIColor(white: 0, alpha: 0.9).cgColor)
            c.strokePath()
            c.addPath(path)
            c.setFillColor(AltitudeColors.color(band: band).cgColor)
            c.fillPath()
        }
        cache[key] = img
        return img
    }
}

/// The subset of SVG path syntax the silhouettes use: absolute M, L, C and Z.
enum SVGPath {
    static func parse(_ d: String) -> CGPath {
        let path = CGMutablePath()
        var nums: [CGFloat] = []
        var cmd: Character = "M"
        func flush() {
            switch cmd {
            case "M" where nums.count >= 2: path.move(to: CGPoint(x: nums[0], y: nums[1]))
            case "L" where nums.count >= 2: path.addLine(to: CGPoint(x: nums[0], y: nums[1]))
            case "C" where nums.count >= 6:
                path.addCurve(to: CGPoint(x: nums[4], y: nums[5]), control1: CGPoint(x: nums[0], y: nums[1]), control2: CGPoint(x: nums[2], y: nums[3]))
            default: break
            }
            nums.removeAll()
        }
        var token = ""
        for ch in d + " " {
            if ch.isNumber || ch == "." || ch == "-" {
                token.append(ch)
                continue
            }
            if let v = Double(token) { nums.append(CGFloat(v)) }
            token = ""
            if "MLCZ".contains(ch) {
                flush()
                cmd = ch
                if ch == "Z" { path.closeSubpath() }
            }
        }
        flush()
        return path
    }
}
