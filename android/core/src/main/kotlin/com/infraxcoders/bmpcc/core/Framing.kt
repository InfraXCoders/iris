package com.infraxcoders.bmpcc.core

import kotlin.math.max
import kotlin.math.min

/**
 * What the cinema camera will actually record, for the director's viewfinder.
 *
 * Fixes vs the original Android UniversalFramingEngine:
 * - Anamorphic: the horizontal view is 2·atan(squeeze·W / 2f). The old code multiplied the *angle* by the squeeze,
 *   e.g. a 2x lens at 40 mm on an ALEXA 35 gave 77.1° instead of the correct 70.0°.
 * - Diagonal: real diagonal angle, not sqrt(h² + v²) of the angles.
 * - Delivery aspect ratio (2.39, 1.85…) is applied as a mask inside the desqueezed sensor area.
 */
data class ReferenceFrame(
    /** Desqueezed sensor area in mm at the effective focal length. */
    val captureWidthMm: Double,
    val captureHeightMm: Double,
    /** Delivered (masked) area. */
    val deliveredWidthMm: Double,
    val deliveredHeightMm: Double,
    val effectiveFocalLengthMm: Double,
    val captureFov: FieldOfView,
    /** FOV of the delivered frame: what the frame lines show. */
    val deliveredFov: FieldOfView,
    val deliveredAspect: Double,
    val cropFactor: Double,
) {
    val tanHalfH: Double get() = deliveredWidthMm / (2 * effectiveFocalLengthMm)
    val tanHalfV: Double get() = deliveredHeightMm / (2 * effectiveFocalLengthMm)
}

object Framing {
    /**
     * @param focalLengthMultiplier e.g. 0.71 for a 0.71x speed booster.
     * @param aspectRatio delivery aspect (width / height); null = the full desqueezed sensor.
     */
    fun reference(
        sensorWidthMm: Double, sensorHeightMm: Double, focalLengthMm: Double,
        anamorphicSqueeze: Double = 1.0, focalLengthMultiplier: Double = 1.0, aspectRatio: Double? = null,
    ): ReferenceFrame? {
        val f = focalLengthMm * focalLengthMultiplier
        val squeeze = if (anamorphicSqueeze > 0) anamorphicSqueeze else 1.0
        if (f <= 0 || sensorWidthMm <= 0 || sensorHeightMm <= 0) return null
        val capW = sensorWidthMm * squeeze
        val capH = sensorHeightMm
        var outW = capW
        var outH = capH
        if (aspectRatio != null && aspectRatio > 0) {
            if (aspectRatio > capW / capH) outH = capW / aspectRatio else outW = capH * aspectRatio
        }
        return ReferenceFrame(
            captureWidthMm = capW, captureHeightMm = capH,
            deliveredWidthMm = outW, deliveredHeightMm = outH,
            effectiveFocalLengthMm = f,
            captureFov = Optics.fieldOfView(f, capW, capH),
            deliveredFov = Optics.fieldOfView(f, outW, outH),
            deliveredAspect = outW / outH,
            cropFactor = Optics.cropFactor(sensorWidthMm),
        )
    }

    fun reference(
        camera: CameraProfile, lens: LensProfile, focalLengthMm: Double,
        aspectRatio: Double? = null, focalLengthMultiplier: Double = 1.0,
    ): ReferenceFrame? = reference(
        camera.sensorWidthMm, camera.sensorHeightMm, focalLengthMm,
        lens.anamorphicSqueeze, focalLengthMultiplier, aspectRatio,
    )
}

/** The phone camera view the preview shows, as horizontal/vertical FOV along the preview's on-screen axes. */
data class PreviewView(val horizontalFov: Double, val verticalFov: Double) {
    /** The view after zoom [factor] (1 = unzoomed). */
    fun zoomed(factor: Double): PreviewView {
        val z = max(factor, 0.0001)
        return PreviewView(
            Optics.fovFromHalfTan(Optics.halfTan(horizontalFov) / z),
            Optics.fovFromHalfTan(Optics.halfTan(verticalFov) / z),
        )
    }

