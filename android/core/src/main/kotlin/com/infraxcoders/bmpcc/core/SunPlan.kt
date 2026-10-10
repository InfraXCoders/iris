package com.infraxcoders.bmpcc.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A direction or vector in the local East-North-Up frame. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { if (it < 1e-12) this else Vec3(x / it, y / it, z / it) }
}

/**
 * Planning with the sun: its path over a day, where it is at any time, drawing it over the camera picture, and
 * when it lines up with (or rises above) a point the user aims at. Buildings, trees and hills are included only when
 * the user has recorded the [Skyline] around the spot; weather comes separately ([CloudForecast]).
 */
object SunPlan {
    data class Sample(val time: Long, val position: SolarPosition)

    private fun rad(d: Double) = d * Math.PI / 180
    private fun deg(r: Double) = r * 180 / Math.PI

    /** Start and end (epoch ms) of the local calendar day containing [epochMs]. */
    fun dayBounds(epochMs: Long, zone: ZoneId): Pair<Long, Long> {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Sun positions through the local day, every [stepMinutes]. */
    fun path(epochMs: Long, latitude: Double, longitude: Double, zone: ZoneId, stepMinutes: Int = 10): List<Sample> {
        val (start, end) = dayBounds(epochMs, zone)
        val step = stepMinutes * 60_000L
        return generateSequence(start) { it + step }.takeWhile { it <= end }
            .map { Sample(it, Solar.position(it, latitude, longitude)) }.toList()
    }

    /** Unit vector (East, North, Up) for an azimuth (clockwise from north) and elevation, degrees. */
    fun vector(azimuth: Double, elevation: Double): Vec3 {
        val a = rad(azimuth); val e = rad(elevation)
        return Vec3(sin(a) * cos(e), cos(a) * cos(e), sin(e))
    }

    /** Azimuth and elevation (degrees) of a direction in East-North-Up. */
    fun direction(v: Vec3): Pair<Double, Double> {
        val n = v.normalized()
        val az = (deg(atan2(n.x, n.y)) + 360) % 360
        return az to deg(asin(n.z.coerceIn(-1.0, 1.0)))
    }

    /** Angle between two directions, degrees. */
    fun angleBetween(az1: Double, el1: Double, az2: Double, el2: Double): Double =
        deg(acos((vector(az1, el1) dot vector(az2, el2)).coerceIn(-1.0, 1.0)))

    /**
     * Atmospheric refraction (degrees) for a geometric elevation: how much higher the sun appears (Sæmundsson's
     * formula, standard conditions). Used to compare the sun with a skyline measured by eye / camera.
     */
    fun refraction(elevation: Double): Double {
        val h = elevation.coerceAtLeast(-1.0)
        return 1.02 / kotlin.math.tan(rad(h + 10.3 / (h + 5.11))) / 60
    }

    /** Half the sun's apparent diameter, degrees: the sun is "out" once its top edge clears the skyline. */
    const val SUN_RADIUS = 0.27

    /** True when the sun is above the horizon but hidden behind the recorded skyline. */
    fun behindSkyline(p: SolarPosition, skyline: Skyline?): Boolean {
        if (p.elevation <= Solar.HORIZON || skyline == null) return false
        val top = skyline.elevation(p.azimuth) ?: return false
        return p.elevation + refraction(p.elevation) + SUN_RADIUS <= top
    }

    /** True when the sun can shine on the spot: above the horizon and, where recorded, above the skyline. */
    fun directSun(p: SolarPosition, skyline: Skyline?): Boolean = p.elevation > Solar.HORIZON && !behindSkyline(p, skyline)

    /**
     * Periods of direct sun at the spot over the local day of [epochMs] (start to end, epoch ms), refined to ~10 s.
     * Without a skyline this is simply sunrise to sunset.
     */
    fun directSunPeriods(epochMs: Long, latitude: Double, longitude: Double, zone: ZoneId, skyline: Skyline?): List<Pair<Long, Long>> {
        val (start, end) = dayBounds(epochMs, zone)
        fun lit(t: Long) = directSun(Solar.position(t, latitude, longitude), skyline)
        fun edge(a: Long, b: Long, litAtA: Boolean): Long {
            var lo = a; var hi = b
            while (hi - lo > 10_000) { val mid = (lo + hi) / 2; if (lit(mid) == litAtA) lo = mid else hi = mid }
            return (lo + hi) / 2
        }
        val out = mutableListOf<Pair<Long, Long>>()
        val step = 120_000L
        var t = start
        var prev = lit(t)
        var from: Long? = if (prev) start else null
        while (t < end) {
            val n = minOf(t + step, end)
            val now = lit(n)
            if (now != prev) {
                val e = edge(t, n, prev)
                if (now) from = e else { from?.let { out += it to e }; from = null }
            }
            t = n; prev = now
        }
        from?.let { out += it to end }
        return out
    }

    /** Signed smallest difference a − b in degrees (−180..180). */
    fun azimuthDifference(a: Double, b: Double): Double = ((a - b + 540) % 360) - 180

    /**
     * When the sun meets a point the user aimed at (e.g. a window, a gap between buildings, a rooftop edge),
     * for the local day of [epochMs]. Only daylight (sun above the horizon) counts.
     */
    data class SpotReport(
        /** Time the sun is closest to the point, and how far (degrees). */
        val closestTime: Long?,
        val closestDegrees: Double,
        /** Times the sun passes the point's compass direction, with the sun's elevation minus the point's. */
        val sameDirection: List<Pair<Long, Double>>,
        /** The sun is higher than the point from [aboveFrom] to [aboveUntil] (null = not that day). */
        val aboveFrom: Long?,
        val aboveUntil: Long?,
    ) {
        /** Within 2°: the sun's disc (½°) passes the point itself. */
        val passesThrough: Boolean get() = closestTime != null && closestDegrees <= 2.0
    }

    fun spot(azimuth: Double, elevation: Double, epochMs: Long, latitude: Double, longitude: Double, zone: ZoneId): SpotReport {
        val (start, end) = dayBounds(epochMs, zone)
        val samples = path(epochMs, latitude, longitude, zone, 2).filter { it.position.elevation > Solar.HORIZON }

        // Closest approach (refined to 10 s).
        var best: Sample? = null
        var bestD = Double.MAX_VALUE
        for (s in samples) {
            val d = angleBetween(s.position.azimuth, s.position.elevation, azimuth, elevation)
            if (d < bestD) { bestD = d; best = s }
        }
        var closest = best?.time
        if (best != null) {
            var t = best.time - 120_000
            while (t <= best.time + 120_000) {
                val p = Solar.position(t, latitude, longitude)
                val d = angleBetween(p.azimuth, p.elevation, azimuth, elevation)
                if (d < bestD && p.elevation > Solar.HORIZON) { bestD = d; closest = t }
                t += 10_000
            }
        }

        // Passing the same compass direction (sign change of the azimuth difference), refined by bisection.
        val same = mutableListOf<Pair<Long, Double>>()
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            if (b.time - a.time > 150_000) continue
            val da = azimuthDifference(a.position.azimuth, azimuth)
            val db = azimuthDifference(b.position.azimuth, azimuth)
            if (abs(da) < 90 && abs(db) < 90 && (da == 0.0 || (da < 0) != (db < 0))) {
                var lo = a.time; var hi = b.time; var flo = da
                repeat(25) {
                    val mid = (lo + hi) / 2
                    val fm = azimuthDifference(Solar.position(mid, latitude, longitude).azimuth, azimuth)
                    if ((fm < 0) == (flo < 0)) { lo = mid; flo = fm } else hi = mid
                }
                val t = (lo + hi) / 2
                same += t to (Solar.position(t, latitude, longitude).elevation - elevation)
            }
        }

        // Sun higher than the point: between its rising and setting crossings of that elevation.
        val target = maxOf(elevation, Solar.HORIZON)
        val rise = Solar.crossings(target, start, end, latitude, longitude, true).firstOrNull()
        val set = Solar.crossings(target, start, end, latitude, longitude, false).lastOrNull()
        val noonHigher = samples.any { it.position.elevation > target }
        return SpotReport(
            closest, if (closest == null) Double.MAX_VALUE else bestD, same,
            if (noonHigher) rise ?: start else null, if (noonHigher) set ?: end else null,
        )
    }
}

