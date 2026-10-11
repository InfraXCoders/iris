import Foundation

// Exposure and focus tools: false colour, zebras, focus peaking settings, the scopes (waveform, RGB parade,
// histogram) and matching the phone's exposure to the cinema camera. Levels are display (Rec.709-style) code values,
// 0–100 %, luma with Rec.709 weights. Pixels are packed 0xAARRGGBB. Same maths as the Android app.

public enum ScopeKind: String, CaseIterable, Sendable {
    case none, waveform = "wave", parade, histogram = "hist"
    public var label: String {
        switch self { case .none: return "No scope"; case .waveform: return "Waveform"; case .parade: return "RGB parade"; case .histogram: return "Histogram" }
    }
}

public enum PeakingColour: String, CaseIterable, Sendable {
    case red, green, blue, yellow, white
    public var label: String { rawValue.capitalized }
    public var argb: UInt32 {
        switch self { case .red: return 0xFFFF2A2A; case .green: return 0xFF35FF4A; case .blue: return 0xFF3D8BFF; case .yellow: return 0xFFFFE600; case .white: return 0xFFFFFFFF }
    }
}

/// Focus peaking sensitivity: the edge contrast (luma difference across ~2 px) that gets coloured.
public enum PeakingLevel: String, CaseIterable, Sendable {
    case low, medium, high
    public var label: String { rawValue.capitalized }
    public var threshold: Double { switch self { case .low: return 0.30; case .medium: return 0.20; case .high: return 0.12 } }
}

public enum Zebra {
    public static let levels = [70, 75, 80, 85, 90, 95, 100]
    public static let defaultLevel = 95
    /// 100 % means "at the top of the range" (≥ 99 %).
    public static func threshold(_ percent: Int) -> Double { percent >= 100 ? 0.99 : Double(percent) / 100 }
    public static func striped(luma: Double, percent: Int) -> Bool { luma >= threshold(percent) }
}

/// False colour with ARRI's published exposure bands (ALEXA manual, "False Color Exposure Check"). Everything else
/// grey. Blackmagic doesn't publish its own band values.
public enum FalseColour {
    public struct Band: Sendable {
        public var low: Double, high: Double, argb: UInt32, name: String, meaning: String
        public var label: String {
            func pct(_ v: Double) -> String { let p = v * 100; return p == p.rounded() ? "\(Int(p))" : "\(p)" }
            return "\(pct(low))–\(pct(min(high, 1)))%"
        }
    }
    public static let source = "ARRI ALEXA manual, False Color Exposure Check"
    public static let bands: [Band] = [
        Band(low: 0, high: 0.025, argb: 0xFF9B30FF, name: "Purple", meaning: "Black clipping"),
        Band(low: 0.025, high: 0.04, argb: 0xFF2E6BFF, name: "Blue", meaning: "Just above black"),
        Band(low: 0.38, high: 0.42, argb: 0xFF2EC83C, name: "Green", meaning: "18% grey"),
        Band(low: 0.52, high: 0.56, argb: 0xFFFF6EC7, name: "Pink", meaning: "Grey +1 stop (skin)"),
        Band(low: 0.97, high: 0.99, argb: 0xFFFFE600, name: "Yellow", meaning: "Just below clipping"),
        Band(low: 0.99, high: 1.0001, argb: 0xFFFF2020, name: "Red", meaning: "White clipping"),
    ]
    public static func band(for luma: Double) -> Band? { bands.first { luma >= $0.low && luma < $0.high } }
    public static func colour(_ luma: Double) -> UInt32 {
        if let b = band(for: luma) { return b.argb }
        let g = UInt32((min(max(luma, 0), 1) * 255).rounded())
        return 0xFF00_0000 | (g << 16) | (g << 8) | g
    }
}

/// A small sample of the picture, upright as on screen.
public struct ScopeFrame: Sendable {
    public let width: Int, height: Int
    public var pixels: [UInt32]
    public init(width: Int, height: Int, pixels: [UInt32]) {
        precondition(pixels.count == width * height)
        self.width = width; self.height = height; self.pixels = pixels
    }

    /// Samples a 32-bit image buffer (already upright) into `outW` × `outH`, inside the normalised crop u0,v0–u1,v1.
    /// `bgra` = BGRA byte order (Core Video's kCVPixelFormatType_32BGRA); otherwise RGBA.
    public static func sample(_ base: UnsafeRawPointer, bytesPerRow: Int, width: Int, height: Int, bgra: Bool,
                              outW: Int, outH: Int, crop: (Double, Double, Double, Double) = (0, 0, 1, 1)) -> ScopeFrame {
        let p = base.assumingMemoryBound(to: UInt8.self)
        var out = [UInt32](repeating: 0, count: outW * outH)
        for j in 0..<outH {
            let v = crop.1 + (crop.3 - crop.1) * (Double(j) + 0.5) / Double(outH)
            let sy = min(max(Int(v * Double(height)), 0), height - 1)
            for i in 0..<outW {
                let u = crop.0 + (crop.2 - crop.0) * (Double(i) + 0.5) / Double(outW)
                let sx = min(max(Int(u * Double(width)), 0), width - 1)
                let o = sy * bytesPerRow + sx * 4
                let c0 = UInt32(p[o]), c1 = UInt32(p[o + 1]), c2 = UInt32(p[o + 2])
                out[j * outW + i] = bgra ? (0xFF00_0000 | (c2 << 16) | (c1 << 8) | c0) : (0xFF00_0000 | (c0 << 16) | (c1 << 8) | c2)
            }
        }
        return ScopeFrame(width: outW, height: outH, pixels: out)
    }
}

