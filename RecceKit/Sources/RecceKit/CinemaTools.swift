import Foundation

// Viewfinder helpers shared with the Android app: shot-size shortcuts, focal-length stepping, relative exposure,
// full-screen monitor geometry, phone calibration and depth of field. Same maths as the Android core.

/// Shot sizes offered as shortcuts in the viewfinder.
public enum ShotSize: String, CaseIterable, Sendable {
    case wide, medium, close
    public var label: String { switch self { case .wide: return "Wide"; case .medium: return "Medium"; case .close: return "Close" } }
    public var shotType: ShotType { switch self { case .wide: return .ws; case .medium: return .ms; case .close: return .cu } }
}

/// What focal lengths a lens choice offers in the viewfinder, and how shot sizes map onto them.
public enum FocalOptions {
    private static let standardFocals: [Double] = [8, 10, 12, 14, 16, 18, 21, 24, 28, 32, 35, 40, 50, 65, 75, 85,
                                                    100, 135, 150, 180, 200, 250, 300, 400, 500, 600]

    /// The other focal lengths of the same prime series, shortest first.
    public static func seriesMembers(_ lens: LensProfile, all: [LensProfile] = Catalog.lenses) -> [LensProfile] {
        guard let series = lens.series, !lens.isZoom else { return [lens] }
        var seen = Set<Double>()
        let members = all.filter { $0.manufacturer == lens.manufacturer && $0.series == series && !$0.isZoom }
            .filter { seen.insert($0.focalLengthMin).inserted }
            .sorted { $0.focalLengthMin < $1.focalLengthMin }
        return members.isEmpty ? [lens] : members
    }

    /// Focal lengths to step through: a zoom's marked/standard stops, or the prime set's focal lengths.
    public static func focals(_ lens: LensProfile, all: [LensProfile] = Catalog.lenses) -> [Double] {
        if lens.isZoom {
            let marks = lens.availableFocalLengths + standardFocals.filter { $0 > lens.focalLengthMin && $0 < lens.focalLengthMax }
                + [lens.focalLengthMin, lens.focalLengthMax]
            return Array(Set(marks)).sorted()
        }
        return seriesMembers(lens, all: all).map { $0.focalLengthMin }
    }

    /// The lens to use for a focal length: the zoom itself, or the series member at that focal length.
    public static func lens(for lens: LensProfile, focal: Double, all: [LensProfile] = Catalog.lenses) -> LensProfile {
        lens.isZoom ? lens : (seriesMembers(lens, all: all).min { abs($0.focalLengthMin - focal) < abs($1.focalLengthMin - focal) } ?? lens)
    }

    /// Wide = shortest, close = longest, medium = nearest a slightly long "normal" lens (1.15 × the sensor-area
    /// diagonal, times the squeeze for anamorphic).
    public static func focal(for size: ShotSize, focals: [Double], mode: SensorMode, squeeze: Double = 1) -> Double? {
        guard let first = focals.first, let last = focals.last else { return nil }
        switch size {
        case .wide: return first
        case .close: return last
        case .medium:
            let normal = mode.diagonalMm * max(squeeze, 1) * 1.15
            return focals.min { abs($0 - normal) < abs($1 - normal) }
        }
    }
}

