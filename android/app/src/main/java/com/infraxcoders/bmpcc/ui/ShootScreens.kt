package com.infraxcoders.bmpcc.ui

import android.Manifest
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.ble.CameraLink
import com.infraxcoders.bmpcc.core.Bmd
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.platform.hasPermission
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────────── Connect camera ─────────────────────────────

/** Finds Blackmagic cameras over Bluetooth and pairs; goes on to Shoot mode once connected. */
@Composable
fun ConnectScreen(nav: Navigator) {
    var message by remember { mutableStateOf<String?>(null) }
    val start = rememberBleStarter { message = it }
    val phase = CameraLink.phase
    fun rescan() { message = null; start { CameraLink.stopScan(); CameraLink.startScan() } }

    LaunchedEffect(Unit) { if (!CameraLink.isConnected && phase == CameraLink.Phase.IDLE) rescan() }
    LaunchedEffect(CameraLink.isConnected) { if (CameraLink.isConnected) nav.replace(Dest.Shoot) }
    DisposableEffect(Unit) { onDispose { if (CameraLink.phase == CameraLink.Phase.SCANNING) CameraLink.stopScan() } }

    Column(Modifier.fillMaxSize().background(Brand.background).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackSquare { nav.pop() }
            Spacer(Modifier.width(14.dp))
            Text("Connect camera", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Radar(active = phase != CameraLink.Phase.IDLE, modifier = Modifier.align(Alignment.CenterHorizontally).size(170.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            when (phase) {
                CameraLink.Phase.SCANNING -> "Scanning for cameras…"
                CameraLink.Phase.IDLE -> if (CameraLink.found.isEmpty()) "No camera found yet." else "Tap Connect on your camera."
                else -> phase.label
            },
            color = Color(0xFFD5DDF0), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        (message ?: CameraLink.error)?.let {
            Text(it, color = Color(0xFFFFB35C), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(CameraLink.found, key = { it.device.address }) { f ->
                val busy = f.device.address == CameraLink.deviceAddress &&
                    phase in listOf(CameraLink.Phase.CONNECTING, CameraLink.Phase.PAIRING, CameraLink.Phase.SETTING_UP)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (busy) phase.label else signalText(f.rssi), color = Color(0xFFD5DDF0), fontSize = 12.sp)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Brand.light, strokeWidth = 2.dp)
                    else Pill("Connect", color = Brand.lightChip) { start { CameraLink.connect(f.device) } }
                }
            }
        }
        Text(
            if (phase == CameraLink.Phase.PAIRING) "Type the 6-digit PIN shown on the camera's screen when Android asks."
            else "Turn on Bluetooth on the camera (Settings → Setup → Bluetooth) and keep it close.",
            color = Color(0xFFD5DDF0), fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp),
        )
        Text(
            if (phase == CameraLink.Phase.SCANNING) "Stop" else "Rescan", color = Brand.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Brand.lightChip, RoundedCornerShape(14.dp))
                .clickable(enabled = phase == CameraLink.Phase.IDLE || phase == CameraLink.Phase.SCANNING) {
                    if (phase == CameraLink.Phase.SCANNING) CameraLink.stopScan() else rescan()
                }.padding(vertical = 15.dp),
        )
    }
}

private fun signalText(rssi: Int): String = when {
    rssi == 0 -> "Paired before"
    rssi > -60 -> "Strong signal"
    rssi > -78 -> "Medium signal"
    else -> "Weak signal · move closer"
}

