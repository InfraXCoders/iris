package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCharacterTest {
    @Test fun depthOfField() {
        // 50mm f/2.8 at 3 m, CoC 0.029 mm (full frame): standard tables give about 2.74–3.32 m, hyperfocal ≈ 30.8 m.
        val r = Focus.depthOfField(50.0, 2.8, 3.0, 0.029)!!
        assertEquals(30.84, r.hyperfocalM, 0.05)
        assertEquals(2.74, r.nearM, 0.01)
        assertEquals(3.32, r.farM, 0.01)
        // At the hyperfocal distance the far limit is infinity and near is about half of it.
        val h = Focus.depthOfField(50.0, 2.8, r.hyperfocalM, 0.029)!!
        assertTrue(h.farM.isInfinite())
        assertEquals(r.hyperfocalM / 2, h.nearM, 0.1)
        assertTrue(Focus.depthOfField(50.0, 2.8, 0.3, 0.029, closeFocusM = 0.45)!!.tooClose)
        assertNull(Focus.depthOfField(50.0, 0.0, 3.0, 0.029))
        assertEquals("85 cm", Focus.distanceText(0.85)); assertEquals("2.4 m", Focus.distanceText(2.4)); assertEquals("∞", Focus.distanceText(Double.POSITIVE_INFINITY))
        val pocket6k = Catalog.camera("bmpcc_6k_pro")!!.sensorModes[0]
        assertEquals(0.0177, Focus.circleOfConfusionMm(pocket6k), 0.001)
    }

    @Test fun distortionProfiles() {
        assertTrue(Distortion.points.size > 100)
        // Every profile belongs to a lens in the database.
        assertTrue(Distortion.points.all { Catalog.lens(it.lensId) != null })
        val sigma = Catalog.lens("sigma_art_18_35_f18")!!
        assertTrue(Distortion.hasProfile(sigma))
        val wide = Distortion.modelFor(sigma, 18.0)!!
        val tele = Distortion.modelFor(sigma, 35.0)!!
        // Undistort is the inverse of distort.
        for (r in listOf(0.05, 0.3, 0.6)) assertEquals(r, wide.undistort(wide.distort(r)), 1e-9)
        // Barrel at 18mm, pincushion at 35mm on a Pocket 6K sensor.
        assertTrue(Distortion.cornerPercent(wide, 23.1, 12.99, 18.0) < 0)
        assertTrue(Distortion.cornerPercent(tele, 23.1, 12.99, 35.0) > 0)
        // In between, the model is interpolated.
        assertNotNull(Distortion.modelFor(sigma, 27.0))
        // The outline: 4 sides × points; barrel puts the corner direction outside the perfect rectangle.
        val out = Distortion.frameOutline(wide, 23.1, 12.99, 18.0, perSide = 8)
        assertEquals(32, out.size)
        assertTrue(kotlin.math.abs(out[0].first) > 23.1 / 2 / 18.0)
        // Cine lenses have no published distortion data.
        assertFalse(Distortion.hasProfile(Catalog.lens("arri_signature_prime_47")!!))
        assertNull(Distortion.modelFor(Catalog.lens("arri_signature_prime_47")!!, 47.0))
        assertTrue(Distortion.withinCalibration(sigma, 23.1, 12.99))
    }

    @Test fun shotOptics() {
        val cam = Catalog.camera("bmpcc_6k_pro")!!
        val shot = RecceShot.create("s", "1", cam, Catalog.lens("sigma_art_18_35_f18")!!).copy(aperture = "T2.8", estimatedDistance = 3.0)
        val dof = ShotOptics.depthOfField(shot)!!
        assertTrue(dof.nearM < 3.0 && dof.farM > 3.0)
        assertTrue(ShotOptics.dofText(dof).startsWith("DoF "))
        assertTrue(ShotOptics.distortionPercent(shot)!! < 0)
        assertNull(ShotOptics.depthOfField(shot.copy(estimatedDistance = null)))
        val cine = RecceShot.create("s", "1", cam, Catalog.lens("arri_signature_prime_47")!!)
        assertNull(ShotOptics.distortionPercent(cine))
    }

    @Test fun userMeasuredDistortion() {
        val lens = Catalog.lens("arri_signature_prime_47")!!
        val k1 = Distortion.k1FromCornerPercent(-2.0, 23.1, 12.99, 47.0)
        Distortion.custom = mapOf(lens.id to listOf(47.0 to k1))
        try {
            assertTrue(Distortion.hasProfile(lens)); assertTrue(Distortion.isCustom(lens))
            val m = Distortion.modelFor(lens, 47.0)!!
            assertEquals(-2.0, Distortion.cornerPercent(m, 23.1, 12.99, 47.0), 1e-6)
            assertTrue(Distortion.withinCalibration(lens, 23.1, 12.99))
            assertFalse(Distortion.isCustom(Catalog.lens("sigma_art_18_35_f18")!!))
        } finally { Distortion.custom = emptyMap() }
        assertFalse(Distortion.hasProfile(lens))
    }
}
