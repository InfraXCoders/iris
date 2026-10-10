package com.infraxcoders.bmpcc.ui

import android.Manifest
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.infraxcoders.bmpcc.core.CameraProfile
import com.infraxcoders.bmpcc.core.Coverage
import com.infraxcoders.bmpcc.core.FocalOptions
import com.infraxcoders.bmpcc.core.LensProfile
import com.infraxcoders.bmpcc.core.QuickRecce
import com.infraxcoders.bmpcc.core.QuickRecce.LensKind
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.data.Kit
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.hasPermission
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker

/** What the New recce sheet decides: camera + recording mode, lens (the set member at [focal]), focal length and frame. */
data class RecceChoice(val camera: CameraProfile, val modeId: String, val lens: LensProfile, val focal: Double, val aspect: String) {
    /** Applies the choice to an existing shot (Setup from the viewfinder). */
    fun applyTo(shot: RecceShot): RecceShot =
        shot.withCamera(camera).copy(sensorModeId = modeId).withLens(lens)
            .copy(focalLength = ShotPresets.focalText(focal), aspectRatio = aspect)

    fun save() {
        Settings.setDefaultCamera(camera.id); Settings.chooseDefaultMode(modeId)
        Settings.setDefaultLens(lens.id); Settings.chooseDefaultAspect(aspect)
    }

    companion object {
        /** Last choice on this phone, or Pocket 6K Pro + the generic prime set at 35mm + 2.39:1. */
        fun last(): RecceChoice {
            val camera = Settings.defaultCamera
            val mode = camera.mode(Settings.defaultModeId) ?: camera.sensorModes[0]
            val lens = if (Settings.lensChosen) Settings.defaultLens else QuickRecce.lensFor(LensKind.PRIME, null)
            val focal = QuickRecce.nearestFocal(lens, if (lens.isZoom) 35.0 else lens.focalLengthMin)
            return RecceChoice(camera, mode.id, FocalOptions.lensFor(lens, focal), focal, Settings.defaultAspect)
        }

        fun of(shot: RecceShot) = RecceChoice(shot.baseCamera, shot.sensorMode.id, shot.lens, shot.focalMm, shot.aspectRatio)
    }
}

