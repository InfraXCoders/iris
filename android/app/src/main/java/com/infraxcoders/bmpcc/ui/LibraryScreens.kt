package com.infraxcoders.bmpcc.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.CameraProfile
import com.infraxcoders.bmpcc.core.Catalog
import com.infraxcoders.bmpcc.core.Coverage
import com.infraxcoders.bmpcc.core.Distortion
import com.infraxcoders.bmpcc.core.Framing
import com.infraxcoders.bmpcc.core.LensProfile
import com.infraxcoders.bmpcc.core.Optics
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.data.Settings
import kotlin.math.abs

data class LensFilter(
    val kind: Kind = Kind.ALL,
    val manufacturer: String? = null,
    val squeeze: Double? = null,
    val format: String? = null,
    val mount: String? = null,
    val coversDefaultCamera: Boolean = false,
) {
    enum class Kind(val label: String) { ALL("All"), ANAMORPHIC("Anamorphic"), SPHERICAL("Spherical") }

    val isActive: Boolean get() = this != LensFilter()

    fun apply(lenses: List<LensProfile>, camera: CameraProfile): List<LensProfile> = lenses.filter { l ->
        when (kind) {
            Kind.ALL -> true
            Kind.ANAMORPHIC -> l.isAnamorphic
            Kind.SPHERICAL -> !l.isAnamorphic
        } &&
            (manufacturer == null || l.manufacturer == manufacturer) &&
            (squeeze == null || abs(l.anamorphicSqueeze - squeeze) < 0.001) &&
            (format == null || l.format == format) &&
            (mount == null || mount in l.allMountNames) &&
            (!coversDefaultCamera || Coverage.evaluate(l, camera.sensorModes[0]) == Coverage.FULL)
    }

    companion object {
        val manufacturers: List<String> by lazy { Catalog.lenses.map { it.manufacturer }.distinct().sorted() }
        val squeezes: List<Double> by lazy { Catalog.lenses.map { it.anamorphicSqueeze }.distinct().sorted() }
        val formats: List<String> by lazy { listOf("MFT", "APS-C", "S35", "FF", "LF", "65").filter { f -> Catalog.lenses.any { it.format == f } } }
        val mounts: List<String> by lazy {
            Catalog.lenses.flatMap { it.allMountNames }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: Navigator, dest: Dest.Library) {
    val picking = dest.pickCamera != null || dest.pickLens != null
    val tab = dest.tab
    val query = dest.query
    val filter = dest.filter
    val camera = Settings.defaultCamera
    val title = when {
        dest.pickCamera != null -> "Choose camera"
        dest.pickLens != null -> "Choose lens"
        tab == LibraryTab.LENSES -> "Lens database"
        else -> "Camera database"
    }

    Screen(title, onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                if (!picking) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LibraryTab.entries.forEachIndexed { i, t ->
                            SegmentedButton(selected = tab == t, onClick = { dest.tab = t }, shape = SegmentedButtonDefaults.itemShape(i, LibraryTab.entries.size)) { Text(t.label) }
                        }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { dest.query = it }, singleLine = true,
                    placeholder = { Text(if (tab == LibraryTab.LENSES) "Search lenses, series, mounts" else "Search cameras") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ dest.query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (tab == LibraryTab.LENSES) LensFilterBar(filter, camera) { dest.filter = it }
            }
            if (tab == LibraryTab.LENSES) {
                val favs = Settings.favoriteLenses
                val found = filter.apply(Catalog.searchLenses(query), camera)
                val favourites = found.filter { it.id in favs }
                if (favourites.isNotEmpty()) section("Favourites") {
                    items(favourites, key = { "fav-" + it.id }) { l -> LensRow(l, camera, favs, nav, dest) }
                }
                val groups = found.filter { it.id !in favs }.groupBy { "${it.manufacturer} · ${it.series ?: "Other lenses"}" }
                for (g in groups.keys.sortedBy { it.lowercase() }) {
                    section(g) {
                        items(groups[g]!!.sortedBy { it.focalLengthMin }, key = { it.id }) { l -> LensRow(l, camera, favs, nav, dest) }
                    }
                }
                item {
                    Hint(if (found.isEmpty()) "No lens matches." else "${found.size} lenses · specs from makers' published data (source link on each lens)")
                }
            } else {
                val favs = Settings.favoriteCameras
                val found = Catalog.searchCameras(query)
                val favourites = found.filter { it.id in favs }
                if (favourites.isNotEmpty()) section("Favourites") {
                    items(favourites, key = { "fav-" + it.id }) { c -> CameraRow(c, favs, nav, dest) }
                }
                val groups = found.filter { it.id !in favs }.groupBy { it.manufacturer }
                for (g in groups.keys.sorted()) {
                    section(g) { items(groups[g]!!.sortedBy { it.model }, key = { it.id }) { c -> CameraRow(c, favs, nav, dest) } }
                }
                if (found.isEmpty()) item { Hint("No camera matches “$query”.") }
            }
        }
    }
}

@Composable
private fun LensFilterBar(filter: LensFilter, camera: CameraProfile, onChange: (LensFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        LensFilter.Kind.entries.forEach { k ->
            FilterChip(filter.kind == k, { onChange(filter.copy(kind = k)) }, { Text(k.label) })
        }
        MenuChip(filter.manufacturer ?: "Maker", filter.manufacturer != null,
            listOf<Pair<String, () -> Unit>>("Any maker" to { onChange(filter.copy(manufacturer = null)) }) +
                LensFilter.manufacturers.map { m -> m to { onChange(filter.copy(manufacturer = m)) } })
        MenuChip(filter.squeeze?.let { "${ShotPresets.trim(it)}x" } ?: "Squeeze", filter.squeeze != null,
            listOf<Pair<String, () -> Unit>>("Any squeeze" to { onChange(filter.copy(squeeze = null)) }) +
                LensFilter.squeezes.map { s -> (if (s == 1.0) "1x (spherical)" else "${ShotPresets.trim(s)}x") to { onChange(filter.copy(squeeze = s)) } })
        MenuChip(filter.format ?: "Format", filter.format != null,
            listOf<Pair<String, () -> Unit>>("Any format" to { onChange(filter.copy(format = null)) }) +
                LensFilter.formats.map { f -> f to { onChange(filter.copy(format = f)) } })
        MenuChip(filter.mount ?: "Mount", filter.mount != null,
            listOf<Pair<String, () -> Unit>>("Any mount" to { onChange(filter.copy(mount = null)) }) +
                LensFilter.mounts.map { m -> m to { onChange(filter.copy(mount = m)) } })
        FilterChip(filter.coversDefaultCamera, { onChange(filter.copy(coversDefaultCamera = !filter.coversDefaultCamera)) },
            { Text("Covers ${camera.model}") })
        if (filter.isActive) AssistChip({ onChange(LensFilter()) }, { Text("Clear") })
    }
}

@Composable
private fun MenuChip(label: String, active: Boolean, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(active, { open = true }, { Text(label, maxLines = 1) }, trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Brand.accent.copy(alpha = 0.8f)))
        DropdownMenu(open, { open = false }) {
            options.forEach { (text, action) -> DropdownMenuItem({ Text(text) }, { action(); open = false }) }
        }
    }
}

