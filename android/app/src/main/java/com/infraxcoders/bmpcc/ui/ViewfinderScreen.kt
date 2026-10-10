package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.IntOffset
import com.infraxcoders.bmpcc.core.Lut3D
import com.infraxcoders.bmpcc.core.LutInput
import com.infraxcoders.bmpcc.data.LutStore
import com.infraxcoders.bmpcc.platform.PictureEffect
import com.infraxcoders.bmpcc.platform.PictureTools
import com.infraxcoders.bmpcc.platform.ScopeAnalyzer
import com.infraxcoders.bmpcc.platform.ScopeResult
import com.infraxcoders.bmpcc.core.FalseColour
import com.infraxcoders.bmpcc.core.PeakingColour
import com.infraxcoders.bmpcc.core.PeakingLevel
import com.infraxcoders.bmpcc.core.ScopeKind
import com.infraxcoders.bmpcc.core.Scopes
import com.infraxcoders.bmpcc.core.Zebra
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.data.PhoneExposure
import com.infraxcoders.bmpcc.core.ExposureMatch
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import com.infraxcoders.bmpcc.core.Framing
import com.infraxcoders.bmpcc.core.LensProfile
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.ble.CameraLink
import com.infraxcoders.bmpcc.core.Bmd
import com.infraxcoders.bmpcc.core.Distortion
import com.infraxcoders.bmpcc.core.Exposure
import com.infraxcoders.bmpcc.core.Focus
import com.infraxcoders.bmpcc.core.ShotOptics
import com.infraxcoders.bmpcc.core.FocalOptions
import com.infraxcoders.bmpcc.core.FrameLines
import com.infraxcoders.bmpcc.core.MarkerType
import com.infraxcoders.bmpcc.core.Monitor
import com.infraxcoders.bmpcc.core.Optics
import com.infraxcoders.bmpcc.core.QuickRecce
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.ScreenRect
import com.infraxcoders.bmpcc.core.ShotMarker
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.core.ShotReference
import com.infraxcoders.bmpcc.core.ShotSize
import com.infraxcoders.bmpcc.core.Viewfinder
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.Images
import com.infraxcoders.bmpcc.platform.hasPermission
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.infraxcoders.bmpcc.core.PreviewView as PhoneView

/** The phone's back camera: its field of view, zoom range and controls. */
class PhoneCamera {
    var camera by mutableStateOf<Camera?>(null)
    var capture: ImageCapture? = null
    var preview: Preview? = null
    /** Field of view across the stream's long side at zoom 1, degrees. */
    var longSideFov by mutableStateOf(70.0)
    var fovMeasured by mutableStateOf(false)
    /** What the phone itself reports (before any calibration). */
    var reportedFov by mutableStateOf<Double?>(null)
    /** true when [longSideFov] comes from the user's calibration. */
    var calibrated by mutableStateOf(false)
    var streamAspect by mutableStateOf(4.0 / 3.0)
    var minZoom by mutableStateOf(1.0)
    var maxZoom by mutableStateOf(4.0)
    var zoom by mutableStateOf(1.0)
    var error by mutableStateOf<String?>(null)
    var evStep = 0.0
    var evMin = 0
    var evMax = 0
    /** Manual ISO / exposure time (Camera2 MANUAL_SENSOR), for matching the cinema camera's exposure. */
    var manualSupported by mutableStateOf(false)
    var isoMin = 0; var isoMax = 0
    var exposureMinNs = 0L; var exposureMaxNs = 0L
    var phoneAperture = 1.8
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var manual: ExposureMatch.Phone? = null

    /** Sends white balance and (when matching) manual exposure together: Camera2 options replace each other. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun pushOptions() {
        val c = camera ?: return
        runCatching {
            val b = CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            manual?.let { m ->
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(m.exposureNs, 33_333_333L))
            }
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(b.build())
        }
    }

    /** Manual exposure (null = back to auto-exposure). */
    fun applyManual(m: ExposureMatch.Phone?) {
        if (m == manual) return
        manual = m
        if (m != null) camera?.cameraControl?.setExposureCompensationIndex(0)
        pushOptions()
    }

    fun applyZoom(z: Double) {
        val c = camera ?: return
        val v = z.coerceIn(minZoom, maxZoom)
        zoom = v
        c.cameraControl.setZoomRatio(v.toFloat())
    }

    fun applyExposure(ev: Double) {
        val c = camera ?: return
        if (evStep <= 0) return
        c.cameraControl.setExposureCompensationIndex(Exposure.compensationIndex(ev, evStep, evMin, evMax))
    }

    /** White balance preset nearest to a colour temperature in Kelvin. */
    fun applyWhiteBalance(kelvin: Int?) {
        awbMode = when {
            kelvin == null -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            kelvin < 3600 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            kelvin < 4700 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            kelvin < 6000 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            else -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        }
        pushOptions()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun readOptics(c: Camera) {
        runCatching {
            val info = Camera2CameraInfo.from(c.cameraInfo)
            val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val timeRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let { if (it > 0) phoneAperture = it.toDouble() }
            if (caps != null && isoRange != null && timeRange != null &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            ) {
                isoMin = isoRange.lower; isoMax = isoRange.upper
                exposureMinNs = timeRange.lower; exposureMaxNs = timeRange.upper
                manualSupported = isoMax > isoMin && exposureMaxNs > exposureMinNs
            }
            if (physical != null && pixels != null && active != null && focal != null && focal > 0) {
                val activeW = physical.width * active.width().toDouble() / pixels.width
                val activeH = physical.height * active.height().toDouble() / pixels.height
                val long = max(activeW, activeH)
                val short = min(activeW, activeH)
                // A 4:3 stream uses the full width of a 4:3 sensor; on a wider sensor it is cropped at the sides.
                val streamLong = min(long, short * streamAspect)
                longSideFov = 2 * Optics.degrees(atan(streamLong / (2 * focal)))
                fovMeasured = true
                reportedFov = longSideFov
            }
        }
        calibrated = false
        com.infraxcoders.bmpcc.data.Settings.fovCalibration?.let { longSideFov = it; calibrated = true }
        c.cameraInfo.zoomState.value?.let {
            minZoom = it.minZoomRatio.toDouble()
            maxZoom = it.maxZoomRatio.toDouble()
            zoom = it.zoomRatio.toDouble()
        }
        val es = c.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            evStep = es.exposureCompensationStep.toDouble()
            evMin = es.exposureCompensationRange.lower
            evMax = es.exposureCompensationRange.upper
        }
    }
}

private val T_STOPS = listOf(0.95, 1.0, 1.2, 1.3, 1.4, 1.5, 1.8, 1.9, 2.0, 2.2, 2.4, 2.5, 2.8, 3.5, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Suppress("DEPRECATION")
internal fun displayRotation(context: Context): Int =
    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay?.rotation ?: Surface.ROTATION_0

/** Phone attitude: roll (horizon, degrees, clockwise +) and pitch (camera tilt, degrees, up +). */
data class Tilt(val roll: Float, val pitch: Float)

/** Live tilt from the gravity sensor (accelerometer if there is none), smoothed; null until the first reading. */
@Composable
fun rememberTilt(screenRotation: Int = 0): State<Tilt?> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf<Tilt?>(null) }
    DisposableEffect(screenRotation) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val g = FloatArray(3)
        var started = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val a = if (started) 0.15f else 1f
                for (i in 0..2) g[i] += a * (e.values[i] - g[i])
                started = true
                val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (n < 1f) return
                // Portrait: roll from x/y; the back camera points along -z, so pitch = -asin(z / |g|).
                // Roll of the phone, then relative to the screen's own "up" (landscape turns the screen 90°).
                var roll = Math.toDegrees(atan2(-g[0], g[1]).toDouble()).toFloat() + screenRotation
                while (roll > 180f) roll -= 360f
                while (roll <= -180f) roll += 360f
                val pitch = Math.toDegrees(asin((-g[2] / n).coerceIn(-1f, 1f)).toDouble()).toFloat()
                val old = tilt.value
                if (old == null || abs(old.roll - roll) > 0.2f || abs(old.pitch - pitch) > 0.2f) tilt.value = Tilt(roll, pitch)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}

