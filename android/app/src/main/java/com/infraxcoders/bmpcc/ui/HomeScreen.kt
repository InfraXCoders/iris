package com.infraxcoders.bmpcc.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.RecceSession
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.Locator
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(nav: Navigator) {
    val sessions by RecceStore.sessions.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<RecceSession?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = try {
            val s = RecceStore.importJson(uri)
            "Imported “${s.projectName}” with ${s.scenes.size} scene(s) and ${s.shotCount} shot(s)."
        } catch (e: Exception) { e.message ?: "Import failed." }
    }

    Screen("BMPCC Control", onBack = null) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                ActionRow(Icons.Filled.CenterFocusStrong, "Quick recce", "Open the viewfinder with ${Settings.defaultCamera.model}", Brand.accent) {
                    val (s, sc, sh) = RecceStore.quickRecceShot(Settings.defaultCamera, Settings.defaultLens)
                    nav.push(Dest.Finder(s, sc, sh))
                }
                ActionRow(Icons.Filled.Add, "New recce", null) { showNew = true }
            }
            section("Recces") {
                if (sessions.isEmpty()) item { Hint("No recces yet. Start one above, or import a JSON file from the iPhone app.") }
                items(sessions, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.push(Dest.Session(s.id)) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.projectName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${s.locationName} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.timestamp))}",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                            Text("${s.scenes.size} scene(s) · ${s.shotCount} shot(s)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.Gray) }
                    }
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }
            }
            section("Databases & tools") {
                item {
                    ActionRow(Icons.Filled.Lens, "Lens database", "${com.infraxcoders.bmpcc.core.Catalog.lenses.size} lenses") { nav.push(Dest.Library(LibraryTab.LENSES)) }
                    ActionRow(Icons.Filled.Videocam, "Camera database", "${com.infraxcoders.bmpcc.core.Catalog.cameras.size} cameras with recording modes") { nav.push(Dest.Library(LibraryTab.CAMERAS)) }
                    ActionRow(Icons.Filled.Camera, "Lens coverage tool", "Image circle over the sensor") { nav.push(Dest.Coverage()) }
                    ActionRow(Icons.Filled.FileDownload, "Import recce (JSON)", "From the iPhone app or another phone") {
                        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                    }
                    ActionRow(Icons.Filled.SettingsInputAntenna, "Camera control", "Bluetooth control of the BMPCC: coming in a later version", Color.Gray, enabled = false) {}
                }
            }
        }
    }

    if (showNew) NewSessionDialog(onDismiss = { showNew = false }) { project, location, lat, lon ->
        showNew = false
        val s = RecceStore.newSession(project, location, lat, lon)
        nav.push(Dest.Session(s.id))
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${s.projectName}”?") },
            text = { Text("Its scenes, shots, notes and reference photos are deleted from this phone.") },
            confirmButton = { TextButton({ RecceStore.delete(s.id); confirmDelete = null }) { Text("Delete", color = Color(0xFFFF453A)) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton({ message = null }) { Text("OK") } })
    }
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String?, tint: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Color.White else Color.Gray)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
    HorizontalDivider(color = Color(0x22FFFFFF))
}

@Composable
fun NewSessionDialog(onDismiss: () -> Unit, onCreate: (String, String, Double?, Double?) -> Unit) {
    val context = LocalContext.current
    var project by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val asker = rememberPermissionAsker { status = "Location access is off." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New recce") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Field("Project", project, { project = it }, modifier = Modifier.padding(0.dp))
                Field("Location", location, { location = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({
                        asker.withPermission(Manifest.permission.ACCESS_FINE_LOCATION) {
                            status = "Locating…"
                            Locator.request(context) { loc, err ->
                                if (loc != null) { lat = loc.latitude; lon = loc.longitude; status = fmt("%.5f, %.5f", loc.latitude, loc.longitude) }
                                else status = err
                            }
                        }
                    }) { Icon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Use my location") }
                }
                status?.let { MonoText(it) }
            }
        },
        confirmButton = { TextButton({ onCreate(project, location, lat, lon) }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
