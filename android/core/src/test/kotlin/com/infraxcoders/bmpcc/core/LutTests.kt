package com.infraxcoders.bmpcc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LutTest {
    private fun identityCube(n: Int, extra: String = ""): String {
        val sb = StringBuilder("TITLE \"Identity\"\n# comment\nLUT_3D_SIZE $n\n$extra")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n)
            sb.append("${r / (n - 1.0)} ${g / (n - 1.0)} ${b / (n - 1.0)}\n")
        return sb.toString()
    }

    @Test fun parseAndApplyIdentity() {
        val lut = CubeParser.parse(identityCube(5), "file")
        assertEquals("Identity", lut.name)
        assertEquals(5, lut.size)
        val out = FloatArray(3)
        lut.apply(0.3f, 0.62f, 0.91f, out)
        assertEquals(0.3f, out[0], 1e-5f); assertEquals(0.62f, out[1], 1e-5f); assertEquals(0.91f, out[2], 1e-5f)
        lut.apply(1.5f, -0.2f, 1f, out) // clamped to the domain
        assertEquals(1f, out[0], 1e-6f); assertEquals(0f, out[1], 1e-6f)
        val px = intArrayOf(0xFF336699.toInt())
        lut.applyToPixels(px, LutInput.REC709)
        assertEquals(0xFF336699.toInt(), px[0])
    }

    @Test fun domainAnd1D() {
        // Domain 0..2: input 1.0 is the middle of the table.
        val lut = CubeParser.parse(identityCube(3, "DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n"), "d")
        val out = FloatArray(3)
        lut.apply(1f, 1f, 1f, out)
        assertEquals(0.5f, out[0], 1e-5f)
        // 1D LUT that inverts each channel.
        val one = CubeParser.parse("LUT_1D_SIZE 2\n1 1 1\n0 0 0\n", "invert")
        one.apply(0.25f, 0.5f, 1f, out)
        assertEquals(0.75f, out[0], 1e-3f); assertEquals(0.5f, out[1], 1e-3f); assertEquals(0f, out[2], 1e-3f)
    }

    @Test fun badFiles() {
        for (bad in listOf("hello", "LUT_3D_SIZE 2\n0 0 0\n", "LUT_3D_SIZE 300\n", "LUT_3D_SIZE 129\n", "0 0 0\nLUT_3D_SIZE 2\n")) {
            try { CubeParser.parse(bad, "x"); throw AssertionError("should fail: $bad") } catch (e: CubeParser.CubeException) { }
        }
    }

    @Test fun textureLayout() {
        val lut = CubeParser.parse(identityCube(5), "t")
        assertEquals(3, lut.tileColumns) // ceil(sqrt(5))
        assertEquals(15, lut.textureWidth); assertEquals(10, lut.textureHeight)
        val px = lut.texturePixels()
        // Slice b = 4 sits in tile (1, 1); its (r=4, g=0) texel is pure red + full blue.
        val p = px[(5 + 0) * 15 + 5 + 4]
        assertEquals(255, (p shr 16) and 0xFF); assertEquals(0, (p shr 8) and 0xFF); assertEquals(255, p and 0xFF)
        // 65-point LUTs fit a GPU texture (no side over 4096).
        assertTrue(9 * 65 <= 4096)
    }

    @Test fun gen5AndLooks() {
        assertEquals(0.3836, InputTransform.gen5Oetf(0.18f).toDouble(), 1e-3)
        // Continuous at the linear/log join.
        assertEquals(InputTransform.gen5Oetf(0.004999f).toDouble(), InputTransform.gen5Oetf(0.005f).toDouble(), 1e-4)
        assertEquals(0.5, InputTransform.displayToLinear(0.735357f).toDouble(), 1e-3)
        val bw = BuiltInLooks.all.first { it.name == "Black & white" }
        val out = FloatArray(3)
        bw.apply(0.8f, 0.2f, 0.1f, out)
        assertEquals(out[0], out[1], 1e-6f); assertEquals(out[1], out[2], 1e-6f)
        assertEquals(6, BuiltInLooks.all.size)
        assertEquals(64 * 16, BuiltInLooks.testChart(64, 16).size)
    }
}