/**
 * Portrait director's viewfinder, styled like a camera monitor. The phone zooms so the frame shows what the chosen
 * cinema camera and lens see; tapping a focal length (or W / M / C) changes the lens and the phone zoom follows.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewfinderScreen(nav: Navigator, sessionId: String, sceneId: String, shotId: String) {
    FullScreen()
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessions by RecceStore.sessions.collectAsState()
    val shot = sessions.firstOrNull { it.id == sessionId }?.scenes?.firstOrNull { it.id == sceneId }?.shots?.firstOrNull { it.id == shotId }
        ?: return Gone(nav)
    val phone = remember { PhoneCamera() }
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { phone.error = "Camera access is off. Allow it in the phone's settings to use the viewfinder." }
    var locked by remember { mutableStateOf(false) }
    var surroundings by remember { mutableStateOf(false) }
    var showThirds by remember { mutableStateOf(true) }
    var showCentre by remember { mutableStateOf(false) }
    var showSafe by remember { mutableStateOf(false) }
    var showLevel by remember { mutableStateOf(true) }
    var compare by remember { mutableStateOf(false) }
    var showDistortion by remember { mutableStateOf(true) }
    var lutMenu by remember { mutableStateOf(false) }
    var lutCompare by remember { mutableStateOf(false) }
    var lutSplit by remember { mutableFloatStateOf(0.5f) }
    var expoMenu by remember { mutableStateOf(false) }
    var distortionDialog by remember { mutableStateOf(false) }
    var calibrationNote by remember { mutableStateOf(false) }
    var customFocal by remember { mutableStateOf(false) }
    var markerType by remember { mutableStateOf<MarkerType?>(null) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var flash by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var exposureSheet by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var guidesMenu by remember { mutableStateOf(false) }
    var aspectMenu by remember { mutableStateOf(false) }
    val rotation = rememberDisplayRotation()
    val landscape = rotation == 90 || rotation == 270
    val tilt = rememberTilt(rotation)
    fun edit(change: (RecceShot) -> RecceShot) = RecceStore.updateShot(sessionId, sceneId, shotId, change)

    LaunchedEffect(Unit) { if (!hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }

    val lens = shot.lens
    val mode = shot.sensorMode
    val focals = remember(lens.id) { FocalOptions.focals(lens) }
    val reference = shot.reference

    // Geometry: the camera picture fills the screen; the cinema frame is centred, clear of the top and bottom controls.
    // Portrait: controls above and below. Landscape: controls at the sides and a thin bar top and bottom.
    val w = screen.width.toDouble()
    val h = screen.height.toDouble()
    val video = Monitor.aspectFill(if (landscape) phone.streamAspect else 1 / phone.streamAspect, w, h)
    val area = if (landscape) Monitor.centredArea(w, h, with(density) { 96.dp.toPx() }.toDouble(), with(density) { 86.dp.toPx() }.toDouble())
    else Monitor.centredArea(w, h, 0.0, min(with(density) { 210.dp.toPx() }.toDouble(), h * 0.3))
    val view = PhoneView.fromFormat(phone.longSideFov, phone.streamAspect, !landscape)
    val layout = if (reference != null && w > 0 && h > 0) Monitor.layout(
        reference, view, video, area, phone.minZoom, phone.maxZoom,
        margin = when {
            compare -> max(1.0, shot.focalMm / compareFocals(focals, shot.focalMm).minOrNull().let { it ?: shot.focalMm }) * 1.03
            surroundings -> 1.35
            else -> 1.0
        },
        fixedZoom = if (locked) phone.zoom else null,
    ) else null

    // The lens data drives the phone: zoom, exposure, white balance.
    LaunchedEffect(layout?.zoom, phone.camera, locked) { if (!locked) layout?.let { phone.applyZoom(it.zoom) } }
    val ev = Exposure.ev(shot.iso, shot.shutter, shot.nd, shot.aperture)
    val exposureMode = Settings.phoneExposure
    val match = if (exposureMode == PhoneExposure.MATCH && phone.manualSupported) {
        ExposureMatch.target(shot.iso, shot.shutter, shot.fps, shot.nd, shot.aperture, phone.phoneAperture)
            ?.let { ExposureMatch.phone(it, phone.isoMin, phone.isoMax, phone.exposureMinNs, phone.exposureMaxNs, ExposureMatch.exposureSeconds(shot.shutter, shot.fps)) }
    } else null
    LaunchedEffect(ev, exposureMode, match, phone.camera) {
        phone.applyManual(match)
        if (match == null) phone.applyExposure(if (exposureMode != PhoneExposure.OFF) ev else 0.0)
    }
    val kelvin = shot.whiteBalance.filter { it.isDigit() }.toIntOrNull()
    LaunchedEffect(kelvin, phone.camera) { phone.applyWhiteBalance(kelvin) }

    // Bluetooth: show the connected camera's own values.
    val link = CameraLink.isConnected
    LaunchedEffect(link, CameraLink.iso) { CameraLink.iso?.let { v -> if (link && shot.iso != "$v") edit { it.copy(iso = "$v") } } }
    LaunchedEffect(link, CameraLink.shutterAngle) {
        CameraLink.shutterAngle?.let { v -> val t = "${ShotPresets.trim(v)}°"; if (link && shot.shutter != t) edit { it.copy(shutter = t) } }
    }
    LaunchedEffect(link, CameraLink.whiteBalance) { CameraLink.whiteBalance?.let { v -> if (link && shot.whiteBalance != "${v}K") edit { it.copy(whiteBalance = "${v}K") } } }
    LaunchedEffect(link, CameraLink.ndStops) { CameraLink.ndStops?.let { v -> val t = Bmd.ndPreset(v); if (link && shot.nd != t) edit { it.copy(nd = t) } } }
    LaunchedEffect(link, CameraLink.fNumber) {
        CameraLink.fNumber?.let { v -> val t = ShotPresets.tStopText(Math.round(v * 10) / 10.0); if (link && tText(shot.aperture) != t) edit { it.copy(aperture = t) } }
    }
    LaunchedEffect(link, CameraLink.fps) { CameraLink.fps?.let { v -> if (link && shot.fps != "$v") edit { it.copy(fps = "$v") } } }

    fun setFocal(f: Double) {
        if (locked) { flash = "Zoom is locked (Guides → Lock zoom)."; return }
        val target = FocalOptions.lensFor(lens, f)
        edit { s ->
            val withLens = if (target.id != s.lens.id) s.withLens(target) else s
            withLens.copy(focalLength = ShotPresets.focalText(if (target.isZoom) target.clampFocal(f) else target.focalLengthMin))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { screen = it }) {
        // The shot's look (LUT), live on the picture (Android 13+). Before / after: left of the split stays neutral.
        val lutEntry = LutStore.entry(shot.lut)
        val lut by produceState<Lut3D?>(LutStore.cached(shot.lut), shot.lut) {
            value = withContext(Dispatchers.Default) { LutStore.load(shot.lut) }
        }
        val lutInput = if (lutEntry != null) LutStore.input(lutEntry.id) else LutInput.REC709
        // Exposure and focus tools: false colour, zebras and peaking on the picture; scopes measure inside the frame.
        val tools = PictureTools(
            falseColour = Settings.falseColour,
            zebraLevel = if (Settings.zebras) Settings.zebraLevel else null,
            peaking = if (Settings.peaking) Settings.peakingLevel else null,
            peakingColour = Settings.peakingColour,
        )
        val scopeKind = Settings.scope
        val scopes = remember { ScopeAnalyzer(ContextCompat.getMainExecutor(context)) }
        val scopeCrop = Scopes.crop(layout?.frame, video)
        // Phones without GPU picture tools (Android 12 and older, or a shader failure): a lower-resolution CPU picture.
        val cpuPicture = hasCamera && (lut != null || tools.any) && !(PictureEffect.supported && PictureEffect.working)
        val splitFraction = if (lutCompare && lut != null && video.width > 0) (lutSplit * w - video.x) / video.width else 0.0
        SideEffect {
            scopes.kind = scopeKind; scopes.lut = lut; scopes.input = lutInput; scopes.crop = scopeCrop
            scopes.pictureOn = cpuPicture; scopes.tools = tools; scopes.splitFraction = splitFraction
        }
        LaunchedEffect(cpuPicture) { if (!cpuPicture) scopes.clearPicture() }
        LaunchedEffect(scopeKind) { scopes.clear() }
        if (hasCamera) CameraPreview(
            context, lifecycleOwner, phone, rotation, lut, lutInput, if (lutCompare && lut != null) lutSplit * w.toFloat() else null,
            tools, scopes, scopeKind, cpuPicture,
        )
        if (cpuPicture) CpuPicture(scopes, video)
        MonitorOverlay(
            layout?.frame, layout?.lines, shot.markers, showThirds, showCentre, showSafe,
            maskAlpha = if (surroundings) 0.45f else 0.82f,
            modifier = Modifier.fillMaxSize().pointerInput(markerType, layout?.frame) {
                detectTapGestures { p ->
                    val t = markerType ?: return@detectTapGestures
                    val f = layout?.frame ?: return@detectTapGestures
                    Viewfinder.normalisedPoint(p.x.toDouble(), p.y.toDouble(), f)?.let { (x, y) ->
                        edit { it.copy(markers = it.markers + ShotMarker(shotId = shotId, type = t, x = x, y = y)) }
                    }
                }
            },
        )
        if (showLevel) LevelOverlay(layout?.frame, tilt)
        // Compare: the other focal lengths of the set as labelled frame lines around / inside the current frame.
        // Lens distortion (Lensfun profile): where the frame edges really fall for this photo lens.
        val distortionModel = remember(lens.id, shot.focalMm, Settings.distortionVersion) { Distortion.modelFor(lens, shot.focalMm) }
        if (showDistortion && distortionModel != null && layout != null && layout.lines.fits && reference != null &&
            Distortion.withinCalibration(lens, reference.deliveredWidthMm, reference.deliveredHeightMm)
        ) {
            val pts = Distortion.frameOutline(distortionModel, reference.deliveredWidthMm, reference.deliveredHeightMm, reference.effectiveFocalLengthMm)
            DistortionOverlay(pts, layout.frame, reference.tanHalfH, reference.tanHalfV)
        }
        if (compare && layout != null) {
            val zoomed = Monitor.viewAcross(view, video, area).zoomed(layout.zoom)
            val others = compareFocals(focals, shot.focalMm).mapNotNull { f ->
                val ref = Framing.reference(shot.camera, FocalOptions.lensFor(lens, f), f, shot.aspectValue) ?: return@mapNotNull null
                val lines = Viewfinder.frameLines(ref, zoomed)
                if (lines.fits) f to Viewfinder.frameRect(lines, area) else null
            }
            CompareOverlay(others)
        }

        if (scopeKind != ScopeKind.NONE && hasCamera) ScopePanel(
            scopes, scopeKind,
            if (landscape) Modifier.align(Alignment.BottomStart).padding(start = 70.dp, bottom = 62.dp)
            else Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 12.dp, bottom = 222.dp),
        )

        // Above the frame overlays, so it gets the drags.
        if (lutCompare && lut != null && w > 0) SplitHandle(lutSplit, w.toFloat()) { lutSplit = it }

        // ── Top: camera, focal length and view (plus exposure in landscape), frame ──
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = if (landscape) 8.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(QuickRecce.shortName(shot.baseCamera)) { setup = true }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shot.focalLength, color = Color(0xFFC9D8FF), fontSize = if (landscape) 24.sp else 32.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1)
                val hfov = listOfNotNull(reference?.let { "H-FOV ${Math.round(it.deliveredFov.horizontal)}°" }, lutEntry?.name)
                    .joinToString(" · ").ifEmpty { null }
                if (landscape) {
                    ExposureLine(shot, tilt, prefix = hfov) { exposureSheet = true }
                    FocusLine(shot) { exposureSheet = true }
                }
                else hfov?.let {
                    Text(it, color = Color(0xFFAFC0E6), fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                }
            }
            Box {
                Pill(shot.aspectRatio, mono = true) { aspectMenu = true }
                DropdownMenu(aspectMenu, { aspectMenu = false }) {
                    ShotPresets.aspectRatios.forEach { a -> DropdownMenuItem({ Text(a) }, { edit { it.copy(aspectRatio = a) }; aspectMenu = false }) }
                }
            }
        }

        // ── Messages (and record on the real camera when connected over Bluetooth) ──
        val warn = when {
            phone.error != null -> phone.error
            layout != null && !layout.lines.fits -> "Wider than the phone camera can see: the frame shows the phone's widest view."
            ShotOptics.depthOfField(shot)?.tooClose == true -> fmt("Closer than this lens can focus (%.2f m).", lens.minimumFocusDistance)
            !phone.fovMeasured && phone.camera != null -> "This phone didn't report its lens angle; using 70°."
            else -> null
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 76.dp else 96.dp, start = if (landscape) 110.dp else 24.dp, end = if (landscape) 110.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (link) RecordPill()
            warn?.let { VfPill(it, Color(0xCC8A3A00)) }
            flash?.let { VfPill(it, Color(0xCC1E6B2E)) }
            markerType?.let { VfPill("Tap inside the frame to place: ${it.label}", Color(0xCC000000)) }
            if (calibrationNote && !phone.calibrated) Pill(
                "Phone not calibrated: frame lines can be a few % off. Calibrate ›", modifier = Modifier.padding(top = 6.dp),
                color = Color(0xCC000000), textColor = Color.White,
            ) { calibrationNote = false; nav.push(Dest.Calibrate) }
            if (cpuPicture && scopes.available) VfPill("Reduced-quality live preview (full quality needs Android 13+ graphics).", Color(0xCC000000))
            if ((scopeKind != ScopeKind.NONE || cpuPicture) && !scopes.available)
                VfPill("This phone can't run the scopes or picture tools next to the camera picture." +
                    (if (lut != null) " The LUT is applied to saved frames." else ""), Color(0xCC000000))
            match?.let { m ->
                if (m.stopsOff > 0.5) VfPill(fmt("Phone picture %.1f stops darker than the real exposure (phone's limit).", m.stopsOff), Color(0xCC000000))
                else if (m.stopsOff < -0.5) VfPill(fmt("Phone picture %.1f stops brighter than the real exposure (phone's limit).", -m.stopsOff), Color(0xCC000000))
            }
            if (tools.falseColour) FalseColourLegend()
        }

        // Pieces used in both layouts.
        val sizeButtons: @Composable () -> Unit = {
            ShotSize.entries.forEach { size ->
                val target = FocalOptions.focalFor(size, focals, mode, lens.anamorphicSqueeze)
                val active = target != null && abs(target - shot.focalMm) < 0.5
                RoundButton(size.label.take(1), selected = active, size = 40.dp) {
                    target?.let { f -> setFocal(f); if (!locked) edit { it.copy(shotType = size.shotType) } }
                }
            }
        }
        val toolButtons: @Composable () -> Unit = {
            RoundButton("Grid", selected = showThirds, size = 42.dp) { showThirds = !showThirds }
            RoundButton("Level", selected = showLevel, size = 42.dp) { showLevel = !showLevel }
            Box {
                RoundButton("Guides", selected = showCentre || showSafe || surroundings || locked || markerType != null, size = 42.dp) { guidesMenu = true }
                DropdownMenu(guidesMenu, { guidesMenu = false }) {
                    // Honest accuracy: where the phone's angle of view comes from.
                    DropdownMenuItem(
                        {
                            Text(
                                fmt("Phone lens %.1f° · %s", phone.longSideFov, when {
                                    phone.calibrated -> "calibrated"
                                    phone.fovMeasured -> "as reported, not calibrated"
                                    else -> "assumed"
                                }) + "\nCalibrate phone…",
                                fontSize = 13.sp,
                            )
                        },
                        { guidesMenu = false; nav.push(Dest.Calibrate) },
                    )
                    DropdownMenuItem({ Text("Sun planner (sun path, AR)…", fontSize = 13.sp) }, {
                        guidesMenu = false; nav.push(Dest.Sun(sessionId, sceneId, shotId, shot.plannedTime ?: System.currentTimeMillis()))
                    })
                    HorizontalDivider()
                    ToggleItem("Centre mark", showCentre) { showCentre = it }
                    ToggleItem("Safe areas 90% / 80%", showSafe) { showSafe = it }
                    ToggleItem("Show outside the frame", surroundings) { surroundings = it }
                    ToggleItem("Compare focal lengths", compare) { compare = it }
                    if (Distortion.hasProfile(lens)) ToggleItem(
                        "Lens distortion" + (ShotOptics.distortionPercent(shot)?.let { " (" + ShotOptics.distortionText(it) + ")" } ?: "") +
                            if (Distortion.isCustom(lens)) " · your measurement" else "",
                        showDistortion,
                    ) { showDistortion = it }
                    if (reference != null && (!Distortion.hasProfile(lens) || Distortion.isCustom(lens))) DropdownMenuItem(
                        { Text(if (Distortion.isCustom(lens)) "Edit your distortion measurement…" else "Enter this lens's distortion…", fontSize = 13.sp) },
                        { guidesMenu = false; distortionDialog = true },
                    )
                    ToggleItem("Lock zoom", locked) { locked = it }
                    HorizontalDivider()
                    MarkerType.entries.forEach { t ->
                        DropdownMenuItem({ Text("Place marker: ${t.label}" + if (markerType == t) "  ✓" else "") }, { markerType = t; guidesMenu = false })
                    }
                    if (markerType != null) DropdownMenuItem({ Text("Stop placing markers") }, { markerType = null; guidesMenu = false })
                    if (shot.markers.isNotEmpty()) DropdownMenuItem({ Text("Remove all markers", color = Color(0xFFFF453A)) },
                        { edit { it.copy(markers = emptyList()) }; guidesMenu = false })
                }
            }
            Box {
                RoundButton("LUT", selected = lut != null, size = 42.dp) { lutMenu = true }
                DropdownMenu(lutMenu, { lutMenu = false }) {
                    DropdownMenuItem({ Text("No LUT" + if (shot.lut == null) "  ✓" else "") }, { edit { it.copy(lut = null) }; lutCompare = false; lutMenu = false })
                    LutStore.entries.forEach { e ->
                        DropdownMenuItem(
                            { Text(e.name + (if (e.builtIn) "" else " (.cube)") + if (e.id == shot.lut) "  ✓" else "") },
                            { edit { it.copy(lut = e.id) }; lutMenu = false },
                        )
                    }
                    HorizontalDivider()
                    if (lut != null) ToggleItem("Before / after", lutCompare) { lutCompare = it }
                    DropdownMenuItem({ Text("Import and manage LUTs…") }, { lutMenu = false; nav.push(Dest.Luts) })
                }
            }
            Box {
                RoundButton("Expo", selected = tools.any || scopeKind != ScopeKind.NONE, size = 42.dp) { expoMenu = true }
                DropdownMenu(expoMenu, { expoMenu = false }) {
                    ToggleItem("False colour", Settings.falseColour) { Settings.chooseFalseColour(it) }
                    ToggleItem("Zebras", Settings.zebras) { Settings.chooseZebras(it) }
                    if (Settings.zebras) DropdownMenuItem({ Text("Zebra level: ${Settings.zebraLevel}%  (tap to change)", fontSize = 13.sp) }, {
                        val l = Zebra.levels
                        Settings.chooseZebraLevel(l[(l.indexOf(Settings.zebraLevel) + 1) % l.size])
                    })
                    ToggleItem("Focus peaking", Settings.peaking) { Settings.choosePeaking(it) }
                    if (Settings.peaking) {
                        DropdownMenuItem({ Text("Peaking colour: ${Settings.peakingColour.label}  (tap to change)", fontSize = 13.sp) }, {
                            val c = PeakingColour.entries
                            Settings.choosePeakingColour(c[(Settings.peakingColour.ordinal + 1) % c.size])
                        })
                        DropdownMenuItem({ Text("Peaking sensitivity: ${Settings.peakingLevel.label}  (tap to change)", fontSize = 13.sp) }, {
                            val c = PeakingLevel.entries
                            Settings.choosePeakingLevel(c[(Settings.peakingLevel.ordinal + 1) % c.size])
                        })
                    }
                    HorizontalDivider()
                    ScopeKind.entries.forEach { k ->
                        DropdownMenuItem({ Text(k.label + if (k == scopeKind) "  ✓" else "") }, { Settings.chooseScope(k); expoMenu = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem({
                        Text(
                            "Measures the phone's picture (exposed to follow ISO, shutter, ND and iris), through the LUT. " +
                                "A guide for the scene, not the camera's own signal. Peaking shows what is sharp for the phone's lens.",
                            fontSize = 11.sp, color = Color.Gray,
                        )
                    }, {}, enabled = false)
                }
            }
            RoundButton("Notes", size = 42.dp) { notes = true }
        }
        val shutter: @Composable () -> Unit = {
            Box(
                Modifier.size(70.dp).border(3.dp, Color.White, CircleShape).padding(6.dp).background(Color.White, CircleShape)
                    .pointerInput(layout?.frame, video, shot.shotNumber) {
                        detectTapGestures {
                            takeReference(context, phone, layout?.frame, video) { ref, err ->
                                if (ref != null) {
                                    edit { s -> s.copy(references = s.references + ref.copy(shotId = shotId)) }
                                    flash = "Frame saved to shot ${shot.shotNumber}"
                                } else flash = err
                            }
                        }
                    },
            )
        }
        val thumb: @Composable () -> Unit = { Thumbnail(shot.references.lastOrNull()) { nav.push(Dest.Shot(sessionId, sceneId, shotId)) } }

        if (landscape) {
            // ── Landscape: tools left, photo / capture / setup right, W M C and focal lengths along the bottom ──
            Column(Modifier.align(Alignment.CenterStart).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { toolButtons() }
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RoundButton("Setup", size = 46.dp) { setup = true }
                shutter()
                thumb()
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 70.dp, end = 100.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sizeButtons()
                Box(Modifier.weight(1f)) { FocalChips(focals, shot.focalMm, ::setFocal, onCustom = if (lens.isZoom || lens.manufacturer == QuickRecce.GENERIC) ({ customFocal = true }) else null) }
            }
        } else {
            // ── Portrait: W M C left, tools right; exposure, focal lengths and photo / capture / setup at the bottom ──
            Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { sizeButtons() }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { toolButtons() }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ExposureLine(shot, tilt) { exposureSheet = true }
                FocusLine(shot) { exposureSheet = true }
                Spacer(Modifier.height(6.dp))
                FocalChips(focals, shot.focalMm, ::setFocal, onCustom = if (lens.isZoom || lens.manufacturer == QuickRecce.GENERIC) ({ customFocal = true }) else null)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    thumb()
                    Spacer(Modifier.weight(1f))
                    shutter()
                    Spacer(Modifier.weight(1f))
                    RoundButton("Setup", size = 46.dp) { setup = true }
                }
            }
        }
    }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(2500); flash = null } }
    // Reminder (for a few seconds) while the phone's angle of view isn't calibrated.
    LaunchedEffect(phone.camera != null, phone.calibrated) {
        if (phone.camera != null && !phone.calibrated) { calibrationNote = true; kotlinx.coroutines.delay(8000); calibrationNote = false }
    }

    if (setup) RecceSheet(
        title = "Setup", subtitle = "Camera, lens and frame for shot ${shot.shotNumber}.", button = "Apply",
        initial = RecceChoice.of(shot), onDismiss = { setup = false },
    ) { c -> edit { c.applyTo(it) }; setup = false }
    if (exposureSheet) ExposureSheet(
        shot, exposureMode, phone.manualSupported,
        match?.let { fmt("Phone set to ISO %d · 1/%.0f s", it.iso, 1e9 / it.exposureNs) }, ::edit, { flash = it },
    ) { exposureSheet = false }
    if (notes) NoteComposer(sessionId, shotId) { notes = false }
    if (distortionDialog && reference != null) DistortionDialog(
        current = if (Distortion.isCustom(lens)) ShotOptics.distortionPercent(shot) else null,
        lensName = lens.displayName, focal = shot.focalLength,
        onDismiss = { distortionDialog = false },
        onSave = { pct ->
            val k1 = pct?.let { Distortion.k1FromCornerPercent(it, reference.deliveredWidthMm, reference.deliveredHeightMm, reference.effectiveFocalLengthMm) }
            Settings.saveDistortion(lens.id, shot.focalMm, k1)
            if (pct != null) showDistortion = true
            distortionDialog = false
        },
    )
    if (customFocal) FocalDialog(lens, shot.focalMm, onDismiss = { customFocal = false }) { f ->
        customFocal = false
        if (locked) { flash = "Zoom is locked (Guides → Lock zoom)."; return@FocalDialog }
        // Zoom: any focal length in its range. Generic prime set: any focal length (the view depends only on it).
        val target = if (lens.isZoom) lens else FocalOptions.lensFor(lens, f)
        val mm = if (lens.isZoom) lens.clampFocal(f) else f
        edit { s -> (if (target.id != s.lens.id) s.withLens(target) else s).copy(focalLength = ShotPresets.focalText(mm)) }
    }
}

/** "T2.8 · ISO 800 · 24p · 180° · Tilt 0°" (tap for exposure settings). Reads the tilt here so only this line updates. */
@Composable
private fun ExposureLine(shot: RecceShot, tilt: State<Tilt?>, prefix: String? = null, onClick: () -> Unit) {
    val parts = listOfNotNull(
        prefix, tText(shot.aperture), "ISO ${shot.iso}", "${shot.fps}p", shot.shutter,
        tilt.value?.let { "Tilt ${Math.round(it.pitch)}°" },
    )
    Text(
        parts.joinToString(" · "), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        modifier = Modifier.clickable(onClick = onClick).background(Color(0x66000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** "Focus 3.0 m · DoF 2.7–3.3 m" under the exposure line (tap for the focus distance). */
@Composable
private fun FocusLine(shot: RecceShot, onClick: () -> Unit) {
    val d = shot.estimatedDistance ?: return
    val dof = ShotOptics.depthOfField(shot)
    Text(
        listOfNotNull("Focus ${Focus.distanceText(d)}", dof?.let { ShotOptics.dofText(it) }).joinToString(" · "),
        color = if (dof?.tooClose == true) Color(0xFFFFB35C) else Color(0xFFC9D8FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        maxLines = 1, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Horizon line through the frame centre that turns green when the phone is level. */
@Composable
private fun LevelOverlay(frame: ScreenRect?, tilt: State<Tilt?>) {
    val t = tilt.value ?: return
    if (frame == null || frame.width <= 0) return
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset((frame.x + frame.width / 2).toFloat(), (frame.y + frame.height / 2).toFloat())
        val half = (frame.width * 0.3).toFloat()
        val level = abs(t.roll) < 1f
        val col = if (level) Color(0xFF34C759) else Color.White
        rotate(-t.roll, c) { drawLine(col, c - Offset(half, 0f), c + Offset(half, 0f), 2.dp.toPx()) }
        val gap = 6.dp.toPx(); val tick = 12.dp.toPx(); val ref = Color.White.copy(alpha = 0.7f)
        drawLine(ref, c - Offset(half + gap + tick, 0f), c - Offset(half + gap, 0f), 2.dp.toPx())
        drawLine(ref, c + Offset(half + gap, 0f), c + Offset(half + gap + tick, 0f), 2.dp.toPx())
    }
}

/** Horizontal row of focal lengths; the current one is blue. "+" types any focal length (zooms, generic set). */
@Composable
private fun FocalChips(focals: List<Double>, current: Double, onPick: (Double) -> Unit, onCustom: (() -> Unit)? = null) {
    val state = rememberLazyListState()
    val idx = focals.indexOfFirst { abs(it - current) < 0.5 }
    LaunchedEffect(idx, focals.size) { if (idx >= 0) state.animateScrollToItem(max(0, idx - 3)) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (idx < 0 && onCustom != null) item { ChoiceChip(ShotPresets.trim(current), true, mono = true, modifier = Modifier.widthIn(min = 46.dp)) { onCustom() } }
        itemsIndexed(focals) { i, f -> ChoiceChip(ShotPresets.trim(f), i == idx, mono = true, modifier = Modifier.widthIn(min = 46.dp)) { onPick(f) } }
        if (onCustom != null) item { ChoiceChip("+", false, mono = true, modifier = Modifier.widthIn(min = 46.dp)) { onCustom() } }
    }
}

/** Up to six focal lengths (other than [current]) to compare: the whole prime set, or spread over a zoom's stops. */
private fun compareFocals(focals: List<Double>, current: Double): List<Double> {
    val others = focals.filter { abs(it - current) >= 0.5 }
    if (others.size <= 6) return others
    return (0 until 6).map { others[it * (others.size - 1) / 5] }.distinct()
}

/** The frame outline bent by the lens's measured distortion (dotted), over the straight frame lines. */
@Composable
private fun DistortionOverlay(points: List<Pair<Double, Double>>, frame: ScreenRect, tanHalfH: Double, tanHalfV: Double) {
    Canvas(Modifier.fillMaxSize()) {
        if (points.isEmpty() || tanHalfH <= 0 || tanHalfV <= 0) return@Canvas
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, (tx, ty) ->
            val x = (frame.midX + tx / tanHalfH * frame.width / 2).toFloat()
            val y = (frame.midY + ty / tanHalfV * frame.height / 2).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, Color(0xFFFF9F0A), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
    }
}

/** Thin labelled frame lines for the compared focal lengths. */
@Composable
private fun CompareOverlay(frames: List<Pair<Double, ScreenRect>>) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        for ((f, r) in frames) {
            val c = Color(0xFFFFE08A)
            drawRect(c.copy(alpha = 0.85f), Offset(r.x.toFloat(), r.y.toFloat()), Size(r.width.toFloat(), r.height.toFloat()),
                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            drawLabel(measurer, ShotPresets.focalText(f), Offset(r.x.toFloat() + 4.dp.toPx(), r.y.toFloat() + 2.dp.toPx()),
                style = TextStyle(color = c, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
        }
    }
}

/** Type a focal length: anything in a zoom's range, or any value for the generic prime set. */
@Composable
private fun FocalDialog(lens: LensProfile, current: Double, onDismiss: () -> Unit, onDone: (Double) -> Unit) {
    var text by remember { mutableStateOf(ShotPresets.trim(current)) }
    val v = text.replace(',', '.').toDoubleOrNull()
    val ok = v != null && v in 4.0..1200.0 && (!lens.isZoom || v in lens.focalLengthMin..lens.focalLengthMax)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Focal length") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, suffix = { Text("mm") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text(
                    if (lens.isZoom) "${lens.displayName}: ${ShotPresets.trim(lens.focalLengthMin)}–${ShotPresets.focalText(lens.focalLengthMax)}"
                    else "Any focal length (generic spherical lens).",
                    fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton({ v?.let(onDone) }, enabled = ok) { Text("Use") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Last saved frame of this shot (tap to open the shot). */
@Composable
private fun Thumbnail(ref: ShotReference?, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, ref?.filePath) {
        value = ref?.let { r -> withContext(Dispatchers.IO) { Images.load(File(RecceStore.referencesDir, r.fileName), 240) } }
    }
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(9.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5F8BD6), Color(0xFF14295A))))
            .border(2.dp, Color.White, RoundedCornerShape(9.dp)).clickable(onClick = onClick),
    ) {
        bmp?.let { Image(it.asImageBitmap(), "Last frame", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Camera settings for the shot (and the connected camera): frame rate, shutter, ISO, WB, ND, iris, recording mode. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposureSheet(
    shot: RecceShot, exposureMode: PhoneExposure, manualSupported: Boolean, matchInfo: String?,
    edit: ((RecceShot) -> RecceShot) -> Unit, onNote: (String) -> Unit, onDismiss: () -> Unit,
) {
    val link = CameraLink.isConnected
    val lens = shot.lens
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White, contentColor = Brand.ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            Text("Exposure", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Brand.ink)
            Text(if (link) "Changes are sent to ${CameraLink.cameraName ?: "the camera"} too." else "Saved with the shot; the phone preview follows.",
                fontSize = 13.sp, color = Color(0xFF5B6B8C))
            ChipLine("Frame rate", shot.fps, ShotPresets.frameRates) { v ->
                edit { it.copy(fps = v) }
                if (link) v.toDoubleOrNull()?.let { if (!CameraLink.sendFps(Math.round(it).toInt())) onNote("Camera frame rate: waiting for its recording format.") }
            }
            ChipLine("Shutter", shot.shutter, ShotPresets.shutterAngles) { v ->
                edit { it.copy(shutter = v) }; if (link) Exposure.shutterAngle(v)?.let { CameraLink.sendShutterAngle(it) }
            }
            ChipLine("ISO", shot.iso, ShotPresets.isos) { v -> edit { it.copy(iso = v) }; if (link) v.toIntOrNull()?.let { CameraLink.sendIso(it) } }
            ChipLine("White balance", shot.whiteBalance, ShotPresets.whiteBalances) { v ->
                edit { it.copy(whiteBalance = v) }; if (link) v.filter { c -> c.isDigit() }.toIntOrNull()?.let { CameraLink.sendWhiteBalance(it) }
            }
            ChipLine("ND", shot.nd, ShotPresets.ndFilters, display = ::ndShort) { v -> edit { it.copy(nd = v) }; if (link) CameraLink.sendNd(Exposure.ndStops(v)) }
            val stops = T_STOPS.filter { !lens.hasAperture || it >= lens.maximumAperture - 0.001 }.map { ShotPresets.tStopText(it) }
            ChipLine("Iris", tText(shot.aperture), stops) { v -> edit { it.copy(aperture = v) }; if (link) Exposure.stop(v)?.let { CameraLink.sendAperture(it) } }
            // Focus distance → depth of field (and a warning when closer than the lens focuses).
            val distTexts = Focus.presetsM.map { Focus.distanceText(it) }
            ChipLine("Focus distance", shot.estimatedDistance?.let { Focus.distanceText(it) } ?: "", distTexts) { t ->
                val i = distTexts.indexOf(t)
                if (i >= 0) edit { it.copy(estimatedDistance = Focus.presetsM[i]) }
            }
            ShotOptics.depthOfField(shot)?.let { d ->
                Text(
                    ShotOptics.dofText(d) + " · hyperfocal " + Focus.distanceText(d.hyperfocalM) +
                        (if (d.tooClose) fmt("\nCloser than this lens can focus (%.2f m).", lens.minimumFocusDistance) else ""),
                    fontSize = 13.sp, color = if (d.tooClose) Color(0xFFD9341F) else Brand.ink, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text("Circle of confusion ${fmt("%.3f", Focus.circleOfConfusionMm(shot.sensorMode))} mm (sensor diagonal / 1500); T-stop used as the stop.",
                    fontSize = 11.sp, color = Color(0xFF5B6B8C))
            }
            val modes = shot.baseCamera.sensorModes
            if (modes.size > 1) ChipLine("Recording mode", shot.sensorMode.name, modes.map { it.name }) { name ->
                modes.firstOrNull { it.name == name }?.let { m -> edit { it.copy(sensorModeId = m.id) } }
            }
            Text("Phone picture exposure", fontSize = 15.sp, color = Brand.ink, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhoneExposure.entries.forEach { m -> ChoiceChip(m.label, m == exposureMode) { Settings.choosePhoneExposure(m) } }
            }
            Text(
                exposureMode.detail + when {
                    exposureMode == PhoneExposure.MATCH && !manualSupported -> " This phone doesn't allow manual exposure, so it follows instead."
                    exposureMode == PhoneExposure.MATCH && matchInfo != null -> "\n$matchInfo."
                    exposureMode == PhoneExposure.MATCH -> " Needs ISO, shutter, frame rate and iris set."
                    else -> ""
                },
                fontSize = 12.sp, color = Color(0xFF5B6B8C), modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ChipLine(label: String, current: String, options: List<String>, display: (String) -> String = { it }, onPick: (String) -> Unit) {
    StepLabel(label)
    val all = if (current.isEmpty() || current in options) options else listOf(current) + options
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { o -> ChoiceChip(display(o), o == current, mono = true) { onPick(o) } }
    }
}

private fun ndShort(nd: String): String = when {
    nd.startsWith("None", ignoreCase = true) || nd.isBlank() -> "Clear"
    else -> nd.substringBefore(" (").removePrefix("ND ").let { "ND $it" }
}

private fun tText(aperture: String): String {
    val v = Exposure.stop(aperture) ?: return aperture.ifBlank { "T—" }
    return ShotPresets.tStopText(v)
}

@Composable
private fun VfPill(text: String, color: Color) = Text(
    text, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
    modifier = Modifier.padding(top = 6.dp).background(color, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
)

@Composable
private fun ToggleItem(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(text = { Text(label) }, trailingIcon = { Switch(value, onChange) }, onClick = { onChange(!value) })
}

@Composable
internal fun CameraPreview(
    context: Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner, phone: PhoneCamera, rotation: Int,
    lut: Lut3D? = null, lutInput: LutInput = LutInput.REC709, splitPx: Float? = null,
    tools: PictureTools = PictureTools(), scopes: ScopeAnalyzer? = null, scopeKind: ScopeKind = ScopeKind.NONE,
    pictureOn: Boolean = false,
) {
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // Re-bound when the screen turns, so the picture and photos have the right orientation.
    // The scope stream is only bound while a scope is shown (rebinding blinks the picture once).
    val scopeOn = scopes != null && (scopeKind != ScopeKind.NONE || pictureOn)
    DisposableEffect(lifecycleOwner, rotation, scopeOn) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            runCatching {
                val p = future.get()
                provider = p
                val rotation = displayRotation(context)
                val selector = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val preview = Preview.Builder().setResolutionSelector(selector).setTargetRotation(rotation).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder().setResolutionSelector(selector).setTargetRotation(rotation)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.unbindAll()
                val cam = if (scopeOn && scopes != null) {
                    runCatching {
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, scopes.useCase(rotation))
                            .also { scopes.available = true }
                    }.getOrElse {
                        // This phone can't run three streams: keep the picture and photos, no scopes.
                        p.unbindAll(); scopes.available = false
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }
                } else p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                phone.capture = capture
                phone.preview = preview
                phone.readOptics(cam)
                phone.camera = cam
                // Re-apply the zoom chosen before this (re)bind.
                cam.cameraControl.setZoomRatio(phone.zoom.coerceIn(phone.minZoom, phone.maxZoom).toFloat())
            }.onFailure { phone.error = "The camera couldn't start: ${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; provider?.unbindAll(); phone.camera = null }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize(), update = { v ->
        PictureEffect.apply(v, lut, lutInput, splitPx ?: -1f, tools)
    })
}

/**
 * The user's own distortion figure for a lens without a Lensfun profile (most cine lenses): % at the corners of this
 * frame, from a grid / lens test or the maker's chart. Saved for the lens at this focal length.
 */
@Composable
private fun DistortionDialog(current: Double?, lensName: String, focal: String, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.let { fmt("%.1f", it) } ?: "") }
    val v = text.replace(',', '.').replace('−', '-').toDoubleOrNull()
    val ok = v != null && v in -30.0..30.0 && v != 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lens distortion") },
        text = {
            Column {
                Text("$lensName at $focal. Enter what you measured at the corners of this frame (shoot a grid or a straight wall).",
                    fontSize = 13.sp)
                OutlinedTextField(text, { text = it }, singleLine = true, suffix = { Text("%") }, modifier = Modifier.padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                Text("Negative = barrel (e.g. −2), positive = pincushion. Shown as \"your measurement\"; not a maker's figure.",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton({ onSave(v) }, enabled = ok) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton({ onSave(null) }) { Text("Remove", color = Color(0xFFFF453A)) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Record / timecode from the connected camera; reads the timecode here so only this pill redraws as it runs. */
@Composable
private fun RecordPill() = Pill(
    (if (CameraLink.recording) "● REC  " else "● ") + (CameraLink.timecode ?: "Camera connected"), mono = true,
    color = if (CameraLink.recording) Brand.record else Color(0x99000000), textColor = Color.White,
) { CameraLink.record(!CameraLink.recording) }

/** The CPU-processed picture over the camera picture, where the camera picture is drawn ([video]). */
@Composable
private fun CpuPicture(scopes: ScopeAnalyzer, video: ScreenRect) {
    val bmp = scopes.picture ?: return
    val img = remember(bmp, bmp.generationId) { bmp.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        drawImage(
            img, dstOffset = androidx.compose.ui.unit.IntOffset(video.x.toInt(), video.y.toInt()),
            dstSize = IntSize(video.width.toInt(), video.height.toInt()),
        )
    }
}

/** The false colour key: band colours and their levels (ARRI's published bands). */
@Composable
private fun FalseColourLegend() {
    Row(
        Modifier.padding(top = 6.dp).background(Color(0xB3000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FalseColour.bands.forEach { b ->
            Box(Modifier.size(9.dp).background(Color(b.argb), RoundedCornerShape(2.dp)))
            Text(b.label.removeSuffix("%"), color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Text("%", color = Color.White, fontSize = 9.sp)
    }
}

/** Waveform, RGB parade or histogram of the picture inside the frame, with the share of clipped and crushed pixels. Tap to enlarge. */
@Composable
private fun ScopePanel(scopes: ScopeAnalyzer, kind: ScopeKind, modifier: Modifier) {
    // Read here, so only the panel redraws with each scope frame.
    val result: ScopeResult? = scopes.result
    var big by remember { mutableStateOf(false) }
    val pw = if (big) 280.dp else 190.dp
    val ph = if (big) 150.dp else 100.dp
    Column(modifier.background(Color(0xB3000000), RoundedCornerShape(8.dp)).clickable { big = !big }.padding(6.dp)) {
        Box(Modifier.size(pw, ph)) {
            val grid = Color(0x55FFFFFF)
            Canvas(Modifier.fillMaxSize()) {
                if (kind == ScopeKind.HISTOGRAM) {
                    listOf(0.25f, 0.5f, 0.75f).forEach { f -> drawLine(grid, Offset(size.width * f, 0f), Offset(size.width * f, size.height), 1f) }
                } else {
                    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                        val y = size.height * (1 - f)
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = if (f == 0f || f == 1f) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                    }
                    if (kind == ScopeKind.PARADE) listOf(1 / 3f, 2 / 3f).forEach { f -> drawLine(grid, Offset(size.width * f, 0f), Offset(size.width * f, size.height), 1f) }
                }
            }
            val wave = result?.waveform
            val hist = result?.histogram
            when {
                result == null || result.kind != kind -> Text("…", color = Color.White, modifier = Modifier.align(Alignment.Center))
                kind != ScopeKind.HISTOGRAM && wave != null -> Image(wave.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                kind == ScopeKind.HISTOGRAM && hist != null -> Canvas(Modifier.fillMaxSize()) {
                    val peak = hist.peak.toFloat()
                    fun trace(bins: IntArray, color: Color, fill: Boolean) {
                        val path = androidx.compose.ui.graphics.Path()
                        val step = size.width / (bins.size - 1).coerceAtLeast(1)
                        path.moveTo(0f, size.height)
                        bins.forEachIndexed { i, n -> path.lineTo(i * step, size.height * (1 - n / peak)) }
                        path.lineTo(size.width, size.height); path.close()
                        if (fill) drawPath(path, color) else drawPath(path, color, style = Stroke(1.5f))
                    }
                    trace(hist.r, Color(0x66FF4040), true)
                    trace(hist.g, Color(0x6640FF40), true)
                    trace(hist.b, Color(0x664080FF), true)
                    trace(hist.y, Color(0xDDFFFFFF), false)
                }
            }
            if (kind != ScopeKind.HISTOGRAM) {
                Text("100", color = Color(0x99FFFFFF), fontSize = 8.sp, modifier = Modifier.align(Alignment.TopStart))
                Text("50", color = Color(0x99FFFFFF), fontSize = 8.sp, modifier = Modifier.align(Alignment.CenterStart))
            }
        }
        val stats = result?.histogram?.let { fmt(" · Clip %.1f%% · Crush %.1f%%", it.clipped * 100, it.crushed * 100) } ?: ""
        Text(kind.label + stats, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** Draggable divider for the LUT before / after view. */
@Composable
private fun SplitHandle(fraction: Float, width: Float, onChange: (Float) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(fraction)
    val x = fraction * width
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            drawCircle(Color.White, 14.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(Brand.accent, 11.dp.toPx(), Offset(x, size.height / 2))
        }
        Text("BEFORE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = with(density) { (x - 64.dp.toPx()).coerceAtLeast(0f).toDp() }))
        Text("AFTER", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = with(density) { (x + 18.dp.toPx()).toDp() }))
        // The touch strip around the line.
        Box(
            Modifier.width(48.dp).fillMaxHeight(0.6f).align(Alignment.CenterStart)
                .padding(start = 0.dp)
                .offset { IntOffset((x - 24.dp.toPx()).toInt(), 0) }
                .pointerInput(width) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        onChange(((current * width + drag) / width).coerceIn(0.05f, 0.95f))
                    }
                },
        )
    }
}

/** Monitor mask, frame lines, guides and markers. */
@Composable
private fun MonitorOverlay(
    frame: ScreenRect?, lines: FrameLines?, markers: List<ShotMarker>,
    thirds: Boolean, centre: Boolean, safe: Boolean, maskAlpha: Float, modifier: Modifier,
) {
    Canvas(modifier) {
        if (frame == null || frame.width <= 0) return@Canvas
        val mask = Color(0xFF071533).copy(alpha = maskAlpha)
        val fx = frame.x.toFloat(); val fy = frame.y.toFloat(); val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        drawRect(mask, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, fy))
        drawRect(mask, Offset(0f, fy + fh), androidx.compose.ui.geometry.Size(size.width, size.height - fy - fh))
        drawRect(mask, Offset(0f, fy), androidx.compose.ui.geometry.Size(fx, fh))
        drawRect(mask, Offset(fx + fw, fy), androidx.compose.ui.geometry.Size(size.width - fx - fw, fh))
        val clipped = lines != null && !lines.fits
        drawRect(if (clipped) Color(0xFFFF9F0A) else Brand.frameLine, Offset(fx, fy), androidx.compose.ui.geometry.Size(fw, fh), style = Stroke(1.5.dp.toPx()))
        val guide = Color.White.copy(alpha = 0.4f)
        if (thirds) for (i in 1..2) {
            drawLine(guide, Offset(fx + fw * i / 3, fy), Offset(fx + fw * i / 3, fy + fh), 1.dp.toPx())
            drawLine(guide, Offset(fx, fy + fh * i / 3), Offset(fx + fw, fy + fh * i / 3), 1.dp.toPx())
        }
        if (centre) {
            val c = Offset(fx + fw / 2, fy + fh / 2); val l = 14.dp.toPx()
            drawLine(guide, c - Offset(l, 0f), c + Offset(l, 0f), 1.5.dp.toPx())
            drawLine(guide, c - Offset(0f, l), c + Offset(0f, l), 1.5.dp.toPx())
        }
        if (safe) for (fr in listOf(0.9, 0.8)) {
            val s = Viewfinder.safeArea(frame, fr)
            drawRect(guide, Offset(s.x.toFloat(), s.y.toFloat()), androidx.compose.ui.geometry.Size(s.width.toFloat(), s.height.toFloat()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
        }
        // Corner brackets, like a camera monitor's frame guide.
        val b = 18.dp.toPx(); val sw = 3.dp.toPx(); val col = Color.White
        listOf(Offset(fx, fy) to Offset(1f, 1f), Offset(fx + fw, fy) to Offset(-1f, 1f), Offset(fx, fy + fh) to Offset(1f, -1f), Offset(fx + fw, fy + fh) to Offset(-1f, -1f))
            .forEach { (p, d) ->
                drawLine(col, p, p + Offset(d.x * b, 0f), sw)
                drawLine(col, p, p + Offset(0f, d.y * b), sw)
            }
        for (m in markers) {
            val p = Offset(fx + (m.x * fw).toFloat(), fy + (m.y * fh).toFloat())
            drawCircle(markerColor(m.type), 9.dp.toPx(), p)
            drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
}

fun markerColor(t: MarkerType): Color = when (t) {
    MarkerType.CAMERA_POSITION -> Color(0xFF40B3FF)
    MarkerType.SUBJECT_POSITION -> Color(0xFFFF5500)
    MarkerType.KEY_LIGHT -> Color(0xFFFFD60A)
    MarkerType.FILL -> Color(0xFFFFF3B0)
    MarkerType.PRACTICAL -> Color(0xFFFF9F0A)
    MarkerType.WINDOW -> Color(0xFF64D2FF)
}

/** Captures a photo of the whole camera picture and remembers where the cinema frame was in it. */
private fun takeReference(
    context: Context, phone: PhoneCamera, frame: ScreenRect?, video: ScreenRect,
    done: (ShotReference?, String?) -> Unit,
) {
    val capture = phone.capture ?: return done(null, "The camera isn't ready.")
    capture.targetRotation = displayRotation(context)
    val file = RecceStore.newReferenceFile()
    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val ref = if (frame != null && video.width > 0 && video.height > 0) {
                    val f = Monitor.frameInPicture(frame, video)
                    ShotReference(filePath = file.name, frameX = f.x, frameY = f.y, frameWidth = f.width, frameHeight = f.height)
                } else ShotReference(filePath = file.name)
                done(ref, null)
            }
            override fun onError(e: ImageCaptureException) { file.delete(); done(null, "Couldn't save the photo: ${e.message}") }
        },
    )
}
