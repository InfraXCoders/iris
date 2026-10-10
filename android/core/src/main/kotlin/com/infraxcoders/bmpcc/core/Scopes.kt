package com.infraxcoders.bmpcc.core

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Exposure and focus tools (Module D): false colour, zebras, focus peaking settings and the scopes
 * (waveform, RGB parade, histogram), computed from a small sample of the phone's camera picture.
 *
 * Levels are display (Rec.709-style, gamma-encoded) code values, 0–100 %, with luma = Rec.709 weights.
 * They describe the phone's picture (exposed to follow the cinema camera's settings), not a BRAW recording.
 */

/** What the live tools show. Saved app-wide. */
enum class ScopeKind(val label: String, val code: String) {
    NONE("No scope", "none"),
    WAVEFORM("Waveform", "wave"),
    PARADE("RGB parade", "parade"),
    HISTOGRAM("Histogram", "hist");

    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } ?: NONE }
}

enum class PeakingColour(val label: String, val argb: Int) {
    RED("Red", 0xFFFF2A2A.toInt()), GREEN("Green", 0xFF35FF4A.toInt()), BLUE("Blue", 0xFF3D8BFF.toInt()),
    YELLOW("Yellow", 0xFFFFE600.toInt()), WHITE("White", 0xFFFFFFFF.toInt());

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: RED }
}

/** Focus peaking sensitivity: the edge contrast (luma difference across ~2 px) that gets coloured. */
enum class PeakingLevel(val label: String, val threshold: Float) {
    LOW("Low", 0.30f), MEDIUM("Medium", 0.20f), HIGH("High", 0.12f);

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: MEDIUM }
}

object Zebra {
    /** Zebra thresholds offered, % of the video level (Blackmagic cameras offer 75–100 %). */
    val levels = listOf(70, 75, 80, 85, 90, 95, 100)
    const val DEFAULT = 95
    /** True when a pixel with this luma (0–1) gets stripes. 100 % means "at the top of the range" (≥ 99 %). */
    fun striped(luma: Double, levelPercent: Int): Boolean = luma >= threshold(levelPercent)
    fun threshold(levelPercent: Int): Double = if (levelPercent >= 100) 0.99 else levelPercent / 100.0
}

/**
 * False colour with ARRI's published exposure bands (ALEXA manual, "False Color Exposure Check"):
 * purple 0–2.5 % black clipping, blue 2.5–4 % just above black, green 38–42 % 18 % grey,
 * pink 52–56 % one stop over grey (skin), yellow 97–99 % just below white clipping, red 99–100 % white clipping.
 * Everything else is shown in grey. Blackmagic doesn't publish its own band values.
 */
object FalseColour {
    data class Band(val low: Double, val high: Double, val argb: Int, val name: String, val meaning: String) {
        val label: String get() = "${pct(low)}–${pct(minOf(high, 1.0))}%"
        private fun pct(v: Double) = (v * 100).let { if (it == Math.floor(it)) "${it.toInt()}" else "$it" }
    }

    const val SOURCE = "ARRI ALEXA manual, False Color Exposure Check"

    val bands = listOf(
        Band(0.0, 0.025, 0xFF9B30FF.toInt(), "Purple", "Black clipping"),
        Band(0.025, 0.04, 0xFF2E6BFF.toInt(), "Blue", "Just above black"),
        Band(0.38, 0.42, 0xFF2EC83C.toInt(), "Green", "18% grey"),
        Band(0.52, 0.56, 0xFFFF6EC7.toInt(), "Pink", "Grey +1 stop (skin)"),
        Band(0.97, 0.99, 0xFFFFE600.toInt(), "Yellow", "Just below clipping"),
        Band(0.99, 1.0001, 0xFFFF2020.toInt(), "Red", "White clipping"),
    )

    fun bandFor(luma: Double): Band? = bands.firstOrNull { luma >= it.low && luma < it.high }