/// Exposure maths on the shot's text values (0 EV = ISO 400, 180°, T2.8, no ND).
public enum Exposure {
    public static func iso(_ text: String) -> Double? { Double(text.filter(\.isNumber)).flatMap { $0 > 0 ? $0 : nil } }
    public static func shutterAngle(_ text: String) -> Double? {
        Double(text.replacingOccurrences(of: "°", with: "").trimmingCharacters(in: .whitespaces)).flatMap { $0 > 0 ? $0 : nil }
    }
    /// "ND 0.6 (2 stops)" → 2, "None" → 0.
    public static func ndStops(_ text: String) -> Double {
        if let r = text.range(of: #"\((\d+(?:\.\d+)?) stop"#, options: .regularExpression) {
            let inner = text[r].dropFirst().split(separator: " ").first.map(String.init) ?? ""
            if let v = Double(inner) { return v }
        }
        if let r = text.range(of: #"\d+(?:\.\d+)?"#, options: .regularExpression), let v = Double(text[r]) { return v / 0.3 }
        return 0
    }
    /// "f/2.8", "T2.0", "2.8" → 2.8.
    public static func stop(_ text: String) -> Double? {
        guard let r = text.range(of: #"\d+(?:\.\d+)?"#, options: .regularExpression), let v = Double(text[r]), v > 0 else { return nil }
        return v
    }
    /// Exposure difference in stops versus ISO 400 / 180° / T2.8 / no ND.
    public static func ev(iso isoText: String, shutter: String, nd: String, aperture: String) -> Double {
        var ev = 0.0
        if let s = iso(isoText) { ev += log2(s / 400) }
        if let a = shutterAngle(shutter) { ev += log2(a / 180) }
        ev -= ndStops(nd)
        if let n = stop(aperture) { ev -= 2 * log2(n / 2.8) }
        return ev
    }
}

/// Geometry for the full-screen monitor: the camera picture fills the screen, the cinema frame sits in an area.
public enum Monitor {
    /// Where content of `contentAspect` appears when scaled to fill a container (cropping the overflow).
    public static func aspectFill(contentAspect: Double, width: Double, height: Double) -> ScreenRect {
        guard contentAspect > 0, width > 0, height > 0 else { return ScreenRect(x: 0, y: 0, width: 0, height: 0) }
        if width / height > contentAspect {
            let h = width / contentAspect
            return ScreenRect(x: 0, y: (height - h) / 2, width: width, height: h)
        }
        let w = height * contentAspect
        return ScreenRect(x: (width - w) / 2, y: 0, width: w, height: height)
    }

    /// The phone's view across a centred screen area, when the whole camera picture (`video`, seeing `view`) is
    /// drawn larger than the area. tan(half angle) scales with on-screen distance from the centre.
    public static func viewAcross(_ view: PreviewView, video: ScreenRect, area: ScreenRect) -> PreviewView {
        PreviewView(horizontalFov: Optics.fov(fromHalfTan: Optics.halfTan(view.horizontalFov) * area.width / video.width),
                    verticalFov: Optics.fov(fromHalfTan: Optics.halfTan(view.verticalFov) * area.height / video.height))
    }

    public struct Layout: Equatable, Sendable {
        public var zoom: Double
        public var lines: FrameLines
        public var frame: ScreenRect
    }

    /// Zoom the phone so the cinema frame fills `area` (with `margin`: 1.0 = edge to edge), then where to draw it.
    /// `view` is the phone's view of the whole `video` at zoom 1. With `fixedZoom` the zoom is not changed.
    public static func layout(reference: ReferenceFrame, view: PreviewView, video: ScreenRect, area: ScreenRect,
                              minZoom: Double, maxZoom: Double, margin: Double = 1, fixedZoom: Double? = nil) -> Layout {
        let areaView = viewAcross(view, video: video, area: area)
        let zoom = fixedZoom ?? Viewfinder.bestZoom(for: reference, base: areaView, minZoom: minZoom, maxZoom: maxZoom, margin: margin)
        let lines = Viewfinder.frameLines(reference, on: areaView.zoomed(zoom))
        if lines.fits { return Layout(zoom: zoom, lines: lines, frame: Viewfinder.frameRect(lines, in: area)) }
        // Wider than the phone can see: keep the frame's real shape (e.g. 2.39:1), as large as the area allows.
        let scale = 1 / max(lines.widthFraction, lines.heightFraction)
        let fw = area.width * lines.widthFraction * scale, fh = area.height * lines.heightFraction * scale
        return Layout(zoom: zoom, lines: lines, frame: ScreenRect(x: area.midX - fw / 2, y: area.midY - fh / 2, width: fw, height: fh))
    }

    /// A centred area inset by `side` left/right and `bar` top/bottom.
    public static func centredArea(width: Double, height: Double, side: Double, bar: Double) -> ScreenRect {
        ScreenRect(x: side, y: bar, width: max(width - 2 * side, 1), height: max(height - 2 * bar, 1))
    }

    /// Frame position as fractions of the full camera picture (saved with a reference photo).
    public static func frameInPicture(_ frame: ScreenRect, video: ScreenRect) -> ScreenRect {
        ScreenRect(x: min(max((frame.x - video.x) / video.width, 0), 1), y: min(max((frame.y - video.y) / video.height, 0), 1),
                   width: min(frame.width / video.width, 1), height: min(frame.height / video.height, 1))
    }
}

/// Measuring the phone camera's real angle of view with an object of known width at a measured distance.
public enum Calibration {
    /// Full angle (degrees) across the measured axis; `fraction` = object width / full picture width on that axis.
    public static func fov(objectWidthM: Double, distanceM: Double, fraction: Double) -> Double? {
        guard objectWidthM > 0, distanceM > 0, fraction > 0, fraction <= 1 else { return nil }
        return Optics.fov(fromHalfTan: objectWidthM / (2 * distanceM) / fraction)
    }
    /// Angle across the other side of a picture with ratio `ratio` (e.g. short → long side).
    public static func otherSide(_ fov: Double, ratio: Double) -> Double { Optics.fov(fromHalfTan: Optics.halfTan(fov) * ratio) }
    /// Signed frame-line size error (percent) of the reported angle against the measured one.
    public static func errorPercent(reported: Double, measured: Double) -> Double {
        (Optics.halfTan(reported) / Optics.halfTan(measured) - 1) * 100
    }
}

/// Focus and depth of field (thin-lens formulas, as in makers' DoF tables). Circle of confusion = sensor-area
/// diagonal / 1500. The T-stop is used as the stop (slightly deeper, safe-side depth of field).
public enum Focus {
    public struct Result: Equatable, Sendable {
        public var hyperfocalM: Double
        public var nearM: Double
        /// `.infinity` at or beyond the hyperfocal distance.
        public var farM: Double
        /// The subject is closer than the lens can focus.
        public var tooClose: Bool
    }

    public static func circleOfConfusionMm(_ mode: SensorMode) -> Double { mode.diagonalMm / 1500 }

    public static func depthOfField(focalMm: Double, stop: Double, distanceM: Double, cocMm: Double, closeFocusM: Double = 0) -> Result? {
        guard focalMm > 0, stop > 0, distanceM > 0, cocMm > 0 else { return nil }
        let f = focalMm / 1000, c = cocMm / 1000
        let h = f * f / (stop * c) + f
        let s = max(distanceM, f * 1.01)
        let near = s * (h - f) / (h + s - 2 * f)
        let far = s >= h ? Double.infinity : s * (h - f) / (h - s)
        return Result(hyperfocalM: h, nearM: near, farM: far, tooClose: closeFocusM > 0 && distanceM < closeFocusM - 1e-9)
    }

    /// "2.4 m", "85 cm", "∞".
    public static func distanceText(_ m: Double) -> String {
        if m.isInfinite { return "∞" }
        if m < 1 { return "\(Int((m * 100).rounded())) cm" }
        if m < 10 { return String(format: "%.1f m", m) }
        return "\(Int(m.rounded())) m"
    }

    /// "DoF 2.7–3.3 m" or "DoF 1.2 m–∞".
    public static func dofText(_ r: Result) -> String {
        r.farM.isInfinite ? "DoF \(distanceText(r.nearM))–∞"
            : "DoF \(distanceText(r.nearM).replacingOccurrences(of: " m", with: ""))–\(distanceText(r.farM))"
    }

    public static let presetsM: [Double] = [0.5, 0.75, 1, 1.5, 2, 3, 5, 10, 20]
}
