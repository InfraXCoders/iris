package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.infraxcoders.bmpcc.core.CameraProfile
import com.infraxcoders.bmpcc.core.Catalog
import com.infraxcoders.bmpcc.core.Coverage
import com.infraxcoders.bmpcc.core.LensProfile
import com.infraxcoders.bmpcc.core.SensorMode
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.data.Settings

/**
 * "Start recce" popup: step 1 choose the cinema camera (and its recording mode), step 2 choose the lens.
 * Choosing a lens finishes and calls [onDone].
 */
@Composable
fun RecceSetupDialog(
    title: String = "Start recce",
    onDismiss: () -> Unit,
    onDone: (CameraProfile, SensorMode, LensProfile) -> Unit,
) {
    var camera by remember { mutableStateOf<CameraProfile?>(null) }
    var mode by remember { mutableStateOf<SensorMode?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Brand.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // Header with steps
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (camera != null) { camera = null; mode = null } else onDismiss() }) {
                        Icon(if (camera != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(if (camera == null) "Step 1 of 2 · Choose the cinema camera" else "Step 2 of 2 · Choose the lens",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                StepBar(if (camera == null) 1 else 2)
                val c = camera
                if (c == null) CameraStep { picked -> camera = picked; mode = picked.sensorModes[0]; Settings.setDefaultCamera(picked.id) }
                else LensStep(c, mode ?: c.sensorModes[0], onMode = { mode = it }) { lens ->
                    Settings.setDefaultLens(lens.id)
                    onDone(c, mode ?: c.sensorModes[0], lens)
                }
            }
        }
    }
}

