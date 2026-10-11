package com.infraxcoders.bmpcc.platform

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.infraxcoders.bmpcc.core.PhoneOptics

/**
 * Finds the phone's back lenses and their real angles of view: every back camera the camera library can open, plus the
 * physical cameras hidden behind a logical (multi-lens) camera (Android 9+). Angles come from each lens's own
 * characteristics (sensor size, focal length, lens calibration), with a 70° fallback when the data is missing or odd.
 */
object PhoneLensScanner {
    /** Reads one camera's angle of view across a [streamAspect] stream. */
    fun reading(get: (CameraCharacteristics.Key<*>) -> Any?, streamAspect: Double): PhoneOptics.Reading {
        val physical = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) as? SizeF
        val pixels = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) as? Size
        val active = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as? Rect
        val focal = (get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) as? FloatArray)?.minOrNull()?.toDouble()
        val intrinsic = get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) as? FloatArray
        // Android 9+: intrinsics are in the pre-correction active array's pixels.
        val pre = if (Build.VERSION.SDK_INT >= 28) get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) as? Rect else null
        return PhoneOptics.longSideFov(
            physical?.width?.toDouble(), physical?.height?.toDouble(), pixels?.width, pixels?.height,
            active?.width(), active?.height(), focal, streamAspect,
            intrinsicFx = intrinsic?.getOrNull(0)?.toDouble()?.takeIf { it > 0 },
            intrinsicArrayW = (pre ?: active)?.width(), intrinsicArrayH = (pre ?: active)?.height(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun getter(c: CameraCharacteristics): (CameraCharacteristics.Key<*>) -> Any? = { k -> runCatching { c.get(k as CameraCharacteristics.Key<Any>) }.getOrNull() }

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalCamera2Interop::class)
    fun getter(info: Camera2CameraInfo): (CameraCharacteristics.Key<*>) -> Any? =
        { k -> runCatching { info.getCameraCharacteristic(k as CameraCharacteristics.Key<Any>) }.getOrNull() }

    /** Fresh reading of a lens's characteristics (its physical camera when it has one), and its largest aperture. */
    fun reread(context: Context, lens: PhoneOptics.Lens, streamAspect: Double): Pair<PhoneOptics.Reading, Double?>? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val c = runCatching { cm.getCameraCharacteristics(lens.physicalId ?: lens.cameraId) }.getOrNull() ?: return null
        val aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.toDouble()?.takeIf { it > 0 }
        return reading(getter(c), streamAspect) to aperture
    }

    private fun zoomRange(c: CameraCharacteristics): Pair<Double, Double> {
        if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
            if (it.lower > 0f && it.upper >= it.lower) return it.lower.toDouble() to it.upper.toDouble()
        }
        val max = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble()?.takeIf { it >= 1.0 } ?: 4.0
        return 1.0 to max
    }

    /** The id of the camera the library opens by default for the back camera. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun defaultBackId(provider: ProcessCameraProvider): String? = runCatching {
        CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()?.let { Camera2CameraInfo.from(it).cameraId }
    }.getOrNull()

    @OptIn(ExperimentalCamera2Interop::class)
    fun scan(context: Context, provider: ProcessCameraProvider, streamAspect: Double): List<PhoneOptics.Lens> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val defaultId = defaultBackId(provider)
        val out = mutableListOf<PhoneOptics.Lens>()
        val backs: List<CameraInfo> = runCatching { provider.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK } }
            .getOrDefault(emptyList())
        for (info in backs) {
            val id = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() ?: continue
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val r = reading(getter(c), streamAspect)
            val (zMin, zMax) = zoomRange(c)
            out += PhoneOptics.Lens(id, null, r.longSideFov, r.source, zMin, zMax, id == defaultId)
            // Physical lenses behind a logical camera (ultra-wide, tele), streamed directly.
            if (Build.VERSION.SDK_INT >= 28) for (pid in runCatching { c.physicalCameraIds }.getOrDefault(emptySet())) {
                val pc = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                if (pc.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val pr = reading(getter(pc), streamAspect)
                if (pr.source == PhoneOptics.Source.ASSUMED) continue // can't tell how wide it is
                out += PhoneOptics.Lens(id, pid, pr.longSideFov, pr.source, 1.0, 1.0, false)
            }
        }
        return PhoneOptics.distinct(out)
    }
}
