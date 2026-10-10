package com.infraxcoders.bmpcc.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A 3D colour lookup table (from a .cube file or built in). [data] holds size³ RGB triples, red changing fastest
 * (the .cube order). Values are 0..1 output; input is mapped from [domainMin]..[domainMax].
 */
class Lut3D(
    val name: String,
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init { require(size in 2..256 && data.size == size * size * size * 3) { "Bad LUT size" } }

    private fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    /** Trilinear lookup of one colour (0..1 in the LUT's domain), written into [out]. */
    fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        fun pos(v: Float, c: Int): Float {
            val t = (v - domainMin[c]) / (domainMax[c] - domainMin[c])
            return (t.coerceIn(0f, 1f)) * n
        }
        val x = pos(r, 0); val y = pos(g, 1); val z = pos(b, 2)
        val x0 = floor(x).toInt().coerceAtMost(n - 1).coerceAtLeast(0); val y0 = floor(y).toInt().coerceIn(0, n - 1); val z0 = floor(z).toInt().coerceIn(0, n - 1)
        val fx = x - x0; val fy = y - y0; val fz = z - z0
        for (c in 0..2) {
            fun v(dx: Int, dy: Int, dz: Int) = data[idx(x0 + dx, y0 + dy, z0 + dz) + c]
            val c00 = v(0, 0, 0) + (v(1, 0, 0) - v(0, 0, 0)) * fx
            val c10 = v(0, 1, 0) + (v(1, 1, 0) - v(0, 1, 0)) * fx
            val c01 = v(0, 0, 1) + (v(1, 0, 1) - v(0, 0, 1)) * fx
            val c11 = v(0, 1, 1) + (v(1, 1, 1) - v(0, 1, 1)) * fx
            val c0 = c00 + (c10 - c00) * fy
            val c1 = c01 + (c11 - c01) * fy
            out[c] = c0 + (c1 - c0) * fz
        }
    }

    /**
     * The LUT as a 2D image for a GPU shader: blue slices laid out in a grid of [tileColumns] columns, each slice
     * size × size with red across and green down. Returns ARGB pixels (8 bits per channel).
     */
    val tileColumns: Int get() = ceil(sqrt(size.toDouble())).toInt()
    val textureWidth: Int get() = tileColumns * size
    val textureHeight: Int get() = ceil(size.toDouble() / tileColumns).toInt() * size

    fun texturePixels(): IntArray {
        val w = textureWidth
        val px = IntArray(w * textureHeight)
        for (b in 0 until size) {
            val tx = (b % tileColumns) * size; val ty = (b / tileColumns) * size
            for (g in 0 until size) for (r in 0 until size) {
                val i = idx(r, g, b)
                fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
                px[(ty + g) * w + tx + r] = (0xFF shl 24) or (ch(data[i]) shl 16) or (ch(data[i + 1]) shl 8) or ch(data[i + 2])
            }
        }
        return px
    }

    /** Applies the LUT (after [input]) to ARGB pixels in place. */
    fun applyToPixels(pixels: IntArray, input: LutInput) {
        val out = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            var r = ((p shr 16) and 0xFF) / 255f; var g = ((p shr 8) and 0xFF) / 255f; var b = (p and 0xFF) / 255f
            if (input == LutInput.BMD_FILM_GEN5) {
                InputTransform.displayToGen5(r, g, b, out)
                r = out[0]; g = out[1]; b = out[2]
            }
            apply(r, g, b, out)
            fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[i] = (p and 0xFF000000.toInt()) or (ch(out[0]) shl 16) or (ch(out[1]) shl 8) or ch(out[2])
        }
    }
}

/** What the LUT expects as input. The phone camera gives display (Rec.709 / sRGB) pictures. */
enum class LutInput(val label: String, val code: String) {
    REC709("Rec.709 (look / display LUTs)", "rec709"),
    BMD_FILM_GEN5("Blackmagic Film Gen 5 (log, approximated from the phone)", "bmdgen5");

