package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.infraxcoders.bmpcc.core.ReferenceCanvas
import com.infraxcoders.bmpcc.core.ReferenceCrop
import com.infraxcoders.bmpcc.platform.ReferenceImages
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.CameraMovement
import com.infraxcoders.bmpcc.core.Coverage
import com.infraxcoders.bmpcc.core.Focus
import com.infraxcoders.bmpcc.core.Optics
import com.infraxcoders.bmpcc.core.ShotOptics
import com.infraxcoders.bmpcc.core.Solar
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.core.ShotReference
import com.infraxcoders.bmpcc.core.ShotType
import com.infraxcoders.bmpcc.data.RecceStore
import androidx.compose.runtime.produceState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.infraxcoders.bmpcc.data.LutStore
import com.infraxcoders.bmpcc.platform.Images
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
fun ShotScreen(nav: Navigator, sessionId: String, sceneId: String, shotId: String) {
    val sessions by RecceStore.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId } ?: return Gone(nav)
    val shot = session.scenes.firstOrNull { it.id == sceneId }?.shots?.firstOrNull { it.id == shotId } ?: return Gone(nav)
    var showNote by remember { mutableStateOf(false) }
    fun edit(change: (RecceShot) -> RecceShot) = RecceStore.updateShot(sessionId, sceneId, shotId, change)

    Screen("Shot ${shot.shotNumber}", onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                Button(
                    { nav.push(Dest.Finder(sessionId, sceneId, shotId)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.accent),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Icon(Icons.Filled.CenterFocusStrong, null); Text("  Open director's viewfinder") }
            }
            section("Shot") {
                item {
                    Field("Shot number", shot.shotNumber, { v -> edit { it.copy(shotNumber = v) } })
                    ChoiceRow("Shot type", shot.shotType, ShotType.entries.toList(), { "${it.label} – ${it.longName}" }) { v -> edit { it.copy(shotType = v) } }
                    ChoiceRow("Camera movement", shot.cameraMovement, CameraMovement.entries.toList(), { it.label }) { v -> edit { it.copy(cameraMovement = v) } }
                    Field("Subject movement", shot.subjectMovement, { v -> edit { it.copy(subjectMovement = v) } })
                    Field("Camera position", shot.cameraPosition, { v -> edit { it.copy(cameraPosition = v) } })
                    Field("Subject position", shot.subjectPosition, { v -> edit { it.copy(subjectPosition = v) } })
                    PresetRow("Camera height", shot.cameraHeight, ShotPresets.cameraHeights) { v -> edit { it.copy(cameraHeight = v) } }
                    DistanceField(shot.estimatedDistance) { v -> edit { it.copy(estimatedDistance = v) } }
                    ShotOptics.depthOfField(shot)?.let { d ->
                        LabeledRow("Depth of field", ShotOptics.dofText(d).removePrefix("DoF ") + fmt(" · hyperfocal %s", Focus.distanceText(d.hyperfocalM)))
                        if (d.tooClose) Hint(fmt("Closer than this lens can focus (%.2f m).", shot.lens.minimumFocusDistance))
                    }
                    ShotOptics.distortionPercent(shot)?.let {
                        LabeledRow("Lens distortion", ShotOptics.distortionText(it) + if (com.infraxcoders.bmpcc.core.Distortion.isCustom(shot.lens)) " (your measurement)" else "")
                    }
                    // Planned time and where the sun is then (needs the recce's location).
                    val planned = shot.plannedTime
                    val lat = session.latitude; val lon = session.longitude
                    LabeledRow(
                        "Planned time",
                        when {
                            planned == null -> "Not set: open the sun planner"
                            lat != null && lon != null -> "${clockText(planned)} · sun ${sunText(Solar.position(planned, lat, lon))}"
                            else -> clockText(planned) + " (save a location to see the sun)"
                        },
                        onClick = { nav.push(Dest.Sun(sessionId, sceneId, shotId, planned ?: System.currentTimeMillis())) },
                    )
                }
            }
            section("Camera & lens") {
                item {
                    LabeledRow("Camera", shot.cameraModel, onClick = {
                        nav.push(Dest.Library(LibraryTab.CAMERAS, pickCamera = { c -> edit { it.withCamera(c) } }))
                    })
                    val modes = shot.baseCamera.sensorModes
                    if (modes.size > 1) {
                        ChoiceRow("Recording mode", shot.sensorMode, modes, { it.name }) { m -> edit { it.copy(sensorModeId = m.id) } }
                    }
                    LabeledRow("Lens", shot.lensModel, onClick = {
                        nav.push(Dest.Library(LibraryTab.LENSES, pickLens = { l -> edit { it.withLens(l) } }))
                    })
                    FocalControl(shot) { f -> edit { it.copy(focalLength = ShotPresets.focalText(f)) } }
                    Field("Aperture / T-stop", shot.aperture, { v -> edit { it.copy(aperture = v) } })
                    PresetRow("Aspect ratio", shot.aspectRatio, ShotPresets.aspectRatios) { v -> edit { it.copy(aspectRatio = v) } }
                }
            }
            section("Exposure") {
                item {
                    PresetRow("Frame rate", shot.fps, ShotPresets.frameRates) { v -> edit { it.copy(fps = v) } }
                    PresetRow("Shutter", shot.shutter, ShotPresets.shutterAngles) { v -> edit { it.copy(shutter = v) } }
                    PresetRow("ISO", shot.iso, ShotPresets.isos) { v -> edit { it.copy(iso = v) } }
                    PresetRow("ND", shot.nd, ShotPresets.ndFilters) { v -> edit { it.copy(nd = v) } }
                    PresetRow("White balance", shot.whiteBalance, ShotPresets.whiteBalances) { v -> edit { it.copy(whiteBalance = v) } }
                    // Look (LUT) for this shot: shown live in the viewfinder and on the saved frames below.
                    ChoiceRow("LUT", shot.lut, listOf<String?>(null) + LutStore.entries.map { it.id }, { LutStore.nameOf(it) ?: "None" }) { v ->
                        edit { it.copy(lut = v) }
                    }
                }
            }
            section("Framing", footer = "Calculated from the ${shot.camera.model}'s ${shot.sensorMode.name.lowercase()} area (${shot.sensorMode.sizeText}) with rectilinear lens geometry.") {
                item { FramingRows(shot, nav) }
            }
            section("Reference photos") {
                val refs = shot.references.sortedBy { it.timestamp }
                if (refs.isEmpty()) item { Hint("Capture frames from the viewfinder; they're saved here with the frame lines.") }
                items(refs, key = { it.id }) { r ->
                    ReferencePhoto(r, shot, shot.lut) {
                        File(RecceStore.referencesDir, r.fileName).delete()
                        edit { s -> s.copy(references = s.references.filter { it.id != r.id }) }
                    }
                }
            }
            if (shot.markers.isNotEmpty()) section("Markers") {
                items(shot.markers, key = { it.id }) { m ->
                    Row(Modifier.cardRow().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.type.label}  (${(m.x * 100).toInt()}%, ${(m.y * 100).toInt()}%)", modifier = Modifier.weight(1f))
                        IconButton({ edit { s -> s.copy(markers = s.markers.filter { it.id != m.id }) } }) { Icon(Icons.Filled.Delete, "Delete marker", tint = Color.Gray) }
                    }
                }
            }
            section("Notes") {
                item { Field("Shot notes", shot.notes, { v -> edit { it.copy(notes = v) } }, singleLine = false) }
                items(session.sortedNotes.filter { it.shotId == shotId }, key = { it.id }) { n -> NoteRow(n) { RecceStore.deleteNote(sessionId, n.id) } }
                item { ActionRow(Icons.Filled.Mic, "Add voice or text note", null) { showNote = true } }
            }
        }
    }
    if (showNote) NoteComposer(sessionId = sessionId, shotId = shotId) { showNote = false }
}

