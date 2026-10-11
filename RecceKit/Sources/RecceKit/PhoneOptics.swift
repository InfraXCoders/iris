import Foundation

/// The phone camera's own angle of view and choosing which of the phone's back lenses to use for a cinema frame.
/// On iPhone the angle comes straight from the camera format (`videoFieldOfView`); these helpers keep the choice
/// and the fallbacks the same as on Android.
public enum PhoneOptics {
    public enum Source: String, Sendable {
        case reported, manual, assumed
        public var label: String {
            switch self {
            case .reported: return "Auto-calibrated (reported by the camera)"
            case .manual: return "Calibrated by you"
            case .assumed: return "Assumed 70° (the camera reports nothing usable)"
            }
        }
        public var short: String { switch self { case .reported: return "Auto-calibrated"; case .manual: return "Calibrated"; case .assumed: return "FOV assumed 70°" } }
    }

    public static let defaultFov = 70.0
    /// Plausible angle across a phone camera's long side (main ~65–80°, ultra-wide ~100–125°).
    public static let plausible = 30.0...140.0

    /// A reported angle if plausible, else the 70° fallback.
    public static func checked(_ fov: Double?) -> (fov: Double, source: Source) {
        if let f = fov, plausible.contains(f) { return (f, .reported) }
        return (defaultFov, .assumed)
    }

    /// Long side of a centred `aspect` crop of a long × short picture.
    public static func streamLong(long: Double, short: Double, aspect: Double) -> Double { min(long, short * aspect) }

    /// One of the phone's back cameras as the app can use it.
    public struct Lens: Equatable, Sendable, Identifiable {
        /// The device's unique id.
        public var id: String
        public var name: String
        /// Angle across the picture's long side at zoom 1.
        public var longSideFov: Double
        public var source: Source
        public var minZoom: Double
        public var maxZoom: Double
        public var isDefault: Bool
        public init(id: String, name: String, longSideFov: Double, source: Source, minZoom: Double, maxZoom: Double, isDefault: Bool) {
            self.id = id; self.name = name; self.longSideFov = longSideFov; self.source = source
            self.minZoom = minZoom; self.maxZoom = maxZoom; self.isDefault = isDefault
        }
        /// Widest angle this lens reaches (zoom below 1 on multi-lens cameras = their built-in ultra-wide).
        public var widestFov: Double { Optics.fov(fromHalfTan: Optics.halfTan(longSideFov) / max(minZoom, 0.01)) }
    }

    /// The main lens when the frame fits, otherwise the narrowest wider lens that fits, otherwise the widest.
    public static func choose(_ lenses: [Lens], fits: (Lens) -> Bool) -> Lens? {
        guard let main = lenses.first(where: \.isDefault) ?? lenses.first else { return nil }
        if fits(main) { return main }
        let wider = lenses.filter { $0.widestFov > main.widestFov + 1 }.sorted { $0.widestFov < $1.widestFov }
        return wider.first(where: fits) ?? wider.last ?? main
    }
}
