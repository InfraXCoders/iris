package com.infraxcoders.bmpcc.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Focus and depth of field for a shot (thin-lens formulas, the same ones printed in makers' DoF tables).
 *
 * Circle of confusion: sensor-area diagonal / 1500 (≈0.029 mm full frame, ≈0.019 mm Pocket 6K), a common cine
 * convention. Cine lenses are marked in T-stops; the f-number is a little smaller, so using the T-stop gives a
 * slightly deeper (safe-side) depth of field. Anamorphic lenses: the real focal length is used, as in maker tables.
 */
object Focus {
    data class Result(
        val hyperfocalM: Double,
        val nearM: Double,
        /** [Double.POSITIVE_INFINITY] at or beyond the hyperfocal distance. */
        val farM: Double,
        /** True when the subject is closer than the lens can focus. */
        val tooClose: Boolean,
    ) {
        val totalM: Double get() = farM - nearM
    }

    fun circleOfConfusionMm(mode: SensorMode): Double = mode.diagonalMm / 1500

    /**
     * Depth of field at [distanceM] (focus distance from the sensor) for [focalMm] at [stop].
     * Returns null when the stop or distance is unknown.
     */
    fun depthOfField(focalMm: Double, stop: Double, distanceM: Double, cocMm: Double, closeFocusM: Double = 0.0): Result? {
        if (focalMm <= 0 || stop <= 0 || distanceM <= 0 || cocMm <= 0) return null
        val f = focalMm / 1000
        val c = cocMm / 1000
        val h = f * f / (stop * c) + f
        val s = max(distanceM, f * 1.01)
        val near = s * (h - f) / (h + s - 2 * f)
        val far = if (s >= h) Double.POSITIVE_INFINITY else s * (h - f) / (h - s)
        return Result(h, near, far, closeFocusM > 0 && distanceM < closeFocusM - 1e-9)
    }

    /** "2.4 m", "85 cm", "∞". */
    fun distanceText(m: Double): String = when {
        m.isInfinite() -> "∞"
        m < 1 -> String.format(java.util.Locale.US, "%d cm", Math.round(m * 100))
        m < 10 -> String.format(java.util.Locale.US, "%.1f m", m)
        else -> String.format(java.util.Locale.US, "%d m", Math.round(m))
    }

    /** Focus distances to pick from. */
    val presetsM = listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0, 20.0)
}

/**
 * Lens distortion (barrel / pincushion) from measured calibrations in the Lensfun database (CC BY-SA 3.0), for the
 * photo lenses that have one. Cine lens makers don't publish distortion data, so for cine lenses the user can enter
 * their own measurement ([custom]).
 *
 * Lensfun's ptlens / poly3 / poly5 models use "Hugin" units (r = 1 at half the short side of the calibration sensor).
 * They are rescaled here, exactly as Lensfun's rescale_polynomial_coefficients does, to units of the focal length,
 * where an undistorted point at angle θ sits at r_u = tan θ and the lens images it at r_d = r_u · P(r_u).
 */
object Distortion {
    data class Point(
        val lensId: String, val focalMm: Double, val model: String,
        val t1: Double, val t2: Double, val t3: Double,
        val calibCrop: Double, val calibAspect: Double, val realFocalMm: Double?,
    )

    /** Coefficients in focal-length units: r_d = r_u · (1 + a r³ + b r² + c r) (ptlens) or (1 + k1 r² + k2 r⁴) (poly). */
    data class Model(val ptlens: Boolean, val a: Double, val b: Double, val c: Double) {
        fun distort(ru: Double): Double = if (ptlens) ru * (1 + a * ru * ru * ru + b * ru * ru + c * ru)
        else ru * (1 + a * ru * ru + b * ru * ru * ru * ru)

        /** Inverse of [distort] by Newton's method (monotonic in the range lenses use). */
        fun undistort(rd: Double): Double {
            var ru = rd
            repeat(30) {
                val f = distort(ru) - rd
                val h = 1e-6
                val d = (distort(ru + h) - distort(ru - h)) / (2 * h)
                if (abs(d) < 1e-9) return ru
                val next = ru - f / d
                if (abs(next - ru) < 1e-12) return next
                ru = next
            }
            return ru
        }
    }

    val points: List<Point> by lazy { load(resource()) }
    private val byLens: Map<String, List<Point>> by lazy { points.groupBy { it.lensId } }

