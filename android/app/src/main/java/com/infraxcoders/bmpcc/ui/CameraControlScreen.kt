package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.infraxcoders.bmpcc.ble.CameraLink
import com.infraxcoders.bmpcc.core.ShotPresets

/** Permissions Bluetooth needs on this Android version. */
fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Asks for Bluetooth permissions and to switch Bluetooth on, then runs the action. */
@Composable
fun rememberBleStarter(onError: (String) -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (CameraLink.bluetoothOn) pending?.invoke() else onError("Bluetooth is off.")
        pending = null
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            if (CameraLink.bluetoothOn) { pending?.invoke(); pending = null }
            else runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { onError("Turn Bluetooth on in Settings.") }
        } else { onError("Bluetooth permission is needed to talk to the camera."); pending = null }
    }
    return { action ->
        pending = action
        val missing = blePermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
        else if (!CameraLink.bluetoothOn) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { onError("Turn Bluetooth on in Settings.") }
        else { action(); pending = null }
    }
}

private val ISO_VALUES = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 12800, 25600)
private val ANGLES = listOf(11.2, 15.0, 22.5, 45.0, 60.0, 90.0, 120.0, 144.0, 172.8, 180.0, 216.0, 270.0, 324.0, 360.0)
private val KELVINS = listOf(2500, 2800, 3200, 3600, 4000, 4500, 5000, 5600, 6000, 6500, 7500, 8000)
private val FSTOPS = listOf(1.4, 1.8, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)
private val ND = listOf(0.0, 2.0, 4.0, 6.0)