@Composable
private fun LensRow(l: LensProfile, camera: CameraProfile, favs: Set<String>, nav: Navigator, dest: Dest.Library) {
    Row(
        Modifier.cardRow().clickable {
            val pick = dest.pickLens
            if (pick != null) { pick(l); nav.pop() } else nav.push(Dest.LensInfo(l.id))
        }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rangeText(l), fontWeight = FontWeight.SemiBold)
                Text(ShotPresets.tStopText(l.maximumAperture))
                if (l.isAnamorphic) Tag("${ShotPresets.trim(l.anamorphicSqueeze)}x")
                if (l.id == Settings.defaultLensId) Icon(Icons.Filled.CheckCircle, "Default", tint = Brand.accent)
            }
            Text(if (l.series == null) l.displayName else lensDetails(l), style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
        }
        CoverageBadge(Coverage.evaluate(l, camera.sensorModes[0]), Coverage.circle(l)?.nominal ?: false)
        IconButton({ Settings.toggleFavoriteLens(l.id) }) {
            Icon(if (l.id in favs) Icons.Filled.Star else Icons.Filled.StarBorder, "Favourite", tint = if (l.id in favs) Color(0xFFFFCC00) else Color.Gray)
        }
    }
    RowDivider()
}

private fun lensDetails(l: LensProfile): String = buildList {
    if (l.hasImageCircle) add(fmt("IC %.1f mm", l.imageCircleMm)) else l.format?.let { add(it) }
    l.weightG?.let { add(if (it >= 1000) fmt("%.2f kg", it / 1000) else "${it.toInt()} g") }
    l.frontDiameterMm?.let { add("Ø${it.toInt()}") }
    l.mountNames?.let { add(it.joinToString("/")) }
}.joinToString(" · ")