    companion object {
        /** From a camera stream: FOV across its long side and its aspect (long / short). */
        fun fromFormat(longSideFov: Double, longOverShort: Double, portrait: Boolean): PreviewView {
            val tanLong = Optics.halfTan(longSideFov)
            val shortFov = Optics.fovFromHalfTan(tanLong / max(longOverShort, 0.0001))
            return if (portrait) PreviewView(shortFov, longSideFov) else PreviewView(longSideFov, shortFov)
        }
    }
}

/** Where to draw the cinema frame on the preview, as fractions of the preview's width and height. */
data class FrameLines(val widthFraction: Double, val heightFraction: Double) {
    /** true when the whole cinema frame is visible on the preview. */
    val fits: Boolean get() = widthFraction <= 1.000001 && heightFraction <= 1.000001
    val drawableWidth: Double get() = min(widthFraction, 1.0)
    val drawableHeight: Double get() = min(heightFraction, 1.0)
}

/** A rectangle in screen pixels. */
data class ScreenRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
}

object Viewfinder {
    /** Frame lines for a cinema frame on a preview, from tan(half-angle) ratios (exact for rectilinear lenses). */
    fun frameLines(reference: ReferenceFrame, preview: PreviewView): FrameLines {
        val ph = Optics.halfTan(preview.horizontalFov)
        val pv = Optics.halfTan(preview.verticalFov)
        if (ph <= 0 || pv <= 0) return FrameLines(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        return FrameLines(reference.tanHalfH / ph, reference.tanHalfV / pv)
    }

    /** Zoom so the cinema frame fills the preview with [margin] breathing room, within minZoom..maxZoom. */
    fun bestZoom(
        reference: ReferenceFrame, base: PreviewView,
        minZoom: Double = 1.0, maxZoom: Double = 10.0, margin: Double = 1.1,
    ): Double {
        val ph = Optics.halfTan(base.horizontalFov)
        val pv = Optics.halfTan(base.verticalFov)
        if (reference.tanHalfH <= 0 || reference.tanHalfV <= 0 || ph <= 0 || pv <= 0) return minZoom
        val z = min(ph / (reference.tanHalfH * margin), pv / (reference.tanHalfV * margin))
        return min(max(z, minZoom), max(minZoom, maxZoom))
    }

    /** Where content of [contentAspect] appears inside a container when scaled to fit without cropping. */
    fun aspectFit(contentAspect: Double, containerWidth: Double, containerHeight: Double): ScreenRect {
        if (contentAspect <= 0 || containerWidth <= 0 || containerHeight <= 0) return ScreenRect(0.0, 0.0, 0.0, 0.0)
        return if (containerWidth / containerHeight > contentAspect) {
            val w = containerHeight * contentAspect
            ScreenRect((containerWidth - w) / 2, 0.0, w, containerHeight)
        } else {
            val h = containerWidth / contentAspect
            ScreenRect(0.0, (containerHeight - h) / 2, containerWidth, h)
        }
    }

    /** The cinema frame rectangle, centred in the on-screen video rectangle. */
    fun frameRect(lines: FrameLines, video: ScreenRect): ScreenRect {
        val w = video.width * lines.drawableWidth
        val h = video.height * lines.drawableHeight
        return ScreenRect(video.midX - w / 2, video.midY - h / 2, w, h)
    }

    fun safeArea(frame: ScreenRect, fraction: Double): ScreenRect {
        val w = frame.width * fraction
        val h = frame.height * fraction
        return ScreenRect(frame.midX - w / 2, frame.midY - h / 2, w, h)
    }

    /** A tap inside the frame as normalised (0..1) coordinates, or null if outside. */
    fun normalisedPoint(x: Double, y: Double, frame: ScreenRect): Pair<Double, Double>? {
        if (frame.width <= 0 || frame.height <= 0) return null
        val nx = (x - frame.x) / frame.width
        val ny = (y - frame.y) / frame.height
        if (nx !in 0.0..1.0 || ny !in 0.0..1.0) return null
        return nx to ny
    }
}