    /** ARGB for a pixel: the band colour, or the pixel's luma as grey. */
    fun colour(luma: Double): Int {
        bandFor(luma)?.let { return it.argb }
        val g = (luma.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
}

/** A small sample of the picture, upright as on screen: ARGB pixels, row by row. */
class ScopeFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }

    companion object {
        /**
         * Samples an RGBA_8888 camera image (as delivered by the camera, before rotation) into an upright
         * [outW]×[outH] frame. [rotationDegrees] is how far the image must turn clockwise to be upright.
         * The crop (u0, v0)–(u1, v1) is in upright, normalised coordinates (0–1), e.g. the cinema frame.
         */
        fun fromRgba(
            buffer: ByteBuffer, rowStride: Int, pixelStride: Int, srcW: Int, srcH: Int, rotationDegrees: Int,
            outW: Int, outH: Int,
            u0: Double = 0.0, v0: Double = 0.0, u1: Double = 1.0, v1: Double = 1.0,
        ): ScopeFrame {
            val out = IntArray(outW * outH)
            val base = buffer.position()
            for (j in 0 until outH) {
                val v = v0 + (v1 - v0) * (j + 0.5) / outH
                for (i in 0 until outW) {
                    val u = u0 + (u1 - u0) * (i + 0.5) / outW
                    val (sx, sy) = sourcePoint(u, v, rotationDegrees, srcW, srcH)
                    val o = base + sy * rowStride + sx * pixelStride
                    val r = buffer.get(o).toInt() and 0xFF
                    val g = buffer.get(o + 1).toInt() and 0xFF
                    val b = buffer.get(o + 2).toInt() and 0xFF
                    out[j * outW + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return ScopeFrame(outW, outH, out)
        }

        /** Pixel in the unrotated source for an upright normalised point. */
        fun sourcePoint(u: Double, v: Double, rotationDegrees: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
            val (nx, ny) = when (((rotationDegrees % 360) + 360) % 360) {
                90 -> v to 1 - u
                180 -> 1 - u to 1 - v
                270 -> 1 - v to u
                else -> u to v
            }
            return (nx * srcW).toInt().coerceIn(0, srcW - 1) to (ny * srcH).toInt().coerceIn(0, srcH - 1)
        }
    }
}

object Scopes {
    /**
     * The part of the camera picture the scopes measure, as normalised (u0, v0, u1, v1): the cinema [frame] on
     * screen, relative to where the whole camera picture is drawn ([video], which may spill off screen).
     */
    fun crop(frame: ScreenRect?, video: ScreenRect): FloatArray {
        if (frame == null || video.width <= 0 || video.height <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
        fun u(x: Double) = ((x - video.x) / video.width).coerceIn(0.0, 1.0).toFloat()
        fun v(y: Double) = ((y - video.y) / video.height).coerceIn(0.0, 1.0).toFloat()
        val r = floatArrayOf(u(frame.x), v(frame.y), u(frame.x + frame.width), v(frame.y + frame.height))
        return if (r[2] - r[0] < 0.01f || r[3] - r[1] < 0.01f) floatArrayOf(0f, 0f, 1f, 1f) else r
    }

    fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }

    class Histogram(val bins: Int, val r: IntArray, val g: IntArray, val b: IntArray, val y: IntArray, val total: Int) {
        /** Share of pixels with any channel at or above 99 % (clipping). */
        var clipped = 0.0; internal set
        /** Share of pixels with luma under 2.5 % (crushed blacks). */
        var crushed = 0.0; internal set
        val peak: Int get() = maxOf(r.max(), g.max(), b.max(), y.max()).coerceAtLeast(1)
    }

    fun histogram(f: ScopeFrame, bins: Int = 128): Histogram {
        val r = IntArray(bins); val g = IntArray(bins); val b = IntArray(bins); val y = IntArray(bins)
        var clip = 0; var crush = 0
        fun bin(v: Int) = (v * bins / 256).coerceIn(0, bins - 1)
        for (p in f.pixels) {
            val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
            r[bin(rr)]++; g[bin(gg)]++; b[bin(bb)]++
            val l = luma(p)
            y[(l * bins).toInt().coerceIn(0, bins - 1)]++
            if (rr >= CLIP || gg >= CLIP || bb >= CLIP) clip++
            if (l < 0.025) crush++
        }
        val n = f.pixels.size.coerceAtLeast(1)
        return Histogram(bins, r, g, b, y, f.pixels.size).apply { clipped = clip.toDouble() / n; crushed = crush.toDouble() / n }
    }

    /** 99 % of 255. */
    private const val CLIP = 253

    /**
     * Waveform: for each of [columns] slices of the picture (left to right), how many pixels sit at each of
     * [levels] levels (0 % at index 0). Luma, or R, G and B side by side for a parade.
     */
    class Waveform(val columns: Int, val levels: Int, val parade: Boolean, val counts: Array<IntArray>) {
        /** Counts for channel [c] (0 = luma, or R/G/B for a parade): index = column * levels + level. */
        fun channel(c: Int) = counts[c]
        val peak: Int get() = counts.maxOf { it.max() }.coerceAtLeast(1)
    }

    fun waveform(f: ScopeFrame, columns: Int = 128, levels: Int = 100, parade: Boolean = false): Waveform {
        val counts = Array(if (parade) 3 else 1) { IntArray(columns * levels) }
        fun lv(v: Double) = (v * (levels - 1) + 0.5).toInt().coerceIn(0, levels - 1)
        for (j in 0 until f.height) for (i in 0 until f.width) {
            val p = f.pixels[j * f.width + i]
            val col = (i * columns / f.width).coerceIn(0, columns - 1)
            if (parade) {
                counts[0][col * levels + lv(((p shr 16) and 0xFF) / 255.0)]++
                counts[1][col * levels + lv(((p shr 8) and 0xFF) / 255.0)]++
                counts[2][col * levels + lv((p and 0xFF) / 255.0)]++
            } else counts[0][col * levels + lv(luma(p))]++
        }
        return Waveform(columns, levels, parade, counts)
    }

    /**
     * The waveform as an image ([width] = columns, or 3 × columns for a parade; height = levels, 100 % at the top),
     * ARGB with a transparent background, brightness by pixel count (log-ish so faint traces stay visible).
     */
    fun waveformImage(w: Waveform): IntArray {
        val chans = w.counts.size
        val width = w.columns * chans
        val img = IntArray(width * w.levels)
        val peak = w.peak.toDouble()
        val tint = if (chans == 3) intArrayOf(0xFF4040, 0x40FF40, 0x4080FF) else intArrayOf(0xE8F0E0)
        for (c in 0 until chans) {
            val counts = w.counts[c]
            for (col in 0 until w.columns) for (l in 0 until w.levels) {
                val n = counts[col * w.levels + l]
                if (n == 0) continue
                val a = (60 + 195 * kotlin.math.sqrt(n / peak)).toInt().coerceIn(0, 255)
                val x = c * w.columns + col
                val y = w.levels - 1 - l
                img[y * width + x] = (a shl 24) or tint[c]
            }
        }
        return img
    }
}

/**
 * The live picture tools on the CPU, for phones that can't run them on the GPU (Android 12 and older): the LUT
 * (right of [splitFraction]), then false colour and zebras on the graded picture, then peaking from the ungraded
 * picture's edges. Works on a small upright frame (~480 px), so it's coarser than the GPU version.
 */
object PictureProcessor {
    fun process(
        f: ScopeFrame, lut: Lut3D?, input: LutInput, splitFraction: Double, falseColour: Boolean, zebraLevel: Int?,
        peaking: PeakingLevel?, peakingColour: PeakingColour, stripePx: Int = 8,
    ): IntArray {
        val w = f.width; val h = f.height
        val src = f.pixels
        val out = src.copyOf()
        if (lut != null) {
            val from = (splitFraction.coerceIn(0.0, 1.0) * w).toInt()
            if (from <= 0) lut.applyToPixels(out, input)
            else if (from < w) {
                val row = IntArray(w - from)
                for (y in 0 until h) {
                    System.arraycopy(out, y * w + from, row, 0, row.size)
                    lut.applyToPixels(row, input)
                    System.arraycopy(row, 0, out, y * w + from, row.size)
                }
            }
        }
        val zebra = zebraLevel?.let { Zebra.threshold(it) }
        if (falseColour || zebra != null) for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val l = Scopes.luma(out[i])
            if (falseColour) out[i] = FalseColour.colour(l)
            if (zebra != null && l >= zebra) {
                val p = out[i]
                out[i] = if ((x + y) % stripePx < stripePx / 2) 0xFFFFFFFF.toInt() else
                    (0xFF shl 24) or ((((p shr 16) and 0xFF) * 35 / 100) shl 16) or ((((p shr 8) and 0xFF) * 35 / 100) shl 8) or ((p and 0xFF) * 35 / 100)
            }
        }
        if (peaking != null) for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = Scopes.luma(src[y * w + x + 1]) - Scopes.luma(src[y * w + x - 1])
            val gy = Scopes.luma(src[(y + 1) * w + x]) - Scopes.luma(src[(y - 1) * w + x])
            if (kotlin.math.sqrt(gx * gx + gy * gy) > peaking.threshold) out[y * w + x] = peakingColour.argb
        }
        return out
    }
}

/**
 * Exposure match: sets the phone's own ISO and exposure time so its picture gets the same exposure as the cinema
 * camera (ISO, shutter, frame rate, ND and T-stop), instead of the phone's auto-exposure. Exposure ∝ t·ISO / (N²·2^ND).
 * Approximate: phone and camera ISO ratings and tone curves differ; the T-stop is treated as an f-number.
 */
object ExposureMatch {
    data class Phone(val iso: Int, val exposureNs: Long, /** > 0: the phone picture is this many stops darker than it should be; < 0 brighter. */ val stopsOff: Double)

