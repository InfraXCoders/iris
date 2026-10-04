package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.core.FrameLines
import com.infraxcoders.bmpcc.core.MarkerType
import com.infraxcoders.bmpcc.core.Optics
import com.infraxcoders.bmpcc.core.PreviewView as PhoneView
import com.infraxcoders.bmpcc.core.ScreenRect
import com.infraxcoders.bmpcc.core.ShotMarker
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.core.ShotReference
import com.infraxcoders.bmpcc.core.Viewfinder
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.hasPermission
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/** The phone's back camera: its field of view and zoom range. */
class PhoneCamera {
    var camera by mutableStateOf<Camera?>(null)
    var capture: ImageCapture? = null
    /** Field of view across the stream's long side at zoom 1, degrees. */
    var longSideFov by mutableStateOf(70.0)
    var fovMeasured by mutableStateOf(false)
    var streamAspect by mutableStateOf(4.0 / 3.0)
    var minZoom by mutableStateOf(1.0)
    var maxZoom by mutableStateOf(4.0)
    var zoom by mutableStateOf(1.0)
    var error by mutableStateOf<String?>(null)

    fun applyZoom(z: Double) {
        val c = camera ?: return
        val v = z.coerceIn(minZoom, maxZoom)
        zoom = v
        c.cameraControl.setZoomRatio(v.toFloat())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun readOptics(c: Camera) {
        runCatching {
            val info = Camera2CameraInfo.from(c.cameraInfo)
            val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            if (physical != null && pixels != null && active != null && focal != null && focal > 0) {
                val activeW = physical.width * active.width().toDouble() / pixels.width
                val activeH = physical.height * active.height().toDouble() / pixels.height
                val long = max(activeW, activeH)
                val short = min(activeW, activeH)
                // A 4:3 stream uses the full width of a 4:3 sensor; on a wider sensor it is cropped at the sides.
                val streamLong = min(long, short * streamAspect)
                longSideFov = 2 * Optics.degrees(atan(streamLong / (2 * focal)))
                fovMeasured = true
            }
        }
        c.cameraInfo.zoomState.value?.let {
            minZoom = it.minZoomRatio.toDouble()
            maxZoom = it.maxZoomRatio.toDouble()
            zoom = it.zoomRatio.toDouble()
        }
    }
}

@Composable
fun ViewfinderScreen(nav: Navigator, sessionId: String, sceneId: String, shotId: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessions by RecceStore.sessions.collectAsState()
    val shot = sessions.firstOrNull { it.id == sessionId }?.scenes?.firstOrNull { it.id == sceneId }?.shots?.firstOrNull { it.id == shotId }
        ?: return Gone(nav)
    val phone = remember { PhoneCamera() }
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { phone.error = "Camera access is off. Allow it in the phone's settings to use the viewfinder." }
    var autoZoom by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var showThirds by remember { mutableStateOf(true) }
    var showSafe by remember { mutableStateOf(false) }
    var showCentre by remember { mutableStateOf(true) }
    var markerType by remember { mutableStateOf<MarkerType?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var markerMenu by remember { mutableStateOf(false) }
    var aspectMenu by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var flash by remember { mutableStateOf<String?>(null) }
    fun edit(change: (com.infraxcoders.bmpcc.core.RecceShot) -> com.infraxcoders.bmpcc.core.RecceShot) =
        RecceStore.updateShot(sessionId, sceneId, shotId, change)

    LaunchedEffect(Unit) { if (!hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }

    val reference = shot.reference
    val base = PhoneView.fromFormat(phone.longSideFov, phone.streamAspect, portrait)
    // Auto zoom so the cinema frame fills the screen.
    LaunchedEffect(reference, base, autoZoom, locked, phone.camera, phone.minZoom, phone.maxZoom) {
        if (autoZoom && !locked && reference != null && phone.camera != null) {
            phone.applyZoom(Viewfinder.bestZoom(reference, base, max(phone.minZoom, 0.5), phone.maxZoom))
        }
    }
    val lines = reference?.let { Viewfinder.frameLines(it, base.zoomed(phone.zoom)) }
    val video = Viewfinder.aspectFit(if (portrait) 1 / phone.streamAspect else phone.streamAspect, boxSize.width.toDouble(), boxSize.height.toDouble())
    val frame = lines?.let { Viewfinder.frameRect(it, video) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
            if (hasCamera) CameraPreview(context, lifecycleOwner, phone)
            Overlay(frame, video, lines, shot.markers, showThirds, showSafe, showCentre, Modifier.fillMaxSize().pointerInput(markerType, frame) {
                detectTapGestures { p ->
                    val t = markerType ?: return@detectTapGestures
                    val f = frame ?: return@detectTapGestures
                    Viewfinder.normalisedPoint(p.x.toDouble(), p.y.toDouble(), f)?.let { (x, y) ->
                        edit { it.copy(markers = it.markers + ShotMarker(shotId = shotId, type = t, x = x, y = y)) }
                    }
                }
            })
        }

        // Top HUD
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ nav.pop() }, Modifier.background(Brand.panel, CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(shot.cameraModel)
                    if (shot.baseCamera.sensorModes.size > 1) Chip(shot.sensorMode.name)
                    Chip("${shot.focalLength} ${shot.lens.series ?: ""}".trim())
                    Chip(shot.aspectRatio, highlighted = true)
                    reference?.let { Chip("${it.deliveredFov.horizontal.degreesText()} H") }
                }
                IconButton({ locked = !locked }, Modifier.background(if (locked) Brand.locked else Brand.panel, CircleShape)) {
                    Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, "Lock", tint = Color.White)
                }
                Spacer(Modifier.width(6.dp))
                Box {
                    IconButton({ settingsOpen = true }, Modifier.background(Brand.panel, CircleShape)) { Icon(Icons.Filled.Tune, "Settings", tint = Color.White) }
                    DropdownMenu(settingsOpen, { settingsOpen = false }) {
                        ToggleItem("Auto zoom to fit the frame", autoZoom) { autoZoom = it }
                        ToggleItem("Rule of thirds", showThirds) { showThirds = it }
                        ToggleItem("Centre mark", showCentre) { showCentre = it }
                        ToggleItem("Safe areas (90% / 80%)", showSafe) { showSafe = it }
                    }
                }
            }
            val warn = when {
                phone.error != null -> phone.error
                lines != null && !lines.fits -> "The cinema frame is wider than this phone camera can show. The frame lines are clipped."
                !phone.fovMeasured && phone.camera != null -> "This phone didn't report its lens angle; frame lines use 70°."
                else -> null
            }
            warn?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).background(Color(0xCC8A3A00), RoundedCornerShape(6.dp)).padding(8.dp)) }
            flash?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).background(Brand.panel, RoundedCornerShape(6.dp)).padding(8.dp)) }
            markerType?.let { Text("Tap inside the frame to place: ${it.label}", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp).background(Brand.panel, RoundedCornerShape(6.dp)).padding(8.dp)) }
        }

        // Bottom HUD
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!autoZoom) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Brand.panel, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp)) {
                    Text(fmt("%.1fx", phone.zoom), color = Color.White, fontSize = 12.sp)
                    Slider(phone.zoom.toFloat(), { phone.applyZoom(it.toDouble()) }, valueRange = phone.minZoom.toFloat()..max(phone.maxZoom, phone.minZoom + 0.1).toFloat(), enabled = !locked)
                }
            }
            val lens = shot.lens
            if (lens.quickFocalLengths.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lens.quickFocalLengths.forEach { f ->
                        Chip(ShotPresets.focalText(f), highlighted = abs(shot.focalMm - f) < 0.5) { if (!locked) edit { it.copy(focalLength = ShotPresets.focalText(f)) } }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box {
                    TextButton({ aspectMenu = true }, Modifier.background(Brand.panel, RoundedCornerShape(50))) { Text(shot.aspectRatio, color = Color.White) }
                    DropdownMenu(aspectMenu, { aspectMenu = false }) {
                        ShotPresets.aspectRatios.forEach { a -> DropdownMenuItem({ Text(a) }, { edit { it.copy(aspectRatio = a) }; aspectMenu = false }) }
                    }
                }
                // Shutter
                Box(
                    Modifier.size(72.dp).background(Color.White, CircleShape).padding(5.dp).background(Color.Black, CircleShape).padding(3.dp)
                        .background(Color.White, CircleShape)
                        .pointerInput(frame, video) {
                            detectTapGestures {
                                takeReference(context, phone, frame, video) { ref, err ->
                                    if (ref != null) {
                                        edit { s -> s.copy(references = s.references + ref.copy(shotId = shotId)) }
                                        flash = "Reference saved to shot ${shot.shotNumber}"
                                    } else flash = err
                                }
                            }
                        },
                )
                Box {
                    IconButton({ markerMenu = true }, Modifier.background(if (markerType != null) Brand.accent else Brand.panel, CircleShape)) {
                        Icon(Icons.Filled.Place, "Markers", tint = Color.White)
                    }
                    DropdownMenu(markerMenu, { markerMenu = false }) {
                        DropdownMenuItem({ Text("No marker (tap does nothing)") }, { markerType = null; markerMenu = false })
                        MarkerType.entries.forEach { t -> DropdownMenuItem({ Text(t.label) }, { markerType = t; markerMenu = false }) }
                        if (shot.markers.isNotEmpty()) DropdownMenuItem({ Text("Remove all markers", color = Color(0xFFFF453A)) }, {
                            edit { it.copy(markers = emptyList()) }; markerMenu = false
                        })
                    }
                }
            }
        }
    }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(2500); flash = null } }
}