@Composable
private fun StepBar(step: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Camera", "Lens", "Viewfinder").forEachIndexed { i, label ->
            val active = i + 1 <= step
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(if (active) Brand.accent else Color(0xFF22407F), RoundedCornerShape(2.dp)))
                Text(label, fontSize = 11.sp, color = if (active) Color.White else Color.Gray, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Filled.Close, "Clear") } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun CameraStep(onPick: (CameraProfile) -> Unit) {
    var query by remember { mutableStateOf("") }
    val found = Catalog.searchCameras(query)
    val last = Catalog.camera(Settings.defaultCameraId)
    val favs = Settings.favoriteCameras
    Column {
        SearchField(query, "Search cameras (e.g. pocket 6k, alexa)") { query = it }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isEmpty() && last != null) section("Last used") { item(key = "last") { CameraPickRow(last, true, onPick) } }
            val favourites = found.filter { it.id in favs }
            if (favourites.isNotEmpty()) section("Favourites") { items(favourites, key = { "f" + it.id }) { CameraPickRow(it, false, onPick) } }
            val groups = found.groupBy { it.manufacturer }
            for (maker in groups.keys.sorted()) {
                section(maker) { items(groups[maker]!!.sortedBy { it.model }, key = { it.id }) { CameraPickRow(it, false, onPick) } }
            }
            if (found.isEmpty()) item { Hint("No camera matches “$query”.") }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CameraPickRow(c: CameraProfile, highlight: Boolean, onPick: (CameraProfile) -> Unit) {
    Row(Modifier.cardRow().clickable { onPick(c) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(if (highlight) Brand.accent else Color(0xFF22407F), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.model, fontWeight = FontWeight.SemiBold)
            val n = c.sensorModes.size
            Text(fmt("%.1f × %.1f mm", c.sensorWidthMm, c.sensorHeightMm) + " · ${c.allMountNames.joinToString("/")}" + if (n > 1) " · $n recording modes" else "",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (c.id in Settings.favoriteCameras) Icon(Icons.Filled.Star, null, tint = Color(0xFFFFCC00), modifier = Modifier.size(18.dp))
    }
    RowDivider()
}

/** A prime series (or a single lens) shown as one card with its focal lengths as buttons. */
private data class LensSet(val title: String, val subtitle: String, val lenses: List<LensProfile>)

private fun lensSets(lenses: List<LensProfile>): List<LensSet> {
    val bySeries = lenses.groupBy { if (it.series != null && !it.isZoom) "${it.manufacturer}|${it.series}" else "single|${it.id}" }
    return bySeries.values.map { group ->
        val first = group.first()
        val sorted = group.sortedBy { it.focalLengthMin }
        if (first.series != null && !first.isZoom) {
            val stops = sorted.mapNotNull { if (it.hasAperture) it.maximumAperture else null }
            val tText = if (stops.isEmpty()) "" else " · T${ShotPresets.trim(stops.min())}" + if (stops.max() > stops.min()) "–${ShotPresets.trim(stops.max())}" else ""
            LensSet("${first.manufacturer} ${first.series}",
                (if (first.isAnamorphic) "${ShotPresets.trim(first.anamorphicSqueeze)}x anamorphic" else "Spherical") + tText +
                    (first.format?.let { " · $it" } ?: "") + " · ${first.allMountNames.joinToString("/")}", sorted)
        } else LensSet(first.displayName,
            (if (first.isAnamorphic) "${ShotPresets.trim(first.anamorphicSqueeze)}x anamorphic · " else "") +
                "${rangeText(first)} · ${ShotPresets.tStopText(first.maximumAperture)} · ${first.allMountNames.joinToString("/")}", sorted)
    }.sortedBy { it.title.lowercase() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LensStep(camera: CameraProfile, mode: SensorMode, onMode: (SensorMode) -> Unit, onPick: (LensProfile) -> Unit) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(LensFilter.Kind.ALL) }
    var maker by remember { mutableStateOf<String?>(null) }
    var modeMenu by remember { mutableStateOf(false) }
    var makerMenu by remember { mutableStateOf(false) }
    val filtered = LensFilter(kind = kind, manufacturer = maker).apply(Catalog.searchLenses(query), camera)
    val sets = remember(filtered) { lensSets(filtered) }
    val last = Catalog.lens(Settings.defaultLensId)

    Column {
        // Chosen camera + recording mode
        Row(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(camera.model, fontWeight = FontWeight.SemiBold)
                Text("${mode.name} · ${mode.sizeText}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (camera.sensorModes.size > 1) Box {
                FilterChip(true, { modeMenu = true }, { Text("Mode") })
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    camera.sensorModes.forEach { m -> DropdownMenuItem({ Text("${m.name}  (${m.sizeText})") }, { onMode(m); modeMenu = false }) }
                }
            }
        }
        SearchField(query, "Search lenses (e.g. master anamorphic, sirui, 50)") { query = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LensFilter.Kind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(k.label) }) }
            Box {
                FilterChip(maker != null, { makerMenu = true }, { Text(maker ?: "All makers") })
                DropdownMenu(makerMenu, { makerMenu = false }) {
                    DropdownMenuItem({ Text("All makers") }, { maker = null; makerMenu = false })
                    LensFilter.manufacturers.forEach { m -> DropdownMenuItem({ Text(m) }, { maker = m; makerMenu = false }) }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isEmpty() && maker == null && kind == LensFilter.Kind.ALL && last != null) section("Last used") {
                item(key = "last") { LensSetCard(LensSet(last.displayName, rangeText(last), listOf(last)), mode, onPick, single = true) }
            }
            section("Tap a focal length") {
                items(sets, key = { it.title + it.lenses.first().id }) { set -> LensSetCard(set, mode, onPick) }
            }
            if (sets.isEmpty()) item { Hint("No lens matches.") }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LensSetCard(set: LensSet, mode: SensorMode, onPick: (LensProfile) -> Unit, single: Boolean = false) {
    val first = set.lenses.first()
    val cov = Coverage.evaluate(first, mode)
    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(set.title, fontWeight = FontWeight.SemiBold)
                Text(set.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2)
            }
            CoverageBadge(cov, Coverage.circle(first)?.nominal ?: false)
            Text(" ${cov.label}", fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            set.lenses.forEach { l ->
                Text(
                    if (single || l.isZoom || l.series == null) rangeText(l) else ShotPresets.focalText(l.focalLengthMin),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.background(Brand.accent.copy(alpha = 0.22f), RoundedCornerShape(50))
                        .clickable { onPick(l) }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    RowDivider()
}

/** Full-screen list of every camera (search, favourites, makers); used by "More…" in the New recce sheet. */
@Composable
fun CameraPickerDialog(onDismiss: () -> Unit, onPick: (CameraProfile) -> Unit) =
    PickerFrame("Choose camera", onDismiss) { CameraStep(onPick) }

/** Full-screen lens list for a camera and recording mode; used by "Change" in the New recce sheet. */
@Composable
fun LensPickerDialog(camera: CameraProfile, mode: SensorMode, onMode: (SensorMode) -> Unit, onDismiss: () -> Unit, onPick: (LensProfile) -> Unit) =
    PickerFrame("Choose lens", onDismiss) { LensStep(camera, mode, onMode, onPick) }

@Composable
private fun PickerFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Brand.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Filled.Close, "Close") }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                content()
            }
        }
    }
}
