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
import com.infraxcoders.bmpcc.core.PeakingColour
import com.infraxcoders.bmpcc.core.PeakingLevel
import com.infraxcoders.bmpcc.core.ScopeKind
import com.infraxcoders.bmpcc.core.Zebra
import com.infraxcoders.bmpcc.core.Distortion
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

    fun newShot(sessionId: String, sceneId: String, camera: CameraProfile, lens: LensProfile, modeId: String? = null): RecceShot? {
        val s = session(sessionId) ?: return null
        val scene = s.scenes.firstOrNull { it.id == sceneId } ?: return null
        val next = (scene.shots.mapNotNull { it.shotNumber.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val shot = RecceShot.create(sceneId, "$next", camera, lens).copy(sensorModeId = modeId)
        updateScene(sessionId, sceneId) { it.copy(shots = it.shots + shot) }
        return shot
    }

    /** "Quick recce": one session/scene/shot ready for the viewfinder. Returns (sessionId, sceneId, shotId). */
    fun quickRecceShot(camera: CameraProfile, lens: LensProfile, modeId: String? = null): Triple<String, String, String> {
        val s = _sessions.value.firstOrNull { it.projectName == "Quick Recce" } ?: newSession("Quick Recce", "Location scouting")
        val scene = session(s.id)!!.sortedScenes.firstOrNull() ?: newScene(s.id)!!
        val shot = newShot(s.id, scene.id, camera, lens, modeId)!!
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

/** A saved camera + lens combination, e.g. "A-cam: 6K Pro + Orion set". */
data class Kit(val name: String, val cameraId: String, val modeId: String, val lensId: String, val focal: Double, val aspect: String)

/** Choices remembered between launches, as Compose state so screens update when they change. */
object Settings {
    private lateinit var p: SharedPreferences

    var defaultCameraId by mutableStateOf(Catalog.defaultCamera.id); private set
    var defaultLensId by mutableStateOf(Catalog.defaultLens.id); private set
    var favoriteCameras by mutableStateOf(emptySet<String>()); private set
    var favoriteLenses by mutableStateOf(emptySet<String>()); private set
    var noteLanguage by mutableStateOf("en-IN"); private set
    /** Frame (aspect ratio) last chosen in the New recce sheet. */
    var defaultAspect by mutableStateOf("2.39:1"); private set
    var defaultModeId by mutableStateOf<String?>(null); private set
    /** Measured angle of view across the long side of this phone's camera picture (degrees), or null to trust the phone. */
    var fovCalibration by mutableStateOf<Double?>(null); private set
    var kits by mutableStateOf<List<Kit>>(emptyList()); private set

    // Exposure and focus tools in the viewfinder (Module D), kept between sessions.
    var falseColour by mutableStateOf(false); private set
    var zebras by mutableStateOf(false); private set
    var zebraLevel by mutableStateOf(Zebra.DEFAULT); private set
    var peaking by mutableStateOf(false); private set
    var peakingColour by mutableStateOf(PeakingColour.RED); private set
    var peakingLevel by mutableStateOf(PeakingLevel.MEDIUM); private set
    var scope by mutableStateOf(ScopeKind.NONE); private set
    /** Bumped when the user's own lens distortion measurements change (kept in [Distortion.custom]). */
    var distortionVersion by mutableStateOf(0); private set
    /** Cloud forecast turned on (it sends the location to Open-Meteo). */
    var weatherOn by mutableStateOf(false); private set

    fun init(context: Context) {
        p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        defaultCameraId = p.getString("defaultCameraId", null) ?: Catalog.defaultCamera.id
        defaultLensId = p.getString("defaultLensId", null) ?: Catalog.defaultLens.id
        favoriteCameras = p.getStringSet("favoriteCameras", emptySet())?.toSet() ?: emptySet()
        favoriteLenses = p.getStringSet("favoriteLenses", emptySet())?.toSet() ?: emptySet()
        noteLanguage = p.getString("noteLanguage", null) ?: "en-IN"
        defaultAspect = p.getString("defaultAspect", null) ?: "2.39:1"
        defaultModeId = p.getString("defaultModeId", null)
        fovCalibration = p.getFloat("fovCalibration", 0f).takeIf { it > 0f }?.toDouble()
        falseColour = p.getBoolean("falseColour", false)
        zebras = p.getBoolean("zebras", false)
        zebraLevel = p.getInt("zebraLevel", Zebra.DEFAULT).takeIf { it in Zebra.levels } ?: Zebra.DEFAULT
        peaking = p.getBoolean("peaking", false)
        peakingColour = PeakingColour.of(p.getString("peakingColour", null))
        peakingLevel = PeakingLevel.of(p.getString("peakingLevel", null))
        scope = ScopeKind.of(p.getString("scope", null))
        weatherOn = p.getBoolean("weatherOn", false)
        loadDistortion()
        kits = (p.getString("kits", null) ?: "").lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) null else Kit(f[0], f[1], f[2], f[3], f[4].toDoubleOrNull() ?: 35.0, f[5])
        }
    }

    fun setDefaultCamera(id: String) { defaultCameraId = id; p.edit().putString("defaultCameraId", id).apply() }
    fun setDefaultLens(id: String) { defaultLensId = id; p.edit().putString("defaultLensId", id).apply() }
    /** Adds a kit (a kit with the same name is replaced). */
    fun addKit(k: Kit) {
        val clean = k.copy(name = k.name.replace('\t', ' ').replace('\n', ' ').trim().ifEmpty { "Kit" })
        storeKits(kits.filter { it.name != clean.name } + clean)
    }
    fun removeKit(name: String) = storeKits(kits.filter { it.name != name })
    private fun storeKits(list: List<Kit>) {
        kits = list
        p.edit().putString("kits", list.joinToString("\n") { listOf(it.name, it.cameraId, it.modeId, it.lensId, "${it.focal}", it.aspect).joinToString("\t") }).apply()
    }

    fun saveFovCalibration(v: Double?) {
        fovCalibration = v
        p.edit().apply { if (v == null) remove("fovCalibration") else putFloat("fovCalibration", v.toFloat()) }.apply()
    }
    fun chooseFalseColour(on: Boolean) { falseColour = on; p.edit().putBoolean("falseColour", on).apply() }
    fun chooseZebras(on: Boolean) { zebras = on; p.edit().putBoolean("zebras", on).apply() }
    fun chooseZebraLevel(v: Int) { zebraLevel = v; p.edit().putInt("zebraLevel", v).apply() }
    fun choosePeaking(on: Boolean) { peaking = on; p.edit().putBoolean("peaking", on).apply() }
    fun choosePeakingColour(c: PeakingColour) { peakingColour = c; p.edit().putString("peakingColour", c.name).apply() }
    fun choosePeakingLevel(l: PeakingLevel) { peakingLevel = l; p.edit().putString("peakingLevel", l.name).apply() }
    fun chooseWeather(on: Boolean) { weatherOn = on; p.edit().putBoolean("weatherOn", on).apply() }

    // The user's own distortion measurements: one line per lens and focal length, "lensId<TAB>focal<TAB>k1".
    private fun loadDistortion() {
        Distortion.custom = (p.getString("customDistortion", null) ?: "").lines().mapNotNull { line ->
            val f = line.split('\t')
            val focal = f.getOrNull(1)?.toDoubleOrNull(); val k1 = f.getOrNull(2)?.toDoubleOrNull()
            if (f.size < 3 || focal == null || k1 == null) null else Triple(f[0], focal, k1)
        }.groupBy({ it.first }, { it.second to it.third })
    }
    /** Saves a measured distortion for [lensId] at [focal] mm (replacing one at the same focal length); null k1 removes it. */
    fun saveDistortion(lensId: String, focal: Double, k1: Double?) {
        val all = Distortion.custom.toMutableMap()
        val list = (all[lensId] ?: emptyList()).filter { kotlin.math.abs(it.first - focal) > 0.05 } + listOfNotNull(k1?.let { focal to it })
        if (list.isEmpty()) all.remove(lensId) else all[lensId] = list
        Distortion.custom = all
        p.edit().putString("customDistortion", all.flatMap { (id, l) -> l.map { "$id\t${it.first}\t${it.second}" } }.joinToString("\n")).apply()
        distortionVersion++
    }
    fun chooseScope(k: ScopeKind) { scope = k; p.edit().putString("scope", k.code).apply() }
    fun chooseDefaultAspect(a: String) { defaultAspect = a; p.edit().putString("defaultAspect", a).apply() }
    fun chooseDefaultMode(id: String?) { defaultModeId = id; p.edit().putString("defaultModeId", id).apply() }
    /** True once a lens has been picked on this phone (otherwise a quick recce starts with the generic prime set). */
    val lensChosen: Boolean get() = p.contains("defaultLensId")
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