    companion object { fun of(code: String?): LutInput = entries.firstOrNull { it.code == code } ?: REC709 }
}

/**
 * Turning the phone's display picture into what a log LUT expects: display → linear Rec.709 → Blackmagic Wide Gamut
 * (Gen 5) → Blackmagic Film Gen 5 curve. Still an approximation: the phone picture is already tone-mapped and
 * limited to Rec.709 colours and the phone's dynamic range, so highlights won't match a real BRAW recording.
 */
object InputTransform {
    /** Rec.709 / sRGB-style display value → linear (sRGB EOTF). */
    fun displayToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /**
     * Blackmagic Film Generation 5 OETF (Blackmagic Design, "Blackmagic Generation 5 Color Science", 2021;
     * constants as in colour-science): y = D·x + E below 0.005, else A·ln(x + B) + C.
     */
    fun gen5Oetf(x: Float): Float {
        val a = 0.08692876065491224; val b = 0.005494072432257808; val c = 0.5300133392291939
        val d = 8.283605932402494; val e = 0.09246575342465753
        return if (x < 0.005f) (d * x + e).toFloat() else (a * ln(x + b) + c).toFloat()
    }

    fun rec709ToGen5(v: Float): Float = gen5Oetf(displayToLinear(v.coerceIn(0f, 1f)))

    /** xy chromaticities: Rec.709 / sRGB and Blackmagic Wide Gamut (Gen 4/5), both with a D65 white. */
    private val REC709 = doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06)
    /** Blackmagic Design, "Blackmagic Generation 5 Color Science" (2021), values as in colour-science. */
    private val BMD_WIDE_GAMUT = doubleArrayOf(0.7177215, 0.3171181, 0.2280410, 0.8615690, 0.1005841, -0.0820452)
    private val D65 = doubleArrayOf(0.3127170, 0.3290312)

    /** Linear Rec.709 → linear Blackmagic Wide Gamut, row-major 3×3. */
    val rec709ToWideGamut: DoubleArray by lazy { multiply(invert(npm(BMD_WIDE_GAMUT, D65)), npm(REC709, D65)) }

    /** Display (Rec.709 / sRGB-style) RGB → Blackmagic Film Gen 5 code values in Blackmagic Wide Gamut, into [out]. */
    fun displayToGen5(r: Float, g: Float, b: Float, out: FloatArray) {
        val m = rec709ToWideGamut
        val lr = displayToLinear(r.coerceIn(0f, 1f)); val lg = displayToLinear(g.coerceIn(0f, 1f)); val lb = displayToLinear(b.coerceIn(0f, 1f))
        for (i in 0 until 3) {
            val v = (m[i * 3] * lr + m[i * 3 + 1] * lg + m[i * 3 + 2] * lb).toFloat()
            out[i] = gen5Oetf(maxOf(v, 0f))
        }
    }

    /** Normalised primary matrix (RGB → XYZ) from xy primaries and white. */
    internal fun npm(p: DoubleArray, w: DoubleArray): DoubleArray {
        fun xyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val r = xyz(p[0], p[1]); val g = xyz(p[2], p[3]); val b = xyz(p[4], p[5])
        val prim = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val wv = xyz(w[0], w[1])
        val inv = invert(prim)
        val s = DoubleArray(3) { i -> inv[i * 3] * wv[0] + inv[i * 3 + 1] * wv[1] + inv[i * 3 + 2] * wv[2] }
        return DoubleArray(9) { k -> prim[k] * s[k % 3] }
    }

    internal fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { k ->
        val i = k / 3; val j = k % 3
        a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }

    internal fun invert(m: DoubleArray): DoubleArray {
        val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }
}

/** Reads Adobe/Resolve .cube files: 3D (LUT_3D_SIZE) or 1D (LUT_1D_SIZE, turned into a 33³ 3D LUT). */
object CubeParser {
    class CubeException(message: String) : Exception(message)

