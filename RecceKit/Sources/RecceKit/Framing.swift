import Foundation

/// What the cinema camera will actually record, for the director's viewfinder.
///
/// Fixes vs the Android UniversalFramingEngine:
/// - Anamorphic: the horizontal view is 2·atan(squeeze·W / 2f). Android multiplied the *angle* by the squeeze,
///   e.g. a 2x lens at 40 mm on an ALEXA 35 gave 77.1° instead of the correct 70.0°.
/// - Diagonal: real diagonal angle, not sqrt(h² + v²) of the angles.
/// - Delivery aspect ratio (2.39, 1.85…) is applied as a mask inside the desqueezed sensor area.
public struct ReferenceFrame: Equatable, Codable, Sendable {
    /// Desqueezed sensor area, in the units of tan(half-angle)·2f, i.e. millimetres at focal length f.
    public var captureWidthMm: Double
    public var captureHeightMm: Double
    /// Delivered (masked) area at the effective focal length.
    public var deliveredWidthMm: Double
    public var deliveredHeightMm: Double
    public var effectiveFocalLengthMm: Double
    /// FOV of the full desqueezed sensor.
    public var captureFov: FieldOfView
    /// FOV of the delivered frame (after the aspect-ratio mask). This is what the frame lines show.
    public var deliveredFov: FieldOfView
    /// Width/height of the delivered frame.
    public var deliveredAspect: Double
    public var cropFactor: Double

    /// tan(half horizontal / vertical angle) of the delivered frame.
    public var tanHalfH: Double { deliveredWidthMm / (2 * effectiveFocalLengthMm) }
    public var tanHalfV: Double { deliveredHeightMm / (2 * effectiveFocalLengthMm) }
}

public enum Framing {
    /// - Parameters:
    ///   - focalLengthMultiplier: e.g. 0.71 for a 0.71x speed booster, 1.4 for a 1.4x extender.
    ///   - aspectRatio: delivery aspect (width / height). `nil` = the full desqueezed sensor.
    public static func reference(sensorWidthMm: Double, sensorHeightMm: Double, focalLengthMm: Double,
                                 anamorphicSqueeze: Double = 1, focalLengthMultiplier: Double = 1,
                                 aspectRatio: Double? = nil) -> ReferenceFrame? {
        let f = focalLengthMm * focalLengthMultiplier
        let squeeze = anamorphicSqueeze > 0 ? anamorphicSqueeze : 1
        guard f > 0, sensorWidthMm > 0, sensorHeightMm > 0 else { return nil }
        let capW = sensorWidthMm * squeeze
        let capH = sensorHeightMm
        var outW = capW, outH = capH
        if let a = aspectRatio, a > 0 {
            if a > capW / capH { outH = capW / a } else { outW = capH * a }   // letterbox or pillarbox inside the sensor
        }
        return ReferenceFrame(
            captureWidthMm: capW, captureHeightMm: capH,
            deliveredWidthMm: outW, deliveredHeightMm: outH,
            effectiveFocalLengthMm: f,
            captureFov: Optics.fieldOfView(focalLengthMm: f, widthMm: capW, heightMm: capH),
            deliveredFov: Optics.fieldOfView(focalLengthMm: f, widthMm: outW, heightMm: outH),
            deliveredAspect: outW / outH,
            cropFactor: Optics.cropFactor(sensorWidthMm: sensorWidthMm))
    }

    public static func reference(camera: CameraProfile, lens: LensProfile, focalLengthMm: Double,
                                 aspectRatio: Double? = nil, focalLengthMultiplier: Double = 1) -> ReferenceFrame? {
        reference(sensorWidthMm: camera.sensorWidthMm, sensorHeightMm: camera.sensorHeightMm, focalLengthMm: focalLengthMm,
                  anamorphicSqueeze: lens.anamorphicSqueeze, focalLengthMultiplier: focalLengthMultiplier, aspectRatio: aspectRatio)
    }
}

/// The phone/Mac camera view the preview is showing, as horizontal and vertical FOV in degrees,
/// measured along the preview's on-screen width and height.
public struct PreviewView: Equatable, Codable, Sendable {
    public var horizontalFov: Double
    public var verticalFov: Double

    public init(horizontalFov: Double, verticalFov: Double) {
        self.horizontalFov = horizontalFov
        self.verticalFov = verticalFov
    }

    /// From a camera format: its horizontal FOV across the long side and its pixel aspect (long / short side).
    /// `portrait` = the preview is shown with the long side vertical.
    public static func fromFormat(longSideFov: Double, longOverShort: Double, portrait: Bool) -> PreviewView {
        let tanLong = Optics.halfTan(longSideFov)
        let shortFov = Optics.fov(fromHalfTan: tanLong / max(longOverShort, 0.0001))
        return portrait ? PreviewView(horizontalFov: shortFov, verticalFov: longSideFov)
                        : PreviewView(horizontalFov: longSideFov, verticalFov: shortFov)
    }

    /// The view after digital/optical zoom `factor` (1 = unzoomed).
    public func zoomed(_ factor: Double) -> PreviewView {
        let z = max(factor, 0.0001)
        return PreviewView(horizontalFov: Optics.fov(fromHalfTan: Optics.halfTan(horizontalFov) / z),
                           verticalFov: Optics.fov(fromHalfTan: Optics.halfTan(verticalFov) / z))
    }
}