public enum Scopes {
    /// The part of the camera picture the scopes measure (normalised u0, v0, u1, v1): the frame on screen relative to
    /// where the whole camera picture is drawn.
    public static func crop(frame: ScreenRect?, video: ScreenRect) -> (Double, Double, Double, Double) {
        guard let f = frame, video.width > 0, video.height > 0 else { return (0, 0, 1, 1) }
        func u(_ x: Double) -> Double { min(max((x - video.x) / video.width, 0), 1) }
        func v(_ y: Double) -> Double { min(max((y - video.y) / video.height, 0), 1) }
        let r = (u(f.x), v(f.y), u(f.x + f.width), v(f.y + f.height))
        return (r.2 - r.0 < 0.01 || r.3 - r.1 < 0.01) ? (0, 0, 1, 1) : r
    }

    public static func luma(_ argb: UInt32) -> Double {
        (0.2126 * Double((argb >> 16) & 0xFF) + 0.7152 * Double((argb >> 8) & 0xFF) + 0.0722 * Double(argb & 0xFF)) / 255
    }

    public struct Histogram: Sendable {
        public var bins: Int, r: [Int], g: [Int], b: [Int], y: [Int], total: Int
        /// Share of pixels with any channel ≥ 99 %; share with luma < 2.5 %.
        public var clipped: Double, crushed: Double
        public var peak: Int { max(r.max() ?? 0, g.max() ?? 0, b.max() ?? 0, y.max() ?? 0, 1) }
    }

    public static func histogram(_ f: ScopeFrame, bins: Int = 128) -> Histogram {
        var r = [Int](repeating: 0, count: bins), g = r, b = r, y = r
        var clip = 0, crush = 0
        func bin(_ v: UInt32) -> Int { min(Int(v) * bins / 256, bins - 1) }
        for p in f.pixels {
            let rr = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, bb = p & 0xFF
            r[bin(rr)] += 1; g[bin(gg)] += 1; b[bin(bb)] += 1
            let l = luma(p)
            y[min(max(Int(l * Double(bins)), 0), bins - 1)] += 1
            if rr >= 253 || gg >= 253 || bb >= 253 { clip += 1 }
            if l < 0.025 { crush += 1 }
        }
        let n = Double(max(f.pixels.count, 1))
        return Histogram(bins: bins, r: r, g: g, b: b, y: y, total: f.pixels.count, clipped: Double(clip) / n, crushed: Double(crush) / n)
    }

    /// For each of `columns` slices (left to right), pixel counts at each of `levels` levels (0 % at index 0).
    public struct Waveform: Sendable {
        public var columns: Int, levels: Int, parade: Bool, counts: [[Int]]
        public var peak: Int { max(counts.map { $0.max() ?? 0 }.max() ?? 0, 1) }
    }

    public static func waveform(_ f: ScopeFrame, columns: Int = 128, levels: Int = 100, parade: Bool = false) -> Waveform {
        var counts = [[Int]](repeating: [Int](repeating: 0, count: columns * levels), count: parade ? 3 : 1)
        func lv(_ v: Double) -> Int { min(max(Int(v * Double(levels - 1) + 0.5), 0), levels - 1) }
        for j in 0..<f.height { for i in 0..<f.width {
            let p = f.pixels[j * f.width + i]
            let col = min(i * columns / f.width, columns - 1)
            if parade {
                counts[0][col * levels + lv(Double((p >> 16) & 0xFF) / 255)] += 1
                counts[1][col * levels + lv(Double((p >> 8) & 0xFF) / 255)] += 1
                counts[2][col * levels + lv(Double(p & 0xFF) / 255)] += 1
            } else { counts[0][col * levels + lv(luma(p))] += 1 }
        } }
        return Waveform(columns: columns, levels: levels, parade: parade, counts: counts)
    }

    /// The waveform as an image (width = columns × channels, height = levels, 100 % at the top), 0xAARRGGBB with a
    /// transparent background, brightness by count.
    public static func waveformImage(_ w: Waveform) -> (width: Int, height: Int, pixels: [UInt32]) {
        let chans = w.counts.count, width = w.columns * chans
        var img = [UInt32](repeating: 0, count: width * w.levels)
        let peak = Double(w.peak)
        let tint: [UInt32] = chans == 3 ? [0xFF4040, 0x40FF40, 0x4080FF] : [0xE8F0E0]
        for c in 0..<chans { for col in 0..<w.columns { for l in 0..<w.levels {
            let n = w.counts[c][col * w.levels + l]
            if n == 0 { continue }
            let a = UInt32(min(max(60 + 195 * (Double(n) / peak).squareRoot(), 0), 255))
            img[(w.levels - 1 - l) * width + c * w.columns + col] = (a << 24) | tint[c]
        } } }
        return (width, w.levels, img)
    }
}

