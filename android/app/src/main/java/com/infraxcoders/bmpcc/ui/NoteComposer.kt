package com.infraxcoders.bmpcc.ui

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infraxcoders.bmpcc.core.NotesSorter
import com.infraxcoders.bmpcc.data.RecceStore
import com.infraxcoders.bmpcc.data.Settings
import com.infraxcoders.bmpcc.platform.Dictation
import com.infraxcoders.bmpcc.platform.rememberPermissionAsker

/** Dictate or type a recce note; the transcript stays editable and is saved with the recce. */
@Composable
fun NoteComposer(sessionId: String, shotId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val dictation = remember { Dictation(context) }
    var text by remember { mutableStateOf("") }
    var permissionError by remember { mutableStateOf<String?>(null) }
    val asker = rememberPermissionAsker { permissionError = "Microphone access is off. You can still type the note." }
    DisposableEffect(Unit) { onDispose { dictation.release() } }
    LaunchedEffect(dictation.transcript) { if (dictation.transcript.isNotEmpty()) text = dictation.transcript }

    AlertDialog(
        onDismissRequest = { dictation.release(); onDone() },
        title = { Text(if (shotId == null) "Recce note" else "Shot note") },
        text = {
            Column {
                ChoiceRow("Language", Settings.noteLanguage, Dictation.languages.map { it.first },
                    { id -> Dictation.languages.firstOrNull { it.first == id }?.second ?: id }) { Settings.chooseNoteLanguage(it) }
                Button(
                    onClick = {
                        if (dictation.isListening) dictation.stop()
                        else asker.withPermission(Manifest.permission.RECORD_AUDIO) { dictation.start(Settings.noteLanguage, text) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (dictation.isListening) Brand.locked else Brand.accent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(if (dictation.isListening) Icons.Filled.Stop else Icons.Filled.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (dictation.isListening) "Listening… tap to stop" else "Dictate")
                }
                (dictation.error ?: permissionError)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9F0A)) }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("e.g. Window light from the left, 35mm, dolly in slowly") },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
                val t = text.trim()
                if (t.isNotEmpty()) {
                    val s = NotesSorter.sort(t)
                    Text("Will be sorted as", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Row {
                        Tags {
                            Tag(s.language)
                            if (s.composition != null) Tag("Composition")
                            if (s.lighting != null) Tag("Lighting")
                            if (NotesSorter.isMovement(t)) Tag("Movement")
                            s.focalLength?.let { Tag(it) }
                        }
                    }
                }
                Text("Speak naturally in English, Hindi or Hinglish. You can edit the text before saving.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                dictation.release()
                RecceStore.addNote(sessionId, text.trim(), shotId)
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton({ dictation.release(); onDone() }) { Text("Cancel") } },
    )
}