@Composable
private fun ToggleItem(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Switch(value, onChange) },
        onClick = { onChange(!value) },
    )
}

@Composable
private fun Chip(text: String, highlighted: Boolean = false, onClick: (() -> Unit)? = null) {
    val m = Modifier.background(if (highlighted) Brand.accent else Brand.panel, RoundedCornerShape(50))
    if (onClick != null) {
        TextButton(onClick, m) { Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = m.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
    }
}

@Composable
private fun CameraPreview(context: Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner, phone: PhoneCamera) {
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val selector = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val preview = Preview.Builder().setResolutionSelector(selector).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder().setResolutionSelector(selector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                phone.capture = capture
                preview.resolutionInfo?.resolution?.let { r: Size -> phone.streamAspect = max(r.width, r.height).toDouble() / min(r.width, r.height) }
                phone.readOptics(cam)
                phone.camera = cam
            }.onFailure { phone.error = "The camera couldn't start: ${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll(); phone.camera = null }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}

/** Frame lines, mask, guides and markers over the camera picture. */
@Composable
private fun Overlay(
    frame: ScreenRect?, video: ScreenRect, lines: FrameLines?, markers: List<ShotMarker>,
    thirds: Boolean, safe: Boolean, centre: Boolean, modifier: Modifier,
) {
    Canvas(modifier) {
        if (frame == null || video.width <= 0) return@Canvas
        val mask = Color.Black.copy(alpha = 0.55f)
        val fx = frame.x.toFloat(); val fy = frame.y.toFloat(); val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        val vx = video.x.toFloat(); val vy = video.y.toFloat(); val vw = video.width.toFloat(); val vh = video.height.toFloat()
        // Darken the picture outside the cinema frame.
        drawRect(mask, Offset(vx, vy), GSize(vw, fy - vy))
        drawRect(mask, Offset(vx, fy + fh), GSize(vw, vy + vh - fy - fh))
        drawRect(mask, Offset(vx, fy), GSize(fx - vx, fh))
        drawRect(mask, Offset(fx + fw, fy), GSize(vx + vw - fx - fw, fh))
        val clipped = lines != null && !lines.fits
        drawRect(if (clipped) Color(0xFFFF9F0A) else Brand.frameLine, Offset(fx, fy), GSize(fw, fh), style = Stroke(2.dp.toPx()))
        val guide = Color.White.copy(alpha = 0.45f)
        if (thirds) for (i in 1..2) {
            drawLine(guide, Offset(fx + fw * i / 3, fy), Offset(fx + fw * i / 3, fy + fh), 1.dp.toPx())
            drawLine(guide, Offset(fx, fy + fh * i / 3), Offset(fx + fw, fy + fh * i / 3), 1.dp.toPx())
        }
        if (centre) {
            val c = Offset(fx + fw / 2, fy + fh / 2); val l = 12.dp.toPx()
            drawLine(guide, c - Offset(l, 0f), c + Offset(l, 0f), 1.5.dp.toPx())
            drawLine(guide, c - Offset(0f, l), c + Offset(0f, l), 1.5.dp.toPx())
        }
        if (safe) for (fr in listOf(0.9, 0.8)) {
            val s = Viewfinder.safeArea(frame, fr)
            drawRect(guide, Offset(s.x.toFloat(), s.y.toFloat()), GSize(s.width.toFloat(), s.height.toFloat()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
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

/** Captures a photo and remembers where the frame lines were, as fractions of the photo. */
private fun takeReference(
    context: Context, phone: PhoneCamera, frame: ScreenRect?, video: ScreenRect,
    done: (ShotReference?, String?) -> Unit,
) {
    val capture = phone.capture ?: return done(null, "The camera isn't ready.")
    @Suppress("DEPRECATION")
    val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay?.rotation ?: Surface.ROTATION_0
    capture.targetRotation = rotation
    val file = RecceStore.newReferenceFile()
    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val f = frame
                val ref = if (f != null && video.width > 0 && video.height > 0) ShotReference(
                    filePath = file.name,
                    frameX = ((f.x - video.x) / video.width).coerceIn(0.0, 1.0),
                    frameY = ((f.y - video.y) / video.height).coerceIn(0.0, 1.0),
                    frameWidth = (f.width / video.width).coerceIn(0.0, 1.0),
                    frameHeight = (f.height / video.height).coerceIn(0.0, 1.0),
                ) else ShotReference(filePath = file.name)
                done(ref, null)
            }
            override fun onError(e: ImageCaptureException) { file.delete(); done(null, "Couldn't save the photo: ${e.message}") }
        },
    )
}
