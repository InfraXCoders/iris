import Foundation

/// A 3D colour lookup table (from a .cube file or built in). `data` holds size³ RGB triples, red changing fastest
/// (the .cube order, also what Core Image's CIColorCube expects). Output is 0...1; input is mapped from
/// `domainMin...domainMax`. Same maths as the Android app's `Lut3D`.
public final class Lut3D: @unchecked Sendable {
    public let name: String
    public let size: Int
    public let data: [Float]
    public let domainMin: [Float]
    public let domainMax: [Float]

    public init(name: String, size: Int, data: [Float], domainMin: [Float] = [0, 0, 0], domainMax: [Float] = [1, 1, 1]) throws {
        guard (2...256).contains(size), data.count == size * size * size * 3 else { throw CubeParser.CubeError.message("Bad LUT size") }
        self.name = name; self.size = size; self.data = data; self.domainMin = domainMin; self.domainMax = domainMax
    }

    @inline(__always) private func idx(_ r: Int, _ g: Int, _ b: Int) -> Int { ((b * size + g) * size + r) * 3 }

    /// Trilinear lookup of one colour (in the LUT's domain).
    public func apply(_ r: Float, _ g: Float, _ b: Float) -> (Float, Float, Float) {
        let n = size - 1
        func pos(_ v: Float, _ c: Int) -> Float {
            let t = (v - domainMin[c]) / (domainMax[c] - domainMin[c])
            return min(max(t, 0), 1) * Float(n)
        }
        let x = pos(r, 0), y = pos(g, 1), z = pos(b, 2)
        let x0 = min(max(Int(x.rounded(.down)), 0), n - 1), y0 = min(max(Int(y.rounded(.down)), 0), n - 1), z0 = min(max(Int(z.rounded(.down)), 0), n - 1)
        let fx = x - Float(x0), fy = y - Float(y0), fz = z - Float(z0)
        var out: [Float] = [0, 0, 0]
        for c in 0..<3 {
            func v(_ dx: Int, _ dy: Int, _ dz: Int) -> Float { data[idx(x0 + dx, y0 + dy, z0 + dz) + c] }
            let c00 = v(0, 0, 0) + (v(1, 0, 0) - v(0, 0, 0)) * fx
            let c10 = v(0, 1, 0) + (v(1, 1, 0) - v(0, 1, 0)) * fx
            let c01 = v(0, 0, 1) + (v(1, 0, 1) - v(0, 0, 1)) * fx
            let c11 = v(0, 1, 1) + (v(1, 1, 1) - v(0, 1, 1)) * fx
            let c0 = c00 + (c10 - c00) * fy, c1 = c01 + (c11 - c01) * fy
            out[c] = c0 + (c1 - c0) * fz
        }
        return (out[0], out[1], out[2])
    }

    /// RGBA float data for Core Image's CIColorCube (red fastest, alpha 1). Only valid for the 0...1 domain;
    /// other domains are resampled to it.
    public func cubeRGBA() -> [Float] {
        var out = [Float](repeating: 1, count: size * size * size * 4)
        let unitDomain = domainMin == [0, 0, 0] && domainMax == [1, 1, 1]
        var o = 0
        for b in 0..<size { for g in 0..<size { for r in 0..<size {
            if unitDomain {
                let i = idx(r, g, b)
                out[o] = data[i]; out[o + 1] = data[i + 1]; out[o + 2] = data[i + 2]
            } else {
                let s = Float(size - 1)
                let v = apply(domainMin[0] + Float(r) / s * (domainMax[0] - domainMin[0]),
                              domainMin[1] + Float(g) / s * (domainMax[1] - domainMin[1]),
                              domainMin[2] + Float(b) / s * (domainMax[2] - domainMin[2]))
                out[o] = v.0; out[o + 1] = v.1; out[o + 2] = v.2
            }
            o += 4
        } } }
        return out
    }

    /// Applies the LUT (after `input`) to packed 0xAARRGGBB pixels in place.
    public func apply(toPixels pixels: inout [UInt32], input: LutInput) {
        for i in pixels.indices {
            let p = pixels[i]
            var r = Float((p >> 16) & 0xFF) / 255, g = Float((p >> 8) & 0xFF) / 255, b = Float(p & 0xFF) / 255
            if input == .bmdFilmGen5 { (r, g, b) = InputTransform.displayToGen5(r, g, b) }
            let o = apply(r, g, b)
            func ch(_ v: Float) -> UInt32 { UInt32((min(max(v, 0), 1) * 255).rounded()) }
            pixels[i] = (p & 0xFF00_0000) | (ch(o.0) << 16) | (ch(o.1) << 8) | ch(o.2)
        }
    }
}

