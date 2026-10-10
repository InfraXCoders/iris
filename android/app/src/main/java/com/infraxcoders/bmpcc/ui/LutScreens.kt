package com.infraxcoders.bmpcc.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infraxcoders.bmpcc.core.BuiltInLooks
import com.infraxcoders.bmpcc.core.LutInput
import com.infraxcoders.bmpcc.data.LutEntry
import com.infraxcoders.bmpcc.data.LutStore
import com.infraxcoders.bmpcc.platform.PictureEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The LUT library: built-in looks and imported .cube files, each with a preview strip and its input. */
@Composable
fun LutLibraryScreen(nav: Navigator) {
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<LutEntry?>(null) }
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = "Importing…"
            scope.launch {
                message = try {
                    val e = LutStore.import(uri)
                    "Imported “${e.name}” (${e.size}³). Input: ${LutStore.input(e.id).label}."
                } catch (e: Exception) { e.message ?: "Import failed." }
            }
        }
    }
    Screen("LUTs", onBack = { nav.pop() }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                ActionRow(Icons.Filled.FileDownload, "Import a .cube LUT", "From Files, Drive or WhatsApp; 3D (up to 65³) or 1D") {
                    importer.launch(arrayOf("*/*"))
                }
                message?.let { Hint(it) }
                Hint(
                    "Monitoring preview only: the LUT is applied to the phone's camera picture on screen, not through a colour-managed " +
                        "pipeline. Log LUTs (e.g. Blackmagic Film Gen 5 → Rec.709) get an approximate log version of the phone picture: " +
                        "tone only, no gamut conversion, and highlights won't match a real BRAW recording." +
                        if (PictureEffect.supported) "" else " Live LUT on the camera picture needs Android 13 or newer; on this phone LUTs are applied to saved frames.",
                )
            }
            section("Looks") {
                items(LutStore.entries, key = { it.id }) { e -> LutRow(e) { confirmDelete = e } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${e.name}”?") },
            text = { Text("Shots that use it will show no LUT.") },
            confirmButton = { TextButton({ LutStore.delete(e.id); confirmDelete = null }) { Text("Delete", color = Color(0xFFFF453A)) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LutRow(e: LutEntry, onDelete: () -> Unit) {
    val input = LutStore.input(e.id)
    // Preview: a colour chart through the LUT.
    val chart by produceState<Bitmap?>(null, e.id, input) {
        value = withContext(Dispatchers.Default) {
            val w = 160; val h = 40
            val src = Bitmap.createBitmap(BuiltInLooks.testChart(w, h), w, h, Bitmap.Config.ARGB_8888)
            LutStore.graded(src, e.id)
        }
    }
    Column(Modifier.cardRow().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            chart?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(96.dp, 24.dp).clip(RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.SemiBold)
                Text((if (e.builtIn) "Built in" else ".cube") + (e.size?.let { " · ${it}³" } ?: ""), color = Brand.muted, fontSize = 12.sp)
            }
            if (!e.builtIn) IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = Color.Gray) }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Input:", color = Brand.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
            ChoiceChip("Rec.709", input == LutInput.REC709) { LutStore.chooseInput(e.id, LutInput.REC709) }
            ChoiceChip("BMD Film Gen 5", input == LutInput.BMD_FILM_GEN5) { LutStore.chooseInput(e.id, LutInput.BMD_FILM_GEN5) }
        }
    }
    RowDivider()
}