@Composable
private fun DistanceField(value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { ShotPresets.trim(it) } ?: "") }
    Field("Distance to subject (m)", text, { t ->
        text = t
        if (t.isBlank()) onChange(null) else t.replace(',', '.').toDoubleOrNull()?.let(onChange)
    }, keyboard = KeyboardType.Decimal)
}

/** Focal length: a slider for zooms, plus quick buttons. */
@Composable
fun FocalControl(shot: RecceShot, onChange: (Double) -> Unit) {
    val lens = shot.lens
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row { Text("Focal length", modifier = Modifier.weight(1f)); Text(shot.focalLength, color = Color.LightGray) }
        if (lens.isZoom) {
            Slider(
                value = shot.focalMm.toFloat().coerceIn(lens.focalLengthMin.toFloat(), lens.focalLengthMax.toFloat()),
                onValueChange = { onChange(Math.rint(it.toDouble())) },
                valueRange = lens.focalLengthMin.toFloat()..lens.focalLengthMax.toFloat(),
            )
        }
        if (lens.quickFocalLengths.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                lens.quickFocalLengths.forEach { f ->
                    FilterChip(abs(shot.focalMm - f) < 0.5, { onChange(f) }, { Text(ShotPresets.focalText(f)) })
                }
            }
        }
    }
}

@Composable
private fun FramingRows(shot: RecceShot, nav: Navigator) {
    val ref = shot.reference
    if (ref == null) { Hint("Check the focal length and aspect ratio."); return }
    LabeledRow("Horizontal view", ref.deliveredFov.horizontal.degreesText())
    LabeledRow("Vertical view", ref.deliveredFov.vertical.degreesText())
    LabeledRow("Diagonal view", ref.deliveredFov.diagonal.degreesText())
    LabeledRow("Full-frame equivalent", "${Math.round(Optics.equivalentFocalLength(shot.focalMm, shot.camera.sensorWidthMm))}mm")
    LabeledRow("Crop factor", fmt("%.2fx", ref.cropFactor))
    if (shot.lens.isAnamorphic) {
        LabeledRow("Anamorphic", fmt("%sx squeeze, desqueezed %.2f:1", ShotPresets.trim(shot.lens.anamorphicSqueeze), ref.captureWidthMm / ref.captureHeightMm))
    }
    val coverage = Coverage.evaluate(shot.lens, shot.sensorMode)
    LabeledRow("Lens coverage", coverage.label, onClick = {
        nav.push(Dest.Coverage(shot.baseCamera.id, shot.lens.id, shot.sensorMode.id))
    }) { CoverageBadge(coverage, Coverage.circle(shot.lens)?.nominal ?: false) }
    if (!Coverage.sharesMount(shot.baseCamera, shot.lens)) LabeledRow("Mount", "Adapter needed")
    if (shot.lens.minimumFocusDistance > 0) LabeledRow("Minimum focus", fmt("%.2f m", shot.lens.minimumFocusDistance))
}

