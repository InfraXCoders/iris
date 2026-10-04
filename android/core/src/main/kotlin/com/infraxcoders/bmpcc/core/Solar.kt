package com.infraxcoders.bmpcc.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Sun position: azimuth clockwise from true north, elevation above the horizon (degrees). */
data class SolarPosition(val azimuth: Double, val elevation: Double) {
    val compass: String get() = Solar.compass(azimuth)
    /** Heading to point the camera so the sun is directly behind the subject. */
    val backlightHeading: Double get() = (azimuth + 180) % 360
}

/** A time range in epoch milliseconds. */
data class TimeRange(val start: Long, val end: Long)

/** Sun times (epoch milliseconds) for one local calendar day; null when it doesn't happen that day. */
data class SunDay(
    val sunrise: Long?,
    val sunset: Long?,
    val solarNoon: Long,
    val noonElevation: Double,
    val goldenMorning: TimeRange?,
    val goldenEvening: TimeRange?,
    val blueMorning: TimeRange?,
    val blueEvening: TimeRange?,
) {
    val isPolarDay: Boolean get() = sunrise == null && sunset == null && noonElevation > 0
    val isPolarNight: Boolean get() = sunrise == null && sunset == null && noonElevation <= 0
}

/**
 * NOAA solar position algorithm. Replaces the old Android approximation, which ignored the equation of time
 * (up to ±16 min) and defined golden hour as "sunset − 1 h".
 */
object Solar {
    /** Sunrise/sunset: centre of the sun 0.833° below the horizon. */
    const val HORIZON = -0.833
    /** Golden hour: sun between 6° above and 4° below the horizon. Blue hour: 4° to 6° below. */
    const val GOLDEN_HIGH = 6.0
    const val GOLDEN_LOW = -4.0
    const val BLUE_LOW = -6.0

    private fun rad(d: Double) = d * Math.PI / 180
    private fun deg(r: Double) = r * 180 / Math.PI
    private fun mod(a: Double, m: Double): Double { val r = a % m; return if (r < 0) r + m else r }

    /** (declination degrees, equation of time minutes) at a Julian century. */
    private fun declinationAndEoT(t: Double): Pair<Double, Double> {
        val l0 = mod(280.46646 + t * (36000.76983 + t * 0.0003032), 360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val appLong = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(eps)) * sin(rad(appLong))))
        val y = tan(rad(eps / 2)).pow(2)
        val eot = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        return decl to eot
    }

    private fun julianCentury(epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        return (jd - 2451545) / 36525
    }

    fun position(epochMs: Long, latitude: Double, longitude: Double): SolarPosition {
        val (decl, eot) = declinationAndEoT(julianCentury(epochMs))
        val utcMinutes = mod(epochMs / 60_000.0, 1440.0)
        val trueSolarTime = mod(utcMinutes + eot + 4 * longitude, 1440.0)
        var hourAngle = trueSolarTime / 4 - 180
        if (hourAngle < -180) hourAngle += 360
        val lat = rad(latitude)
        val dec = rad(decl)
        val cosZen = max(-1.0, min(1.0, sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(rad(hourAngle))))
        val zenith = deg(acos(cosZen))
        val denom = cos(lat) * sin(rad(zenith))
        val azimuth = if (abs(denom) > 1e-9) {
            val a = deg(acos(max(-1.0, min(1.0, (sin(lat) * cos(rad(zenith)) - sin(dec)) / denom))))
            if (hourAngle > 0) mod(a + 180, 360.0) else mod(540 - a, 360.0)
        } else if (latitude > 0) 180.0 else 0.0
        return SolarPosition(azimuth, 90 - zenith)
    }

    /** Times in [start, end) where the elevation crosses [target], rising or setting. */
    internal fun crossings(target: Double, start: Long, end: Long, latitude: Double, longitude: Double, rising: Boolean): List<Long> {
        fun f(t: Long) = position(t, latitude, longitude).elevation - target
        val step = 600_000L
        val out = mutableListOf<Long>()
        var a = start
        var fa = f(a)
        while (a < end) {
            val b = min(a + step, end)
            val fb = f(b)
            if ((rising && fa < 0 && fb >= 0) || (!rising && fa > 0 && fb <= 0)) {
                var lo = a.toDouble()
                var hi = b.toDouble()
                var flo = fa
                repeat(40) {
                    val mid = (lo + hi) / 2
                    val fm = f(mid.toLong())
                    if ((fm < 0) == (flo < 0)) { lo = mid; flo = fm } else hi = mid
                }
                out.add(((lo + hi) / 2).toLong())
            }
            a = b
            fa = fb
        }
        return out
    }

    /** Sun times for the local calendar day containing [epochMs] in [zone]. */
    fun day(epochMs: Long, latitude: Double, longitude: Double, zone: ZoneId): SunDay {
        val localDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val start = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun first(target: Double, rising: Boolean) = crossings(target, start, end, latitude, longitude, rising).firstOrNull()
        fun last(target: Double, rising: Boolean) = crossings(target, start, end, latitude, longitude, rising).lastOrNull()

        // Solar noon: hour angle 0. UTC minutes = 720 - 4·longitude - EoT, iterated at the noon instant.
        val utcMidnight = floor(start / 86_400_000.0) * 86_400_000.0
        var noonMinutes = 720 - 4 * longitude
        repeat(3) {
            val guess = (utcMidnight + noonMinutes * 60_000).toLong()
            noonMinutes = 720 - 4 * longitude - declinationAndEoT(julianCentury(guess)).second
        }
        var noon = (utcMidnight + noonMinutes * 60_000).toLong()
        if (noon < start) noon += 86_400_000
        if (noon >= end) noon -= 86_400_000
        val noonEl = position(noon, latitude, longitude).elevation

        fun range(a: Long?, b: Long?): TimeRange? = if (a != null && b != null && a <= b) TimeRange(a, b) else null
        return SunDay(
            sunrise = first(HORIZON, true),
            sunset = last(HORIZON, false),
            solarNoon = noon, noonElevation = noonEl,
            goldenMorning = range(first(GOLDEN_LOW, true), first(GOLDEN_HIGH, true)),
            goldenEvening = range(last(GOLDEN_HIGH, false), last(GOLDEN_LOW, false)),
            blueMorning = range(first(BLUE_LOW, true), first(GOLDEN_LOW, true)),
            blueEvening = range(last(GOLDEN_LOW, false), last(BLUE_LOW, false)),
        )
    }

    /** 8-point compass name. */
    fun compass(degrees: Double): String {
        val names = listOf("North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West")
        val n = mod(degrees, 360.0)
        return names[((n + 22.5) / 45).toInt() % 8]
    }
}