/// What the LUT expects as input. The phone camera gives display (Rec.709 / sRGB) pictures.
public enum LutInput: String, CaseIterable, Sendable {
    case rec709 = "rec709", bmdFilmGen5 = "bmdgen5"
    public var label: String {
        switch self {
        case .rec709: return "Rec.709 (look / display LUTs)"
        case .bmdFilmGen5: return "Blackmagic Film Gen 5 (log, approximated from the phone)"
        }
    }
    public static func of(_ code: String?) -> LutInput { LutInput(rawValue: code ?? "") ?? .rec709 }
}

/// Turning the phone's display picture into what a log LUT expects: display → linear Rec.709 → Blackmagic Wide Gamut
/// (Gen 5) → Blackmagic Film Gen 5 curve. An approximation: the phone picture is already tone-mapped and limited to
/// Rec.709 colours and the phone's dynamic range.
public enum InputTransform {
    public static func displayToLinear(_ v: Float) -> Float { v <= 0.04045 ? v / 12.92 : Float(pow(Double((v + 0.055) / 1.055), 2.4)) }

    /// Blackmagic Film Generation 5 OETF (Blackmagic Design, "Blackmagic Generation 5 Color Science", 2021; constants
    /// as in colour-science): y = D·x + E below 0.005, else A·ln(x + B) + C.
    public static func gen5Oetf(_ x: Float) -> Float {
        let a = 0.08692876065491224, b = 0.005494072432257808, c = 0.5300133392291939
        let d = 8.283605932402494, e = 0.09246575342465753
        return x < 0.005 ? Float(d * Double(x) + e) : Float(a * log(Double(x) + b) + c)
    }

    public static func rec709ToGen5(_ v: Float) -> Float { gen5Oetf(displayToLinear(min(max(v, 0), 1))) }

    private static let rec709Primaries: [Double] = [0.64, 0.33, 0.30, 0.60, 0.15, 0.06]
    /// Blackmagic Design, "Blackmagic Generation 5 Color Science" (2021), values as in colour-science.
    private static let wideGamutPrimaries: [Double] = [0.7177215, 0.3171181, 0.2280410, 0.8615690, 0.1005841, -0.0820452]
    private static let d65: [Double] = [0.3127170, 0.3290312]

    /// Linear Rec.709 → linear Blackmagic Wide Gamut, row-major 3×3.
    public static let rec709ToWideGamut: [Double] = multiply(invert(npm(wideGamutPrimaries, d65)), npm(rec709Primaries, d65))

    public static func displayToGen5(_ r: Float, _ g: Float, _ b: Float) -> (Float, Float, Float) {
        let m = rec709ToWideGamut
        let l = [Double(displayToLinear(min(max(r, 0), 1))), Double(displayToLinear(min(max(g, 0), 1))), Double(displayToLinear(min(max(b, 0), 1)))]
        func row(_ i: Int) -> Float { gen5Oetf(max(Float(m[i * 3] * l[0] + m[i * 3 + 1] * l[1] + m[i * 3 + 2] * l[2]), 0)) }
        return (row(0), row(1), row(2))
    }

    static func npm(_ p: [Double], _ w: [Double]) -> [Double] {
        func xyz(_ x: Double, _ y: Double) -> [Double] { [x / y, 1, (1 - x - y) / y] }
        let r = xyz(p[0], p[1]), g = xyz(p[2], p[3]), b = xyz(p[4], p[5])
        let prim = [r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2]]
        let wv = xyz(w[0], w[1])
        let inv = invert(prim)
        let s = (0..<3).map { i in inv[i * 3] * wv[0] + inv[i * 3 + 1] * wv[1] + inv[i * 3 + 2] * wv[2] }
        return (0..<9).map { k in prim[k] * s[k % 3] }
    }

    static func multiply(_ a: [Double], _ b: [Double]) -> [Double] {
        (0..<9).map { k in let i = k / 3, j = k % 3; return a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j] }
    }

    static func invert(_ m: [Double]) -> [Double] {
        let (a, b, c, d, e, f, g, h, i) = (m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8])
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return [(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]
    }
}