    /**
     * Distortion the user measured themselves (e.g. from a grid / lens test, or a maker's chart), for lenses Lensfun
     * doesn't cover — most cine lenses. Lens id → (focal length mm, k1 in focal-length units).
     */
    @Volatile var custom: Map<String, List<Pair<Double, Double>>> = emptyMap()

    fun hasProfile(lens: LensProfile): Boolean = byLens.containsKey(lens.id) || custom[lens.id]?.isNotEmpty() == true
    /** True when the profile is the user's own measurement (not Lensfun). */
    fun isCustom(lens: LensProfile): Boolean = !byLens.containsKey(lens.id) && custom[lens.id]?.isNotEmpty() == true

    /**
     * A one-term model that gives [percent] distortion at the corner of a [widthMm] × [heightMm] frame at [focalMm]
     * (negative = barrel). Returns k1 in focal-length units: r_d = r_u (1 + k1 r_u²).
     */
    fun k1FromCornerPercent(percent: Double, widthMm: Double, heightMm: Double, focalMm: Double): Double {
        val rd = hypot(widthMm / 2, heightMm / 2) / focalMm
        val ratio = 1 + percent / 100
        val ru = rd / ratio
        return (ratio - 1) / (ru * ru)
    }

    /** The user's measured model at [focalMm] (nearest measured focal length; linear between two). */
    fun customModel(lens: LensProfile, focalMm: Double): Model? {
        val list = custom[lens.id]?.sortedBy { it.first } ?: return null
        if (list.isEmpty()) return null
        if (focalMm <= list.first().first) return Model(false, list.first().second, 0.0, 0.0)
        if (focalMm >= list.last().first) return Model(false, list.last().second, 0.0, 0.0)
        val hi = list.indexOfFirst { it.first >= focalMm }
        val (f0, k0) = list[hi - 1]; val (f1, k1) = list[hi]
        return Model(false, k0 + (k1 - k0) * (focalMm - f0) / (f1 - f0), 0.0, 0.0)
    }

    private fun resource(): String =
        Distortion::class.java.classLoader?.getResourceAsStream("data/distortion.csv")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    fun load(csv: String): List<Point> = Csv.records(csv).mapNotNull { r ->
        val id = r["lens_id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val f = r["focal_mm"]?.toDoubleOrNull() ?: return@mapNotNull null
        Point(
            id, f, r["model"] ?: "ptlens", r["t1"]?.toDoubleOrNull() ?: 0.0, r["t2"]?.toDoubleOrNull() ?: 0.0,
            r["t3"]?.toDoubleOrNull() ?: 0.0, r["calib_crop"]?.toDoubleOrNull() ?: 1.0, r["calib_aspect"]?.toDoubleOrNull() ?: 1.5,
            r["real_focal_mm"]?.toDoubleOrNull(),
        )
    }

    /** One calibration point rescaled to focal-length units (Lensfun's rescale_polynomial_coefficients). */
    fun rescale(p: Point): Model {
        val realFocal = p.realFocalMm ?: p.focalMm
        val hugin = hypot(36.0, 24.0) / p.calibCrop / hypot(p.calibAspect, 1.0) / 2.0
        val h = realFocal / hugin
        return when (p.model) {
            "poly3" -> { val d = 1 - p.t1; Model(false, p.t1 * h * h / (d * d * d), 0.0, 0.0) }
            "poly5" -> Model(false, p.t1 * h * h, p.t2 * h * h * h * h, 0.0)
            else -> {
                val d = 1 - p.t1 - p.t2 - p.t3
                Model(true, p.t1 * h * h * h / (d * d * d * d), p.t2 * h * h / (d * d * d), p.t3 * h / (d * d))
            }
        }
    }

    /** The model at [focalMm], linearly between the nearest calibrated focal lengths (null when none). */
    fun modelFor(lens: LensProfile, focalMm: Double): Model? {
        val list = byLens[lens.id]?.sortedBy { it.focalMm } ?: return customModel(lens, focalMm)
        if (list.isEmpty()) return null
        if (focalMm <= list.first().focalMm) return rescale(list.first())
        if (focalMm >= list.last().focalMm) return rescale(list.last())
        val hi = list.indexOfFirst { it.focalMm >= focalMm }
        val p0 = list[hi - 1]; val p1 = list[hi]
        val m0 = rescale(p0); val m1 = rescale(p1)
        if (m0.ptlens != m1.ptlens) return if (focalMm - p0.focalMm < p1.focalMm - focalMm) m0 else m1
        val t = (focalMm - p0.focalMm) / (p1.focalMm - p0.focalMm)
        return Model(m0.ptlens, m0.a + (m1.a - m0.a) * t, m0.b + (m1.b - m0.b) * t, m0.c + (m1.c - m0.c) * t)
    }

    /**
     * The scene directions (tan x, tan y) that land on the edge of a [widthMm] × [heightMm] frame (centred on the
     * sensor), [perSide] points per side, clockwise from the top-left corner. Without distortion this is the
     * rectangle ±width/2f, ±height/2f.
     */
    fun frameOutline(model: Model, widthMm: Double, heightMm: Double, focalMm: Double, perSide: Int = 16): List<Pair<Double, Double>> {
        val hw = widthMm / 2; val hh = heightMm / 2
        val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        val out = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until 4) {
            val (x0, y0) = corners[i]; val (x1, y1) = corners[(i + 1) % 4]
            for (k in 0 until perSide) {
                val t = k.toDouble() / perSide
                val x = (x0 + (x1 - x0) * t) / focalMm
                val y = (y0 + (y1 - y0) * t) / focalMm
                val rd = hypot(x, y)
                val s = if (rd < 1e-12) 1.0 else model.undistort(rd) / rd
                out += x * s to y * s
            }
        }
        return out
    }