    /** Largest 3D LUT accepted (65³ is the usual maximum for grading LUTs; keeps memory small on phones). */
    const val MAX_3D = 65

    fun parse(text: String, fallbackName: String): Lut3D = parse(text.lineSequence(), fallbackName)

    /** Streams lines into a pre-sized float array (no per-number objects), so big LUTs don't run out of memory. */
    fun parse(lines: Sequence<String>, fallbackName: String): Lut3D {
        var title: String? = null
        var size3 = 0; var size1 = 0
        val dMin = floatArrayOf(0f, 0f, 0f); val dMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0
        val tok = arrayOfNulls<String>(4)
        for (raw in lines) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val first = line[0]
            if (first.isDigit() || first == '-' || first == '.' || first == '+') {
                // A data row: three numbers.
                val n = split(line, tok)
                if (n != 3) continue
                val arr = data ?: run {
                    val total = when {
                        size3 > 0 -> size3 * size3 * size3 * 3
                        size1 > 0 -> size1 * 3
                        else -> throw CubeException("Data before LUT_3D_SIZE / LUT_1D_SIZE")
                    }
                    FloatArray(total).also { data = it }
                }
                if (count + 3 > arr.size) throw CubeException("More entries than the LUT size")
                for (i in 0..2) arr[count++] = tok[i]!!.toFloatOrNull() ?: throw CubeException("Bad number: ${tok[i]}")
                continue
            }
            val n = split(line, tok)
            when (tok[0]!!.uppercase()) {
                "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                "LUT_3D_SIZE" -> {
                    size3 = tok.getOrNull(1)?.toIntOrNull() ?: throw CubeException("Bad LUT_3D_SIZE")
                    if (size3 !in 2..MAX_3D) throw CubeException("LUT_3D_SIZE $size3 is not supported (2–$MAX_3D)")
                }
                "LUT_1D_SIZE" -> {
                    size1 = tok.getOrNull(1)?.toIntOrNull() ?: throw CubeException("Bad LUT_1D_SIZE")
                    if (size1 !in 2..65536) throw CubeException("LUT_1D_SIZE $size1 is not supported")
                }
                "DOMAIN_MIN" -> if (n >= 4) for (i in 0..2) dMin[i] = tok[i + 1]!!.toFloatOrNull() ?: 0f
                "DOMAIN_MAX" -> if (n >= 4) for (i in 0..2) dMax[i] = tok[i + 1]!!.toFloatOrNull() ?: 1f
                "LUT_3D_INPUT_RANGE", "LUT_1D_INPUT_RANGE" -> if (n >= 3) {
                    val lo = tok[1]!!.toFloatOrNull() ?: 0f; val hi = tok[2]!!.toFloatOrNull() ?: 1f
                    for (i in 0..2) { dMin[i] = lo; dMax[i] = hi }
                }
                // Other keywords (e.g. LUT_IN_VIDEO_RANGE) are ignored.
            }
        }
        val name = title?.takeIf { it.isNotBlank() } ?: fallbackName
        for (i in 0..2) if (dMax[i] <= dMin[i]) throw CubeException("Bad DOMAIN")
        val arr = data
        if (size3 > 0) {
            if (arr == null || count != size3 * size3 * size3 * 3) throw CubeException("Expected ${size3 * size3 * size3} entries, found ${count / 3}")
            return Lut3D(name, size3, arr, dMin, dMax)
        }
        if (size1 > 0) {
            if (arr == null || count != size1 * 3) throw CubeException("Expected $size1 entries, found ${count / 3}")
            fun curve(v: Float, c: Int): Float {
                val t = ((v - dMin[c]) / (dMax[c] - dMin[c])).coerceIn(0f, 1f) * (size1 - 1)
                val i0 = floor(t).toInt().coerceIn(0, size1 - 2); val f = t - i0
                return arr[i0 * 3 + c] + (arr[(i0 + 1) * 3 + c] - arr[i0 * 3 + c]) * f
            }
            return BuiltInLooks.generate(name, 33) { r, g, b, out ->
                out[0] = curve(dMin[0] + r * (dMax[0] - dMin[0]), 0)
                out[1] = curve(dMin[1] + g * (dMax[1] - dMin[1]), 1)
                out[2] = curve(dMin[2] + b * (dMax[2] - dMin[2]), 2)
            }
        }
        throw CubeException("Not a .cube LUT (no LUT_3D_SIZE or LUT_1D_SIZE)")
    }

    /** Splits on spaces/tabs into [out] (at most out.size tokens); returns the token count (can exceed out.size). */
    private fun split(line: String, out: Array<String?>): Int {
        java.util.Arrays.fill(out, null)
        var n = 0; var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val start = i
            while (i < line.length && !line[i].isWhitespace()) i++
            if (n < out.size) out[n] = line.substring(start, i)
            n++
        }
        return n
    }
}