/**
 * A reference photo for the shot's current settings: by default cut to the cinema frame (what the selected camera,
 * lens and aspect ratio would show; dark where the phone couldn't see), or the whole photo with the frame drawn on it.
 * Follows the shot when its camera, lens, focal length or aspect ratio change.
 */
@Composable
fun ReferencePhoto(r: ShotReference, shot: RecceShot, lutId: String? = null, onDelete: (() -> Unit)? = null) {
    val crop = ReferenceCrop.forShot(r, shot)
    var showCrop by remember { mutableStateOf(true) }
    var showGraded by remember(lutId) { mutableStateOf(lutId != null) }
    val gradeWith = if (showGraded) lutId else null
    val neutral = Images.load(File(RecceStore.referencesDir, r.fileName))
    // The crop (and its graded version) in the background; the photo file is read once and cached.
    val cropped by produceState<android.graphics.Bitmap?>(null, r.filePath, crop.frame, gradeWith, LutStore.version) {
        value = withContext(Dispatchers.Default) {
            val c = ReferenceImages.cropped(r, shot, 1600)?.bitmap
            if (c != null && gradeWith != null) LutStore.graded(c, gradeWith) ?: c else c
        }
    }
    val gradedFull by produceState<android.graphics.Bitmap?>(null, r.filePath, gradeWith, showCrop, LutStore.version) {
        value = if (showCrop || gradeWith == null || neutral == null) null else withContext(Dispatchers.Default) { LutStore.graded(neutral, gradeWith) }
    }
    if (neutral != null) Row(Modifier.cardRow().horizontalScroll(rememberScrollState()).padding(start = 12.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceChip("Frame", showCrop) { showCrop = true }
        ChoiceChip("Full photo", !showCrop) { showCrop = false }
        if (lutId != null) {
            Spacer(Modifier.width(8.dp))
            ChoiceChip("Neutral", !showGraded) { showGraded = false }
            ChoiceChip(LutStore.nameOf(lutId) ?: "Graded", showGraded) { showGraded = true }
        }
    }
    Column(Modifier.cardRow().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (neutral == null) {
            Text("Photo not on this phone (${r.fileName})", color = Color.Gray)
        } else if (showCrop) {
            // The frame itself, at its own shape (the shape is known before the picture is ready).
            val aspect = crop.aspect(neutral.width, neutral.height).toFloat().coerceIn(0.2f, 6f)
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(6.dp)).background(Color(ReferenceImages.PADDING))) {
                cropped?.let { Image(it.asImageBitmap(), "Reference photo, cut to the frame", contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
            }
        } else {
            // The whole photo with the frame drawn on it; a frame wider than the photo shows it inside, dark around.
            val bmp = gradedFull ?: neutral
            val c = ReferenceCanvas.of(crop.frame.x, crop.frame.y, crop.frame.width, crop.frame.height)
            val image = remember(bmp) { bmp.asImageBitmap() }
            Box(
                Modifier.fillMaxWidth().aspectRatio((bmp.width * c.widthInPhotos / (bmp.height * c.heightInPhotos)).toFloat())
                    .clip(RoundedCornerShape(6.dp)).background(Color(ReferenceImages.PADDING)),
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawImage(
                        image,
                        dstOffset = IntOffset((c.photo.x * size.width).roundToInt(), (c.photo.y * size.height).roundToInt()),
                        dstSize = IntSize((c.photo.width * size.width).roundToInt(), (c.photo.height * size.height).roundToInt()),
                    )
                    if (crop.basis != ReferenceCrop.Basis.NO_FRAME) c.frame?.let { f ->
                        drawRect(Brand.frameLine, Offset((f.x * size.width).toFloat(), (f.y * size.height).toFloat()),
                            Size((f.width * size.width).toFloat(), (f.height * size.height).toFloat()), style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // When it was taken, with the shot's settings then, how exact the frame is, and where this crop comes from.
            val taken = listOfNotNull(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.timestamp)),
                listOfNotNull(r.cameraId?.let { com.infraxcoders.bmpcc.core.Catalog.camera(it)?.model }, r.focalLength, r.aspectRatio)
                    .joinToString(" · ").ifEmpty { null },
                when (r.calibrated) {
                    true -> "calibrated frame"
                    false -> "approximate frame"
                    null -> null
                },
                ReferenceImages.basisText(r, shot, crop),
            ).joinToString("\n")
            Text(taken, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f))
            if (onDelete != null) IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete photo", tint = Color.Gray) }
        }
    }
    RowDivider()
}
