import Foundation

/// Lens distortion (barrel / pincushion) from measured calibrations in the Lensfun database (CC BY-SA 3.0), for the
/// photo lenses that have one, plus the user's own measurement for other lenses (most cine lenses: makers don't
/// publish distortion). Lensfun's ptlens / poly3 / poly5 coefficients are rescaled (as Lensfun's
/// rescale_polynomial_coefficients does) to focal-length units: a point at angle θ sits at r_u = tan θ and the lens
/// images it at r_d = r_u · P(r_u). Same maths as the Android app.
public enum Distortion {
    public struct Point: Equatable, Sendable {
        public var lensId: String, focalMm: Double, model: String
        public var t1: Double, t2: Double, t3: Double
        public var calibCrop: Double, calibAspect: Double, realFocalMm: Double?
    }

    /// r_d = r_u · (1 + a r³ + b r² + c r) (ptlens) or r_u · (1 + a r² + b r⁴) (poly).
    public struct Model: Equatable, Sendable {
        public var ptlens: Bool, a: Double, b: Double, c: Double
        public init(ptlens: Bool, a: Double, b: Double, c: Double) { self.ptlens = ptlens; self.a = a; self.b = b; self.c = c }
        public func distort(_ ru: Double) -> Double {
            ptlens ? ru * (1 + a * ru * ru * ru + b * ru * ru + c * ru) : ru * (1 + a * ru * ru + b * ru * ru * ru * ru)
        }
        /// Inverse of `distort` by Newton's method.
        public func undistort(_ rd: Double) -> Double {
            var ru = rd
            for _ in 0..<30 {
                let f = distort(ru) - rd, h = 1e-6
                let d = (distort(ru + h) - distort(ru - h)) / (2 * h)
                if abs(d) < 1e-9 { return ru }
                let next = ru - f / d
                if abs(next - ru) < 1e-12 { return next }
                ru = next
            }
            return ru
        }
    }

    public static let points: [Point] = load(Catalog.text(resource: "distortion"))
    private static let byLens: [String: [Point]] = Dictionary(grouping: points, by: \.lensId)

    /// The user's own measurements: lens id → [(focal mm, k1 in focal-length units)]. Set by the app at start.
    public static var custom: [String: [(Double, Double)]] = [:]

    public static func hasProfile(_ lens: LensProfile) -> Bool { byLens[lens.id] != nil || !(custom[lens.id] ?? []).isEmpty }
    public static func isCustom(_ lens: LensProfile) -> Bool { byLens[lens.id] == nil && !(custom[lens.id] ?? []).isEmpty }

    public static func load(_ csv: String) -> [Point] {
        CSV.records(csv).compactMap { r in
            guard let id = r["lens_id"], !id.isEmpty, let f = Double(r["focal_mm"] ?? "") else { return nil }
            return Point(lensId: id, focalMm: f, model: r["model"] ?? "ptlens", t1: Double(r["t1"] ?? "") ?? 0, t2: Double(r["t2"] ?? "") ?? 0,
                         t3: Double(r["t3"] ?? "") ?? 0, calibCrop: Double(r["calib_crop"] ?? "") ?? 1, calibAspect: Double(r["calib_aspect"] ?? "") ?? 1.5,
                         realFocalMm: Double(r["real_focal_mm"] ?? ""))
        }
    }

    /// One calibration point rescaled to focal-length units.
    public static func rescale(_ p: Point) -> Model {
        let realFocal = p.realFocalMm ?? p.focalMm
        let hugin = (36.0 * 36 + 24 * 24).squareRoot() / p.calibCrop / (p.calibAspect * p.calibAspect + 1).squareRoot() / 2
        let h = realFocal / hugin
        switch p.model {
        case "poly3": let d = 1 - p.t1; return Model(ptlens: false, a: p.t1 * h * h / (d * d * d), b: 0, c: 0)
        case "poly5": return Model(ptlens: false, a: p.t1 * h * h, b: p.t2 * h * h * h * h, c: 0)
        default:
            let d = 1 - p.t1 - p.t2 - p.t3
            return Model(ptlens: true, a: p.t1 * h * h * h / (d * d * d * d), b: p.t2 * h * h / (d * d * d), c: p.t3 * h / (d * d))
        }
    }

