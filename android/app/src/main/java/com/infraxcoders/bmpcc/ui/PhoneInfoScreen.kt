package com.infraxcoders.bmpcc.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.infraxcoders.bmpcc.BuildConfig
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.CrashLog
import com.infraxcoders.bmpcc.platform.PhoneLensScanner
import com.infraxcoders.bmpcc.platform.PictureEffect
import kotlin.math.max
import kotlin.math.min

/**
 * What this phone offers the app: the cameras Android lists and those apps may open, each lens's angle of view and
 * where it comes from, manual exposure, the live picture tools and the sensors. Shareable, to see why the app behaves
 * differently from one phone to another.
 */
@Composable
fun PhoneInfoScreen(nav: Navigator) {
    val context = LocalContext.current
    val conf = LocalConfiguration.current
    var sections by remember { mutableStateOf<List<Pair<String, List<String>>>>(emptyList()) }
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            sections = report(context, provider, conf.screenWidthDp, conf.screenHeightDp)
        }, ContextCompat.getMainExecutor(context))
    }
    Screen("Phone info", onBack = { nav.pop() }, actions = {
        TextButton({ CrashLog.share(context, sections.joinToString("\n\n") { (t, l) -> t.uppercase() + "\n" + l.joinToString("\n") }, "BMPCC Control phone info", "Share phone info") }) { Text("Share") }
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            if (sections.isEmpty()) item { Hint("Reading the cameras…") }
            for ((title, lines) in sections) section(title) {
                item {
                    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        for (l in lines) Text(l, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
            item { Hint("Tip: share this from each phone that behaves differently.") }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun report(context: Context, provider: ProcessCameraProvider?, wDp: Int, hDp: Int): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, List<String>>>()
    out += "Phone" to listOf(
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "App ${BuildConfig.VERSION_NAME} build ${BuildConfig.BUILD_NUMBER}",
        "Screen ${wDp} × ${hDp} dp (${String.format(java.util.Locale.US, "%.2f", max(wDp, hDp).toDouble() / min(wDp, hDp).coerceAtLeast(1))}:1)",
    )

    // Cameras: what Android lists, and which the app may open (and their hidden lenses).
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    val listed = runCatching { cm?.cameraIdList?.toList() }.getOrNull().orEmpty()
    val lines = mutableListOf("Android lists ${listed.size} camera(s): ${listed.joinToString(", ")}")
    for (id in listed) {
        val c = runCatching { cm!!.getCameraCharacteristics(id) }.getOrNull() ?: continue
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"; CameraCharacteristics.LENS_FACING_FRONT -> "front"; else -> "external"
        }
        val r = PhoneLensScanner.reading(PhoneLensScanner.getter(c), 4.0 / 3.0)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val logical = Build.VERSION.SDK_INT >= 28 && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"; CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"; CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level 3"; else -> "external"
        }
        val zoom = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { " · zoom ${it.lower}–${it.upper}×" } ?: "" else ""
        lines += "#$id $facing · ${String.format(java.util.Locale.US, "%.1f", r.longSideFov)}° (${r.source.name.lowercase()}) · $level" +
            (if (manual) " · manual exposure" else "") + zoom
        if (Build.VERSION.SDK_INT >= 28 && logical) {
            val phys = runCatching { c.physicalCameraIds }.getOrDefault(emptySet())
            for (pid in phys) {
                val pc = runCatching { cm!!.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                val pr = PhoneLensScanner.reading(PhoneLensScanner.getter(pc), 4.0 / 3.0)
                lines += "   ↳ hidden lens #$pid · ${String.format(java.util.Locale.US, "%.1f", pr.longSideFov)}° (${pr.source.name.lowercase()})"
            }
        }
    }
    out += "Cameras" to lines

    val usable = if (provider != null) runCatching { PhoneLensScanner.scan(context, provider, 4.0 / 3.0) }.getOrDefault(emptyList()) else emptyList()
    out += "Lenses the app can use" to (if (usable.isEmpty()) listOf("None found (camera access off?)") else usable.map { l ->
        val name = when {
            l.isDefault -> "Main"
            l.physicalId != null -> "Hidden lens ${l.physicalId}"
            else -> "Camera ${l.cameraId}"
        }
        val override = Settings.fovOverride(l.key, l.isDefault)
        "$name · ${String.format(java.util.Locale.US, "%.1f", l.longSideFov)}° at 1× · widest ${String.format(java.util.Locale.US, "%.0f", l.widestFov)}°" +
            (if (l.minZoom < 0.99) " (zooms to ${l.minZoom}×)" else "") +
            (override?.let { " · your calibration ${String.format(java.util.Locale.US, "%.1f", it)}°" } ?: "")
    } + listOf(
        when {
            usable.any { !it.isDefault && it.widestFov > (usable.firstOrNull { d -> d.isDefault }?.widestFov ?: 0.0) + 1 } -> "→ Ultra-wide available to the app."
            usable.any { it.minZoom < 0.99 } -> "→ Ultra-wide reachable by zooming out."
            else -> "→ No ultra-wide open to apps: wide frames may not fit."
        },
    ))

    out += "Live picture tools" to listOf(
        if (PictureEffect.supported && PictureEffect.working) "Full quality (GPU, Android 13+)"
        else if (PictureEffect.supported) "GPU effect failed on this phone: lower-resolution version"
        else "Lower-resolution version (Android 12 or older)",
    )

    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    fun has(t: Int) = sm?.getDefaultSensor(t) != null
    out += "Sensors" to listOf(
        "Rotation vector (sun AR): ${if (has(Sensor.TYPE_ROTATION_VECTOR)) "yes" else "no"}",
        "Gravity (level): ${if (has(Sensor.TYPE_GRAVITY)) "yes" else if (has(Sensor.TYPE_ACCELEROMETER)) "accelerometer only" else "no"}",
        "Compass: ${if (has(Sensor.TYPE_MAGNETIC_FIELD)) "yes" else "no"}",
        "Gyroscope: ${if (has(Sensor.TYPE_GYROSCOPE)) "yes" else "no"}",
    )
    return out
}
