package com.infraxcoders.bmpcc.core

import kotlin.math.abs

/**
 * Choices for the "New recce" sheet: a few cameras as one-tap chips, a lens kind (prime set, zoom, anamorphic)
 * and a frame. A kind picks a sensible lens: the last one used of that kind, or a maker-neutral set
 * (the view of a spherical lens depends only on its focal length and the sensor, so a "Prime set" works for any brand).
 */
object QuickRecce {
    enum class LensKind(val label: String) {
        PRIME("Prime set"), ZOOM("Zoom"), ANAMORPHIC("Anamorphic");

        companion object {
            fun of(lens: LensProfile): LensKind = when {
                lens.isAnamorphic -> ANAMORPHIC
                lens.isZoom -> ZOOM
                else -> PRIME
            }
        }
    }

    const val GENERIC = "Generic"
    const val GENERIC_ZOOM_ID = "generic_zoom_14_135"
    val primeFocals = listOf(14.0, 18.0, 24.0, 35.0, 50.0, 85.0, 135.0)

    /** Maker-neutral lenses: a spherical prime set and a zoom. No T-stop or image circle (coverage unknown). */
    val genericLenses: List<LensProfile> = primeFocals.map { f ->
        val mm = ShotPresets.focalText(f)
        LensProfile(
            id = "generic_prime_${f.toInt()}", manufacturer = GENERIC, model = "Prime set $mm", mount = Mount.PL,
            lensType = LensType.PRIME, focalLengthMin = f, focalLengthMax = f, availableFocalLengths = listOf(f),
            maximumAperture = 0.0, minimumFocusDistance = 0.0, imageCircleMm = 0.0, series = "Prime set",
            mountNames = listOf("Any"), notes = "Any spherical prime of this focal length frames the same.",
        )
    } + LensProfile(
        id = GENERIC_ZOOM_ID, manufacturer = GENERIC, model = "Zoom 14-135mm", mount = Mount.PL, lensType = LensType.ZOOM,
        focalLengthMin = 14.0, focalLengthMax = 135.0, availableFocalLengths = primeFocals,
        maximumAperture = 0.0, minimumFocusDistance = 0.0, imageCircleMm = 0.0,
        mountNames = listOf("Any"), notes = "Any spherical zoom: shows the view at each focal length from 14 to 135mm.",
    )

    /** One-tap camera chips (Blackmagic Pocket family) and their short labels. */
    val quickCameras: List<Pair<String, String>> = listOf(
        "bmpcc_4k" to "Pocket 4K", "bmpcc_6k" to "6K", "bmpcc_6k_g2" to "6K G2", "bmpcc_6k_pro" to "6K Pro",
    )

    fun chipLabel(camera: CameraProfile): String = quickCameras.firstOrNull { it.first == camera.id }?.second ?: shortName(camera)

    /** "Pocket Cinema Camera 6K Pro" → "Pocket 6K Pro"; other cameras keep their model name. */
    fun shortName(camera: CameraProfile): String = camera.model.replace("Pocket Cinema Camera", "Pocket").trim()

    val frames = listOf("16:9", "17:9", "2.39:1", "4:3")

    /** The lens to use for a kind: [last] when it is that kind, otherwise a default. */
    fun lensFor(kind: LensKind, last: LensProfile?, all: List<LensProfile> = Catalog.lenses): LensProfile {
        if (last != null && LensKind.of(last) == kind) return last
        return when (kind) {
            LensKind.PRIME -> all.firstOrNull { it.id == "generic_prime_35" } ?: all.first { !it.isZoom && !it.isAnamorphic }
            LensKind.ZOOM -> all.firstOrNull { it.id == GENERIC_ZOOM_ID } ?: all.first { it.isZoom }
            LensKind.ANAMORPHIC -> all.filter { it.isAnamorphic && it.series == "Orion" }.minByOrNull { abs(it.focalLengthMin - 40) }
                ?: all.first { it.isAnamorphic }
        }
    }

    /** The focal length nearest [wanted] that the lens (or its prime set) offers. */
    fun nearestFocal(lens: LensProfile, wanted: Double, all: List<LensProfile> = Catalog.lenses): Double =
        FocalOptions.focals(lens, all).minByOrNull { abs(it - wanted) } ?: lens.focalLengthMin

    /** A readable name for a lens choice: the set (series) rather than one focal length. */
    fun setName(lens: LensProfile): String = when {
        lens.manufacturer == GENERIC -> if (lens.isZoom) "Any zoom · 14–135mm" else "Any prime set · 14–135mm"
        lens.series != null && !lens.isZoom -> "${lens.manufacturer} ${lens.series}"
        else -> lens.displayName
    }
}
