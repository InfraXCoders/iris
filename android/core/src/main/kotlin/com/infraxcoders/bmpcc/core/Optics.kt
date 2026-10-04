package com.infraxcoders.bmpcc.core

import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/** Field of view in degrees. */
data class FieldOfView(val horizontal: Double, val vertical: Double, val diagonal: Double) {
    companion object {
        val ZERO = FieldOfView(0.0, 0.0, 0.0)
    }
}

/** Rectilinear lens geometry. All angles in degrees, all lengths in millimetres. */
object Optics {
    /** Full Frame still-photo width, used for crop factor and "equivalent" focal length. */
    const val FULL_FRAME_WIDTH_MM = 36.0

    fun degrees(radians: Double): Double = radians * 180.0 / Math.PI
    fun radians(degrees: Double): Double = degrees * Math.PI / 180.0

    /** Angle covered by a sensor dimension: 2·atan(d / 2f). */
    fun angle(dimensionMm: Double, focalLengthMm: Double): Double {
        if (focalLengthMm <= 0 || dimensionMm <= 0) return 0.0
        return 2 * degrees(atan(dimensionMm / (2 * focalLengthMm)))
    }

    /** Horizontal, vertical and diagonal FOV of a sensor area. Zero for a focal length <= 0. */
    fun fieldOfView(focalLengthMm: Double, widthMm: Double, heightMm: Double): FieldOfView {
        if (focalLengthMm <= 0) return FieldOfView.ZERO
        val diagonalMm = sqrt(widthMm * widthMm + heightMm * heightMm)
        return FieldOfView(
            angle(widthMm, focalLengthMm),
            angle(heightMm, focalLengthMm),
            angle(diagonalMm, focalLengthMm),
        )
    }

    /** Width-based crop factor relative to Full Frame (36 mm). */
    fun cropFactor(sensorWidthMm: Double): Double = if (sensorWidthMm > 0) FULL_FRAME_WIDTH_MM / sensorWidthMm else 0.0

    /** Full-Frame-equivalent focal length for the same horizontal view. */
    fun equivalentFocalLength(focalLengthMm: Double, sensorWidthMm: Double): Double =
        focalLengthMm * cropFactor(sensorWidthMm)

    /** tan(half-angle) of a FOV in degrees. Frame lines scale with this, never with the angle itself. */
    fun halfTan(fovDegrees: Double): Double = tan(radians(fovDegrees) / 2)

    /** FOV (degrees) from tan(half-angle). */
    fun fovFromHalfTan(t: Double): Double = 2 * degrees(atan(t))
}
