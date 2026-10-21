package com.infraxcoders.bmpcc.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the cinema frame falls in a reference photo, for the shot's *current* settings, and how to cut it out.
 *
 * The photo stores where the frame was when it was taken ([ShotReference.frameX]…, fractions of the photo; a frame
 * wider than the phone could see extends past 0…1). For a rectilinear lens, the frame's size on the photo is
 * proportional to tan(half angle of view) on each axis, and the phone's own angle cancels out. So when the shot's
 * camera, recording mode, lens, focal length or aspect ratio change after the photo was taken, the new frame is the
 * saved one scaled, about its centre, by tan(new half angle) / tan(old half angle) — width and height separately.
 *
 * This needs the settings the photo was taken with (stored since they were added); older photos keep the frame as
 * captured. Nothing is invented: the part of a crop outside the photo is padding (drawn dark), never picture.
 */
object ReferenceCrop {
    enum class Basis {
        /** The frame saved with the photo (the shot's settings are the same, or the photo's are unknown). */
        CAPTURED,
        /** Recalculated from the photo's capture settings for the shot's current settings. */
        RECALCULATED,
        /** No frame was saved with the photo: the whole photo. */
        NO_FRAME,
    }

    data class Result(
        /** The crop as fractions of the photo (may extend past 0…1: that part is padding). */
        val frame: ScreenRect,
        val basis: Basis,
    ) {
        /** Part of the crop lies outside the photo (the phone couldn't see it). */
        val needsPadding: Boolean get() = frame.x < -EPS || frame.y < -EPS || frame.x + frame.width > 1 + EPS || frame.y + frame.height > 1 + EPS

        /** The part of the photo inside the crop (fractions of the photo), or null if they don't overlap. */
        val photoPart: ScreenRect? get() {
            val l = max(frame.x, 0.0); val t = max(frame.y, 0.0)
            val r = min(frame.x + frame.width, 1.0); val b = min(frame.y + frame.height, 1.0)
            return if (r - l > EPS && b - t > EPS) ScreenRect(l, t, r - l, b - t) else null
        }

        /** Width / height of the crop in pixels, for a photo of [photoWidth] × [photoHeight]. */
        fun aspect(photoWidth: Int, photoHeight: Int): Double = frame.width * photoWidth / (frame.height * photoHeight)
    }

    private const val EPS = 1e-6

    /** The crop for [reference] and the shot's current settings ([shot]). */
    fun forShot(reference: ShotReference, shot: RecceShot): Result {
        val saved = savedFrame(reference) ?: return Result(ScreenRect(0.0, 0.0, 1.0, 1.0), Basis.NO_FRAME)
        val then = captureReference(reference) ?: return Result(saved, Basis.CAPTURED)
        val now = shot.reference ?: return Result(saved, Basis.CAPTURED)
        return Result(rescale(saved, then, now), if (same(then, now)) Basis.CAPTURED else Basis.RECALCULATED)
    }

    /**
     * The photo to show for [shot] (shot screen thumbnails, viewfinder, PDF): the newest one taken at the shot's current
     * framing (same cinema angle of view and shape, so it is exactly framed and at full sharpness), otherwise the newest.
     * null when the shot has no photos.
     */
    fun bestReference(shot: RecceShot): ShotReference? {
        val newestFirst = shot.references.sortedByDescending { it.timestamp }
        val now = shot.reference ?: return newestFirst.firstOrNull()
        return newestFirst.firstOrNull { r -> savedFrame(r) != null && captureReference(r)?.let { same(it, now) } == true }
            ?: newestFirst.firstOrNull()
    }

    /** The saved frame, if it is complete and usable. */
    fun savedFrame(r: ShotReference): ScreenRect? {
        val x = r.frameX; val y = r.frameY; val w = r.frameWidth; val h = r.frameHeight
        if (x == null || y == null || w == null || h == null) return null
        if (listOf(x, y, w, h).any { it.isNaN() || it.isInfinite() } || w <= 0 || h <= 0) return null
        return ScreenRect(x, y, w, h)
    }

    /** The cinema frame the photo was taken with, from its stored settings; null when they're missing or unknown. */
    fun captureReference(r: ShotReference): ReferenceFrame? {
        val camera = r.cameraId?.let { Catalog.camera(it) } ?: return null
        val lens = r.lensId?.let { Catalog.lens(it) } ?: return null
        val focal = r.focalLength?.let { ShotPresets.focalValue(it) } ?: return null
        val aspect = r.aspectRatio?.let { ShotPresets.aspectValue(it) } ?: return null
        return Framing.reference(camera.using(r.sensorModeId), lens, focal, aspect)
    }

    /** [frame] (drawn for [from]) scaled about its centre to show [to]. */
    fun rescale(frame: ScreenRect, from: ReferenceFrame, to: ReferenceFrame): ScreenRect {
        if (from.tanHalfH <= 0 || from.tanHalfV <= 0 || to.tanHalfH <= 0 || to.tanHalfV <= 0) return frame
        val w = frame.width * to.tanHalfH / from.tanHalfH
        val h = frame.height * to.tanHalfV / from.tanHalfV
        return ScreenRect(frame.midX - w / 2, frame.midY - h / 2, w, h)
    }

    private fun same(a: ReferenceFrame, b: ReferenceFrame) =
        abs(a.tanHalfH / b.tanHalfH - 1) < 1e-9 && abs(a.tanHalfV / b.tanHalfV - 1) < 1e-9

    /**
     * Pixel plan for cutting the crop out of a photo of [photoWidth] × [photoHeight]: an output image of
     * [outWidth] × [outHeight] (the crop's shape, at most [maxLongSide] on its long side), filled dark, with the photo
     * pixels [src] drawn into [dst]. [dst] is the whole output unless the crop extends past the photo.
     */
    data class PixelPlan(val outWidth: Int, val outHeight: Int, val src: IntRect?, val dst: IntRect?)

    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun pixelPlan(crop: ScreenRect, photoWidth: Int, photoHeight: Int, maxLongSide: Int = 1600): PixelPlan {
        require(photoWidth > 0 && photoHeight > 0)
        // Crop size in photo pixels, then scaled down to the output size (never up).
        val cw = crop.width * photoWidth; val ch = crop.height * photoHeight
        val scale = min(1.0, maxLongSide / max(cw, ch))
        val outW = max(1, (cw * scale).roundToInt()); val outH = max(1, (ch * scale).roundToInt())
        // The photo pixels inside the crop.
        val l = max(crop.x, 0.0); val t = max(crop.y, 0.0)
        val r = min(crop.x + crop.width, 1.0); val b = min(crop.y + crop.height, 1.0)
        if (r - l <= EPS || b - t <= EPS) return PixelPlan(outW, outH, null, null)
        val src = IntRect(
            (l * photoWidth).roundToInt().coerceIn(0, photoWidth - 1), (t * photoHeight).roundToInt().coerceIn(0, photoHeight - 1),
            (r * photoWidth).roundToInt().coerceIn(1, photoWidth), (b * photoHeight).roundToInt().coerceIn(1, photoHeight),
        )
        // Where they go in the output (fractions of the crop → output pixels).
        fun ox(v: Double) = ((v - crop.x) / crop.width * outW).roundToInt().coerceIn(0, outW)
        fun oy(v: Double) = ((v - crop.y) / crop.height * outH).roundToInt().coerceIn(0, outH)
        val dst = IntRect(ox(l), oy(t), max(ox(r), ox(l) + 1), max(oy(b), oy(t) + 1))
        return PixelPlan(outW, outH, src, dst)
    }
}