/** Concentric rings with a pulsing ring while searching, and the blue "BLE" dot. */
@Composable
private fun Radar(active: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color.White.copy(alpha = 0.85f), r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFF3A5591), r * 0.64f, style = Stroke(1.dp.toPx()))
            if (active) drawCircle(Brand.accent.copy(alpha = 1f - pulse), r * (0.35f + 0.65f * pulse), style = Stroke(2.dp.toPx()))
        }
        Box(Modifier.size(58.dp).background(Brand.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("BLE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ───────────────────────────── Shoot mode ─────────────────────────────

private enum class Setting(val label: String, val title: String) {
    FPS("FPS", "Frame rate"), SHTR("SHTR", "Shutter angle"), IRIS("IRIS", "Iris (f-stop)"),
    ISO("ISO", "ISO"), WB("WB", "White balance (K)"), ND("ND", "ND filter (stops)"),
}

private val FPS_STEPS = listOf(24.0, 25.0, 30.0, 48.0, 50.0, 60.0)
private val ANGLE_STEPS = listOf(11.2, 15.0, 22.5, 45.0, 60.0, 90.0, 120.0, 144.0, 172.8, 180.0, 216.0, 270.0, 324.0, 360.0)
private val IRIS_STEPS = listOf(1.4, 1.8, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)
private val ISO_STEPS = listOf(100.0, 125.0, 160.0, 200.0, 250.0, 320.0, 400.0, 500.0, 640.0, 800.0, 1000.0, 1250.0, 1600.0,
    2000.0, 2500.0, 3200.0, 4000.0, 5000.0, 6400.0, 8000.0, 12800.0, 25600.0)
private val WB_STEPS = listOf(2500.0, 2800.0, 3200.0, 3600.0, 4000.0, 4500.0, 5000.0, 5600.0, 6000.0, 6500.0, 7500.0, 8000.0, 10000.0)
private val ND_STEPS = listOf(0.0, 2.0, 4.0, 6.0)

private fun Setting.steps(): List<Double> = when (this) {
    Setting.FPS -> FPS_STEPS; Setting.SHTR -> ANGLE_STEPS; Setting.IRIS -> IRIS_STEPS
    Setting.ISO -> ISO_STEPS; Setting.WB -> WB_STEPS; Setting.ND -> ND_STEPS
}

private fun Setting.current(): Double? = when (this) {
    Setting.FPS -> CameraLink.fps?.toDouble()
    Setting.SHTR -> CameraLink.shutterAngle
    Setting.IRIS -> CameraLink.fNumber?.let { Math.round(it * 10) / 10.0 }
    Setting.ISO -> CameraLink.iso?.toDouble()
    Setting.WB -> CameraLink.whiteBalance?.toDouble()
    Setting.ND -> CameraLink.ndStops
}

private fun Setting.text(v: Double?): String = if (v == null) "—" else when (this) {
    Setting.ND -> if (v < 0.5) "CLR" else ShotPresets.trim(v)
    Setting.IRIS -> ShotPresets.trim(v)
    else -> ShotPresets.trim(v)
}

private fun Setting.send(v: Double): Boolean {
    when (this) {
        Setting.FPS -> return CameraLink.sendFps(v.roundToInt())
        Setting.SHTR -> CameraLink.sendShutterAngle(v)
        Setting.IRIS -> CameraLink.sendAperture(v)
        Setting.ISO -> CameraLink.sendIso(v.roundToInt())
        Setting.WB -> CameraLink.sendWhiteBalance(v.roundToInt())
        Setting.ND -> CameraLink.sendNd(v)
    }
    return true
}

/** "6144 × 3456" → "6K", using Blackmagic's names for the usual widths. */
private fun formatName(resolution: String?): String? {
    val w = resolution?.substringBefore("×")?.trim()?.toIntOrNull() ?: return null
    return when (w) {
        6144, 6048 -> "6K"; 5744 -> "5.7K"; 4096 -> "4K"; 3840 -> "UHD"; 3728 -> "3.7K"; 2880 -> "2.8K"
        2688 -> "2.6K"; 1920 -> "HD"; 12288 -> "12K"; 8192 -> "8K"
        else -> fmt("%.1fK", w / 1024.0).replace(".0K", "K")
    }
}

/**
 * Live control of the connected camera: record, timecode, and FPS / shutter / iris / ISO / WB / ND on round
 * buttons with a slider. The phone's own camera shows behind as a framing aid (the camera's picture can't be sent
 * over Bluetooth).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShootScreen(nav: Navigator) {
    FullScreen()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phone = remember { PhoneCamera() }
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { }
    var phoneView by rememberSaveable { mutableStateOf(true) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Setting?>(Setting.ISO) }
    var menu by remember { mutableStateOf(false) }
    var lutMenu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val connected = CameraLink.isConnected
    val rec = CameraLink.recording
    val rotation = rememberDisplayRotation()
    val landscape = rotation == 90 || rotation == 270

    LaunchedEffect(phoneView) { if (phoneView && !hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(2500); note = null } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF5F8BD6), Color(0xFF2B4C8F), Brand.background)))) {
        if (phoneView && hasCamera) CameraPreview(context, lifecycleOwner, phone, rotation)
        // Grid (thirds) and soft shading behind the controls.
        Canvas(Modifier.fillMaxSize()) {
            if (grid) {
                val c = Color.White.copy(alpha = 0.28f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                }
            }
            drawRect(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent), 0f, 140.dp.toPx()), size = size.copy(height = 140.dp.toPx()))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)), size.height - 200.dp.toPx(), size.height),
                topLeft = Offset(0f, size.height - 200.dp.toPx()), size = size.copy(height = 200.dp.toPx()))
        }

        // ── Top: standby/record, timecode, battery and format ──
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (landscape) 10.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(if (rec) "REC" else "STBY", textColor = if (rec) Color.White else Brand.ink,
                color = if (rec) Brand.record else Brand.light,
                leading = { Box(Modifier.size(7.dp).background(if (rec) Color.White else Color(0xFF5B6B8C), CircleShape)) },
                onClick = { if (!connected) nav.replace(Dest.Connect) })
            Text(
                CameraLink.timecode ?: "00:00:00:00", color = if (rec) Color(0xFFFF8A7A) else Color.White,
                fontFamily = FontFamily.Monospace, fontSize = 17.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            val info = listOfNotNull(CameraLink.battery?.let { "$it%" }, formatName(CameraLink.resolution), CameraLink.fps?.let { "${it}p" })
            Pill(info.joinToString(" · ").ifEmpty { if (connected) "Connected" else "No camera" })
        }
        if (phoneView && hasCamera) Text(
            "PHONE VIEW", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, letterSpacing = 1.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 52.dp else 68.dp),
        )

        val settingButtons: @Composable () -> Unit = {
            Setting.entries.forEach { s ->
                RoundButton(s.label, s.text(s.current()), selected = selected == s, size = 48.dp) { selected = if (selected == s) null else s }
            }
        }
        val lutButton: @Composable () -> Unit = {
            Box {
                val lut = CameraLink.lut
                RoundButton("LUT", selected = lut != null && lut != Bmd.Lut.NONE, size = 46.dp) { lutMenu = true }
                DropdownMenu(lutMenu, { lutMenu = false }) {
                    Bmd.Lut.entries.forEach { l ->
                        DropdownMenuItem(
                            { Text(l.label + if (l == lut) "  ✓" else "") },
                            { if (connected) CameraLink.sendLut(l) else note = "Connect the camera first."; lutMenu = false },
                        )
                    }
                }
            }
        }

        // ── Messages ──
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 66.dp else 90.dp, start = 24.dp, end = if (landscape) 110.dp else 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!connected) Pill("Camera not connected · tap to connect", color = Color(0xE6FFB35C)) { nav.replace(Dest.Connect) }
            note?.let { Pill(it, color = Color(0xE6FFFFFF)) }
        }

        if (landscape) {
            // ── Landscape: settings along the bottom, Menu / Record / LUT down the right ──
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RoundButton("Menu", size = 46.dp) { menu = true }
                RecordButton(rec, enabled = connected) { CameraLink.record(!rec) }
                lutButton()
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 112.dp, bottom = 12.dp)) {
                selected?.let { s ->
                    SettingPanel(s, enabled = connected, onNote = { note = it }, modifier = Modifier.width(380.dp).padding(bottom = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { settingButtons() }
            }
        } else {
            // ── Portrait: settings down the right; slider, then Menu / Record / LUT at the bottom ──
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) { settingButtons() }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)) {
                selected?.let { s ->
                    SettingPanel(s, enabled = connected, onNote = { note = it },
                        modifier = Modifier.padding(start = 12.dp, end = 76.dp, bottom = 26.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("Menu", size = 46.dp) { menu = true }
                    Spacer(Modifier.weight(1f))
                    RecordButton(rec, enabled = connected) { CameraLink.record(!rec) }
                    Spacer(Modifier.weight(1f))
                    lutButton()
                }
            }
        }
    }

    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = Brand.light, contentColor = Brand.ink) {
        ShootMenu(
            phoneView = phoneView, grid = grid, onPhoneView = { phoneView = it }, onGrid = { grid = it },
            onAllSettings = { menu = false; nav.push(Dest.CameraControl) },
            onDisconnect = { menu = false; CameraLink.disconnect(); nav.pop() },
            onHome = { menu = false; nav.pop() },
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(74.dp).border(3.dp, if (enabled) Brand.record else Color(0x88FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) Box(Modifier.size(28.dp).background(Brand.record, RoundedCornerShape(6.dp)))
        else Box(Modifier.fillMaxSize().background(if (enabled) Brand.record else Color(0x88FF4B33), CircleShape))
    }
}

/** Light card with the chosen setting's value and a slider through its usual steps. */
@Composable
private fun SettingPanel(s: Setting, enabled: Boolean, onNote: (String) -> Unit, modifier: Modifier) {
    val steps = s.steps()
    val current = s.current()
    val start = current?.let { c -> steps.indices.minByOrNull { abs(steps[it] - c) } } ?: (steps.size / 2)
    var pos by remember(s, current) { mutableFloatStateOf(start.toFloat()) }
    val idx = pos.roundToInt().coerceIn(0, steps.lastIndex)
    val fpsBlocked = s == Setting.FPS && !CameraLink.canSetFps
    Column(modifier.fillMaxWidth().background(Brand.light, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = Color(0xFF5B6B8C), fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (s == Setting.WB || s == Setting.IRIS) Text(
                "Auto", color = Brand.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = enabled) {
                    if (s == Setting.WB) CameraLink.autoWhiteBalance() else CameraLink.autoAperture()
                }.padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Text(s.text(steps[idx]), color = Brand.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = pos, onValueChange = { pos = it }, valueRange = 0f..steps.lastIndex.toFloat(),
            steps = (steps.size - 2).coerceAtLeast(0), enabled = enabled && !fpsBlocked,
            onValueChangeFinished = {
                val i = pos.roundToInt().coerceIn(0, steps.lastIndex)
                if (!s.send(steps[i])) onNote("Frame rate: waiting for the camera's format.")
            },
            colors = SliderDefaults.colors(thumbColor = Brand.accent, activeTrackColor = Brand.accent, inactiveTrackColor = Color(0xFFD5DDF0),
                activeTickColor = Color.White.copy(alpha = 0.6f), inactiveTickColor = Color(0xFFB5C2DF)),
        )
        val hint = when {
            !enabled -> "Connect the camera to change this."
            fpsBlocked -> "Waiting for the camera to report its recording format."
            s == Setting.IRIS -> "Needs an electronic lens (EF / MFT with contacts)."
            s == Setting.ND -> "Only cameras with built-in ND filters, like the Pocket 6K Pro."
            current == null -> "The camera hasn't reported this yet."
            else -> null
        }
        hint?.let { Text(it, color = Color(0xFF5B6B8C), fontSize = 11.sp) }
    }
}

@Composable
private fun ShootMenu(
    phoneView: Boolean, grid: Boolean, onPhoneView: (Boolean) -> Unit, onGrid: (Boolean) -> Unit,
    onAllSettings: () -> Unit, onDisconnect: () -> Unit, onHome: () -> Unit,
) {
    var focus by remember { mutableFloatStateOf((CameraLink.focus ?: 0.5).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text(CameraLink.cameraName ?: "Camera", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.ink)
        Text(listOfNotNull(CameraLink.resolution, CameraLink.fps?.let { "$it fps" }).joinToString(" · ").ifEmpty { "Bluetooth" },
            fontSize = 12.sp, color = Color(0xFF5B6B8C))
        StepLabel("Focus")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Near", fontSize = 11.sp, color = Color(0xFF5B6B8C))
            Slider(focus, { focus = it }, onValueChangeFinished = { CameraLink.sendFocus(focus.toDouble()) }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = Brand.accent, activeTrackColor = Brand.accent, inactiveTrackColor = Color(0xFFD5DDF0)))
            Text("Far", fontSize = 11.sp, color = Color(0xFF5B6B8C))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Auto focus", false) { CameraLink.autoFocus() }
            ChoiceChip("Auto iris", false) { CameraLink.autoAperture() }
            ChoiceChip("Auto WB", false) { CameraLink.autoWhiteBalance() }
        }
        StepLabel("Screen")
        MenuSwitch("Phone camera behind controls", phoneView, onPhoneView)
        MenuSwitch("Grid (thirds)", grid, onGrid)
        HorizontalDivider(color = Color(0x22000000), modifier = Modifier.padding(vertical = 8.dp))
        MenuLink("All camera settings", Brand.ink, onAllSettings)
        MenuLink("Back to home", Brand.ink, onHome)
        MenuLink("Disconnect camera", Color(0xFFD9341F), onDisconnect)
    }
}

@Composable
private fun MenuSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Brand.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Brand.accent))
    }
}

@Composable
private fun MenuLink(label: String, color: Color, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}