/**
 * Where a direction in the world appears on screen, for a camera whose orientation is given by its forward,
 * right and up vectors in East-North-Up. Returns normalised screen coordinates (−1..1 across the picture's
 * half-widths; y up), or null when the direction is behind the camera.
 */
object SkyProjection {
    fun project(direction: Vec3, forward: Vec3, right: Vec3, up: Vec3, tanHalfH: Double, tanHalfV: Double): Pair<Double, Double>? {
        val z = direction dot forward
        if (z <= 1e-6 || tanHalfH <= 0 || tanHalfV <= 0) return null
        return ((direction dot right) / z / tanHalfH) to ((direction dot up) / z / tanHalfV)
    }
}

/** One recorded point of the skyline: compass direction (true north) and elevation, degrees. */
data class SkyPoint(val azimuth: Double, val elevation: Double)

/**
 * The tops of buildings, trees and hills around a spot, recorded by sweeping the camera's crosshair along them:
 * elevation by compass direction in 1° steps. Short gaps (up to [MAX_GAP]°) are bridged in a straight line;
 * directions not recorded count as an open horizon.
 */
class Skyline(points: List<SkyPoint>) {
    private val bins = DoubleArray(360) { Double.NaN }

    init {
        for (p in points) {
            val i = bin(p.azimuth)
            bins[i] = if (bins[i].isNaN()) p.elevation else maxOf(bins[i], p.elevation)
        }
    }