    /** Exposure time in seconds from "180°" at [fps], or "1/50". */
    fun exposureSeconds(shutter: String, fps: String): Double? {
        val t = shutter.trim()
        if (t.contains('/')) {
            val parts = t.split('/')
            val a = parts.getOrNull(0)?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            val b = parts.getOrNull(1)?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            return if (a != null && b != null && a > 0 && b > 0) a / b else null
        }
        val angle = Exposure.shutterAngle(t) ?: return null
        val f = fps.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return angle / 360.0 / f
    }

    /** ISO × seconds the phone needs at its f-number [phoneAperture], or null when the shot's settings are incomplete. */
    fun target(iso: String, shutter: String, fps: String, nd: String, aperture: String, phoneAperture: Double): Double? {
        val s = Exposure.iso(iso) ?: return null
        val t = exposureSeconds(shutter, fps) ?: return null
        val n = Exposure.stop(aperture) ?: return null
        return t * s * phoneAperture * phoneAperture / (n * n * Math.pow(2.0, Exposure.ndStops(nd)))
    }

    /**
     * Phone ISO and exposure time for [isoTimesSeconds]. Uses the cinema camera's own exposure time
     * ([preferredSeconds], so motion blur looks alike) when it fits, at most [maxPreviewNs] (smooth preview);
     * otherwise the longest allowed time, so the ISO (noise) stays as low as possible.
     */
    fun phone(
        isoTimesSeconds: Double, isoMin: Int, isoMax: Int, minNs: Long, maxNs: Long,
        preferredSeconds: Double? = null, maxPreviewNs: Long = 33_333_333,
    ): Phone {
        val tMax = minOf(maxNs, maxPreviewNs).coerceAtLeast(minNs) / 1e9
        val tMin = minNs / 1e9
        val t0 = (preferredSeconds ?: tMax).coerceIn(tMin, tMax)
        val iso = (isoTimesSeconds / t0).coerceIn(isoMin.toDouble(), isoMax.toDouble()).roundToInt()
        val t = (isoTimesSeconds / iso).coerceIn(tMin, tMax)
        val got = iso * t
        return Phone(iso, (t * 1e9).toLong(), kotlin.math.ln(isoTimesSeconds / got) / kotlin.math.ln(2.0))
    }
}
