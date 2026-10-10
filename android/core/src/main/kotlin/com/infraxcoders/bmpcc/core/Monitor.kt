package com.infraxcoders.bmpcc.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shot sizes offered as shortcuts in the viewfinder. */
enum class ShotSize(val label: String, val shotType: ShotType) {
    WIDE("Wide", ShotType.WS), MEDIUM("Medium", ShotType.MS), CLOSE("Close", ShotType.CU)
}

/** What focal lengths a lens choice offers in the viewfinder, and how shot sizes map onto them. */
object FocalOptions {
    private val standardFocals = listOf(
        8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 21.0, 24.0, 28.0, 32.0, 35.0, 40.0, 50.0, 65.0, 75.0, 85.0,
        100.0, 135.0, 150.0, 180.0, 200.0, 250.0, 300.0, 400.0, 500.0, 600.0,
    )

    /** The other focal lengths of the same prime series (e.g. Master Anamorphic 28…180), shortest first. */
    fun seriesMembers(lens: LensProfile, all: List<LensProfile> = Catalog.lenses): List<LensProfile> {
        val series = lens.series ?: return listOf(lens)
        if (lens.isZoom) return listOf(lens)
        return all.filter { it.manufacturer == lens.manufacturer && it.series == series && !it.isZoom }
            .distinctBy { it.focalLengthMin }
            .sortedBy { it.focalLengthMin }
            .ifEmpty { listOf(lens) }
    }

    /** Focal lengths to step through: a zoom's marked/standard stops, or the prime set's focal lengths. */
    fun focals(lens: LensProfile, all: List<LensProfile> = Catalog.lenses): List<Double> {
        if (lens.isZoom) {
            val marks = (lens.availableFocalLengths + standardFocals.filter { it > lens.focalLengthMin && it < lens.focalLengthMax } +
                listOf(lens.focalLengthMin, lens.focalLengthMax)).distinct().sorted()
            return marks
        }
        return seriesMembers(lens, all).map { it.focalLengthMin }
    }

    /** The lens to use for a focal length: the zoom itself, or the series member at that focal length. */
    fun lensFor(lens: LensProfile, focal: Double, all: List<LensProfile> = Catalog.lenses): LensProfile =
        if (lens.isZoom) lens else seriesMembers(lens, all).minByOrNull { abs(it.focalLengthMin - focal) } ?: lens

    /**
     * Focal length for a shot size from the available ones: wide = shortest, close = longest,
     * medium = the one nearest a slightly long "normal" lens for the sensor area (1.15 × its diagonal, times the
     * squeeze for anamorphic, which gives the same horizontal view as the spherical equivalent).
     */
    fun focalFor(size: ShotSize, focals: List<Double>, mode: SensorMode, squeeze: Double = 1.0): Double? {
        if (focals.isEmpty()) return null
        return when (size) {
            ShotSize.WIDE -> focals.first()
            ShotSize.CLOSE -> focals.last()
            ShotSize.MEDIUM -> {
                val normal = mode.diagonalMm * max(squeeze, 1.0) * 1.15
                focals.minByOrNull { abs(it - normal) }
            }
        }
    }

    /** Next/previous focal length from the current one. */
    fun step(focals: List<Double>, current: Double, by: Int): Double? {
        if (focals.isEmpty()) return null
        val idx = focals.indexOfFirst { abs(it - current) < 0.5 }.let { if (it >= 0) it else focals.indexOfFirst { f -> f > current }.coerceAtLeast(0) }
        return focals[(idx + by).coerceIn(0, focals.lastIndex)]
    }
}

/** Relative exposure of the cinema settings, to preview brightness on the phone (0 EV = ISO 400, 180°, T2.8, no ND). */
object Exposure {
    private fun log2(x: Double) = ln(x) / ln(2.0)

    fun iso(text: String): Double? = text.filter { it.isDigit() }.toDoubleOrNull()?.takeIf { it > 0 }
    fun shutterAngle(text: String): Double? = text.replace("°", "").trim().toDoubleOrNull()?.takeIf { it > 0 }
    /** "ND 0.6 (2 stops)" -> 2, "None" -> 0. */
    fun ndStops(text: String): Double {
        Regex("\\((\\d+(?:\\.\\d+)?) stop").find(text)?.let { return it.groupValues[1].toDouble() }
        Regex("(\\d+(?:\\.\\d+)?)").find(text)?.let { return (it.groupValues[1].toDouble() / 0.3) }
        return 0.0
    }
    /** "f/2.8", "T2.0", "2.8" -> 2.8. */
    fun stop(text: String): Double? = Regex("(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }

    /** Exposure difference in stops versus ISO 400 / 180° / T2.8 / no ND. */
    fun ev(iso: String, shutter: String, nd: String, aperture: String): Double {
        var ev = 0.0
        iso(iso)?.let { ev += log2(it / 400.0) }
        shutterAngle(shutter)?.let { ev += log2(it / 180.0) }
        ev -= ndStops(nd)
        stop(aperture)?.let { ev -= 2 * log2(it / 2.8) }
        return ev
    }

    /** Phone exposure-compensation index for [ev], given the phone's step (EV per index) and range. */
    fun compensationIndex(ev: Double, stepEv: Double, minIndex: Int, maxIndex: Int): Int {
        if (stepEv <= 0) return 0
        return (ev / stepEv).roundToInt().coerceIn(minIndex, maxIndex)
    }
}

/** Geometry for the full-screen monitor: the camera picture fills the screen, the cinema frame sits in [area]. */
object Monitor {
    /** Where content of [contentAspect] appears when scaled to fill a container (cropping the overflow). */
    fun aspectFill(contentAspect: Double, containerWidth: Double, containerHeight: Double): ScreenRect {
        if (contentAspect <= 0 || containerWidth <= 0 || containerHeight <= 0) return ScreenRect(0.0, 0.0, 0.0, 0.0)
        return if (containerWidth / containerHeight > contentAspect) {
            val h = containerWidth / contentAspect
            ScreenRect(0.0, (containerHeight - h) / 2, containerWidth, h)
        } else {
            val w = containerHeight * contentAspect
            ScreenRect((containerWidth - w) / 2, 0.0, w, containerHeight)
        }
    }