fun rangeText(l: LensProfile): String =
    if (l.isZoom) "${ShotPresets.focalText(l.focalLengthMin)}–${ShotPresets.focalText(l.focalLengthMax)}" else ShotPresets.focalText(l.focalLengthMin)

@Composable
private fun CameraRow(c: CameraProfile, favs: Set<String>, nav: Navigator, dest: Dest.Library) {
    Row(
        Modifier.cardRow().clickable {
            val pick = dest.pickCamera
            if (pick != null) { pick(c); nav.pop() } else nav.push(Dest.CameraInfo(c.id))
        }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.model, fontWeight = FontWeight.SemiBold)
                if (c.id == Settings.defaultCameraId) Icon(Icons.Filled.CheckCircle, "Default", tint = Brand.accent)
            }
            val n = c.sensorModes.size
            Text(fmt("%.2f × %.2f mm", c.sensorWidthMm, c.sensorHeightMm) + " · ${c.allMountNames.joinToString("/")}" + (if (n > 1) " · $n modes" else ""),
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        IconButton({ Settings.toggleFavoriteCamera(c.id) }) {
            Icon(if (c.id in favs) Icons.Filled.Star else Icons.Filled.StarBorder, "Favourite", tint = if (c.id in favs) Color(0xFFFFCC00) else Color.Gray)
        }
    }
    RowDivider()
}

private fun feetInches(m: Double): String {
    val inches = Math.round(m / 0.0254).toInt()
    return "${inches / 12}′ ${inches % 12}″"
}

