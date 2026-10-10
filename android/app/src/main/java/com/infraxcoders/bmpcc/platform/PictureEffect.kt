package com.infraxcoders.bmpcc.platform

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.infraxcoders.bmpcc.core.FalseColour
import com.infraxcoders.bmpcc.core.Lut3D
import com.infraxcoders.bmpcc.core.LutInput
import com.infraxcoders.bmpcc.core.PeakingColour
import com.infraxcoders.bmpcc.core.PeakingLevel
import com.infraxcoders.bmpcc.core.Zebra
import java.util.Locale

/** Exposure / focus tools drawn on the live picture. */
data class PictureTools(
    val falseColour: Boolean = false,
    /** Zebra level in %, or null for no zebras. */
    val zebraLevel: Int? = null,
    /** Focus peaking sensitivity, or null for no peaking. */
    val peaking: PeakingLevel? = null,
    val peakingColour: PeakingColour = PeakingColour.RED,
) {
    val any: Boolean get() = falseColour || zebraLevel != null || peaking != null
}

/**
 * The live camera picture, processed on the GPU: the shot's LUT, then false colour and zebras on the graded
 * picture (what you see), and focus peaking from the ungraded picture's edges. An AGSL shader (Android 13+) applied
 * to the preview view as a RenderEffect. The 3D LUT is a 2D texture of blue slices; red/green are interpolated by the
 * texture sampler and blue between two slices (trilinear). A monitoring preview, not a colour-managed pipeline.
 */
object PictureEffect {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    private fun f(v: Double) = String.format(Locale.US, "%.4f", v)
    private fun rgb(argb: Int) = String.format(
        Locale.US, "float3(%.3f, %.3f, %.3f)",
        ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f,
    )

    /** False colour bands from the core table, so the shader and the legend always agree. */
    private val falseColourCode: String = FalseColour.bands.joinToString("\n") { b ->
        "    if (y >= ${f(b.low)} && y < ${f(b.high)}) { return ${rgb(b.argb)}; }"
    }

    private val AGSL = """
uniform shader content;
uniform shader lut;
uniform float hasLut;
uniform float size;
uniform float cols;
uniform float split;
uniform float gen5;
uniform float3 dmin;
uniform float3 dmax;
uniform float falseColour;
uniform float zebra;
uniform float stripe;
uniform float peaking;
uniform float pstep;
uniform float3 pcol;

float3 slice(float2 rg, float b) {
    // +0.5 keeps the division safe on GPUs whose division is slightly inexact.
    float ty = floor((b + 0.5) / cols);
    float tx = b - ty * cols;
    return lut.eval(float2(tx * size + rg.x + 0.5, ty * size + rg.y + 0.5)).rgb;
}

float toLinear(float v) {
    return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4);
}

float toGen5(float v) {
    float x = toLinear(v);
    return x < 0.005 ? 8.283605932402494 * x + 0.09246575342465753
                     : 0.08692876065491224 * log(x + 0.005494072432257808) + 0.5300133392291939;
}

float3 grade(float3 rgb) {
    if (gen5 > 0.5) { rgb = float3(toGen5(rgb.r), toGen5(rgb.g), toGen5(rgb.b)); }
    rgb = clamp((rgb - dmin) / (dmax - dmin), 0.0, 1.0);
    float3 s = rgb * (size - 1.0);
    float b0 = floor(s.b);
    float b1 = min(b0 + 1.0, size - 1.0);
    float3 lo = slice(s.rg, b0);
    float3 hi = slice(s.rg, b1);
    return mix(lo, hi, s.b - b0);
}

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float3 falseColourOf(float y) {
$falseColourCode
    return float3(y);
}

float lumaAt(float2 p) { return luma(clamp(float3(content.eval(p).rgb), 0.0, 1.0)); }

half4 main(float2 p) {
    half4 c = content.eval(p);
    float3 rgb = clamp(float3(c.rgb) / max(float(c.a), 0.0001), 0.0, 1.0);
    if (hasLut > 0.5 && p.x >= split) { rgb = grade(rgb); }
    float y = luma(rgb);
    if (falseColour > 0.5) { rgb = falseColourOf(y); }
    if (zebra > 0.0 && y >= zebra) {
        if (mod(p.x + p.y, stripe) < stripe * 0.5) { rgb = float3(1.0) - rgb * 0.15; } else { rgb = rgb * 0.35; }
    }
    if (peaking > 0.0) {
        float gx = lumaAt(p + float2(pstep, 0.0)) - lumaAt(p - float2(pstep, 0.0));
        float gy = lumaAt(p + float2(0.0, pstep)) - lumaAt(p - float2(0.0, pstep));
        if (sqrt(gx * gx + gy * gy) > peaking) { rgb = pcol; }
    }
    return half4(half3(rgb), 1.0) * c.a;
}
"""