/** A few looks made in code (no third-party LUTs are bundled). They expect Rec.709 input. */
object BuiltInLooks {
    fun generate(name: String, size: Int, f: (Float, Float, Float, FloatArray) -> Unit): Lut3D {
        val data = FloatArray(size * size * size * 3)
        val out = FloatArray(3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            f(r / (size - 1f), g / (size - 1f), b / (size - 1f), out)
            data[i++] = out[0].coerceIn(0f, 1f); data[i++] = out[1].coerceIn(0f, 1f); data[i++] = out[2].coerceIn(0f, 1f)
        }
        return Lut3D(name, size, data)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun sCurve(v: Float, k: Float): Float { val x = v - 0.5f; return 0.5f + x * (1 + k) / (1 + k * 4 * x * x) }

    val all: List<Lut3D> by lazy {
        listOf(
            generate("Warm", 33) { r, g, b, o -> o[0] = r * 1.06f + 0.02f; o[1] = g * 1.01f; o[2] = b * 0.9f },
            generate("Cool", 33) { r, g, b, o -> o[0] = r * 0.92f; o[1] = g * 0.99f + 0.01f; o[2] = b * 1.07f + 0.02f },
            generate("Teal & Orange", 33) { r, g, b, o ->
                val y = luma(r, g, b)
                val skin = (r - b).coerceIn(0f, 1f) // warmer where red leads blue
                val shadow = 1 - y
                o[0] = sCurve(r + 0.06f * skin - 0.05f * shadow, 0.3f)
                o[1] = sCurve(g + 0.02f * shadow, 0.3f)
                o[2] = sCurve(b + 0.08f * shadow - 0.05f * skin, 0.3f)
            },
            generate("Bleach bypass", 33) { r, g, b, o ->
                val y = luma(r, g, b)
                o[0] = sCurve(0.5f * r + 0.5f * y, 0.6f); o[1] = sCurve(0.5f * g + 0.5f * y, 0.6f); o[2] = sCurve(0.5f * b + 0.5f * y, 0.6f)
            },
            generate("High contrast", 33) { r, g, b, o -> o[0] = sCurve(r, 0.7f); o[1] = sCurve(g, 0.7f); o[2] = sCurve(b, 0.7f) },
            generate("Black & white", 33) { r, g, b, o -> val y = sCurve(luma(r, g, b), 0.35f); o[0] = y; o[1] = y; o[2] = y },
        )
    }

    /** A small colour chart (hue ramp over a grey ramp) to preview looks on. ARGB, [w] × [h]. */
    fun testChart(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = x / max(1f, w - 1f)
            val (r, g, b) = if (y < h / 2) hue(t, 0.85f, 0.8f) else Triple(t, t, t)
            fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            px[y * w + x] = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        return px
    }

    private fun hue(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
        val i = (h * 6).toInt().coerceIn(0, 5); val f = h * 6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        return when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
    }

    @Suppress("unused") private fun clamp(v: Float) = min(1f, max(0f, v))
}
