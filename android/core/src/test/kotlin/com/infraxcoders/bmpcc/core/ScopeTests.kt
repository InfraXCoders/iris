package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ScopeTests {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun falseColourBands() {
        assertEquals("Purple", FalseColour.bandFor(0.01)?.name)
        assertEquals("Blue", FalseColour.bandFor(0.03)?.name)
        assertEquals("Green", FalseColour.bandFor(0.40)?.name)
        assertEquals("Pink", FalseColour.bandFor(0.54)?.name)
        assertEquals("Yellow", FalseColour.bandFor(0.98)?.name)
        assertEquals("Red", FalseColour.bandFor(1.0)?.name)
        assertNull(FalseColour.bandFor(0.2))
        assertEquals(grey(51), FalseColour.colour(0.2))
        assertEquals("38–42%", FalseColour.bands[2].label)
        assertEquals("2.5–4%", FalseColour.bands[1].label)
    }

    @Test fun zebraThreshold() {
        assertTrue(Zebra.striped(0.96, 95))
        assertTrue(!Zebra.striped(0.94, 95))
        assertTrue(!Zebra.striped(0.985, 100))
        assertTrue(Zebra.striped(0.995, 100))
    }

    @Test fun lumaRec709() {
        assertEquals(1.0, Scopes.luma(grey(255)), 1e-9)
        assertEquals(0.7152, Scopes.luma(0xFF00FF00.toInt()), 1e-4)
    }

    @Test fun histogramAndClipping() {
        val px = IntArray(100) { if (it < 10) grey(255) else if (it < 30) grey(0) else grey(128) }
        val h = Scopes.histogram(ScopeFrame(10, 10, px), bins = 64)
        assertEquals(100, h.total)
        assertEquals(10, h.y[63])
        assertEquals(20, h.y[0])
        assertEquals(0.10, h.clipped, 1e-9)
        assertEquals(0.20, h.crushed, 1e-9)
    }

    @Test fun waveformColumns() {
        // Left half black, right half white.
        val px = IntArray(8 * 4) { i -> if (i % 8 < 4) grey(0) else grey(255) }
        val w = Scopes.waveform(ScopeFrame(8, 4, px), columns = 2, levels = 10)
        assertEquals(16, w.channel(0)[0 * 10 + 0])
        assertEquals(16, w.channel(0)[1 * 10 + 9])
        val p = Scopes.waveform(ScopeFrame(8, 4, IntArray(32) { 0xFFFF0000.toInt() }), columns = 1, levels = 10, parade = true)
        assertEquals(32, p.channel(0)[9]); assertEquals(32, p.channel(1)[0]); assertEquals(32, p.channel(2)[0])
        val img = Scopes.waveformImage(w)
        assertEquals(2 * 10, img.size)
        assertTrue(img[9 * 2 + 0] != 0) // black at the bottom-left
        assertTrue(img[0 * 2 + 1] != 0) // white at the top-right
    }

    @Test fun rotationMapping() {
        // Source 4×2 (landscape sensor). Rotated 90° clockwise, the upright top-left is the source's bottom-left.
        assertEquals(0 to 1, ScopeFrame.sourcePoint(0.0, 0.0, 90, 4, 2))
        assertEquals(3 to 0, ScopeFrame.sourcePoint(0.99, 0.99, 90, 4, 2))
        assertEquals(3 to 0, ScopeFrame.sourcePoint(0.0, 0.0, 270, 4, 2))
        assertEquals(3 to 1, ScopeFrame.sourcePoint(0.0, 0.0, 180, 4, 2))
        assertEquals(0 to 0, ScopeFrame.sourcePoint(0.0, 0.0, 0, 4, 2))
    }

    @Test fun sampleRgbaBuffer() {
        // 2×1 RGBA with row padding: red, blue.
        val buf = ByteBuffer.allocate(12)
        buf.put(byteArrayOf(-1, 0, 0, -1, 0, 0, -1, -1, 9, 9, 9, 9)); buf.rewind()
        val f = ScopeFrame.fromRgba(buf, rowStride = 12, pixelStride = 4, srcW = 2, srcH = 1, rotationDegrees = 0, outW = 2, outH = 1)
        assertEquals(0xFFFF0000.toInt(), f.pixels[0])
        assertEquals(0xFF0000FF.toInt(), f.pixels[1])
        val r = ScopeFrame.fromRgba(buf, 12, 4, 2, 1, 90, outW = 1, outH = 2)
        assertEquals(0xFFFF0000.toInt(), r.pixels[0])
        assertEquals(0xFF0000FF.toInt(), r.pixels[1])
    }

    @Test fun cropToFrame() {
        val c = Scopes.crop(ScreenRect(100.0, 200.0, 800.0, 400.0), ScreenRect(0.0, -100.0, 1000.0, 1000.0))
        assertEquals(0.1f, c[0], 1e-6f); assertEquals(0.3f, c[1], 1e-6f); assertEquals(0.9f, c[2], 1e-6f); assertEquals(0.7f, c[3], 1e-6f)
        assertEquals(1f, Scopes.crop(null, ScreenRect(0.0, 0.0, 10.0, 10.0))[2], 0f)
        assertEquals("99–100%", FalseColour.bands.last().label)
    }

    @Test fun cpuPictureTools() {
        val f = ScopeFrame(4, 3, IntArray(12) { if (it % 4 < 2) grey(0) else grey(255) })
        val fc = PictureProcessor.process(f, null, LutInput.REC709, 0.0, true, null, null, PeakingColour.RED)
        assertEquals(FalseColour.bands.first().argb, fc[0]); assertEquals(FalseColour.bands.last().argb, fc[3])
        val pk = PictureProcessor.process(f, null, LutInput.REC709, 0.0, false, null, PeakingLevel.HIGH, PeakingColour.GREEN)
        assertEquals(PeakingColour.GREEN.argb, pk[1 * 4 + 1]); assertEquals(grey(0), pk[0])
        val z = PictureProcessor.process(f, null, LutInput.REC709, 0.0, false, 95, null, PeakingColour.RED, stripePx = 2)
        assertEquals(0xFFFFFFFF.toInt(), z[1 * 4 + 3]) // (x + y) even: white stripe
        assertEquals(grey(89), z[1 * 4 + 2]) // odd: darkened (255 × 35 %)
        assertEquals(grey(0), z[0])
        // A LUT right of the split only.
        val bw = BuiltInLooks.all.first { it.name == "Black & white" }
        val col = ScopeFrame(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt()))
        val split = PictureProcessor.process(col, bw, LutInput.REC709, 0.5, false, null, null, PeakingColour.RED)
        assertEquals(0xFFFF0000.toInt(), split[0]); assertTrue(split[1] != 0xFFFF0000.toInt())
    }

    @Test fun exposureMatch() {
        assertEquals(1 / 48.0, ExposureMatch.exposureSeconds("180°", "24")!!, 1e-12)
        assertEquals(1 / 50.0, ExposureMatch.exposureSeconds("1/50", "24")!!, 1e-12)
        // ISO 800, 1/48 s, T2.8, no ND on a f/1.8 phone: ISO·t = 800/48·(1.8/2.8)².
        val k = ExposureMatch.target("800", "180°", "24", "None", "T2.8", 1.8)!!
        assertEquals(800 / 48.0 * (1.8 / 2.8) * (1.8 / 2.8), k, 1e-9)
        val p = ExposureMatch.phone(k, 50, 3200, 10_000, 200_000_000)
        assertEquals(0.0, p.stopsOff, 0.05)
        assertTrue(p.exposureNs <= 33_333_333)
        // With the camera's own 1/48 s: the phone uses 1/48 s too.
        val same = ExposureMatch.phone(k, 50, 3200, 10_000, 200_000_000, preferredSeconds = 1 / 48.0)
        assertEquals(1e9 / 48, same.exposureNs.toDouble(), 2e5)
        assertEquals(0.0, same.stopsOff, 0.05)
        // ND 6 stops more: still reachable with a shorter time.
        val k2 = ExposureMatch.target("800", "180°", "24", "ND 1.8 (6 stops)", "T2.8", 1.8)!!
        assertEquals(k / 64, k2, 1e-9)
        // Far too bright for the phone: reports how many stops off (negative = phone brighter than real).
        val bright = ExposureMatch.phone(1e-7, 50, 3200, 10_000, 200_000_000)
        assertTrue(bright.stopsOff < -5)
        assertNull(ExposureMatch.target("", "180°", "24", "None", "T2.8", 1.8))
    }
}
