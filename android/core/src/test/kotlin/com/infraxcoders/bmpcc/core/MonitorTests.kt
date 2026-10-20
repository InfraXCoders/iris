package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTest {
    private val ma50 = Catalog.lens("arri_zeiss_master_anamorphic_50")!!

    @Test fun seriesFocals() {
        val f = FocalOptions.focals(ma50)
        assertEquals(listOf(28.0, 35.0, 40.0, 50.0, 60.0, 75.0, 100.0, 135.0, 180.0), f)
        assertEquals("arri_zeiss_master_anamorphic_75", FocalOptions.lensFor(ma50, 75.0).id)
        assertEquals(35.0, FocalOptions.step(f, 50.0, -2)!!, 0.0)
        assertEquals(180.0, FocalOptions.step(f, 135.0, 5)!!, 0.0)
        val sigma = BuiltInLibrary.lens("sigma_18_35_art")!!
        val z = FocalOptions.focals(sigma)
        assertEquals(18.0, z.first(), 0.0); assertEquals(35.0, z.last(), 0.0)
        assertTrue(z.contains(24.0))
        assertEquals(sigma, FocalOptions.lensFor(sigma, 24.0))
    }

    @Test fun shotSizes() {
        val mode = Catalog.camera("arri_alexa_35")!!.sensorModes[0]           // diag 33.95
        val f = FocalOptions.focals(ma50)
        assertEquals(28.0, FocalOptions.focalFor(ShotSize.WIDE, f, mode, 2.0)!!, 0.0)
        assertEquals(180.0, FocalOptions.focalFor(ShotSize.CLOSE, f, mode, 2.0)!!, 0.0)
        assertEquals(75.0, FocalOptions.focalFor(ShotSize.MEDIUM, f, mode, 2.0)!!, 0.0)  // 33.95*2*1.15 = 78
        val bm = BuiltInLibrary.camera("bmpcc_6k_pro")!!.sensorModes[0]       // diag 26.5 -> 30.5
        assertEquals(32.0, FocalOptions.focalFor(ShotSize.MEDIUM, FocalOptions.focals(BuiltInLibrary.lens("sigma_18_35_art")!!), bm)!!, 0.0)
    }

    @Test fun exposure() {
        assertEquals(0.0, Exposure.ev("400", "180°", "None", "T2.8"), 1e-9)
        assertEquals(1.0, Exposure.ev("800", "180°", "None", "f/2.8"), 1e-9)
        assertEquals(-2.0, Exposure.ev("400", "180°", "ND 0.6 (2 stops)", "2.8"), 1e-9)
        assertEquals(-1.0, Exposure.ev("400", "90°", "None", "2.8"), 1e-9)
        assertEquals(2.0, Exposure.ev("400", "180°", "None", "T1.4"), 1e-9)
        assertEquals(6, Exposure.compensationIndex(2.0, 1.0 / 3, -12, 12))
        assertEquals(-12, Exposure.compensationIndex(-9.0, 1.0 / 3, -12, 12))
    }

    @Test fun monitorFillsAreaWithCinemaFrame() {
        // Landscape phone screen 2400x1080, 4:3 stream filling it, 2.39 frame for BMPCC + Sigma 35.
        val ref = Framing.reference(BuiltInLibrary.camera("bmpcc_6k_pro")!!, BuiltInLibrary.lens("sigma_18_35_art")!!, 35.0, aspectRatio = 2.39)!!
        val video = Monitor.aspectFill(4.0 / 3.0, 2400.0, 1080.0)
        assertEquals(2400.0, video.width, 1e-9); assertEquals(1800.0, video.height, 1e-9)
        val view = PreviewView.fromFormat(70.0, 4.0 / 3.0, false)
        val area = Monitor.centredArea(2400.0, 1080.0, 220.0, 110.0)
        val l = Monitor.layout(ref, view, video, area, 0.5, 10.0)
        assertTrue(l.lines.fits)
        assertEquals(1.0, maxOf(l.lines.widthFraction, l.lines.heightFraction), 1e-9)   // edge to edge
        assertEquals(2.39, l.frame.width / l.frame.height, 1e-6)
        assertEquals(1200.0, l.frame.midX, 1e-9); assertEquals(540.0, l.frame.midY, 1e-9)
        // Longer lens -> more phone zoom, same frame on screen.
        val ref85 = Framing.reference(BuiltInLibrary.camera("bmpcc_6k_pro")!!, BuiltInLibrary.lens("sigma_18_35_art")!!, 70.0, aspectRatio = 2.39)!!
        val l2 = Monitor.layout(ref85, view, video, area, 0.5, 10.0)
        assertEquals(l.zoom * 2, l2.zoom, 1e-9)
        assertEquals(l.frame.width, l2.frame.width, 1e-6); assertEquals(l.frame.y, l2.frame.y, 1e-6)
        // Frame in the saved photo is centred.
        val f = Monitor.frameInPicture(l.frame, video)
        assertEquals(0.5, f.x + f.width / 2, 1e-9); assertEquals(0.5, f.y + f.height / 2, 1e-9)
    }

    @Test fun tooWideKeepsAspect() {
        // 14mm on full frame, 2.39:1, on a portrait phone: wider than the phone sees, frame keeps its shape.
        val cam = Catalog.camera("bmpcc_6k_pro")!!
        val lens = Catalog.lens("generic_prime_14")!!
        val ref = Framing.reference(cam, lens, 14.0, 2.39)!!
        val video = Monitor.aspectFill(3.0 / 4.0, 1080.0, 2340.0)
        val area = Monitor.centredArea(1080.0, 2340.0, 0.0, 600.0)
        val view = PreviewView.fromFormat(70.0, 4.0 / 3.0, true)
        val l = Monitor.layout(ref, view, video, area, 1.0, 10.0)
        assertTrue(!l.lines.fits)
        assertEquals(ref.deliveredAspect, l.frame.width / l.frame.height, 0.01)
        assertTrue(l.frame.width <= area.width + 0.01 && l.frame.height <= area.height + 0.01)
    }

    @Test fun wideFramesShowThePictureInside() {
        // Cadrage-style: a frame wider than the phone sees fits the screen, with the camera picture smaller inside it.
        val cam = Catalog.camera("bmpcc_6k_pro")!!
        val zoomLens = Catalog.lens(QuickRecce.GENERIC_ZOOM_ID)!!
        for (portrait in listOf(true, false)) for (aspect in listOf(1.78, 2.39, 1.33)) for (focal in listOf(12.0, 14.0, 24.0, 50.0, 135.0)) {
            val (sw, sh) = if (portrait) 1080.0 to 2340.0 else 2340.0 to 1080.0
            val video = Monitor.aspectFill(if (portrait) 3.0 / 4.0 else 4.0 / 3.0, sw, sh)
            val area = if (portrait) Monitor.centredArea(sw, sh, 4.0, 500.0) else Monitor.centredArea(sw, sh, 8.0, 8.0)
            // A phone whose widest lens sees 77 degrees across the long side, zoom 1..10.
            val view = PreviewView.fromFormat(77.0, 4.0 / 3.0, portrait)
            val ref = Framing.reference(cam, zoomLens, focal, aspect)!!
            val l = Monitor.layout(ref, view, video, area, 1.0, 10.0)
            val what = "${focal}mm $aspect ${if (portrait) "portrait" else "landscape"}"
            // 1. The frame always fits the screen area, with its true shape.
            assertTrue(what, l.frame.x >= area.x - 0.01 && l.frame.y >= area.y - 0.01)
            assertTrue(what, l.frame.x + l.frame.width <= area.x + area.width + 0.01 && l.frame.y + l.frame.height <= area.y + area.height + 0.01)
            assertEquals(what, ref.deliveredAspect, l.frame.width / l.frame.height, 0.01)
            if (l.lines.fits) {
                assertEquals(what, null, l.picture)
                assertEquals(what, 1.0 to 1.0, l.coverage)
            } else {
                // 2. The picture is centred in the frame, at the phone's widest zoom, its true size relative to the frame.
                val p = l.picture!!
                assertEquals(what, 1.0, l.zoom, 1e-9)
                assertEquals(what, l.frame.midX, p.midX, 1e-6); assertEquals(what, l.frame.midY, p.midY, 1e-6)
                assertEquals(what, Optics.halfTan(view.horizontalFov) / ref.tanHalfH, p.width / l.frame.width, 1e-6)
                assertEquals(what, Optics.halfTan(view.verticalFov) / ref.tanHalfV, p.height / l.frame.height, 1e-6)
                // The picture keeps the camera's shape and is narrower than the frame on at least one axis.
                assertEquals(what, if (portrait) 0.75 else 4.0 / 3.0, p.width / p.height, 1e-6)
                // Either the phone can't see all of the frame (dark area), or the picture is just drawn smaller so the
                // whole frame fits on screen (e.g. 24mm on a portrait screen with its control bars).
                assertTrue(what, l.coverage.first < 1 || l.coverage.second < 1 || p.width < video.width)
                // 3. Saved with a photo, the frame keeps its true size around the photo; centred either way.
                val f = Monitor.frameInPicture(l.frame, p)
                if (l.coverage.first < 1 || l.coverage.second < 1) assertTrue(what, f.width > 1 + 1e-9 || f.height > 1 + 1e-9)
                val c = ReferenceCanvas.of(f.x, f.y, f.width, f.height)
                assertEquals(what, 0.5, c.photo.midX, 1e-6); assertEquals(what, 0.5, c.photo.midY, 1e-6)
                assertEquals(what, 0.5, c.frame!!.midX, 1e-6); assertEquals(what, 0.5, c.frame!!.midY, 1e-6)
            }
        }
        // 12mm and 14mm on the Pocket 6K Pro are wider than this phone; 50 and 135 fit.
        val v = PreviewView.fromFormat(77.0, 4.0 / 3.0, false)
        val video = Monitor.aspectFill(4.0 / 3.0, 2340.0, 1080.0); val area = Monitor.centredArea(2340.0, 1080.0, 8.0, 8.0)
        assertTrue(Monitor.layout(Framing.reference(cam, zoomLens, 12.0, 2.39)!!, v, video, area, 1.0, 10.0).picture != null)
        assertTrue(Monitor.layout(Framing.reference(cam, zoomLens, 14.0, 1.78)!!, v, video, area, 1.0, 10.0).picture != null)
        assertTrue(Monitor.layout(Framing.reference(cam, zoomLens, 50.0, 2.39)!!, v, video, area, 1.0, 10.0).picture == null)
        assertTrue(Monitor.layout(Framing.reference(cam, zoomLens, 135.0, 2.39)!!, v, video, area, 1.0, 10.0).picture == null)
        // A normal frame inside the photo stays as it was.
        val inside = ReferenceCanvas.of(0.1, 0.2, 0.8, 0.5)
        assertEquals(1.0, inside.widthInPhotos, 1e-9); assertEquals(0.1, inside.frame!!.x, 1e-9)
        assertEquals(null, ReferenceCanvas.of(null, null, null, null).frame)
    }

    @Test fun calibration() {
        // A 1 m wide object at 2 m covering half the picture: tan(half) = 0.25 / 0.5 = 0.5 -> 53.13 degrees.
        assertEquals(53.13, Calibration.fovFromMeasurement(1.0, 2.0, 0.5)!!, 0.01)
        assertEquals(null, Calibration.fovFromMeasurement(1.0, 0.0, 0.5))
        // Short side 53.13 deg on a 4:3 picture -> long side tan(half) = 0.5 * 4/3.
        assertEquals(Optics.fovFromHalfTan(2.0 / 3.0), Calibration.otherSide(53.13010235, 4.0 / 3.0), 0.001)
        assertEquals(0.0, Calibration.errorPercent(60.0, 60.0), 1e-9)
        assertTrue(Calibration.errorPercent(62.0, 60.0) > 0)
    }
}
