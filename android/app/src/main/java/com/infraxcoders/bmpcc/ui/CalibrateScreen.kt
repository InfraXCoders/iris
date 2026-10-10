package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.core.Calibration
import com.infraxcoders.bmpcc.core.Monitor
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.hasPermission
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Measures this phone's real angle of view. Put a flat object of known width (a door, a table, a tape stretched
 * between two marks) square to the phone at a measured distance, drag the two blue lines onto its edges, type the
 * width and distance, and save. Frame lines everywhere then use the measured angle.
 */
@Composable
fun CalibrateScreen(nav: Navigator) {
    FullScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phone = remember { PhoneCamera() }
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { }
    LaunchedEffect(Unit) { if (!hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }
    // The keyboard must not shrink the camera picture (the lines would no longer sit on the object's edges).
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val old = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        onDispose { if (old != null) window?.setSoftInputMode(old) }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var left by remember { mutableFloatStateOf(-1f) }
    var right by remember { mutableFloatStateOf(-1f) }
    var widthText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(size) { if (size.width > 0 && left < 0) { left = size.width * 0.3f; right = size.width * 0.7f } }

    // Portrait: the screen's width shows part of the camera picture's short side (the picture fills the screen).
    val video = Monitor.aspectFill(1 / phone.streamAspect, size.width.toDouble(), size.height.toDouble())
    val fraction = if (video.width > 0) abs(right - left) / video.width else 0.0
    val widthM = widthText.replace(',', '.').toDoubleOrNull()?.div(100)
    val distanceM = distanceText.replace(',', '.').toDoubleOrNull()
    val shortFov = if (widthM != null && distanceM != null) Calibration.fovFromMeasurement(widthM, distanceM, fraction) else null
    val longFov = shortFov?.let { Calibration.otherSide(it, phone.streamAspect) }
    val reported = phone.reportedFov

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
        if (hasCamera) CameraPreview(context, lifecycleOwner, phone, 0)
        // Two draggable edge lines.
        Canvas(
            Modifier.fillMaxSize().pointerInput(size) {
                detectDragGestures { change, drag ->
                    val x = change.position.x
                    if (abs(x - left) <= abs(x - right)) left = (left + drag.x).coerceIn(0f, size.width.toFloat())
                    else right = (right + drag.x).coerceIn(0f, size.width.toFloat())
                }
            },
        ) {
            for (x in listOf(left, right)) if (x >= 0) {
                drawLine(Brand.accent, Offset(x, 0f), Offset(x, this.size.height), 3.dp.toPx())
                drawCircle(Brand.accent, 12.dp.toPx(), Offset(x, this.size.height / 2))
            }
            if (left >= 0) drawLine(Color.White.copy(alpha = 0.8f), Offset(min(left, right), this.size.height / 2),
                Offset(max(left, right), this.size.height / 2), 1.5.dp.toPx())
        }

        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            BackSquare { nav.pop() }
            Spacer(Modifier.width(12.dp))
            Text("Calibrate phone camera", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .pointerInput(Unit) { detectDragGestures { _, _ -> } } // drags on the panel don't move the lines
                .padding(14.dp),
        ) {
            Text(
                "Place a flat object of known width square to the phone (a door, a table edge, tape between two marks). " +
                    "Measure from the phone to the object. Drag the blue lines onto its left and right edges.",
                color = Brand.ink, fontSize = 13.sp,
            )
            Text("For best accuracy: 2–5 m away, object across at least half the screen, phone level.",
                color = Color(0xFF5B6B8C), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Object width (cm)", widthText, Modifier.weight(1f)) { widthText = it; saved = null }
                NumberField("Distance (m)", distanceText, Modifier.weight(1f)) { distanceText = it; saved = null }
            }
            val result = when {
                longFov == null -> "Enter the width and distance."
                reported != null -> fmt("Measured %.1f° · phone reports %.1f° (frame lines %+.1f%%)", longFov, reported,
                    Calibration.errorPercent(reported, longFov))
                else -> fmt("Measured %.1f° (the phone doesn't report its angle)", longFov)
            }
            Text(result, color = Brand.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
            Text(
                if (Settings.fovCalibration != null) fmt("Saved calibration: %.1f°", Settings.fovCalibration) else "Not calibrated: using the phone's own figure.",
                color = Color(0xFF5B6B8C), fontSize = 12.sp,
            )
            saved?.let { Text(it, color = Color(0xFF1E8E3E), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Use phone's figure", Brand.lightChip, Brand.ink, Modifier.weight(1f)) {
                    Settings.saveFovCalibration(null); saved = "Calibration removed."
                }
                ActionButton("Save", if (longFov != null) Brand.accent else Color(0xFFB5C2DF), Color.White, Modifier.weight(1f)) {
                    // Sanity limits: a phone's main camera is roughly 50–100° across its long side.
                    if (longFov != null && longFov in 40.0..110.0) {
                        Settings.saveFovCalibration(longFov); saved = fmt("Saved: %.1f°. Frame lines now use it.", longFov)
                    } else if (longFov != null) saved = "That result looks wrong; check the width, distance and lines."
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Brand.ink, unfocusedTextColor = Brand.ink,
            focusedLabelColor = Brand.accent, unfocusedLabelColor = Color(0xFF5B6B8C)),
    )
}

@Composable
private fun ActionButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = modifier.background(bg, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 13.dp),
    )
}
