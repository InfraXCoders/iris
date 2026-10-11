package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOpticsTests {
    @Test fun fovFromSensorAndFocal() {
        // 6.4 × 4.8 mm 4:3 sensor, f = 4.38 mm: long side 2·atan(3.2 / 4.38) = 72.3°.
        val r = PhoneOptics.longSideFov(6.4, 4.8, 4000, 3000, 4000, 3000, 4.38, 4.0 / 3.0)
        assertEquals(PhoneOptics.Source.SENSOR, r.source)
        assertEquals(72.3, r.longSideFov, 0.1)
        // A 16:9 stream from the same 4:3 sensor keeps the long side.
        assertEquals(r.longSideFov, PhoneOptics.longSideFov(6.4, 4.8, 4000, 3000, 4000, 3000, 4.38, 16.0 / 9.0).longSideFov, 1e-9)
        // A 4:3 stream from a 16:9 sensor is cropped at the sides.
        val wide = PhoneOptics.longSideFov(8.0, 4.5, 1600, 900, 1600, 900, 4.0, 4.0 / 3.0)
        assertEquals(Optics.degrees(kotlin.math.atan(3.0 / 4.0)) * 2, wide.longSideFov, 1e-6)
    }

    @Test fun intrinsicPreferredWhenConsistent() {
        // fx = 2740 px on a 4000 px wide array: 2·atan(2000 / 2740) = 72.3°.
        val r = PhoneOptics.longSideFov(6.4, 4.8, 4000, 3000, 4000, 3000, 4.38, 4.0 / 3.0, intrinsicFx = 2740.0)
        assertEquals(PhoneOptics.Source.INTRINSIC, r.source)
        assertEquals(72.3, r.longSideFov, 0.1)
        // Inconsistent intrinsic (half the focal): ignored.
        assertEquals(PhoneOptics.Source.SENSOR, PhoneOptics.longSideFov(6.4, 4.8, 4000, 3000, 4000, 3000, 4.38, 4.0 / 3.0, intrinsicFx = 1370.0).source)
    }

    @Test fun fallbackWhenMissingOrImplausible() {
        assertEquals(PhoneOptics.Reading(70.0, PhoneOptics.Source.ASSUMED), PhoneOptics.longSideFov(null, null, null, null, null, null, null, 4.0 / 3.0))
        // Focal in the wrong units (e.g. 0.04): gives ~180°, rejected.
        assertEquals(PhoneOptics.Source.ASSUMED, PhoneOptics.longSideFov(6.4, 4.8, 4000, 3000, 4000, 3000, 0.04, 4.0 / 3.0).source)
    }

    private fun lens(id: String, fov: Double, def: Boolean = false, phys: String? = null, minZoom: Double = 1.0) =
        PhoneOptics.Lens(id, phys, fov, PhoneOptics.Source.SENSOR, minZoom, if (phys != null) 1.0 else 8.0, def)

    @Test fun chooseLens() {
        val main = lens("0", 72.0, def = true)
        val uw = lens("0", 110.0, phys = "2")
        val tele = lens("3", 30.0)
        val all = listOf(tele, uw, main)
        assertEquals(main, PhoneOptics.choose(all) { it.widestFov >= 60 })
        assertEquals(uw, PhoneOptics.choose(all) { it.widestFov >= 90 })
        // Even the ultra-wide too narrow: the widest.
        assertEquals(uw, PhoneOptics.choose(all) { false })
        // A logical camera that zooms below 1× already is the ultra-wide.
        val logicalUw = lens("0", 72.0, def = true, minZoom = 0.5)
        assertTrue(logicalUw.widestFov > 110)
        assertEquals("0/2", uw.key)
        assertEquals(2, PhoneOptics.distinct(listOf(main, lens("0", 73.0, phys = "1"), uw)).size)
    }
}