/**
 * Bottom sheet: 1 · camera (one tap for the Pocket family, "More…" for every camera), 2 · lens kind (prime set,
 * zoom, anamorphic; "Change" for a particular lens), 3 · frame. The button applies it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecceSheet(
    title: String, subtitle: String, button: String, initial: RecceChoice,
    onDismiss: () -> Unit, onDone: (RecceChoice) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var camera by remember { mutableStateOf(initial.camera) }
    var modeId by remember { mutableStateOf(initial.modeId) }
    var lens by remember { mutableStateOf(initial.lens) }
    var focal by remember { mutableStateOf(initial.focal) }
    var aspect by remember { mutableStateOf(initial.aspect) }
    // The lens last used for each kind while the sheet is open, so switching kinds back and forth keeps choices.
    val perKind = remember { mutableStateMapOf(LensKind.of(initial.lens) to initial.lens) }
    var pickCamera by remember { mutableStateOf(false) }
    var pickLens by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var aspectMenu by remember { mutableStateOf(false) }
    var editKits by remember { mutableStateOf(false) }
    var savingKit by remember { mutableStateOf(false) }
    val mode = camera.mode(modeId) ?: camera.sensorModes[0]
    val kind = LensKind.of(lens)

    fun chooseLens(l: LensProfile, wantedFocal: Double) {
        val f = QuickRecce.nearestFocal(l, wantedFocal)
        lens = FocalOptions.lensFor(l, f); focal = f
        perKind[LensKind.of(l)] = lens
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color.White, contentColor = Brand.ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Brand.ink)
            Text(subtitle, fontSize = 13.sp, color = Color(0xFF5B6B8C), modifier = Modifier.padding(top = 2.dp))

            // Saved kits: one tap sets camera, mode, lens, focal length and frame.
            val kits = Settings.kits
            if (kits.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepLabel("Saved kits", Modifier.weight(1f))
                    Text(if (editKits) "Done" else "Edit", color = Brand.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { editKits = !editKits }.padding(8.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kits.forEach { k ->
                        val c = com.infraxcoders.bmpcc.core.Catalog.camera(k.cameraId)
                        val l = com.infraxcoders.bmpcc.core.Catalog.lens(k.lensId)
                        val active = c?.id == camera.id && l?.id == lens.id && kotlin.math.abs(k.focal - focal) < 0.5 && k.aspect == aspect
                        ChoiceChip(if (editKits) "✕ ${k.name}" else k.name, active && !editKits) {
                            if (editKits) Settings.removeKit(k.name)
                            else if (c != null && l != null) {
                                camera = c; modeId = c.mode(k.modeId)?.id ?: c.sensorModes[0].id
                                lens = l; focal = k.focal; aspect = k.aspect; perKind[LensKind.of(l)] = l
                            }
                        }
                    }
                }
            }

            StepLabel("1 · Camera")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val quick = QuickRecce.quickCameras.mapNotNull { (id, _) -> com.infraxcoders.bmpcc.core.Catalog.camera(id) }
                val chips = if (quick.any { it.id == camera.id }) quick else listOf(camera) + quick
                chips.forEach { c ->
                    ChoiceChip(QuickRecce.chipLabel(c), c.id == camera.id) {
                        if (c.id != camera.id) { camera = c; modeId = c.sensorModes[0].id }
                    }
                }
                ChoiceChip("More…", false) { pickCamera = true }
            }
            if (camera.sensorModes.size > 1) Box {
                Text(
                    "Recording: ${mode.name} ▾", color = Brand.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { modeMenu = true }.padding(top = 8.dp, bottom = 2.dp),
                )
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    camera.sensorModes.forEach { m -> DropdownMenuItem({ Text("${m.name}  (${m.sizeText})") }, { modeId = m.id; modeMenu = false }) }
                }
            }

            StepLabel("2 · Lens")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LensKind.entries.forEach { k ->
                    ChoiceChip(k.label, k == kind) { if (k != kind) chooseLens(QuickRecce.lensFor(k, perKind[k]), focal) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(QuickRecce.setName(lens), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.ink, maxLines = 1)
                    val cov = Coverage.evaluate(lens, mode)
                    val focals = FocalOptions.focals(lens)
                    Text(
                        listOfNotNull(
                            if (focals.size > 1) "${ShotPresets.focalText(focals.first())}–${ShotPresets.focalText(focals.last())}" else ShotPresets.focalText(focal),
                            if (lens.isAnamorphic) "${ShotPresets.trim(lens.anamorphicSqueeze)}x squeeze" else null,
                            if (cov == Coverage.UNKNOWN) null else cov.label,
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = Color(0xFF5B6B8C), maxLines = 1,
                    )
                }
                Text("Change", color = Brand.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { pickLens = true }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
            }

            StepLabel("3 · Frame")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val frames = if (aspect in QuickRecce.frames) QuickRecce.frames else QuickRecce.frames + aspect
                frames.forEach { a -> ChoiceChip(a, a == aspect) { aspect = a } }
                Box {
                    ChoiceChip("More…", false) { aspectMenu = true }
                    DropdownMenu(aspectMenu, { aspectMenu = false }) {
                        ShotPresets.aspectRatios.filter { it !in QuickRecce.frames }.forEach { a ->
                            DropdownMenuItem({ Text(a) }, { aspect = a; aspectMenu = false })
                        }
                    }
                }
            }

            Text("Save this camera + lens as a kit", color = Brand.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { savingKit = true }.padding(top = 16.dp, bottom = 4.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                button, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Brand.accent, RoundedCornerShape(14.dp))
                    .clickable { RecceChoice(camera, mode.id, lens, focal, aspect).also { it.save(); onDone(it) } }
                    .padding(vertical = 16.dp),
            )
        }
    }

    if (savingKit) KitNameDialog("${QuickRecce.shortName(camera)} + ${QuickRecce.setName(lens)}", onDismiss = { savingKit = false }) { name ->
        Settings.addKit(Kit(name, camera.id, mode.id, lens.id, focal, aspect)); savingKit = false
    }
    if (pickCamera) CameraPickerDialog(onDismiss = { pickCamera = false }) { c ->
        camera = c; modeId = c.sensorModes[0].id; pickCamera = false
    }
    if (pickLens) LensPickerDialog(camera, mode, onMode = { modeId = it.id }, onDismiss = { pickLens = false }) { l ->
        chooseLens(l, if (l.isZoom) focal else l.focalLengthMin); pickLens = false
    }
}

/** Recce mode entry: the New recce sheet over the live camera picture; "Open viewfinder" creates the shot. */
@Composable
fun RecceStartScreen(nav: Navigator) {
    FullScreen()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val phone = remember { PhoneCamera() }
    var hasCamera by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val asker = rememberPermissionAsker { }
    LaunchedEffect(Unit) { if (!hasCamera) asker.withPermission(Manifest.permission.CAMERA) { hasCamera = true } }
    val initial = remember { RecceChoice.last() }
    val rotation = rememberDisplayRotation()

    Box(Modifier.fillMaxSize().background(Brand.background)) {
        if (hasCamera) CameraPreview(context, lifecycleOwner, phone, rotation)
        Box(Modifier.fillMaxSize().background(Color(0xB30B1E45)))
        // A glimpse of the viewfinder's top bar behind the sheet.
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(QuickRecce.shortName(initial.camera), color = Color(0x55FFFFFF), textColor = Color.White)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ShotPresets.focalText(initial.focal), color = Color(0x99C9D8FF), fontSize = 30.sp, fontFamily = FontFamily.Monospace)
            }
            Pill(initial.aspect, color = Color(0x55FFFFFF), textColor = Color.White)
        }
    }
    RecceSheet(
        title = "New recce", subtitle = "Takes 10 seconds. You can change it anytime from the viewfinder.",
        button = "Open viewfinder", initial = initial, onDismiss = { nav.pop() },
    ) { c ->
        val (s, sc, sh) = RecceStore.quickRecceShot(c.camera, c.lens, c.modeId)
        RecceStore.updateShot(s, sc, sh) { it.copy(focalLength = ShotPresets.focalText(c.focal), aspectRatio = c.aspect) }
        nav.replace(Dest.Finder(s, sc, sh))
    }
}

@Composable
private fun KitNameDialog(suggested: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save kit") },
        text = {
            Column {
                Text("Camera, recording mode, lens, focal length and frame. Pick it later from the New recce sheet.", fontSize = 13.sp)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name, e.g. A-cam") },
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton({ onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