    /**
     * The phone's view across a centred screen area, when the whole camera picture ([video], seeing [view]) is
     * drawn larger than the area. tan(half angle) scales with on-screen distance from the centre.
     */
    fun viewAcross(view: PreviewView, video: ScreenRect, area: ScreenRect): PreviewView = PreviewView(
        Optics.fovFromHalfTan(Optics.halfTan(view.horizontalFov) * area.width / video.width),
        Optics.fovFromHalfTan(Optics.halfTan(view.verticalFov) * area.height / video.height),
    )

    data class Layout(val zoom: Double, val lines: FrameLines, val frame: ScreenRect)

    /**
     * Zoom the phone so the cinema frame fills [area] (with [margin]: 1.0 = edge to edge), then where to draw it.
     * [view] is the phone's view of the whole [video] at zoom 1. With [fixedZoom] the zoom is not changed.
     */
    fun layout(
        reference: ReferenceFrame, view: PreviewView, video: ScreenRect, area: ScreenRect,
        minZoom: Double, maxZoom: Double, margin: Double = 1.0, fixedZoom: Double? = null,
    ): Layout {
        val areaView = viewAcross(view, video, area)
        val zoom = fixedZoom ?: Viewfinder.bestZoom(reference, areaView, minZoom, maxZoom, margin)
        val lines = Viewfinder.frameLines(reference, areaView.zoomed(zoom))
        if (lines.fits) return Layout(zoom, lines, Viewfinder.frameRect(lines, area))
        // Wider than the phone can see: keep the frame's real shape (e.g. 2.39:1), as large as the area allows.
        val scale = 1 / max(lines.widthFraction, lines.heightFraction)
        val fw = area.width * lines.widthFraction * scale
        val fh = area.height * lines.heightFraction * scale
        return Layout(zoom, lines, ScreenRect(area.midX - fw / 2, area.midY - fh / 2, fw, fh))
    }

    /** A centred area inset by [sideInset] left/right and [barInset] top/bottom. */
    fun centredArea(width: Double, height: Double, sideInset: Double, barInset: Double): ScreenRect =
        ScreenRect(sideInset, barInset, max(width - 2 * sideInset, 1.0), max(height - 2 * barInset, 1.0))

    /** Frame position as fractions of the full camera picture (for saving with a reference photo). */
    fun frameInPicture(frame: ScreenRect, video: ScreenRect): ScreenRect = ScreenRect(
        ((frame.x - video.x) / video.width).coerceIn(0.0, 1.0),
        ((frame.y - video.y) / video.height).coerceIn(0.0, 1.0),
        min(frame.width / video.width, 1.0),
        min(frame.height / video.height, 1.0),
    )
}

/**
 * Checking the phone camera's real angle of view: point it at an object of known width at a measured distance and
 * mark the object's edges on screen. The phone's reported angle (from its lens focal length and sensor size) can
 * differ by a few percent; the measured one makes frame lines match reality.
 */
object Calibration {
    /**
     * Full angle of view (degrees) across the axis that was measured.
     * [objectWidthM] and [distanceM] in metres (distance from the phone to the object's plane);
     * [fractionOfPicture] = object width / full camera picture width along that axis (0..1).
     */
    fun fovFromMeasurement(objectWidthM: Double, distanceM: Double, fractionOfPicture: Double): Double? {
        if (objectWidthM <= 0 || distanceM <= 0 || fractionOfPicture <= 0 || fractionOfPicture > 1) return null
        val tanHalfObject = objectWidthM / (2 * distanceM)
        return Optics.fovFromHalfTan(tanHalfObject / fractionOfPicture)
    }

    /** Angle across the other side of a picture with aspect [longOverShort] (e.g. short side → long side). */
    fun otherSide(fovDegrees: Double, ratio: Double): Double = Optics.fovFromHalfTan(Optics.halfTan(fovDegrees) * ratio)

    /** Signed difference in percent of the measured angle's tan(half) from the reported one (frame-line size error). */
    fun errorPercent(reportedFov: Double, measuredFov: Double): Double =
        (Optics.halfTan(reportedFov) / Optics.halfTan(measuredFov) - 1) * 100
}
