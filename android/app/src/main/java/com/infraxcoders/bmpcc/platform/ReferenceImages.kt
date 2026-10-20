package com.infraxcoders.bmpcc.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.ReferenceCrop
import com.infraxcoders.bmpcc.core.ShotReference
import com.infraxcoders.bmpcc.data.RecceStore
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Reference photos cut to the cinema frame, for the shot's current settings ([ReferenceCrop]): what the selected
 * camera, lens and aspect ratio would show. The part of a frame the phone couldn't see is dark padding, never picture.
 * Used by the shot screen, the compare screen and the PDF, so all three show the same crop.
 */
object ReferenceImages {
    /** The colour of the part of the frame the phone didn't see. */
    const val PADDING: Int = 0xFF0C0E14.toInt()

    class Cropped(val bitmap: Bitmap, val crop: ReferenceCrop.Result)

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * The photo of [ref] cut to [shot]'s frame, at most [maxSide] pixels on its long side, or null when the photo isn't
     * on this phone. Decodes the photo: call off the main thread.
     */
    fun cropped(ref: ShotReference, shot: RecceShot, maxSide: Int = 1600): Cropped? {
        val crop = ReferenceCrop.forShot(ref, shot)
        val file = File(RecceStore.referencesDir, ref.fileName)
        if (!file.exists()) return null
        val f = crop.frame
        val key = "${file.path}@$maxSide:${f.x},${f.y},${f.width},${f.height}"
        cache.get(key)?.let { return Cropped(it, crop) }
        // Decode enough of the photo for a sharp crop (a 135mm frame is a small part of it), within memory limits.
        val decodeSide = min(3200, max(maxSide, (maxSide / max(min(f.width, f.height), 0.05)).toInt()))
        val photo = Images.load(file, decodeSide) ?: return null
        val bitmap = render(photo, crop.frame, maxSide)
        cache.put(key, bitmap)
        return Cropped(bitmap, crop)
    }

    /**
     * The photo's upright size in pixels (its orientation tag applied), read without decoding it; null when it isn't
     * on this phone.
     */
    fun photoSize(ref: ShotReference): Pair<Int, Int>? {
        val file = File(RecceStore.referencesDir, ref.fileName)
        if (!file.exists()) return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val rotation = runCatching { androidx.exifinterface.media.ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        return if (rotation == 90 || rotation == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    /** Cuts [frame] (fractions of [photo], may extend past it) out of [photo], dark where it extends past. */
    fun render(photo: Bitmap, frame: com.infraxcoders.bmpcc.core.ScreenRect, maxSide: Int): Bitmap {
        val plan = ReferenceCrop.pixelPlan(frame, photo.width, photo.height, maxSide)
        val out = Bitmap.createBitmap(plan.outWidth, plan.outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(PADDING)
        val s = plan.src; val d = plan.dst
        if (s != null && d != null) {
            canvas.drawBitmap(photo, Rect(s.left, s.top, s.right, s.bottom), Rect(d.left, d.top, d.right, d.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** One line saying where the crop comes from (shown under previews and in the PDF), or null when nothing to say. */
    fun basisText(ref: ShotReference, shot: RecceShot, crop: ReferenceCrop.Result): String? = when (crop.basis) {
        ReferenceCrop.Basis.NO_FRAME -> "No frame was saved with this photo: whole photo shown"
        ReferenceCrop.Basis.RECALCULATED ->
            "Frame recalculated for ${shot.focalLength} · ${shot.aspectRatio} (photo taken at ${ref.focalLength} · ${ref.aspectRatio})"
        ReferenceCrop.Basis.CAPTURED ->
            if (ref.focalLength == null && (ref.frameWidth ?: 0.0) > 0) "Frame as captured (this older photo didn't save its lens settings)" else null
    }
}
