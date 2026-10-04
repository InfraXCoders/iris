package com.infraxcoders.bmpcc.ui

import android.Manifest
import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.NotesSorter
import com.infraxcoders.bmpcc.core.RecceNote
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.Locator
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker
import com.infraxcoders.bmpcc.platform.share
import com.infraxcoders.bmpcc.report.ReportPdf
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun SessionScreen(nav: Navigator, sessionId: String) {
    val context = LocalContext.current
    val sessions by RecceStore.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sessionId } ?: return Gone(nav)
    var locating by remember { mutableStateOf<String?>(null) }
    var showNote by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val asker = rememberPermissionAsker { locating = "Location access is off." }

    Screen(s.projectName, onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            section("Recce") {
                item {
                    Field("Project", s.projectName, { v -> RecceStore.update(sessionId) { it.copy(projectName = v) } })
                    Field("Location", s.locationName, { v -> RecceStore.update(sessionId) { it.copy(locationName = v) } })
                    LabeledRow("Date", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.timestamp)), onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = s.timestamp }
                        DatePickerDialog(context, { _, y, m, d ->
                            val c = Calendar.getInstance().apply { timeInMillis = s.timestamp; set(y, m, d) }
                            RecceStore.update(sessionId) { it.copy(timestamp = c.timeInMillis) }
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (s.latitude != null && s.longitude != null) MonoText(fmt("%.5f, %.5f", s.latitude, s.longitude))
                            else Text("No location saved", color = Color.Gray)
                            locating?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9F0A)) }
                        }
                        TextButton({
                            asker.withPermission(Manifest.permission.ACCESS_FINE_LOCATION) {
                                locating = "Locating…"
                                Locator.request(context) { loc, err ->
                                    if (loc != null) {
                                        RecceStore.update(sessionId) { it.copy(latitude = loc.latitude, longitude = loc.longitude) }
                                        locating = null
                                    } else locating = err
                                }
                            }
                        }) { Text("Use my location") }
                    }
                }
            }
            section("Sun") {
                item {
                    if (s.latitude != null && s.longitude != null) SunCard(s.latitude!!, s.longitude!!, s.timestamp)
                    else Hint("Save a location to see sunrise, sunset, golden hour and blue hour.")
                }
            }
            section("Scenes") {
                items(s.sortedScenes, key = { it.id }) { sc ->
                    Row(Modifier.fillMaxWidth().clickable { nav.push(Dest.Scene(sessionId, sc.id)) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Scene ${sc.sceneNumber}", style = MaterialTheme.typography.titleMedium)
                            Text(sc.heading, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text("${sc.shots.size} shot(s) · ${sc.lightingCondition.label}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        IconButton({ RecceStore.update(sessionId) { x -> x.copy(scenes = x.scenes.filter { it.id != sc.id }) } }) {
                            Icon(Icons.Filled.Delete, "Delete scene", tint = Color.Gray)
                        }
                    }
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }
                item { ActionRow(Icons.Filled.Add, "Add scene", null) { RecceStore.newScene(sessionId)?.let { nav.push(Dest.Scene(sessionId, it.id)) } } }
            }
            section("Notes", footer = "Notes are auto-sorted into composition, lighting and movement for the report (English, Hindi and Hinglish).") {
                items(s.sortedNotes, key = { it.id }) { n -> NoteRow(n) { RecceStore.deleteNote(sessionId, n.id) } }
                item { ActionRow(Icons.Filled.Mic, "Add voice or text note", null) { showNote = true } }
            }
            section("Director / DoP notes") {
                item { Field("Look, references, intentions…", s.directorDopNotes, { v -> RecceStore.update(sessionId) { it.copy(directorDopNotes = v) } }, singleLine = false) }
            }
            section("Location notes") {
                item { Field("Power, parking, permissions, noise…", s.generalLocationNotes, { v -> RecceStore.update(sessionId) { it.copy(generalLocationNotes = v) } }, singleLine = false) }
            }
            section("Share") {
                item {
                    ActionRow(Icons.Filled.PictureAsPdf, "Create PDF report", "Summary, sun, one page per shot, sorted notes") {
                        shareError = null
                        runCatching { share(context, ReportPdf.make(context, RecceStore.session(sessionId)!!), "application/pdf") }
                            .onFailure { shareError = it.message ?: "The report couldn't be created." }
                    }
                    ActionRow(Icons.Filled.Code, "Export JSON", "Opens in the iPhone app too") {
                        shareError = null
                        runCatching { share(context, RecceStore.exportJson(RecceStore.session(sessionId)!!), "application/json") }
                            .onFailure { shareError = it.message ?: "Export failed." }
                    }
                    shareError?.let { Hint(it) }
                }
            }
        }
    }
    if (showNote) NoteComposer(sessionId = sessionId, shotId = null) { showNote = false }
}

@Composable
fun NoteRow(note: RecceNote, onDelete: () -> Unit) {
    val sorted = note.sorted
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(note.rawTranscription)
            Spacer(Modifier.width(4.dp))
            Tags {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(note.timestamp)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text(note.detectedLanguage, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (sorted.composition != null) Tag("Composition")
                if (sorted.lighting != null) Tag("Lighting")
                if (NotesSorter.isMovement(note.rawTranscription)) Tag("Movement")
                sorted.focalLength?.let { Tag(it) }
            }
        }
        IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete note", tint = Color.Gray) }
    }
    HorizontalDivider(color = Color(0x22FFFFFF))
}
