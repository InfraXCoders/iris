package com.infraxcoders.bmpcc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infraxcoders.bmpcc.core.RecceShot
import com.infraxcoders.bmpcc.core.ShotReference
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.platform.Images
import java.io.File
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Every saved frame of a scene side by side, cropped to its frame lines, to compare framing options. */
@Composable
fun CompareScreen(nav: Navigator, sessionId: String, sceneId: String) {
    val sessions by RecceStore.sessions.collectAsState()
    val scene = sessions.firstOrNull { it.id == sessionId }?.scenes?.firstOrNull { it.id == sceneId } ?: return Gone(nav)
    val frames = scene.sortedShots.flatMap { shot -> shot.references.sortedBy { it.timestamp }.map { shot to it } }
    var columns by remember { mutableIntStateOf(2) }
    Screen("Compare · Scene ${scene.sceneNumber}", onBack = { nav.pop() }, actions = {
        Text(if (columns == 2) "1 column" else "2 columns", color = Brand.accentText, fontSize = 14.sp,
            modifier = Modifier.clickable { columns = if (columns == 2) 1 else 2 }.padding(12.dp))
    }) { pad ->
        if (frames.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp)) {
                Text("No saved frames in this scene yet. In the viewfinder, the white button saves a frame to the shot.",
                    color = Color.Gray, textAlign = TextAlign.Center)
            }
        } else LazyVerticalGrid(
            GridCells.Fixed(columns), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(frames, key = { "${it.first.id}/${it.second.id}" }) { (shot, ref) ->
                FrameCard(shot, ref) { nav.push(Dest.Shot(sessionId, sceneId, shot.id)) }
            }
        }
    }
}

@Composable
private fun FrameCard(shot: RecceShot, ref: ShotReference, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, ref.filePath) {
        value = withContext(Dispatchers.IO) { Images.load(File(RecceStore.referencesDir, ref.fileName), 1200) }
    }
    Column(Modifier.clickable(onClick = onClick)) {
        val b = bmp
        if (b != null) {
            // Crop to the saved frame lines (the whole photo when none were saved).
            fun ok(v: Double?, d: Double) = v?.takeIf { !it.isNaN() } ?: d
            val fx = ok(ref.frameX, 0.0).coerceIn(0.0, 0.99); val fy = ok(ref.frameY, 0.0).coerceIn(0.0, 0.99)
            val fw = ok(ref.frameWidth, 1.0).coerceIn(0.01, 1.0 - fx); val fh = ok(ref.frameHeight, 1.0).coerceIn(0.01, 1.0 - fy)
            val src = IntOffset((fx * b.width).roundToInt(), (fy * b.height).roundToInt())
            val srcSize = IntSize(
                (fw * b.width).roundToInt().coerceIn(1, (b.width - src.x).coerceAtLeast(1)),
                (fh * b.height).roundToInt().coerceIn(1, (b.height - src.y).coerceAtLeast(1)),
            )
            val image = remember(b) { b.asImageBitmap() }
            Canvas(Modifier.fillMaxWidth().aspectRatio(srcSize.width.toFloat() / srcSize.height).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                drawImage(image, srcOffset = src, srcSize = srcSize, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
        } else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color(0xFF22407F), RoundedCornerShape(6.dp)))
        Text("Shot ${shot.shotNumber} · ${shot.shotType.label}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp))
        Text("${shot.focalLength} · ${shot.aspectRatio} · ${shot.lens.series ?: shot.lens.model}", color = Color.Gray, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}
