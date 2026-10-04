package com.infraxcoders.bmpcc.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.infraxcoders.bmpcc.core.CameraProfile
import com.infraxcoders.bmpcc.core.Catalog
import com.infraxcoders.bmpcc.core.LensProfile
import com.infraxcoders.bmpcc.core.RecceJson
import com.infraxcoders.bmpcc.core.RecceNote
import com.infraxcoders.bmpcc.core.RecceScene
import com.infraxcoders.bmpcc.core.RecceSession
import com.infraxcoders.bmpcc.core.RecceShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Saved recces: one JSON file per recce (same format as the iPhone app's export), plus reference photos.
 * Everything stays on the phone.
 */
object RecceStore {
    private lateinit var appContext: Context
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessions = MutableStateFlow<List<RecceSession>>(emptyList())
    val sessions: StateFlow<List<RecceSession>> = _sessions

    private val recceDir: File get() = File(appContext.filesDir, "recces").apply { mkdirs() }
    val referencesDir: File get() = File(appContext.filesDir, "references").apply { mkdirs() }
    val exportsDir: File get() = File(appContext.cacheDir, "exports").apply { mkdirs() }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _sessions.value = recceDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { RecceJson.decode(f.readText()) }.getOrNull()
        }.sortedByDescending { it.modificationTimestamp }
    }

    fun session(id: String): RecceSession? = _sessions.value.firstOrNull { it.id == id }

    private fun write(s: RecceSession) {
        val text = RecceJson.encode(s)
        val file = File(recceDir, "${s.id}.json")
        io.launch { synchronized(this@RecceStore) { file.writeText(text) } }
    }

    /** Replaces a session (marks it modified) and saves it. */
    fun save(s: RecceSession) {
        val updated = s.touched()
        _sessions.value = (listOf(updated) + _sessions.value.filter { it.id != s.id })
            .sortedByDescending { it.modificationTimestamp }
        write(updated)
    }

    fun update(sessionId: String, change: (RecceSession) -> RecceSession) {
        session(sessionId)?.let { save(change(it)) }
    }

    fun updateScene(sessionId: String, sceneId: String, change: (RecceScene) -> RecceScene) =
        update(sessionId) { s -> s.copy(scenes = s.scenes.map { if (it.id == sceneId) change(it) else it }) }

    fun updateShot(sessionId: String, sceneId: String, shotId: String, change: (RecceShot) -> RecceShot) =
        updateScene(sessionId, sceneId) { sc ->
            sc.copy(shots = sc.shots.map { if (it.id == shotId) change(it).copy(modificationTimestamp = System.currentTimeMillis()) else it })
        }

    fun delete(sessionId: String) {
        val s = session(sessionId) ?: return
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        io.launch {
            File(recceDir, "$sessionId.json").delete()
            s.scenes.flatMap { it.shots }.flatMap { it.references }.forEach { File(referencesDir, it.fileName).delete() }
        }
    }

    fun newSession(project: String, location: String, latitude: Double? = null, longitude: Double? = null): RecceSession {
        val s = RecceSession(
            projectName = project.ifBlank { "Untitled project" }, locationName = location.ifBlank { "Location" },
            latitude = latitude, longitude = longitude,
        )
        save(s)
        return s
    }

    fun newScene(sessionId: String): RecceScene? {
        val s = session(sessionId) ?: return null
        val next = (s.scenes.mapNotNull { it.sceneNumber.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val scene = RecceScene(sessionId = sessionId, sceneNumber = "$next")
        save(s.copy(scenes = s.scenes + scene))
        return scene
    }

    fun newShot(sessionId: String, sceneId: String, camera: CameraProfile, lens: LensProfile): RecceShot? {
        val s = session(sessionId) ?: return null
        val scene = s.scenes.firstOrNull { it.id == sceneId } ?: return null
        val next = (scene.shots.mapNotNull { it.shotNumber.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val shot = RecceShot.create(sceneId, "$next", camera, lens)
        updateScene(sessionId, sceneId) { it.copy(shots = it.shots + shot) }
        return shot
    }

    /** "Quick recce": one session/scene/shot ready for the viewfinder. Returns (sessionId, sceneId, shotId). */
    fun quickRecceShot(camera: CameraProfile, lens: LensProfile): Triple<String, String, String> {
        val s = _sessions.value.firstOrNull { it.projectName == "Quick Recce" } ?: newSession("Quick Recce", "Location scouting")
        val scene = session(s.id)!!.sortedScenes.firstOrNull() ?: newScene(s.id)!!
        val shot = newShot(s.id, scene.id, camera, lens)!!
        return Triple(s.id, scene.id, shot.id)
    }

    fun addNote(sessionId: String, text: String, shotId: String?) =
        update(sessionId) { it.copy(voiceNotes = it.voiceNotes + RecceNote(sessionId = sessionId, shotId = shotId, rawTranscription = text)) }

    fun deleteNote(sessionId: String, noteId: String) =
        update(sessionId) { s -> s.copy(voiceNotes = s.voiceNotes.filter { it.id != noteId }) }

    fun newReferenceFile(): File = File(referencesDir, "${UUID.randomUUID()}.jpg")

    // MARK: JSON import / export

    class ImportException(message: String) : Exception(message)

    fun importJson(uri: Uri): RecceSession {
        val text = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw ImportException("The file couldn't be read.")
        val s = runCatching { RecceJson.decode(text) }.getOrElse { throw ImportException("This isn't a recce file.") }
        if (session(s.id) != null) throw ImportException("“${s.projectName}” is already in your recces.")
        _sessions.value = (listOf(s) + _sessions.value).sortedByDescending { it.modificationTimestamp }
        write(s)
        return s
    }

    fun exportJson(s: RecceSession): File {
        val f = File(exportsDir, "${safeName(s.projectName)}-${safeName(s.locationName)}.json")
        f.writeText(RecceJson.encode(s))
        return f
    }

    fun safeName(text: String): String =
        text.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "Recce" }
}

/** Choices remembered between launches, as Compose state so screens update when they change. */
object Settings {
    private lateinit var p: SharedPreferences

    var defaultCameraId by mutableStateOf(Catalog.defaultCamera.id); private set
    var defaultLensId by mutableStateOf(Catalog.defaultLens.id); private set
    var favoriteCameras by mutableStateOf(emptySet<String>()); private set
    var favoriteLenses by mutableStateOf(emptySet<String>()); private set
    var noteLanguage by mutableStateOf("en-IN"); private set

    fun init(context: Context) {
        p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        defaultCameraId = p.getString("defaultCameraId", null) ?: Catalog.defaultCamera.id
        defaultLensId = p.getString("defaultLensId", null) ?: Catalog.defaultLens.id
        favoriteCameras = p.getStringSet("favoriteCameras", emptySet())?.toSet() ?: emptySet()
        favoriteLenses = p.getStringSet("favoriteLenses", emptySet())?.toSet() ?: emptySet()
        noteLanguage = p.getString("noteLanguage", null) ?: "en-IN"
    }

    fun setDefaultCamera(id: String) { defaultCameraId = id; p.edit().putString("defaultCameraId", id).apply() }
    fun setDefaultLens(id: String) { defaultLensId = id; p.edit().putString("defaultLensId", id).apply() }
    fun chooseNoteLanguage(id: String) { noteLanguage = id; p.edit().putString("noteLanguage", id).apply() }
    fun toggleFavoriteCamera(id: String) {
        favoriteCameras = if (id in favoriteCameras) favoriteCameras - id else favoriteCameras + id
        p.edit().putStringSet("favoriteCameras", favoriteCameras).apply()
    }
    fun toggleFavoriteLens(id: String) {
        favoriteLenses = if (id in favoriteLenses) favoriteLenses - id else favoriteLenses + id
        p.edit().putStringSet("favoriteLenses", favoriteLenses).apply()
    }

    val defaultCamera: CameraProfile get() = Catalog.camera(defaultCameraId) ?: Catalog.defaultCamera
    val defaultLens: LensProfile get() = Catalog.lens(defaultLensId) ?: Catalog.defaultLens
}