/// Where to draw the cinema frame on the preview, as fractions of the preview's width and height.
public struct FrameLines: Equatable, Codable, Sendable {
    /// Frame width / preview width. >1 means the cinema frame is wider than what the phone shows.
    public var widthFraction: Double
    public var heightFraction: Double

    public init(widthFraction: Double, heightFraction: Double) {
        self.widthFraction = widthFraction
        self.heightFraction = heightFraction
    }
    /// true when the whole cinema frame is visible on the preview.
    public var fits: Bool { widthFraction <= 1.000_001 && heightFraction <= 1.000_001 }

    /// Clamped fractions for drawing (the frame can't be drawn outside the preview).
    public var drawable: (width: Double, height: Double) { (min(widthFraction, 1), min(heightFraction, 1)) }
}

public enum Viewfinder {
    /// Frame lines for a cinema frame on a preview. Uses tan(half-angle) ratios, which is exact for rectilinear
    /// lenses. (The Android app used the ratio of the angles and capped it at 1, so the frame never shrank.)
    public static func frameLines(_ reference: ReferenceFrame, on preview: PreviewView) -> FrameLines {
        let ph = Optics.halfTan(preview.horizontalFov), pv = Optics.halfTan(preview.verticalFov)
        guard ph > 0, pv > 0 else { return FrameLines(widthFraction: .infinity, heightFraction: .infinity) }
        return FrameLines(widthFraction: reference.tanHalfH / ph, heightFraction: reference.tanHalfV / pv)
    }

    /// The camera zoom to use so the cinema frame fills the preview as much as possible while staying fully
    /// visible with `margin` (1.1 = 10% breathing room around the frame lines).
    /// Returns a value within `minZoom...maxZoom`. If even `minZoom` cannot show the whole frame, returns `minZoom`
    /// (check `frameLines(...).fits` to warn the user).
    public static func bestZoom(for reference: ReferenceFrame, base: PreviewView,
                                minZoom: Double = 1, maxZoom: Double = 10, margin: Double = 1.1) -> Double {
        let ph = Optics.halfTan(base.horizontalFov), pv = Optics.halfTan(base.verticalFov)
        guard reference.tanHalfH > 0, reference.tanHalfV > 0, ph > 0, pv > 0 else { return minZoom }
        let z = min(ph / (reference.tanHalfH * margin), pv / (reference.tanHalfV * margin))
        return min(max(z, minZoom), max(minZoom, maxZoom))
    }
}

/// A rectangle in screen points (kept free of CoreGraphics so it is testable everywhere).
public struct ScreenRect: Equatable, Sendable {
    public var x: Double, y: Double, width: Double, height: Double
    public init(x: Double, y: Double, width: Double, height: Double) { self.x = x; self.y = y; self.width = width; self.height = height }
    public var midX: Double { x + width / 2 }
    public var midY: Double { y + height / 2 }
}

extension Viewfinder {
    /// Where a video of aspect `contentAspect` (width / height, as shown on screen) appears inside a container
    /// when scaled to fit without cropping (AVLayerVideoGravity.resizeAspect).
    public static func aspectFit(contentAspect: Double, containerWidth: Double, containerHeight: Double) -> ScreenRect {
        guard contentAspect > 0, containerWidth > 0, containerHeight > 0 else { return ScreenRect(x: 0, y: 0, width: 0, height: 0) }
        if containerWidth / containerHeight > contentAspect {
            let w = containerHeight * contentAspect
            return ScreenRect(x: (containerWidth - w) / 2, y: 0, width: w, height: containerHeight)
        } else {
            let h = containerWidth / contentAspect
            return ScreenRect(x: 0, y: (containerHeight - h) / 2, width: containerWidth, height: h)
        }
    }

    /// The cinema frame rectangle, centred inside the on-screen video rectangle.
    public static func frameRect(_ lines: FrameLines, in video: ScreenRect) -> ScreenRect {
        let d = lines.drawable
        let w = video.width * d.width, h = video.height * d.height
        return ScreenRect(x: video.midX - w / 2, y: video.midY - h / 2, width: w, height: h)
    }

    /// A safe-area rectangle (e.g. 0.9 = 90% action safe, 0.8 = title safe) inside a frame.
    public static func safeArea(_ frame: ScreenRect, fraction: Double) -> ScreenRect {
        let w = frame.width * fraction, h = frame.height * fraction
        return ScreenRect(x: frame.midX - w / 2, y: frame.midY - h / 2, width: w, height: h)
    }

    /// Converts a tap inside the frame to normalised marker coordinates (0...1), or nil if outside the frame.
    public static func normalisedPoint(x: Double, y: Double, in frame: ScreenRect) -> (x: Double, y: Double)? {
        guard frame.width > 0, frame.height > 0 else { return nil }
        let nx = (x - frame.x) / frame.width, ny = (y - frame.y) / frame.height
        guard (0...1).contains(nx), (0...1).contains(ny) else { return nil }
        return (nx, ny)
    }
}