@Composable
fun LensDetailScreen(nav: Navigator, lensId: String) {
    val l = Catalog.lens(lensId) ?: return Gone(nav)
    val camera = Settings.defaultCamera
    val context = LocalContext.current
    Screen(l.series?.let { "$it ${ShotPresets.focalText(l.focalLengthMin)}" } ?: l.model, onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            section("Lens") {
                item {
                    LabeledRow("Maker", l.manufacturer)
                    l.series?.let { LabeledRow("Series", it) }
                    LabeledRow("Focal length", rangeText(l))
                    LabeledRow("Maximum aperture", when {
                        !l.hasAperture -> "Not published"
                        l.model.contains(" f/") -> "f/" + ShotPresets.trim(l.maximumAperture) + " (photo lens: f-number)"
                        else -> ShotPresets.tStopText(l.maximumAperture)
                    })
                    LabeledRow("Squeeze", if (l.isAnamorphic) "${ShotPresets.trim(l.anamorphicSqueeze)}x anamorphic" else "Spherical")
                    if (l.minimumFocusDistance > 0) LabeledRow("Close focus", fmt("%.2f m · ", l.minimumFocusDistance) + feetInches(l.minimumFocusDistance))
                    LabeledRow("Image circle", if (l.hasImageCircle) fmt("%.1f mm", l.imageCircleMm) else "Not published")
                    l.format?.let { LabeledRow("Designed for", it) }
                    l.lengthMm?.let { LabeledRow("Length", "${Math.round(it)} mm") }
                    l.weightG?.let { LabeledRow("Weight", if (it >= 1000) fmt("%.2f kg", it / 1000) else "${it.toInt()} g") }
                    l.frontDiameterMm?.let { LabeledRow("Front diameter", "${Math.round(it)} mm") }
                    LabeledRow("Mount", l.allMountNames.joinToString(", "))
                    val profile = Distortion.points.filter { it.lensId == l.id }
                    LabeledRow("Distortion profile", if (profile.isEmpty()) (if (l.model.contains(" f/")) "No Lensfun profile for this lens" else "Not published by the maker")
                        else "Lensfun, ${profile.size} focal length(s): " + profile.joinToString(", ") { ShotPresets.trim(it.focalMm) } + " mm")
                }
            }
            if (l.sourceUrl != null || l.notes != null) section("Data source") {
                item {
                    l.sourceUrl?.let { url ->
                        LabeledRow("Maker's specifications", "Open", onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        })
                    }
                    l.notes?.let { Hint(it) }
                }
            }
            section("On ${camera.model}", footer = if (Coverage.circle(l)?.nominal == true)
                "The maker doesn't publish this lens's image circle, so coverage uses a typical value for ${l.format ?: "its format"}. Test before the shoot." else null) {
                items(camera.sensorModes, key = { it.id }) { m ->
                    val c = Coverage.evaluate(l, m)
                    Row(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name)
                            Framing.reference(camera.using(m.id), l, l.focalLengthMin)?.let { r ->
                                MonoText("${r.deliveredFov.horizontal.degreesText()} × ${r.deliveredFov.vertical.degreesText()} view", Color.Gray)
                            }
                        }
                        Text(c.label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(Modifier.width(6.dp))
                        CoverageBadge(c, Coverage.circle(l)?.nominal ?: false)
                    }
                    RowDivider()
                }
                item {
                    if (!Coverage.sharesMount(camera, l)) Hint("Different mount: needs an adapter, if one exists for ${l.allMountNames.joinToString("/")} → ${camera.allMountNames.joinToString("/")}.")
                    ActionRow(Icons.Filled.Search, "Open in coverage tool", null) { nav.push(Dest.Coverage(camera.id, l.id)) }
                }
            }
            if (l.quickFocalLengths.size > 1) section("Field of view on ${camera.model}") {
                items(l.quickFocalLengths, key = { "f$it" }) { f ->
                    Framing.reference(camera, l, f)?.let { r ->
                        LabeledRow(ShotPresets.focalText(f), "${r.deliveredFov.horizontal.degreesText()} × ${r.deliveredFov.vertical.degreesText()}")
                    }
                }
            }
            item {
                val isDefault = l.id == Settings.defaultLensId
                ActionRow(Icons.Filled.CheckCircle, if (isDefault) "Default for new shots ✓" else "Use for new shots", null, enabled = !isDefault) {
                    Settings.setDefaultLens(l.id)
                }
            }
        }
    }
}

@Composable
fun CameraDetailScreen(nav: Navigator, cameraId: String) {
    val c = Catalog.camera(cameraId) ?: return Gone(nav)
    val lens = Settings.defaultLens
    val context = LocalContext.current
    Screen(c.model, onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            section("Camera") {
                item {
                    LabeledRow("Maker", c.manufacturer)
                    LabeledRow("Mount", c.allMountNames.joinToString(", "))
                    LabeledRow("Full sensor", fmt("%.2f × %.2f mm", c.sensorWidthMm, c.sensorHeightMm))
                    LabeledRow("Crop factor", fmt("%.2fx", Optics.cropFactor(c.sensorWidthMm)))
                    if (c.resolutionWidth > 0) LabeledRow("Resolution", "${c.resolutionWidth} × ${c.resolutionHeight}")
                    c.sourceUrl?.let { url ->
                        LabeledRow("Maker's specifications", "Open", onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
                    }
                }
            }
            section("Recording modes", footer = "Sensor area each mode uses. The badge shows whether ${lens.displayName} covers it.") {
                items(c.sensorModes, key = { it.id }) { m ->
                    Row(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name)
                            MonoText(listOfNotNull(m.sizeText, m.resolutionText).joinToString(" · "), Color.Gray)
                        }
                        CoverageBadge(Coverage.evaluate(lens, m), Coverage.circle(lens)?.nominal ?: false)
                    }
                    RowDivider()
                }
            }
            item {
                ActionRow(Icons.Filled.Search, "Open in coverage tool", null) { nav.push(Dest.Coverage(c.id, lens.id)) }
                val isDefault = c.id == Settings.defaultCameraId
                ActionRow(Icons.Filled.CheckCircle, if (isDefault) "Default for new shots ✓" else "Use for new shots", null, enabled = !isDefault) {
                    Settings.setDefaultCamera(c.id)
                }
            }
        }
    }
}

