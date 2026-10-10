package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.LightingCondition
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.Images
import java.io.File

@Composable
fun SceneScreen(nav: Navigator, sessionId: String, sceneId: String) {
    val sessions by RecceStore.sessions.collectAsState()
    val scene = sessions.firstOrNull { it.id == sessionId }?.scenes?.firstOrNull { it.id == sceneId } ?: return Gone(nav)
    fun edit(change: (com.infraxcoders.bmpcc.core.RecceScene) -> com.infraxcoders.bmpcc.core.RecceScene) =
        RecceStore.updateScene(sessionId, sceneId, change)
    var picking by remember { mutableStateOf(false) }
    if (picking) RecceSheet(
        title = "Add shot", subtitle = "Scene ${scene.sceneNumber}: pick camera, lens and frame.", button = "Open viewfinder",
        initial = remember { RecceChoice.last() }, onDismiss = { picking = false },
    ) { c ->
        picking = false
        RecceStore.newShot(sessionId, sceneId, c.camera, c.lens, c.modeId)?.let { shot ->
            RecceStore.updateShot(sessionId, sceneId, shot.id) { it.copy(focalLength = ShotPresets.focalText(c.focal), aspectRatio = c.aspect) }
            nav.push(Dest.Finder(sessionId, sceneId, shot.id))
        }
    }

    Screen("Scene ${scene.sceneNumber}", onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            section("Scene") {
                item {
                    Field("Scene number", scene.sceneNumber, { v -> edit { it.copy(sceneNumber = v) } })
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(scene.isInterior, { edit { it.copy(isInterior = true) } }, { Text("INT.") })
                        FilterChip(!scene.isInterior, { edit { it.copy(isInterior = false) } }, { Text("EXT.") })
                        Spacer(Modifier.width(12.dp))
                        FilterChip(scene.isDay, { edit { it.copy(isDay = true) } }, { Text("DAY") })
                        FilterChip(!scene.isDay, { edit { it.copy(isDay = false) } }, { Text("NIGHT") })
                    }
                    Field("Location description", scene.locationDescription, { v -> edit { it.copy(locationDescription = v) } })
                    Field("Time of day (e.g. 17:30)", scene.timeOfDay, { v -> edit { it.copy(timeOfDay = v) } })
                    ChoiceRow("Lighting", scene.lightingCondition, LightingCondition.entries.toList(), { it.label }) { v -> edit { it.copy(lightingCondition = v) } }
                    Field("Scene notes", scene.notes, { v -> edit { it.copy(notes = v) } }, singleLine = false)
                }
            }
            section("Shots") {
                items(scene.sortedShots, key = { it.id }) { shot ->
                    ShotRow(shot, onOpen = { nav.push(Dest.Shot(sessionId, sceneId, shot.id)) },
                        onFinder = { nav.push(Dest.Finder(sessionId, sceneId, shot.id)) },
                        onDelete = { edit { sc -> sc.copy(shots = sc.shots.filter { it.id != shot.id }) } })
                }
                item {
                    ActionRow(Icons.Filled.Add, "Add shot", "Choose camera and lens, then frame it in the viewfinder") { picking = true }
                    val frameCount = scene.shots.sumOf { it.references.size }
                    ActionRow(Icons.Filled.GridView, "Compare frames", if (frameCount == 0) "No saved frames yet"
                        else "$frameCount frame(s) side by side, cropped to the frame lines", enabled = frameCount > 0) {
                        nav.push(Dest.Compare(sessionId, sceneId))
                    }
                }
            }
        }
    }
}

@Composable
fun ShotRow(shot: RecceShot, onOpen: () -> Unit, onFinder: () -> Unit, onDelete: () -> Unit) {
    val first = shot.references.minByOrNull { it.timestamp }
    val bmp = first?.let { Images.load(File(RecceStore.referencesDir, it.fileName), 320) }
    Row(Modifier.cardRow().clickable(onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF22407F)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp, 40.dp))
            else Icon(Icons.Filled.PhotoCamera, null, tint = Color.Gray)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Shot ${shot.shotNumber} · ${shot.shotType.label}", style = MaterialTheme.typography.titleMedium)
            Text("${shot.focalLength} · ${shot.aspectRatio} · ${shot.cameraMovement.label}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(shot.lensModel, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
        }
        IconButton(onFinder) { Icon(Icons.Filled.CenterFocusStrong, "Viewfinder", tint = Brand.accent) }
        IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete shot", tint = Color.Gray) }
    }
    RowDivider()
}
