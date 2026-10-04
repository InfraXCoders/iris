package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.Catalog
import com.infraxcoders.bmpcc.core.Coverage
import com.infraxcoders.bmpcc.core.Framing
import com.infraxcoders.bmpcc.core.Optics
import com.infraxcoders.bmpcc.core.SensorMode
import com.infraxcoders.bmpcc.core.ShotPresets
import com.infraxcoders.bmpcc.data.Settings
import kotlin.math.max
import kotlin.math.min

/** Lens coverage tool: the image circle drawn over the sensor area for a camera, recording mode and lens. */
@Composable
fun CoverageScreen(nav: Navigator, st: Dest.Coverage) {
    val camera = st.cameraId?.let { Catalog.camera(it) } ?: Settings.defaultCamera
    val lens = st.lensId?.let { Catalog.lens(it) } ?: Settings.defaultLens
    val mode = camera.mode(st.modeId) ?: camera.sensorModes[0]
    val coverage = Coverage.evaluate(lens, mode)
    val circle = Coverage.circle(lens)

    Screen("Lens coverage", onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                LabeledRow("Camera", camera.model, onClick = {
                    nav.push(Dest.Library(LibraryTab.CAMERAS, pickCamera = { c -> st.cameraId = c.id; st.modeId = null }))
                })
                if (camera.sensorModes.size > 1) {
                    ChoiceRow("Recording mode", mode, camera.sensorModes, { it.name }) { st.modeId = it.id }
                }
                LabeledRow("Lens", lens.series?.let { "${lens.manufacturer} $it ${ShotPresets.focalText(lens.focalLengthMin)}" } ?: lens.displayName,
                    onClick = { nav.push(Dest.Library(LibraryTab.LENSES, pickLens = { l -> st.lensId = l.id })) })
                CoverageDiagram(camera.sensorModes[0], mode, circle?.mm, circle?.nominal ?: false)
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverageBadge(coverage, circle?.nominal ?: false, Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(coverage.label, style = MaterialTheme.typography.titleMedium)
                        Text(explanation(coverage, circle, mode), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Brand.frameLine, "Image inside the circle"); Legend(Color.Red, "Vignetted"); Legend(Color.Gray, "Full sensor")
                }
            }
            section("Numbers") {
                item {
                    LabeledRow("Mode area", mode.sizeText)
                    LabeledRow("Mode diagonal", fmt("%.2f mm", mode.diagonalMm))
                    LabeledRow("Image circle", circle?.let { fmt("%.1f mm", it.mm) + if (it.nominal) " (typical ${lens.format ?: ""})" else "" } ?: "Not published")
                    Framing.reference(camera.using(mode.id), lens, lens.focalLengthMin)?.let { r ->
                        LabeledRow("Field of view", "${r.deliveredFov.horizontal.degreesText()} × ${r.deliveredFov.vertical.degreesText()}")
                        if (lens.isAnamorphic) LabeledRow("Desqueezed aspect", fmt("%.2f:1", r.deliveredAspect))
                        LabeledRow("Full-frame equivalent", "${Math.round(Optics.equivalentFocalLength(lens.focalLengthMin, mode.widthMm))}mm")
                    }
                    Coverage.largestCoveredMode(lens, camera)?.takeIf { it.id != mode.id }?.let { LabeledRow("Largest mode fully covered", it.name) }
                    LabeledRow("Mount", if (Coverage.sharesMount(camera, lens)) "Fits (${lens.allMountNames.joinToString("/")})"
                        else "Adapter needed: ${lens.allMountNames.joinToString("/")} → ${camera.allMountNames.joinToString("/")}")
                }
            }
        }
    }
}

private fun explanation(c: Coverage, circle: Coverage.Circle?, mode: SensorMode): String {
    val note = if (circle?.nominal == true) " Based on a typical image circle; the maker doesn't publish one." else ""
    return when (c) {
        Coverage.FULL -> "The image circle covers the whole ${mode.name} area.$note"
        Coverage.CORNERS_VIGNETTE -> "Covers the width, but the corners go dark. A wider delivery aspect or a smaller mode may hide it.$note"
        Coverage.VIGNETTES -> "The image circle is narrower than the sensor area. Choose a smaller recording mode.$note"
        Coverage.UNKNOWN -> "The maker doesn't publish this lens's image circle or format."
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

/** Sensor rectangles and the image circle, to scale. */
@Composable
fun CoverageDiagram(full: SensorMode, mode: SensorMode, circleMm: Double?, nominal: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp).background(Color.Black)) {
        val span = max(full.diagonalMm, circleMm ?: 0.0) * 1.08
        val scale = (min(size.width, size.height) / span).toFloat()
        val cx = size.width / 2
        val cy = size.height / 2
        fun rect(w: Double, h: Double) = Pair(Offset(cx - (w * scale / 2).toFloat(), cy - (h * scale / 2).toFloat()), Size((w * scale).toFloat(), (h * scale).toFloat()))
        val (fo, fs) = rect(full.widthMm, full.heightMm)
        drawRect(Color.Gray, fo, fs, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        val (ao, asz) = rect(mode.widthMm, mode.heightMm)
        if (circleMm != null) {
            val r = (circleMm * scale / 2).toFloat()
            drawRect(Color.Red.copy(alpha = 0.45f), ao, asz)
            val circlePath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) }
            clipPath(circlePath) { drawRect(Brand.frameLine.copy(alpha = 0.45f), ao, asz) }
            drawCircle(Color.Green, r, Offset(cx, cy), style = Stroke(2.dp.toPx(), pathEffect = if (nominal) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
            drawContext.canvas.nativeCanvas.drawText(fmt("⌀ %.1f mm", circleMm), cx - 40f, cy - r - 8f,
                android.graphics.Paint().apply { color = android.graphics.Color.GREEN; textSize = 28f; isAntiAlias = true })
        } else drawRect(Color.Gray.copy(alpha = 0.35f), ao, asz)
        drawRect(Brand.frameLine, ao, asz, style = Stroke(2.dp.toPx()))
        drawContext.canvas.nativeCanvas.drawText(fmt("%.1f × %.1f mm", mode.widthMm, mode.heightMm), ao.x, ao.y + asz.height + 30f,
            android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 28f; isAntiAlias = true })
    }
}
