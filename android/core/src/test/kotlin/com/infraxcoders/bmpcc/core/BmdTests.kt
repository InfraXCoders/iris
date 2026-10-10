package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmdTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    /** Examples from Blackmagic's Camera Control protocol document. */
    @Test fun documentExamples() {
        assertEquals("FF 04 00 00 00 01 00 00", hex(Bmd.encode(Bmd.autoFocus())))
        assertEquals("FF 08 00 00 01 02 02 00 94 11 00 00", hex(Bmd.encode(Bmd.whiteBalance(4500, 0))))
        assertEquals("FF 06 00 00 00 08 80 01 33 01 00 00", hex(Bmd.encode(Bmd.Command(0, 8, Bmd.Type.FIXED16, 1, byteArrayOf(0x33, 0x01)))))
        assertEquals(307, Bmd.toFixed16(0.15))
    }

    @Test fun commands() {
        // Record = transport mode 2 (the old app sent 1, which is Play).
        assertEquals("FF 09 00 00 0A 01 01 00 02 00 00 00 00 00 00 00", hex(Bmd.encode(Bmd.record(true))))
        assertEquals("FF 08 00 00 01 0E 03 00 20 03 00 00", hex(Bmd.encode(Bmd.iso(800))))
        assertEquals("FF 08 00 00 01 0B 03 00 50 46 00 00", hex(Bmd.encode(Bmd.shutterAngle(180.0))))
        // f/2.8 -> AV 2.97 -> fixed16 6085 (0x17C5)
        val ap = Bmd.encode(Bmd.aperture(2.8))
        assertEquals("FF 06 00 00 00 02 80 00", hex(ap.copyOfRange(0, 8)))
        assertEquals(2.8, Bmd.fNumber(Bmd.parse(ap)[0].fixed16()), 0.01)
        assertEquals("FF 06 00 00 01 10 80 00 00 10 00 00", hex(Bmd.encode(Bmd.ndStops(2.0))))
    }

    @Test fun parseAndDecode() {
        val packet = Bmd.encode(Bmd.iso(1600)) + Bmd.encode(Bmd.shutterAngle(172.8)) + Bmd.encode(Bmd.whiteBalance(3200, -5)) + Bmd.encode(Bmd.record(true))
        val cmds = Bmd.parse(packet)
        assertEquals(4, cmds.size)
        assertEquals(Bmd.Update.Iso(1600), Bmd.decode(cmds[0]))
        assertEquals(Bmd.Update.ShutterAngle(172.8), Bmd.decode(cmds[1]))
        assertEquals(Bmd.Update.WhiteBalance(3200, -5), Bmd.decode(cmds[2]))
        assertEquals(Bmd.Update.Recording(true), Bmd.decode(cmds[3]))
        assertTrue(Bmd.parse(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test fun timecodeAndStatus() {
        assertEquals("01:23:45:12", Bmd.timecode(byteArrayOf(0x12, 0x45, 0x23, 0x01)))
        val s = Bmd.Status(0x23)
        assertTrue(s.poweredOn && s.connected && s.ready)
        assertEquals("ND 0.6 (2 stops)", Bmd.ndPreset(2.0))
        assertEquals("None", Bmd.ndPreset(0.0))
        assertTrue(Bmd.ndPreset(4.0) in ShotPresets.ndFilters)
    }

    @Test fun lutFormatBattery() {
        assertEquals("FF 06 00 00 01 0F 01 00 02 01 00 00", hex(Bmd.encode(Bmd.displayLut(Bmd.Lut.FILM_TO_VIDEO))))
        assertEquals("FF 06 00 00 01 0F 01 00 00 00 00 00", hex(Bmd.encode(Bmd.displayLut(Bmd.Lut.NONE))))
        val fmt = Bmd.encode(Bmd.recordingFormat(25, 25, 6144, 3456, 0))
        assertEquals(Bmd.Update.Format(25, 6144, 3456, 25, 0), Bmd.decode(Bmd.parse(fmt)[0]))
        assertEquals(Bmd.Update.DisplayLut(Bmd.Lut.FILM_TO_VIDEO, true), Bmd.decode(Bmd.parse(Bmd.encode(Bmd.displayLut(Bmd.Lut.FILM_TO_VIDEO)))[0]))
        // 9.0 battery: 7400 mV, 87 %, flags
        val bat = Bmd.Command(9, 0, Bmd.Type.INT16, 0, byteArrayOf(0xE8.toByte(), 0x1C, 87, 0, 7, 0))
        assertEquals(Bmd.Update.Battery(87), Bmd.decode(Bmd.parse(Bmd.encode(bat))[0]))
        // Out-of-range percent is ignored rather than shown.
        assertEquals(null, Bmd.decode(Bmd.Command(9, 0, Bmd.Type.INT16, 0, byteArrayOf(0, 0, 0xC8.toByte(), 0))))
    }

    @Test fun quickRecce() {
        assertEquals(7, QuickRecce.genericLenses.count { !it.isZoom })
        assertEquals(QuickRecce.primeFocals, FocalOptions.focals(Catalog.lens("generic_prime_35")!!))
        assertEquals("generic_prime_35", QuickRecce.lensFor(QuickRecce.LensKind.PRIME, null).id)
        assertEquals(QuickRecce.GENERIC_ZOOM_ID, QuickRecce.lensFor(QuickRecce.LensKind.ZOOM, Catalog.lens("generic_prime_35")).id)
        val ana = QuickRecce.lensFor(QuickRecce.LensKind.ANAMORPHIC, null)
        assertTrue(ana.isAnamorphic)
        assertEquals(35.0, QuickRecce.nearestFocal(Catalog.lens("generic_prime_50")!!, 33.0), 0.01)
        assertEquals("Pocket 6K Pro", QuickRecce.shortName(Catalog.camera("bmpcc_6k_pro")!!))
        for ((id, _) in QuickRecce.quickCameras) assertTrue(id, Catalog.camera(id) != null)
        for (f in QuickRecce.frames) assertTrue(f, f in ShotPresets.aspectRatios)
    }
}
