package com.infraxcoders.bmpcc.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * The phone camera's own angle of view, from what the camera reports (Camera2 characteristics), and choosing which of
 * the phone's back lenses to use for a cinema frame.
 */
object PhoneOptics {
    /** Where an angle of view came from. */
    /**
     * Where the phone's angle of view comes from. Only a measurement by the user ([MANUAL]) is a calibration; the
     * phone's own data gives approximate framing (a few percent off on many phones), an assumed angle more so.
     */
    enum class Source(val label: String, val short: String) {
        INTRINSIC("Approximate: from the phone's lens data (calibrate for accurate framing)", "Approximate"),
        SENSOR("Approximate: from the phone's sensor size and focal length (calibrate for accurate framing)", "Approximate"),
        MANUAL("Calibrated by you", "Calibrated"),
        ASSUMED("Approximate: assumed 70° (the phone reports nothing usable; calibrate)", "Approx. 70° assumed"),
        ;
        val calibrated: Boolean get() = this == MANUAL
    }

    const val DEFAULT_FOV = 70.0
    /** Plausible range for a phone camera's angle across its long side (main lens ~65–80°, ultra-wide ~100–125°). */
    val PLAUSIBLE = 30.0..140.0

    data class Reading(val longSideFov: Double, val source: Source)

    /**
     * Angle of view (degrees) across the long side of a stream with [streamAspect] (long / short, e.g. 4:3 or 16:9),
     * cropped centrally from the active pixel array.
     * - From the sensor: active-array size in mm (physical size scaled by active / pixel-array pixels), FOV = 2·atan(w / 2f).
     * - From LENS_INTRINSIC_CALIBRATION (fx in pixels of the pre-correction array, Android 9+), when present and
     *   within 15 % of the sensor figure (guards against odd units on some phones).
     * Falls back to [DEFAULT_FOV] when the data is missing or implausible.
     */
    fun longSideFov(
        physicalWidthMm: Double?, physicalHeightMm: Double?, pixelArrayW: Int?, pixelArrayH: Int?,
        activeW: Int?, activeH: Int?, focalMm: Double?, streamAspect: Double,
        intrinsicFx: Double? = null, intrinsicArrayW: Int? = null, intrinsicArrayH: Int? = null,
    ): Reading {
        val sensor = run {
            if (physicalWidthMm == null || physicalHeightMm == null || pixelArrayW == null || pixelArrayH == null ||
                activeW == null || activeH == null || focalMm == null || focalMm <= 0 || pixelArrayW <= 0 || pixelArrayH <= 0
            ) return@run null
            val w = physicalWidthMm * activeW / pixelArrayW
            val h = physicalHeightMm * activeH / pixelArrayH
            val long = streamLong(max(w, h), min(w, h), streamAspect)
            Optics.degrees(atan(long / (2 * focalMm))) * 2
        }?.takeIf { it in PLAUSIBLE }
        val intrinsic = run {
            val fx = intrinsicFx ?: return@run null
            val aw = intrinsicArrayW ?: activeW ?: return@run null
            val ah = intrinsicArrayH ?: activeH ?: return@run null
            if (fx <= 0 || aw <= 0 || ah <= 0) return@run null
            // fx is along the sensor's x axis, which is its long side on phones.
            val longPx = streamLong(max(aw, ah).toDouble(), min(aw, ah).toDouble(), streamAspect)
            Optics.degrees(atan(longPx / (2 * fx))) * 2
        }?.takeIf { it in PLAUSIBLE }
        return when {
            intrinsic != null && (sensor == null || abs(Optics.halfTan(intrinsic) / Optics.halfTan(sensor) - 1) <= 0.15) ->
                Reading(intrinsic, Source.INTRINSIC)
            sensor != null -> Reading(sensor, Source.SENSOR)
            else -> Reading(DEFAULT_FOV, Source.ASSUMED)
        }
    }

    /** The long side of a centred [aspect] crop of a [long] × [short] sensor (4:3 on a 4:3 sensor uses it all). */
    fun streamLong(long: Double, short: Double, aspect: Double): Double = min(long, short * aspect)

    /** One of the phone's back lenses as the app can use it. */
    data class Lens(
        /** A camera id the camera library can open (a logical camera or a standalone one). */
        val cameraId: String,
        /** A physical camera inside that logical camera (streamed directly), or null. */
        val physicalId: String?,
        /** Angle across the stream's long side at zoom 1. */
        val longSideFov: Double,
        val source: Source,
        /** Zoom range the lens allows (physical lenses streamed directly: 1–1, no zoom). */
        val minZoom: Double,
        val maxZoom: Double,
        val isDefault: Boolean,
    ) {
        val key: String get() = if (physicalId != null) "$cameraId/$physicalId" else cameraId
        /** Widest angle this lens reaches (zoom below 1 on logical cameras = their built-in ultra-wide). */
        val widestFov: Double get() = Optics.fovFromHalfTan(Optics.halfTan(longSideFov) / minZoom)
    }

    /**
     * Which lens to use: the default (main) lens when the frame fits, otherwise the narrowest wider lens that fits
     * (sharper than an even wider one), otherwise the widest. [fits] says whether the frame fits with a lens.
     */
    fun choose(lenses: List<Lens>, fits: (Lens) -> Boolean): Lens? {
        val main = lenses.firstOrNull { it.isDefault } ?: lenses.firstOrNull() ?: return null
        if (fits(main)) return main
        val wider = lenses.filter { it.widestFov > main.widestFov + 1.0 }.sortedBy { it.widestFov }
        return wider.firstOrNull(fits) ?: wider.lastOrNull() ?: main
    }

    /** Lenses worth offering: the default plus clearly wider ones (≥ 10 % wider), one per angle. */
    fun distinct(lenses: List<Lens>): List<Lens> {
        val out = mutableListOf<Lens>()
        for (l in lenses.sortedWith(compareByDescending<Lens> { it.isDefault }.thenBy { it.physicalId != null })) {
            if (out.none { abs(Optics.halfTan(it.widestFov) / Optics.halfTan(l.widestFov) - 1) < 0.1 }) out += l
        }
        return out
    }
}
