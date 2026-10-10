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
import com.infraxcoders.bmpcc.core.PictureProcessor
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One scope reading: the histogram (always, for the clip / crush figures) and the waveform image when asked for. */
class ScopeResult(val kind: ScopeKind, val histogram: Scopes.Histogram, val waveform: Bitmap?)

/**
 * Reads a small RGBA copy of the phone's camera picture and (1) computes the scope the user picked, ~10 times a
 * second, inside the cinema frame and through the shot's LUT (so it measures what the viewfinder shows); and
 * (2) on phones that can't run the GPU picture tools, makes the processed picture itself (LUT, false colour, zebras,
 * peaking) at a lower resolution, ~15 times a second.
 */
class ScopeAnalyzer(private val main: Executor) : ImageAnalysis.Analyzer {
    @Volatile var kind: ScopeKind = ScopeKind.NONE
    /** Area measured, upright and normalised: u0, v0, u1, v1. */
    @Volatile var crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile var lut: Lut3D? = null
    @Volatile var input: LutInput = LutInput.REC709
    /** Make the processed picture on the CPU (GPU tools unavailable). */
    @Volatile var pictureOn: Boolean = false
    @Volatile var tools: PictureTools = PictureTools()
    /** LUT before / after: the LUT applies right of this fraction of the camera picture's width. */
    @Volatile var splitFraction: Double = 0.0

    /** The latest CPU-processed picture (upright, whole camera picture), while [pictureOn]. */
    var picture by mutableStateOf<Bitmap?>(null)
        private set
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var nextBuffer = 0
    private var lastPicture = 0L

    var result by mutableStateOf<ScopeResult?>(null)
        private set
    /** False when this phone couldn't run the scope stream next to the preview and photo streams. */
    var available by mutableStateOf(true)
        internal set

    private var last = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val k = kind
            val pic = pictureOn
            if (k == ScopeKind.NONE && !pic) return
            val now = SystemClock.elapsedRealtime()
            val plane = image.planes[0]
            val rot = image.imageInfo.rotationDegrees
            val upW = if (rot % 180 == 0) image.width else image.height
            val upH = if (rot % 180 == 0) image.height else image.width
            if (pic && now - lastPicture >= 66) {
                lastPicture = now
                makePicture(plane, image.width, image.height, rot, upW, upH)
            }
            if (k == ScopeKind.NONE || now - last < 90) return
            last = now
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

    private fun makePicture(plane: ImageProxy.PlaneProxy, srcW: Int, srcH: Int, rot: Int, upW: Int, upH: Int) {
        val scale = 480.0 / maxOf(upW, upH)
        val w = (upW * scale).toInt().coerceAtLeast(16); val h = (upH * scale).toInt().coerceAtLeast(16)
        val frame = ScopeFrame.fromRgba(plane.buffer, plane.rowStride, plane.pixelStride, srcW, srcH, rot, w, h)
        val t = tools
        val px = PictureProcessor.process(frame, lut, input, splitFraction, t.falseColour, t.zebraLevel, t.peaking, t.peakingColour, stripePx = 6)
        // Three bitmaps in turn, so the one on screen isn't overwritten while it's drawn.
        val bmp = buffers[nextBuffer]?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffers[nextBuffer] = bmp
        nextBuffer = (nextBuffer + 1) % buffers.size
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        main.execute { if (pictureOn) picture = bmp }
    }

    fun clear() { result = null }
    fun clearPicture() { picture = null }

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
