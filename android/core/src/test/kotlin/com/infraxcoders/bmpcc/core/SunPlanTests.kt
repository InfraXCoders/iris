package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SunPlanTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val lat = 28.6139; private val lon = 77.2090 // New Delhi

    @Test fun vectorsAndProjection() {
        val v = SunPlan.vector(135.0, 30.0)
        val (az, el) = SunPlan.direction(v)
        assertEquals(135.0, az, 1e-9); assertEquals(30.0, el, 1e-9)
        assertEquals(90.0, SunPlan.angleBetween(0.0, 0.0, 90.0, 0.0), 1e-9)
        assertEquals(-20.0, SunPlan.azimuthDifference(350.0, 10.0), 1e-9)
        // Camera facing north, level: a point 10° east of north on the horizon lands right of centre.
        val p = SkyProjection.project(SunPlan.vector(10.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), 0.5, 0.5)!!
        assertEquals(Math.tan(Math.toRadians(10.0)) / 0.5, p.first, 1e-9)
        assertEquals(0.0, p.second, 1e-9)
        // Behind the camera: not drawn.
        assertNull(SkyProjection.project(SunPlan.vector(180.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), 0.5, 0.5))
    }

    @Test fun pathAndSpot() {
        val day = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val path = SunPlan.path(day, lat, lon, zone, 10)
        assertEquals(24 * 6 + 1, path.size)
        assertTrue(path.maxOf { it.position.elevation } in 80.0..90.0) // high summer sun in Delhi
        // Aim at where the sun is at 16:00: the report finds 16:00.
        val t16 = ZonedDateTime.of(2026, 6, 21, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sun = Solar.position(t16, lat, lon)
        val r = SunPlan.spot(sun.azimuth, sun.elevation, day, lat, lon, zone)
        assertTrue(r.passesThrough)
        assertTrue(kotlin.math.abs(r.closestTime!! - t16) < 60_000)
        assertTrue(r.sameDirection.any { kotlin.math.abs(it.first - t16) < 60_000 && kotlin.math.abs(it.second) < 0.2 })
        assertNotNull(r.aboveFrom); assertTrue(r.aboveFrom!! < t16 && r.aboveUntil!! > t16 - 60_000)
        // A point higher than the sun ever gets: never above it.
        val high = SunPlan.spot(0.0, 89.9, day, lat, lon, zone)
        assertNull(high.aboveFrom)
    }

    @Test fun skylineInterpolationAndGaps() {
        val sky = Skyline(listOf(SkyPoint(100.0, 10.0), SkyPoint(110.0, 20.0), SkyPoint(359.6, 5.0), SkyPoint(5.0, 7.0)))
        assertEquals(10.0, sky.elevation(100.2)!!, 1e-9)
        assertEquals(15.0, sky.elevation(105.0)!!, 1e-9)
        assertNull(sky.elevation(200.0))
        assertEquals(6.0, sky.elevation(2.5)!!, 0.6) // across north
        assertEquals(4, sky.recordedDegrees)
        assertTrue(Skyline(emptyList()).isEmpty)
    }

    @Test fun directSunWithSkyline() {
        val day = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sun = Solar.day(day, lat, lon, zone)
        // No skyline: sunrise to sunset.
        val open = SunPlan.directSunPeriods(day, lat, lon, zone, null)
        assertEquals(1, open.size)
        assertTrue(kotlin.math.abs(open[0].first - sun.sunrise!!) < 30_000)
        assertTrue(kotlin.math.abs(open[0].second - sun.sunset!!) < 30_000)
        // A 20° wall all round: the sun clears it later and drops behind it earlier.
        val wall = Skyline((0 until 360).map { SkyPoint(it.toDouble(), 20.0) })
        val walled = SunPlan.directSunPeriods(day, lat, lon, zone, wall)
        assertEquals(1, walled.size)
        assertTrue(walled[0].first > sun.sunrise!! + 3_600_000)
        assertTrue(walled[0].second < sun.sunset!! - 3_600_000)
        val clear = Solar.position(walled[0].first + 60_000, lat, lon)
        assertTrue(SunPlan.directSun(clear, wall))
        assertTrue(SunPlan.behindSkyline(Solar.position(walled[0].first - 600_000, lat, lon), wall))
        assertTrue(SunPlan.refraction(0.0) in 0.45..0.5)
    }

    @Test fun cloudForecast() {
        val json = """{"hourly":{"time":[1790000000,1790003600],"cloud_cover":[10,90],"cloud_cover_low":[0,80],"precipitation_probability":[0,60]}}"""
        val h = CloudForecast.parse(json)
        assertEquals(2, h.size)
        assertEquals(1790000000000L, h[0].time)
        assertEquals("direct sun likely", CloudForecast.sunChance(h[0]))
        assertEquals("Overcast (90% cloud, 80% low), rain 60% · direct sun unlikely (soft light)", CloudForecast.describe(h[1]))
        assertEquals(h[1], CloudForecast.at(h, 1790003600000L + 1_000))
        assertNull(CloudForecast.at(h, 1790003600000L + 3_700_000))
        assertTrue(CloudForecast.url(28.6, 77.2).contains("latitude=28.6000"))
    }

    @Test fun skylineSurvivesJson() {
        val s = RecceSession(skyline = listOf(SkyPoint(90.0, 12.5)))
        assertEquals(s.skyline, RecceJson.decode(RecceJson.encode(s)).skyline)
    }
}