    /// One-term model with `percent` distortion at the corner of a width × height frame at `focalMm` (negative =
    /// barrel). Returns k1 in focal-length units: r_d = r_u (1 + k1 r_u²).
    public static func k1(cornerPercent percent: Double, widthMm: Double, heightMm: Double, focalMm: Double) -> Double {
        let rd = (widthMm * widthMm / 4 + heightMm * heightMm / 4).squareRoot() / focalMm
        let ratio = 1 + percent / 100, ru = rd / ratio
        return (ratio - 1) / (ru * ru)
    }

    private static func customModel(_ lens: LensProfile, focalMm: Double) -> Model? {
        guard let list = custom[lens.id]?.sorted(by: { $0.0 < $1.0 }), let first = list.first, let last = list.last else { return nil }
        if focalMm <= first.0 { return Model(ptlens: false, a: first.1, b: 0, c: 0) }
        if focalMm >= last.0 { return Model(ptlens: false, a: last.1, b: 0, c: 0) }
        let hi = list.firstIndex { $0.0 >= focalMm }!
        let (f0, k0) = list[hi - 1], (f1, k1) = list[hi]
        return Model(ptlens: false, a: k0 + (k1 - k0) * (focalMm - f0) / (f1 - f0), b: 0, c: 0)
    }

    /// The model at `focalMm`, linearly between the nearest calibrated focal lengths (nil when none).
    public static func model(for lens: LensProfile, focalMm: Double) -> Model? {
        guard let list = byLens[lens.id]?.sorted(by: { $0.focalMm < $1.focalMm }), let first = list.first, let last = list.last else {
            return customModel(lens, focalMm: focalMm)
        }
        if focalMm <= first.focalMm { return rescale(first) }
        if focalMm >= last.focalMm { return rescale(last) }
        let hi = list.firstIndex { $0.focalMm >= focalMm }!
        let p0 = list[hi - 1], p1 = list[hi]
        let m0 = rescale(p0), m1 = rescale(p1)
        if m0.ptlens != m1.ptlens { return focalMm - p0.focalMm < p1.focalMm - focalMm ? m0 : m1 }
        let t = (focalMm - p0.focalMm) / (p1.focalMm - p0.focalMm)
        return Model(ptlens: m0.ptlens, a: m0.a + (m1.a - m0.a) * t, b: m0.b + (m1.b - m0.b) * t, c: m0.c + (m1.c - m0.c) * t)
    }

    /// Scene directions (tan x, tan y) that land on the edge of a width × height frame, `perSide` points per side,
    /// clockwise from the top-left corner.
    public static func frameOutline(_ model: Model, widthMm: Double, heightMm: Double, focalMm: Double, perSide: Int = 16) -> [(Double, Double)] {
        let hw = widthMm / 2, hh = heightMm / 2
        let corners = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
        var out: [(Double, Double)] = []
        for i in 0..<4 {
            let (x0, y0) = corners[i], (x1, y1) = corners[(i + 1) % 4]
            for k in 0..<perSide {
                let t = Double(k) / Double(perSide)
                let x = (x0 + (x1 - x0) * t) / focalMm, y = (y0 + (y1 - y0) * t) / focalMm
                let rd = (x * x + y * y).squareRoot()
                let s = rd < 1e-12 ? 1 : model.undistort(rd) / rd
                out.append((x * s, y * s))
            }
        }
        return out
    }

    /// Distortion at the frame corner in percent of radius: negative = barrel, positive = pincushion.
    public static func cornerPercent(_ model: Model, widthMm: Double, heightMm: Double, focalMm: Double) -> Double {
        let rd = (widthMm * widthMm / 4 + heightMm * heightMm / 4).squareRoot() / focalMm
        return (rd / model.undistort(rd) - 1) * 100
    }

    /// True when the frame corner lies inside the area the calibration measured (5 % margin).
    public static func withinCalibration(_ lens: LensProfile, widthMm: Double, heightMm: Double) -> Bool {
        if isCustom(lens) { return min(widthMm, heightMm) > 0 }
        guard let p = byLens[lens.id]?.first else { return false }
        let calibDiag = (36.0 * 36 + 24 * 24).squareRoot() / p.calibCrop
        return (widthMm * widthMm + heightMm * heightMm).squareRoot() <= calibDiag * 1.05 + 1e-9 && min(widthMm, heightMm) > 0
    }

    /// "−1.7 % barrel at the corners", "+0.8 % pincushion at the corners", "negligible".
    public static func text(_ percent: Double) -> String {
        if abs(percent) < 0.3 { return "negligible" }
        return percent < 0 ? String(format: "%.1f %% barrel at the corners", percent) : String(format: "+%.1f %% pincushion at the corners", percent)
    }
}