/// Reads Adobe/Resolve .cube files: 3D (LUT_3D_SIZE) or 1D (LUT_1D_SIZE, turned into a 33³ 3D LUT).
public enum CubeParser {
    public enum CubeError: Error, LocalizedError {
        case message(String)
        public var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
    }

    /// Largest 3D LUT accepted (65³ is the usual maximum for grading LUTs).
    public static let max3D = 65

    public static func parse(_ text: String, fallbackName: String) throws -> Lut3D {
        var title: String?
        var size3 = 0, size1 = 0
        var dMin: [Float] = [0, 0, 0], dMax: [Float] = [1, 1, 1]
        var data: [Float] = []
        var count = 0
        var allocated = false
        for raw in text.split(omittingEmptySubsequences: true, whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            var line = Substring(raw)
            if let hash = line.firstIndex(of: "#") { line = line[..<hash] }
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard let first = trimmed.first else { continue }
            let tok = trimmed.split(whereSeparator: { $0 == " " || $0 == "\t" })
            if first.isNumber || first == "-" || first == "." || first == "+" {
                guard tok.count == 3 else { continue }
                if !allocated {
                    let total: Int
                    if size3 > 0 { total = size3 * size3 * size3 * 3 }
                    else if size1 > 0 { total = size1 * 3 }
                    else { throw CubeError.message("Data before LUT_3D_SIZE / LUT_1D_SIZE") }
                    data = [Float](repeating: 0, count: total); allocated = true
                }
                guard count + 3 <= data.count else { throw CubeError.message("More entries than the LUT size") }
                for t in tok {
                    guard let v = Float(t) else { throw CubeError.message("Bad number: \(t)") }
                    data[count] = v; count += 1
                }
                continue
            }
            switch tok[0].uppercased() {
            case "TITLE":
                title = trimmed.drop(while: { $0 != " " }).trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            case "LUT_3D_SIZE":
                guard tok.count > 1, let s = Int(tok[1]) else { throw CubeError.message("Bad LUT_3D_SIZE") }
                guard (2...max3D).contains(s) else { throw CubeError.message("LUT_3D_SIZE \(s) is not supported (2–\(max3D))") }
                size3 = s
            case "LUT_1D_SIZE":
                guard tok.count > 1, let s = Int(tok[1]) else { throw CubeError.message("Bad LUT_1D_SIZE") }
                guard (2...65536).contains(s) else { throw CubeError.message("LUT_1D_SIZE \(s) is not supported") }
                size1 = s
            case "DOMAIN_MIN": if tok.count >= 4 { for i in 0..<3 { dMin[i] = Float(tok[i + 1]) ?? 0 } }
            case "DOMAIN_MAX": if tok.count >= 4 { for i in 0..<3 { dMax[i] = Float(tok[i + 1]) ?? 1 } }
            case "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE":
                if tok.count >= 3 { let lo = Float(tok[1]) ?? 0, hi = Float(tok[2]) ?? 1; dMin = [lo, lo, lo]; dMax = [hi, hi, hi] }
            default: break // other keywords (e.g. LUT_IN_VIDEO_RANGE) are ignored
            }
        }
        let name = (title?.isEmpty == false ? title : nil) ?? fallbackName
        for i in 0..<3 where dMax[i] <= dMin[i] { throw CubeError.message("Bad DOMAIN") }
        if size3 > 0 {
            guard allocated, count == size3 * size3 * size3 * 3 else { throw CubeError.message("Expected \(size3 * size3 * size3) entries, found \(count / 3)") }
            return try Lut3D(name: name, size: size3, data: data, domainMin: dMin, domainMax: dMax)
        }
        if size1 > 0 {
            guard allocated, count == size1 * 3 else { throw CubeError.message("Expected \(size1) entries, found \(count / 3)") }
            let curveData = data, mn = dMin, mx = dMax
            func curve(_ v: Float, _ c: Int) -> Float {
                let t = min(max((v - mn[c]) / (mx[c] - mn[c]), 0), 1) * Float(size1 - 1)
                let i0 = min(max(Int(t.rounded(.down)), 0), size1 - 2), f = t - Float(i0)
                return curveData[i0 * 3 + c] + (curveData[(i0 + 1) * 3 + c] - curveData[i0 * 3 + c]) * f
            }
            return BuiltInLooks.generate(name, size: 33) { r, g, b in
                (curve(mn[0] + r * (mx[0] - mn[0]), 0), curve(mn[1] + g * (mx[1] - mn[1]), 1), curve(mn[2] + b * (mx[2] - mn[2]), 2))
            }
        }
        throw CubeError.message("Not a .cube LUT (no LUT_3D_SIZE or LUT_1D_SIZE)")
    }
}

