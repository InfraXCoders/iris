package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.content.pm.ActivityInfo
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun CalibrateScreen(nav: Navigator, lensKey: String? = null) {
    FullScreen(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phone = remember { PhoneCamera() }
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { }
    LaunchedEffect(Unit) { if (!hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }
    // Numbers are typed in a dialog, so the keyboard never covers the panel or moves the camera picture
    // (the lines must stay on the object's edges).
    var editing by remember { mutableStateOf<String?>(null) }

    // Main lens: the original single calibration; other lenses: their own override.
    val savedFov = if (lensKey == null) Settings.fovCalibration else Settings.fovOverrides[lensKey]
    fun saveFov(v: Double?) = if (lensKey == null) Settings.saveFovCalibration(v) else Settings.saveFovOverride(lensKey, v)
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
        // The lens being calibrated (e.g. the ultra-wide the viewfinder switched to); null = the main lens.
        val target = lensKey?.let { k -> phone.lenses.firstOrNull { it.key == k } }
        if (hasCamera) CameraPreview(context, lifecycleOwner, phone, 0, lens = target)
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

        when (editing) {
            "width" -> NumberDialog("Object width", "cm", widthText, { editing = null }) { widthText = it.replace(',', '.'); saved = null; editing = null }
            "distance" -> NumberDialog("Distance from the phone", "m", distanceText, { editing = null }) { distanceText = it.replace(',', '.'); saved = null; editing = null }
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
                ValueBox("Object width", widthText.takeIf { it.isNotBlank() }?.let { "$it cm" }, Modifier.weight(1f)) { editing = "width" }
                ValueBox("Distance", distanceText.takeIf { it.isNotBlank() }?.let { "$it m" }, Modifier.weight(1f)) { editing = "distance" }
            }
            Text(fmt("Lines: %.0f%% of the camera picture's width", fraction * 100), color = Color(0xFF5B6B8C), fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            val result = when {
                widthM == null || distanceM == null -> "Tap Object width and Distance to enter them."
                longFov == null -> "Move the lines apart, onto the object's edges."
                reported != null -> fmt("Measured %.1f° · phone reports %.1f° (frame lines %+.1f%%)", longFov, reported,
                    Calibration.errorPercent(reported, longFov))
                else -> fmt("Measured %.1f° (the phone doesn't report its angle)", longFov)
            }
            Text(result, color = Brand.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
            Text(
                (if (lensKey != null) "Calibrating the ultra-wide lens. " else "") +
                if (savedFov != null) fmt("Your calibration is in use: %.1f° (overrides the automatic figure).", savedFov)
                else "Optional. Without it the app uses the angle the phone reports for its lens (auto-calibrated).",
                color = Color(0xFF5B6B8C), fontSize = 12.sp,
            )
            saved?.let { Text(it, color = Color(0xFF1E8E3E), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Use automatic", Brand.lightChip, Brand.ink, Modifier.weight(1f)) {
                    saveFov(null); saved = "Calibration removed: using the automatic figure."
                }
                ActionButton("Save", if (longFov != null) Brand.accent else Color(0xFFB5C2DF), Color.White, Modifier.weight(1f)) {
                    // Sanity limits: phone lenses are roughly 50–130° across the long side (main to ultra-wide).
                    saved = when {
                        longFov == null -> "Enter the width and distance, and put the lines on the object's edges."
                        longFov in 40.0..140.0 -> { saveFov(longFov); fmt("Saved: %.1f°. Frame lines now use it.", longFov) }
                        else -> fmt("%.0f° looks wrong for a phone camera; check the width (cm), distance (m) and lines.", longFov)
                    }
                }
            }
        }
    }
}

/** A tappable value ("Object width · 90 cm"), edited in [NumberDialog]. */
@Composable
private fun ValueBox(label: String, value: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.border(1.dp, if (value == null) Brand.accent else Color(0xFFB5C2DF), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color(0xFF5B6B8C), fontSize = 12.sp)
        Text(value ?: "Tap to enter", color = if (value == null) Brand.accent else Brand.ink, fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

/** Number entry in a dialog (its own window, so the keyboard doesn't disturb the camera picture). */
@Composable
private fun NumberDialog(title: String, unit: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val v = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, suffix = { Text(unit) }, modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (v != null && v > 0) onDone(text) }),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton({ onDone(text) }, enabled = v != null && v > 0) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = modifier.background(bg, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 13.dp),
    )
}
