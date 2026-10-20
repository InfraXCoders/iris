package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCropTest {
    private val fx3 = Catalog.camera("sony_fx3")!!
    private fun prime(mm: Int) = Catalog.lens("generic_prime_$mm")!!
    private fun shot(mm: Int, aspect: String) = RecceShot.create("sc", "1", fx3, prime(mm)).copy(sensorModeId = "ff_4k", aspectRatio = aspect)

    /** A phone whose camera sees 77° across the long side of a 4:3 picture, zoom 1…10. */
    private class Phone(val portrait: Boolean) {
        val photoW = if (portrait) 3000 else 4000
        val photoH = if (portrait) 4000 else 3000
        val view = PreviewView.fromFormat(77.0, 4.0 / 3.0, portrait)
        val screenW = if (portrait) 1080.0 else 2340.0
        val screenH = if (portrait) 2340.0 else 1080.0
        val video = Monitor.aspectFill(if (portrait) 3.0 / 4.0 else 4.0 / 3.0, screenW, screenH)
        val area = Monitor.centredArea(screenW, screenH, 8.0, if (portrait) 500.0 else 8.0)
    }

    /** What the viewfinder saves when a photo is taken: the frame in the photo, and the shot's settings then. */
    private fun capture(sh: RecceShot, phone: Phone): Pair<ShotReference, Double> {
        val l = Monitor.layout(sh.reference!!, phone.view, phone.video, phone.area, 1.0, 10.0)
        val f = Monitor.frameInPicture(l.frame, l.picture ?: phone.video)
        val ref = ShotReference(
            filePath = "x.jpg", frameX = f.x, frameY = f.y, frameWidth = f.width, frameHeight = f.height,
            cameraId = sh.baseCamera.id, sensorModeId = sh.sensorMode.id, lensId = sh.lens.id,
            focalLength = sh.focalLength, aspectRatio = sh.aspectRatio, calibrated = false,
        )
        // tan(half angle) across the photo's width at the zoom it was taken with.
        val tanPhotoH = Optics.halfTan(phone.view.horizontalFov) / l.zoom
        return ref to tanPhotoH
    }

    @Test fun unchangedSettingsKeepTheSavedFrame() {
        for (mm in listOf(18, 35, 135)) for (a in listOf("16:9", "1.85:1")) {
            val sh = shot(mm, a)
            val (ref, _) = capture(sh, Phone(false))
            val c = ReferenceCrop.forShot(ref, sh)
            assertEquals("$mm $a", ReferenceCrop.Basis.CAPTURED, c.basis)
            assertEquals(ref.frameWidth!!, c.frame.width, 1e-12); assertEquals(ref.frameX!!, c.frame.x, 1e-12)
            // The crop has the cinema frame's shape in photo pixels.
            assertEquals("$mm $a", sh.reference!!.deliveredAspect, c.aspect(4000, 3000), 1e-6)
        }
    }

    @Test fun cropFollowsLaterChanges() {
        for (portrait in listOf(false, true)) {
            val phone = Phone(portrait)
            for (from in listOf(18, 35, 135)) for (to in listOf(18, 35, 135)) for (a0 in listOf("16:9", "1.85:1")) for (a1 in listOf("16:9", "1.85:1")) {
                val taken = shot(from, a0)
                val (ref, tanPhotoH) = capture(taken, phone)
                val now = shot(to, a1)
                val c = ReferenceCrop.forShot(ref, now)
                val what = "$from $a0 -> $to $a1 ${if (portrait) "portrait" else "landscape"}"
                assertEquals(what, if (from == to && a0 == a1) ReferenceCrop.Basis.CAPTURED else ReferenceCrop.Basis.RECALCULATED, c.basis)
                // Independent check: the crop covers exactly the new frame's angle of view on the photo…
                assertEquals(what, now.reference!!.tanHalfH / tanPhotoH, c.frame.width, 1e-9)
                // …with the new frame's shape, centred where the frame was.
                assertEquals(what, now.reference!!.deliveredAspect, c.aspect(phone.photoW, phone.photoH), 1e-6)
                assertEquals(what, ref.frameX!! + ref.frameWidth!! / 2, c.frame.midX, 1e-9)
                assertEquals(what, ref.frameY!! + ref.frameHeight!! / 2, c.frame.midY, 1e-9)
            }
        }
    }

    @Test fun lensFocalLengthsScaleTheCrop() {
        val (ref, _) = capture(shot(35, "16:9"), Phone(false))
        val c135 = ReferenceCrop.forShot(ref, shot(135, "16:9"))
        val c18 = ReferenceCrop.forShot(ref, shot(18, "16:9"))
        // Same sensor and aspect: the crop scales with 1 / focal length.
        assertEquals(35.0 / 135.0, c135.frame.width / ref.frameWidth!!, 1e-9)
        assertEquals(35.0 / 18.0, c18.frame.width / ref.frameWidth!!, 1e-9)
        assertTrue(!c135.needsPadding)
        // 18mm on a full-frame FX3 (89° wide) is wider than this phone sees (77°): dark padding, nothing invented.
        assertTrue(c18.needsPadding)
        val part = c18.photoPart!!
        assertTrue(part.x >= 0 && part.y >= 0 && part.x + part.width <= 1 + 1e-9 && part.y + part.height <= 1 + 1e-9)
    }

    @Test fun aspectRatioChangeKeepsWidth() {
        // FX3 full-frame 4K is 35.6 x 20.03 mm: 16:9 uses the full height, 1.85:1 masks it; the width stays.
        val (ref, _) = capture(shot(35, "16:9"), Phone(false))
        val c = ReferenceCrop.forShot(ref, shot(35, "1.85:1"))
        assertEquals(ref.frameWidth!!, c.frame.width, 1e-9)
        assertTrue(c.frame.height < ref.frameHeight!!)
        assertEquals(1.85, c.aspect(4000, 3000), 1e-6)
    }

    @Test fun missingDataIsNeverInvented() {
        val sh = shot(35, "16:9")
        // An older photo: frame saved, capture settings unknown -> the frame as captured, even if the shot changed.
        val old = ShotReference(filePath = "a.jpg", frameX = 0.1, frameY = 0.2, frameWidth = 0.8, frameHeight = 0.45)
        val c = ReferenceCrop.forShot(old, shot(135, "16:9"))
        assertEquals(ReferenceCrop.Basis.CAPTURED, c.basis)
        assertEquals(0.8, c.frame.width, 0.0)
        // Unknown lens id in the capture settings: same.
        assertNull(ReferenceCrop.captureReference(old.copy(cameraId = "sony_fx3", lensId = "no_such_lens", focalLength = "35mm", aspectRatio = "16:9")))
        // No frame saved: the whole photo, nothing cropped.
        val none = ReferenceCrop.forShot(ShotReference(filePath = "b.jpg"), sh)
        assertEquals(ReferenceCrop.Basis.NO_FRAME, none.basis)
        assertEquals(ScreenRect(0.0, 0.0, 1.0, 1.0), none.frame)
        // Broken numbers count as no frame.
        assertEquals(ReferenceCrop.Basis.NO_FRAME, ReferenceCrop.forShot(old.copy(frameWidth = Double.NaN), sh).basis)
        assertEquals(ReferenceCrop.Basis.NO_FRAME, ReferenceCrop.forShot(old.copy(frameHeight = 0.0), sh).basis)
    }

    @Test fun pixelPlanInsideThePhoto() {
        // A 2.39-ish crop inside a 4000x3000 photo: no padding, the output is exactly the photo pixels, scaled down.
        val crop = ScreenRect(0.1, 0.3, 0.8, 0.8 * 4000 / 3000 / 2.39 * 0.75)
        val p = ReferenceCrop.pixelPlan(crop, 4000, 3000, 1600)
        assertEquals(1600, p.outWidth)
        assertEquals(1600.0 / (crop.width * 4000) * crop.height * 3000, p.outHeight.toDouble(), 1.0)
        assertEquals(ReferenceCrop.IntRect(400, 900, 3600, Math.round((0.3 + crop.height) * 3000).toInt()), p.src)
        assertEquals(ReferenceCrop.IntRect(0, 0, p.outWidth, p.outHeight), p.dst)
        // Small crops are not enlarged.
        val small = ReferenceCrop.pixelPlan(ScreenRect(0.45, 0.45, 0.1, 0.1), 4000, 3000, 1600)
        assertEquals(400, small.outWidth); assertEquals(300, small.outHeight)
    }

    @Test fun pixelPlanWiderThanThePhoto() {
        // A frame 1.5x the photo's width and height, centred: the photo fills the middle two thirds, dark around.
        val p = ReferenceCrop.pixelPlan(ScreenRect(-0.25, -0.25, 1.5, 1.5), 4000, 3000, 1200)
        assertEquals(1200, p.outWidth); assertEquals(900, p.outHeight)
        assertEquals(ReferenceCrop.IntRect(0, 0, 4000, 3000), p.src)
        assertEquals(ReferenceCrop.IntRect(200, 150, 1000, 750), p.dst)
        // The photo keeps its shape inside the padding.
        assertEquals(4000.0 / 3000, p.dst!!.width.toDouble() / p.dst!!.height, 0.01)
        // A crop entirely outside the photo has nothing to draw.
        val off = ReferenceCrop.pixelPlan(ScreenRect(1.2, 0.0, 0.5, 0.5), 4000, 3000)
        assertNull(off.src); assertNull(off.dst)
        assertNotNull(ReferenceCrop.pixelPlan(ScreenRect(0.9, 0.0, 0.5, 0.5), 4000, 3000).src)
    }
}