/// A few looks made in code (no third-party LUTs are bundled). They expect Rec.709 input.
public enum BuiltInLooks {
    public static func generate(_ name: String, size: Int, _ f: (Float, Float, Float) -> (Float, Float, Float)) -> Lut3D {
        var data = [Float](repeating: 0, count: size * size * size * 3)
        var i = 0
        let s = Float(size - 1)
        for b in 0..<size { for g in 0..<size { for r in 0..<size {
            let o = f(Float(r) / s, Float(g) / s, Float(b) / s)
            data[i] = min(max(o.0, 0), 1); data[i + 1] = min(max(o.1, 0), 1); data[i + 2] = min(max(o.2, 0), 1)
            i += 3
        } } }
        // swiftlint:disable:next force_try
        return try! Lut3D(name: name, size: size, data: data)
    }

    private static func luma(_ r: Float, _ g: Float, _ b: Float) -> Float { 0.2126 * r + 0.7152 * g + 0.0722 * b }
    private static func sCurve(_ v: Float, _ k: Float) -> Float { let x = v - 0.5; return 0.5 + x * (1 + k) / (1 + k * 4 * x * x) }

    public static let all: [Lut3D] = [
        generate("Warm", size: 33) { r, g, b in (r * 1.06 + 0.02, g * 1.01, b * 0.9) },
        generate("Cool", size: 33) { r, g, b in (r * 0.92, g * 0.99 + 0.01, b * 1.07 + 0.02) },
        generate("Teal & Orange", size: 33) { r, g, b in
            let y = luma(r, g, b), skin = min(max(r - b, 0), 1), shadow = 1 - y
            return (sCurve(r + 0.06 * skin - 0.05 * shadow, 0.3), sCurve(g + 0.02 * shadow, 0.3), sCurve(b + 0.08 * shadow - 0.05 * skin, 0.3))
        },
        generate("Bleach bypass", size: 33) { r, g, b in
            let y = luma(r, g, b)
            return (sCurve(0.5 * r + 0.5 * y, 0.6), sCurve(0.5 * g + 0.5 * y, 0.6), sCurve(0.5 * b + 0.5 * y, 0.6))
        },
        generate("High contrast", size: 33) { r, g, b in (sCurve(r, 0.7), sCurve(g, 0.7), sCurve(b, 0.7)) },
        generate("Black & white", size: 33) { r, g, b in let y = sCurve(luma(r, g, b), 0.35); return (y, y, y) },
    ]

    /// A small colour chart (hue ramp over a grey ramp) to preview looks on, as 0xAARRGGBB pixels.
    public static func testChart(width w: Int, height h: Int) -> [UInt32] {
        var px = [UInt32](repeating: 0, count: w * h)
        func ch(_ v: Float) -> UInt32 { UInt32((min(max(v, 0), 1) * 255).rounded()) }
        for y in 0..<h { for x in 0..<w {
            let t = Float(x) / max(1, Float(w - 1))
            let c = y < h / 2 ? hue(t, 0.85, 0.8) : (t, t, t)
            px[y * w + x] = 0xFF00_0000 | (ch(c.0) << 16) | (ch(c.1) << 8) | ch(c.2)
        } }
        return px
    }

    private static func hue(_ h: Float, _ s: Float, _ v: Float) -> (Float, Float, Float) {
        let i = min(max(Int(h * 6), 0), 5), f = h * 6 - Float(i)
        let p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f))
        switch i { case 0: return (v, t, p); case 1: return (q, v, p); case 2: return (p, v, t); case 3: return (p, q, v); case 4: return (t, p, v); default: return (v, p, q) }
    }
}