/** Bluetooth camera control: find and pair the camera, record, and change its settings. */
@Composable
fun CameraControlScreen(nav: Navigator) {
    var message by remember { mutableStateOf<String?>(null) }
    val start = rememberBleStarter { message = it }
    Screen("Camera control", onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            item { LinkCard(onScan = { start { CameraLink.startScan() } }) }
            message?.let { m -> item { Hint(m) } }
            CameraLink.error?.let { e -> item { Text(e, color = Color(0xFFFF9F0A), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) } }
            if (!CameraLink.isConnected) {
                section("Cameras", footer = "On the camera: Settings → Setup → Bluetooth: On. When Android asks, type the 6-digit PIN shown on the camera's screen.") {
                    if (CameraLink.found.isEmpty()) item {
                        Hint(if (CameraLink.phase == CameraLink.Phase.SCANNING) "Searching… keep the camera close and switched on." else "Tap Search to find your camera.")
                    }
                    items(CameraLink.found, key = { it.device.address }) { f ->
                        Row(Modifier.cardRow().clickable { start { CameraLink.connect(f.device) } }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bluetooth, null, tint = Brand.frameLine)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontWeight = FontWeight.SemiBold)
                                Text(f.device.address + if (f.rssi != 0) " · ${f.rssi} dBm" else "", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Text("Connect", color = Brand.accentText)
                        }
                        RowDivider()
                    }
                }
            } else {
                item { RecordPanel() }
                section("Exposure") {
                    item {
                        ValueChips("ISO", CameraLink.iso?.toString(), ISO_VALUES.map { it.toString() }) { CameraLink.sendIso(it.toInt()) }
                        ValueChips("Shutter angle", CameraLink.shutterAngle?.let { "${ShotPresets.trim(it)}°" }, ANGLES.map { "${ShotPresets.trim(it)}°" }) {
                            CameraLink.sendShutterAngle(it.removeSuffix("°").toDouble())
                        }
                        ValueChips("ND filter", CameraLink.ndStops?.let { if (it < 0.5) "Clear" else "${ShotPresets.trim(it)} stops" }, ND.map { if (it == 0.0) "Clear" else "${ShotPresets.trim(it)} stops" }) {
                            CameraLink.sendNd(if (it == "Clear") 0.0 else it.substringBefore(" ").toDouble())
                        }
                    }
                }
                section("Colour") {
                    item {
                        ValueChips("White balance", CameraLink.whiteBalance?.let { "${it}K" }, KELVINS.map { "${it}K" } + "Auto") {
                            if (it == "Auto") CameraLink.autoWhiteBalance() else CameraLink.sendWhiteBalance(it.removeSuffix("K").toInt())
                        }
                    }
                }
                section("Lens", footer = "Iris, focus and auto focus work with electronic lenses (e.g. EF or MFT lenses with contacts).") {
                    item {
                        ValueChips("Iris", CameraLink.fNumber?.let { "f/${ShotPresets.trim(Math.round(it * 10) / 10.0)}" }, FSTOPS.map { "f/${ShotPresets.trim(it)}" } + "Auto") {
                            if (it == "Auto") CameraLink.autoAperture() else CameraLink.sendAperture(it.removePrefix("f/").toDouble())
                        }
                        FocusControl()
                    }
                }
                item {
                    OutlinedButton({ CameraLink.disconnect() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Icon(Icons.Filled.LinkOff, null); Spacer(Modifier.width(8.dp)); Text("Disconnect")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LinkCard(onScan: () -> Unit) {
    val p = CameraLink.phase
    Column(Modifier.fillMaxWidth().padding(12.dp).background(Brand.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (CameraLink.isConnected) Color(0xFF34C759) else if (p == CameraLink.Phase.IDLE) Color.Gray else Color(0xFFFFCC00), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (CameraLink.isConnected) CameraLink.cameraName ?: "Camera" else p.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        CameraLink.isConnected -> listOfNotNull(CameraLink.fps?.let { "$it fps" }, CameraLink.resolution).joinToString(" · ").ifEmpty { "Connected over Bluetooth" }
                        p == CameraLink.Phase.PAIRING -> "Look at the camera's screen for the PIN."
                        else -> "Blackmagic Pocket, Cinema Camera, URSA (Bluetooth)"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
            if (p == CameraLink.Phase.SCANNING || p == CameraLink.Phase.CONNECTING || p == CameraLink.Phase.SETTING_UP || p == CameraLink.Phase.PAIRING) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        if (!CameraLink.isConnected) {
            Spacer(Modifier.height(12.dp))
            Button(
                { if (p == CameraLink.Phase.SCANNING) CameraLink.stopScan() else onScan() },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.accent), modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.width(8.dp))
                Text(if (p == CameraLink.Phase.SCANNING) "Stop searching" else "Search for cameras")
            }
        }
    }
}

@Composable
private fun RecordPanel() {
    val rec = CameraLink.recording
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Color.Black, RoundedCornerShape(16.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(CameraLink.timecode ?: "--:--:--:--", color = if (rec) Color(0xFFFF453A) else Color.White,
            fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                .background(Color(0xFFFF3B30), if (rec) RoundedCornerShape(10.dp) else CircleShape)
                .clickable { CameraLink.record(!rec) },
        )
        Text(if (rec) "Recording · tap to stop" else "Tap to record", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A setting with its current camera value and tap-to-set choices. */
@Composable
private fun ValueChips(label: String, current: String?, options: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(current ?: "—", color = Brand.accentText, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                val active = o == current
                Text(o, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color.Black else Color.White,
                    modifier = Modifier.background(if (active) Brand.accent else Color(0xFF22407F), RoundedCornerShape(50))
                        .clickable { onPick(o) }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
    RowDivider()
}

@Composable
private fun FocusControl() {
    var pos by remember { mutableStateOf(CameraLink.focus ?: 0.5) }
    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Focus", modifier = Modifier.weight(1f))
            OutlinedButton({ CameraLink.autoFocus() }) { Text("Auto focus") }
        }
        Slider(pos.toFloat(), { pos = it.toDouble() }, onValueChangeFinished = { CameraLink.sendFocus(pos) })
        Row { Text("Near", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f)); Text("Far", fontSize = 11.sp, color = Color.Gray) }
    }
}
