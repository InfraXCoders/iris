import Foundation

/// Field of view in degrees.
public struct FieldOfView: Equatable, Codable, Sendable {
    public var horizontal: Double
    public var vertical: Double
    public var diagonal: Double

    public init(horizontal: Double, vertical: Double, diagonal: Double) {
        self.horizontal = horizontal
        self.vertical = vertical
        self.diagonal = diagonal
    }

    public static let zero = FieldOfView(horizontal: 0, vertical: 0, diagonal: 0)
}

/// Rectilinear lens geometry. All angles in degrees, all lengths in millimetres.
public enum Optics {
    /// Full Frame still-photo width, used for crop factor and "equivalent" focal length.
    public static let fullFrameWidthMm = 36.0

    @inlinable public static func degrees(_ radians: Double) -> Double { radians * 180.0 / .pi }
    @inlinable public static func radians(_ degrees: Double) -> Double { degrees * .pi / 180.0 }

    /// Angle covered by a sensor dimension: 2·atan(d / 2f).
    public static func angle(dimensionMm: Double, focalLengthMm: Double) -> Double {
        guard focalLengthMm > 0, dimensionMm > 0 else { return 0 }
        return 2 * degrees(atan(dimensionMm / (2 * focalLengthMm)))
    }

    /// Horizontal, vertical and diagonal FOV of a sensor area. Returns `.zero` for a focal length <= 0
    /// (same behaviour as the Android FieldOfViewCalculator).
    public static func fieldOfView(focalLengthMm: Double, widthMm: Double, heightMm: Double) -> FieldOfView {
        guard focalLengthMm > 0 else { return .zero }
        let diagonalMm = (widthMm * widthMm + heightMm * heightMm).squareRoot()
        return FieldOfView(horizontal: angle(dimensionMm: widthMm, focalLengthMm: focalLengthMm),
                           vertical: angle(dimensionMm: heightMm, focalLengthMm: focalLengthMm),
                           diagonal: angle(dimensionMm: diagonalMm, focalLengthMm: focalLengthMm))
    }

    /// Width-based crop factor relative to Full Frame (36 mm), as used by the Android app.
    public static func cropFactor(sensorWidthMm: Double) -> Double {
        sensorWidthMm > 0 ? fullFrameWidthMm / sensorWidthMm : 0
    }

    /// Full-Frame-equivalent focal length for the same horizontal view.
    public static func equivalentFocalLength(_ focalLengthMm: Double, sensorWidthMm: Double) -> Double {
        focalLengthMm * cropFactor(sensorWidthMm: sensorWidthMm)
    }

    /// tan(half-angle) of a FOV in degrees. Frame lines scale with this, never with the angle itself.
    @inlinable public static func halfTan(_ fovDegrees: Double) -> Double { tan(radians(fovDegrees) / 2) }

    /// FOV (degrees) from tan(half-angle).
    @inlinable public static func fov(fromHalfTan t: Double) -> Double { 2 * degrees(atan(t)) }
}
