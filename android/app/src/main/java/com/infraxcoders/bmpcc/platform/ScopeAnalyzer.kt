package com.infraxcoders.bmpcc.platform

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.infraxcoders.bmpcc.core.Lut3D
import com.infraxcoders.bmpcc.core.LutInput
import com.infraxcoders.bmpcc.core.ScopeFrame
import com.infraxcoders.bmpcc.core.ScopeKind
import com.infraxcoders.bmpcc.core.Scopes
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One scope reading: the histogram (always, for the clip / crush figures) and the waveform image when asked for. */
class ScopeResult(val kind: ScopeKind, val histogram: Scopes.Histogram, val waveform: Bitmap?)

/**
 * Reads a small RGBA copy of the phone's camera picture ~10 times a second and computes the scope the user picked,
 * inside the cinema frame and through the shot's LUT (so it measures what the viewfinder shows).
 */
class ScopeAnalyzer(private val main: Executor) : ImageAnalysis.Analyzer {
    @Volatile var kind: ScopeKind = ScopeKind.NONE
    /** Area measured, upright and normalised: u0, v0, u1, v1. */
    @Volatile var crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile var lut: Lut3D? = null
    @Volatile var input: LutInput = LutInput.REC709

    var result by mutableStateOf<ScopeResult?>(null)
        private set
    /** False when this phone couldn't run the scope stream next to the preview and photo streams. */
    var available by mutableStateOf(true)
        internal set

    private var last = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val k = kind
            if (k == ScopeKind.NONE) return
            val now = SystemClock.elapsedRealtime()
            if (now - last < 90) return
            last = now
            val plane = image.planes[0]
            val rot = image.imageInfo.rotationDegrees
            val upW = if (rot % 180 == 0) image.width else image.height
            val upH = if (rot % 180 == 0) image.height else image.width
            val c = crop
            val cw = ((c[2] - c[0]) * upW).coerceAtLeast(1f)
            val ch = ((c[3] - c[1]) * upH).coerceAtLeast(1f)
            val outW = 160
            val outH = (outW * ch / cw).toInt().coerceIn(16, 160)
            val frame = ScopeFrame.fromRgba(
                plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height, rot, outW, outH,
                c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(),
            )
            lut?.applyToPixels(frame.pixels, input)
            val hist = Scopes.histogram(frame)
            val wave = when (k) {
                ScopeKind.WAVEFORM -> Scopes.waveform(frame, columns = 160, levels = 100)
                ScopeKind.PARADE -> Scopes.waveform(frame, columns = 80, levels = 100, parade = true)
                else -> null
            }?.let { w ->
                Bitmap.createBitmap(Scopes.waveformImage(w), w.columns * w.counts.size, w.levels, Bitmap.Config.ARGB_8888)
            }
            val r = ScopeResult(k, hist, wave)
            main.execute { if (kind == r.kind) result = r }
        } catch (_: Throwable) {
            // A frame we couldn't read: skip it.
        } finally {
            image.close()
        }
    }

    fun clear() { result = null }

    fun useCase(rotation: Int = Surface.ROTATION_0): ImageAnalysis {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(worker, this) }
    }

    companion object {
        private val worker: Executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "scopes").apply { isDaemon = true } } }
    }
}