    /**
     * Distortion at the frame corner in percent of radius: negative = barrel (the lens sees more in the corners
     * than a perfect lens), positive = pincushion.
     */
    fun cornerPercent(model: Model, widthMm: Double, heightMm: Double, focalMm: Double): Double {
        val rd = hypot(widthMm / 2, heightMm / 2) / focalMm
        val ru = model.undistort(rd)
        return (rd / ru - 1) * 100
    }

    /** True when the frame corner lies inside the area the calibration measured (with a 5 % margin). */
    fun withinCalibration(lens: LensProfile, widthMm: Double, heightMm: Double): Boolean {
        if (isCustom(lens)) return min(widthMm, heightMm) > 0
        val p = byLens[lens.id]?.firstOrNull() ?: return false
        val calibDiag = hypot(36.0, 24.0) / p.calibCrop
        return hypot(widthMm, heightMm) <= calibDiag * 1.05 + 1e-9 && min(widthMm, heightMm) > 0
    }
}

/** Focus and distortion for a saved shot (what the viewfinder, shot page and report show). */
object ShotOptics {
    /** Depth of field at the shot's subject distance, with its stop (T-stop, or the lens's fastest when unset). */
    fun depthOfField(shot: RecceShot): Focus.Result? {
        val d = shot.estimatedDistance ?: return null
        val stop = Exposure.stop(shot.aperture) ?: shot.lens.maximumAperture.takeIf { it > 0 } ?: return null
        return Focus.depthOfField(shot.focalMm, stop, d, Focus.circleOfConfusionMm(shot.sensorMode), shot.lens.minimumFocusDistance)
    }

    /** "DoF 2.7–3.3 m" or "DoF 1.2 m–∞". */
    fun dofText(r: Focus.Result): String =
        if (r.farM.isInfinite()) "DoF ${Focus.distanceText(r.nearM)}–∞" else "DoF ${Focus.distanceText(r.nearM).removeSuffix(" m")}–${Focus.distanceText(r.farM)}"

    /** Distortion at the corner of the delivered frame in percent (null without a profile or outside its calibration). */
    fun distortionPercent(shot: RecceShot): Double? {
        val ref = shot.reference ?: return null
        val model = Distortion.modelFor(shot.lens, shot.focalMm) ?: return null
        if (!Distortion.withinCalibration(shot.lens, ref.deliveredWidthMm, ref.deliveredHeightMm)) return null
        return Distortion.cornerPercent(model, ref.deliveredWidthMm, ref.deliveredHeightMm, ref.effectiveFocalLengthMm)
    }

    /** "−1.7 % barrel", "+0.8 % pincushion", "none". */
    fun distortionText(percent: Double): String = when {
        kotlin.math.abs(percent) < 0.3 -> "negligible"
        percent < 0 -> String.format(java.util.Locale.US, "%.1f %% barrel at the corners", percent)
        else -> String.format(java.util.Locale.US, "+%.1f %% pincushion at the corners", percent)
    }
}
