package com.infraxcoders.bmpcc.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.infraxcoders.bmpcc.core.RecceSearch
import com.infraxcoders.bmpcc.platform.Places
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infraxcoders.bmpcc.BuildConfig
import com.infraxcoders.bmpcc.ble.CameraLink
import com.infraxcoders.bmpcc.core.Catalog
import com.infraxcoders.bmpcc.core.RecceSession
import com.infraxcoders.bmpcc.data.LutStore
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.CrashLog
import com.infraxcoders.bmpcc.platform.Locator
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import java.text.DateFormat
import java.util.Date

/** Start screen: choose Shoot (Bluetooth camera control) or Recce (director's viewfinder). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(nav: Navigator) {
    val sessions by RecceStore.sessions.collectAsState()
    val connected = CameraLink.isConnected
    val recent = sessions.maxByOrNull { it.modificationTimestamp }
    Column(
        Modifier.fillMaxSize().background(Brand.background).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("BMPCC ") }
                    withStyle(SpanStyle(color = Brand.accent)) { append("CTRL") }
                },
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.weight(1f),
            )
            if (connected) Box(Modifier.size(8.dp).background(Color(0xFF34C759), CircleShape))
            Text(
                if (connected) "  ${CameraLink.cameraName ?: "Camera"}" else "No camera",
                color = if (connected) Color.White else Brand.muted, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.clickable { nav.push(if (connected) Dest.Shoot else Dest.Connect) },
            )
        }
        // The app closed unexpectedly last time: let the tester send the report.
        var crash by remember { mutableStateOf(CrashLog.last()) }
        val context = LocalContext.current
        crash?.let { report ->
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().background(Color(0xFF3A1F12), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("The app closed unexpectedly last time.", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(report.lineSequence().drop(4).firstOrNull { it.isNotBlank() }?.take(140) ?: "", color = Color(0xFFFFC9A8), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Pill("Share crash report", color = Brand.light) { CrashLog.share(context, report) }
                    Pill("Dismiss", color = Color(0x33FFFFFF), textColor = Color.White) { CrashLog.clear(); crash = null }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("Choose how you want to work today.", color = Color(0xFFD5DDF0), fontSize = 15.sp)
        Spacer(Modifier.height(22.dp))
        ModeCard(
            Icons.Outlined.PhotoCamera, "Shoot mode",
            if (connected) "Connected to ${CameraLink.cameraName ?: "your camera"}. Tap to control it live."
            else "Connect your camera over Bluetooth and control it live.",
            highlighted = true,
        ) { nav.push(if (connected) Dest.Shoot else Dest.Connect) }
        Spacer(Modifier.height(14.dp))
        ModeCard(Icons.Filled.CropFree, "Recce mode", "Pick a camera and lens, then frame shots on location.") { nav.push(Dest.RecceStart) }
        if (recent != null) {
            val frames = recent.scenes.sumOf { sc -> sc.shots.sumOf { it.references.size } }
            Text(
                "Recent: Project “${recent.projectName}” · $frames frame${if (frames == 1) "" else "s"}",
                color = Color(0xFFD5DDF0), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { nav.push(Dest.Session(recent.id)) }.padding(vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(36.dp))
        Text("LIBRARY & TOOLS", color = Brand.muted, fontSize = 11.sp, letterSpacing = 1.5.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolLink("My recces · ${sessions.size}") { nav.push(Dest.Recces) }
            ToolLink("Recce map") { nav.push(Dest.RecceMap()) }
            ToolLink("Lenses · ${Catalog.lenses.size}") { nav.push(Dest.Library(LibraryTab.LENSES)) }
            ToolLink("Cameras · ${Catalog.cameras.size}") { nav.push(Dest.Library(LibraryTab.CAMERAS)) }
            ToolLink("Lens coverage") { nav.push(Dest.Coverage()) }
            ToolLink("Sun planner") { nav.push(Dest.Sun()) }
            ToolLink("LUTs · ${LutStore.entries.size}") { nav.push(Dest.Luts) }
            ToolLink(if (Settings.fovCalibration != null) "Phone calibrated ✓" else "Calibrate phone") { nav.push(Dest.Calibrate) }
        }
        Spacer(Modifier.height(28.dp))
        // Which copy of the app this is (same as the APK name: BMPCC-Control-v<version>-b<build>.apk).
        Text(
            "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.BUILD_TIME}",
            color = Brand.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Brand.light, RoundedCornerShape(12.dp))
            .border(if (highlighted) 2.dp else 0.dp, if (highlighted) Brand.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Icon(icon, null, tint = Brand.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = Brand.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(text, color = Brand.ink.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

@Composable
private fun ToolLink(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.border(1.dp, Color(0xFF3A5591), RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun RecceListScreen(nav: Navigator) {
    val sessions by RecceStore.sessions.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<RecceSession?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf(RecceSearch.Filters()) }
    val hits = remember(sessions, filters) { RecceSearch.search(sessions, filters) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = try {
            val s = RecceStore.importJson(uri)
            "Imported “${s.projectName}” with ${s.scenes.size} scene(s) and ${s.shotCount} shot(s)."
        } catch (e: Exception) { e.message ?: "Import failed." }
    }

    Screen("My recces", onBack = { nav.pop() }, actions = {
        IconButton({ nav.push(Dest.RecceMap()) }) { Icon(Icons.Filled.Map, "Map of recces") }
        IconButton({ showNew = true }) { Icon(Icons.Filled.Add, "New recce project") }
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                ActionRow(Icons.Filled.CenterFocusStrong, "Quick recce", "Pick camera, lens and frame, then open the viewfinder", Brand.accentText) {
                    nav.push(Dest.RecceStart)
                }
            }
            if (sessions.isNotEmpty()) item { RecceSearchBar(filters) { filters = it } }
            section(if (filters.active) "Found ${hits.size} of ${sessions.size}" else "My recces") {
                if (sessions.isEmpty()) item { Hint("No recces yet. Start a quick recce, tap + for a project, or import a JSON file from the iPhone app.") }
                else if (hits.isEmpty()) item { Hint("Nothing matches. Try fewer words or clear the filters.") }
                items(hits, key = { it.session.id }) { hit ->
                    val s = hit.session
                    Row(
                        Modifier.cardRow().clickable { nav.push(Dest.Session(s.id)) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.projectName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${s.locationName} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.timestamp))}",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                            Text(
                                "${s.scenes.size} scene(s) · ${s.shotCount} shot(s)" + if (s.hasLocation) " · GPS" else "",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                            if (hit.matches.isNotEmpty()) Text("Found in: " + hit.matches.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall, color = Brand.accentText)
                        }
                        if (s.hasLocation) IconButton({ nav.push(Dest.RecceMap(s.id)) }) { Icon(Icons.Filled.Place, "Show on map", tint = Color.Gray) }
                        IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.Gray) }
                    }
                    RowDivider()
                }
            }
            section("More") {
                item {
                    ActionRow(Icons.Filled.FileDownload, "Import recce (JSON)", "From the iPhone app or another phone") {
                        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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

/** Search box and filters for the recce list. */
@Composable
private fun RecceSearchBar(f: RecceSearch.Filters, onChange: (RecceSearch.Filters) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        OutlinedTextField(
            f.query, { onChange(f.copy(query = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search projects, places, scenes, shot and voice notes") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (f.query.isNotEmpty()) IconButton({ onChange(f.copy(query = "")) }) { Icon(Icons.Filled.Close, "Clear") } },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fun <T> next(list: List<T>, v: T) = list[(list.indexOf(v) + 1) % list.size]
            ChoiceChip(f.time.label, f.time != RecceSearch.When.ANY) { onChange(f.copy(time = next(RecceSearch.When.entries, f.time))) }
            ChoiceChip(f.scene.label, f.scene != RecceSearch.SceneKind.ANY) { onChange(f.copy(scene = next(RecceSearch.SceneKind.entries, f.scene))) }
            ChoiceChip("With GPS", f.withLocation) { onChange(f.copy(withLocation = !f.withLocation)) }
            ChoiceChip("Sort: " + f.sort.label, false) { onChange(f.copy(sort = next(RecceSearch.Sort.entries, f.sort))) }
            if (f.active) ChoiceChip("Clear", false) { onChange(RecceSearch.Filters(sort = f.sort)) }
        }
    }
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String?, tint: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.cardRow().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Color.White else Color.Gray)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
    RowDivider()
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
    val scope = rememberCoroutineScope()
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
                                if (loc != null) {
                                    lat = loc.latitude; lon = loc.longitude
                                    val pos = fmt("%.5f, %.5f", loc.latitude, loc.longitude)
                                    status = pos
                                    // Place name from GPS, for an empty Location field.
                                    scope.launch {
                                        Places.name(context, loc.latitude, loc.longitude)?.let { name ->
                                            if (location.isBlank()) location = name
                                            status = "$pos · $name"
                                        }
                                    }
                                } else status = err
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