    val isEmpty: Boolean get() = bins.all { it.isNaN() }
    /** Degrees of the horizon actually recorded. */
    val recordedDegrees: Int get() = bins.count { !it.isNaN() }

    /** Skyline elevation in a direction, or null when that part wasn't recorded. */
    fun elevation(azimuth: Double): Double? {
        val i = bin(azimuth)
        if (!bins[i].isNaN()) return bins[i]
        var l = -1; var r = -1
        for (k in 1..MAX_GAP) { if (!bins[(i - k + 360) % 360].isNaN()) { l = k; break } }
        for (k in 1..MAX_GAP) { if (!bins[(i + k) % 360].isNaN()) { r = k; break } }
        if (l < 0 || r < 0 || l + r > MAX_GAP) return null
        val a = bins[(i - l + 360) % 360]; val b = bins[(i + r) % 360]
        return a + (b - a) * l / (l + r)
    }

    /** The recorded points (one per degree). */
    fun points(): List<SkyPoint> = bins.indices.filter { !bins[it].isNaN() }.map { SkyPoint(it.toDouble(), bins[it]) }

    companion object {
        const val MAX_GAP = 15
        fun bin(azimuth: Double): Int = ((Math.round(azimuth).toInt() % 360) + 360) % 360
    }
}

/** Hourly cloud forecast (Open-Meteo, free, no key) and what it means for direct sun. */
object CloudForecast {
    data class Hour(val time: Long, val cloud: Int, val cloudLow: Int?, val rainChance: Int?)

    /** Forecast request for a location: hourly cloud cover, low cloud and rain chance, Unix times. */
    fun url(latitude: Double, longitude: Double): String = String.format(
        java.util.Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=cloud_cover,cloud_cover_low,precipitation_probability" +
            "&timeformat=unixtime&forecast_days=16&timezone=GMT",
        latitude, longitude,
    )

    const val ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0)"
    const val DAYS = 16

    fun parse(text: String): List<Hour> {
        val root = Json.parse(text) as? Map<*, *> ?: throw Json.ParseException("Not a forecast")
        val hourly = root["hourly"] as? Map<*, *> ?: throw Json.ParseException("No hourly data")
        val times = (hourly["time"] as? List<*>) ?: emptyList<Any>()
        fun ints(k: String): List<Int?> = (hourly[k] as? List<*>)?.map { (it as? Number)?.toInt() } ?: emptyList()
        val cloud = ints("cloud_cover"); val low = ints("cloud_cover_low"); val rain = ints("precipitation_probability")
        return times.indices.mapNotNull { i ->
            val t = (times[i] as? Number)?.toLong() ?: return@mapNotNull null
            val c = cloud.getOrNull(i) ?: return@mapNotNull null
            Hour(t * 1000, c, low.getOrNull(i), rain.getOrNull(i))
        }
    }

    /** The forecast hour containing [time] (null outside the forecast). */
    fun at(hours: List<Hour>, time: Long): Hour? = hours.lastOrNull { it.time <= time }?.takeIf { time - it.time < 3_600_000 }

    fun sky(cloud: Int): String = when {
        cloud < 20 -> "Clear"
        cloud < 50 -> "Partly cloudy"
        cloud < 85 -> "Mostly cloudy"
        else -> "Overcast"
    }

    /** A plain-language read for direct sun. Low cloud blocks the sun most; high thin cloud often doesn't. */
    fun sunChance(h: Hour): String {
        val low = h.cloudLow ?: h.cloud
        return when {
            h.cloud < 30 && low < 20 -> "direct sun likely"
            low < 50 && h.cloud < 80 -> "sun may come and go"
            else -> "direct sun unlikely (soft light)"
        }
    }

    fun describe(h: Hour): String = buildString {
        append(sky(h.cloud)).append(" (").append(h.cloud).append("% cloud")
        h.cloudLow?.let { append(", ").append(it).append("% low") }
        append(")")
        h.rainChance?.let { append(", rain ").append(it).append("%") }
        append(" · ").append(sunChance(h))
    }
}