    private var shader: Any? = null
    private var shaderLut: Lut3D? = null
    private var hasLutTexture = false

    /** False after the shader failed to build or run on this phone (then the picture is shown plain). */
    var working by mutableStateOf(true)
        private set

    @RequiresApi(33)
    private fun shaderFor(lut: Lut3D?): RuntimeShader {
        val s = (shader as? RuntimeShader) ?: RuntimeShader(AGSL).also { shader = it; shaderLut = null; hasLutTexture = false }
        if (lut != null && lut !== shaderLut) {
            val bmp = Bitmap.createBitmap(lut.texturePixels(), lut.textureWidth, lut.textureHeight, Bitmap.Config.ARGB_8888)
            s.setInputShader("lut", BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            s.setFloatUniform("size", lut.size.toFloat())
            s.setFloatUniform("cols", lut.tileColumns.toFloat())
            s.setFloatUniform("dmin", lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
            s.setFloatUniform("dmax", lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])
            shaderLut = lut; hasLutTexture = true
        } else if (!hasLutTexture) {
            // Every input needs a shader, even unused.
            val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            s.setInputShader("lut", BitmapShader(one, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            s.setFloatUniform("size", 2f); s.setFloatUniform("cols", 2f)
            s.setFloatUniform("dmin", 0f, 0f, 0f); s.setFloatUniform("dmax", 1f, 1f, 1f)
            hasLutTexture = true
        }
        return s
    }

    /**
     * Shows [lut] and [tools] on [view] (nothing to show removes the effect). Pixels left of [splitPx] stay ungraded
     * (LUT before / after); the tools cover the whole picture.
     */
    fun apply(view: View, lut: Lut3D?, input: LutInput, splitPx: Float, tools: PictureTools) {
        if (!supported || !working) return
        runCatching { applyApi33(view, lut, input, splitPx, tools) }.onFailure {
            working = false
            runCatching { view.setRenderEffect(null) }
        }
    }

    @RequiresApi(33)
    private fun applyApi33(view: View, lut: Lut3D?, input: LutInput, splitPx: Float, tools: PictureTools) {
        if (lut == null && !tools.any) { view.setRenderEffect(null); return }
        val s = shaderFor(lut)
        val density = view.resources.displayMetrics.density
        s.setFloatUniform("hasLut", if (lut != null) 1f else 0f)
        s.setFloatUniform("split", splitPx)
        s.setFloatUniform("gen5", if (input == LutInput.BMD_FILM_GEN5) 1f else 0f)
        s.setFloatUniform("falseColour", if (tools.falseColour) 1f else 0f)
        s.setFloatUniform("zebra", tools.zebraLevel?.let { Zebra.threshold(it).toFloat() } ?: 0f)
        s.setFloatUniform("stripe", 10f * density)
        s.setFloatUniform("peaking", tools.peaking?.threshold ?: 0f)
        s.setFloatUniform("pstep", maxOf(1f, density * 0.75f))
        val pc = tools.peakingColour.argb
        s.setFloatUniform("pcol", ((pc shr 16) and 0xFF) / 255f, ((pc shr 8) and 0xFF) / 255f, (pc and 0xFF) / 255f)
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"))
    }
}