/// The picture tools on the CPU (for previews that can't use Core Image filters, and for tests).
public enum PictureProcessor {
    public static func process(_ f: ScopeFrame, lut: Lut3D?, input: LutInput, splitFraction: Double, falseColour: Bool,
                               zebraLevel: Int?, peaking: PeakingLevel?, peakingColour: PeakingColour, stripePx: Int = 8) -> [UInt32] {
        let w = f.width, h = f.height, src = f.pixels
        var out = src
        if let lut {
            let from = Int(min(max(splitFraction, 0), 1) * Double(w))
            if from <= 0 { lut.apply(toPixels: &out, input: input) }
            else if from < w {
                for y in 0..<h {
                    var row = Array(out[(y * w + from)..<(y * w + w)])
                    lut.apply(toPixels: &row, input: input)
                    out.replaceSubrange((y * w + from)..<(y * w + w), with: row)
                }
            }
        }
        let zebra = zebraLevel.map(Zebra.threshold)
        if falseColour || zebra != nil {
            for y in 0..<h { for x in 0..<w {
                let i = y * w + x, l = Scopes.luma(out[i])
                if falseColour { out[i] = FalseColour.colour(l) }
                if let z = zebra, l >= z {
                    let p = out[i]
                    out[i] = (x + y) % stripePx < stripePx / 2 ? 0xFFFF_FFFF
                        : 0xFF00_0000 | ((((p >> 16) & 0xFF) * 35 / 100) << 16) | ((((p >> 8) & 0xFF) * 35 / 100) << 8) | ((p & 0xFF) * 35 / 100)
                }
            } }
        }
        if let pk = peaking, w > 2, h > 2 {
            for y in 1..<(h - 1) { for x in 1..<(w - 1) {
                let gx = Scopes.luma(src[y * w + x + 1]) - Scopes.luma(src[y * w + x - 1])
                let gy = Scopes.luma(src[(y + 1) * w + x]) - Scopes.luma(src[(y - 1) * w + x])
                if (gx * gx + gy * gy).squareRoot() > pk.threshold { out[y * w + x] = peakingColour.argb }
            } }
        }
        return out
    }
}

/// Sets the phone's own ISO and exposure time so its picture gets the same exposure as the cinema camera.
/// Exposure ∝ t·ISO / (N²·2^ND). Approximate: phone and camera ISO ratings and tone curves differ; T-stop ≈ f-number.
public enum ExposureMatch {
    public struct Phone: Equatable, Sendable {
        public var iso: Double
        public var seconds: Double
        /// > 0: the phone picture is this many stops darker than it should be; < 0 brighter.
        public var stopsOff: Double
    }

    /// Exposure time from "180°" at `fps`, or "1/50".
    public static func exposureSeconds(shutter: String, fps: String) -> Double? {
        let t = shutter.trimmingCharacters(in: .whitespaces)
        if t.contains("/") {
            let parts = t.split(separator: "/").map { String($0).filter { $0.isNumber || $0 == "." } }
            guard parts.count == 2, let a = Double(parts[0]), let b = Double(parts[1]), a > 0, b > 0 else { return nil }
            return a / b
        }
        guard let angle = Exposure.shutterAngle(t), let f = Double(fps), f > 0 else { return nil }
        return angle / 360 / f
    }

    /// ISO × seconds the phone needs at its f-number, or nil when the shot's settings are incomplete.
    public static func target(iso: String, shutter: String, fps: String, nd: String, aperture: String, phoneAperture: Double) -> Double? {
        guard let s = Exposure.iso(iso), let t = exposureSeconds(shutter: shutter, fps: fps), let n = Exposure.stop(aperture) else { return nil }
        return t * s * phoneAperture * phoneAperture / (n * n * pow(2, Exposure.ndStops(nd)))
    }

    /// Phone ISO and time: the cinema camera's own time when it fits (≤ `maxPreviewSeconds` for a smooth preview),
    /// otherwise the longest allowed, keeping the ISO as low as possible.
    public static func phone(isoTimesSeconds k: Double, isoMin: Double, isoMax: Double, minSeconds: Double, maxSeconds: Double,
                             preferredSeconds: Double? = nil, maxPreviewSeconds: Double = 1.0 / 30) -> Phone {
        let tMax = max(min(maxSeconds, maxPreviewSeconds), minSeconds), tMin = minSeconds
        let t0 = min(max(preferredSeconds ?? tMax, tMin), tMax)
        let iso = min(max(k / t0, isoMin), isoMax).rounded()
        let t = min(max(k / iso, tMin), tMax)
        return Phone(iso: iso, seconds: t, stopsOff: log2(k / (iso * t)))
    }
}
