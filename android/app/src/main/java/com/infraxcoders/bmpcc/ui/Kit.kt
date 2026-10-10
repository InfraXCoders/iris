package com.infraxcoders.bmpcc.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Building blocks of the camera-style screens: light round buttons, pills and chips on the navy background.

/** Round light button with a small label and (optionally) a value under it, e.g. "ISO / 800". Blue when selected. */
@Composable
fun RoundButton(
    label: String, value: String? = null, selected: Boolean = false, size: Dp = 46.dp,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val fg = if (selected) Color.White else Brand.ink
    Column(
        modifier.size(size).background(if (selected) Brand.accent else Brand.light, CircleShape).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (value == null) fg else fg.copy(alpha = 0.75f), fontSize = if (value == null) 10.sp else 8.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center)
        if (value != null) Text(value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** Light rounded pill, e.g. "Pocket 6K Pro" or "2.39:1". */
@Composable
fun Pill(
    text: String, modifier: Modifier = Modifier, mono: Boolean = false, color: Color = Brand.light,
    textColor: Color = Brand.ink, leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.background(color, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke()
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

/** Selectable chip for the sheets: light with navy text, blue when chosen. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, modifier: Modifier = Modifier, mono: Boolean = false, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.White else Brand.ink, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.Center,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        modifier = modifier.background(if (selected) Brand.accent else Brand.lightChip, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Brand.accent else Color(0xFFC9D3EE), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

/** Small light square with a "‹", used as the back button on the camera screens. */
@Composable
fun BackSquare(onClick: () -> Unit) {
    Box(Modifier.size(36.dp).background(Brand.light, RoundedCornerShape(9.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = Brand.ink)
    }
}

/** Numbered step title in a sheet: "1 · CAMERA". */
@Composable
fun StepLabel(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(), color = Color(0xFF5B6B8C), fontSize = 12.sp, letterSpacing = 1.5.sp, fontFamily = FontFamily.Monospace,
    modifier = modifier.padding(top = 14.dp, bottom = 8.dp),
)

/**
 * While shown: full screen (system bars hidden, swipe to show), screen kept on, and the given orientation
 * (default: follows the phone's sensor into portrait or either landscape, even with auto-rotate off).
 * The previous orientation and bars come back when the screen closes.
 */
@Composable
fun FullScreen(orientation: Int = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity()
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = orientation
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = before
        }
    }
}

/** Screen rotation in degrees (0, 90, 180, 270), updated when the phone turns, including landscape ↔ reverse landscape. */
@Composable
fun rememberDisplayRotation(): Int {
    val context = LocalContext.current
    fun read(): Int = when (displayRotation(context)) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }
    var rotation by remember { mutableIntStateOf(read()) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { rotation = read() }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        dm?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(listener) }
    }
    return rotation
}
