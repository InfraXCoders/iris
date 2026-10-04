package com.infraxcoders.bmpcc.core

import java.util.Locale
import java.util.UUID

// Enum names are the JSON values, so files exported by the iPhone app and the old Android app read here.

enum class ShotType(val longName: String) {
    EWS("Extreme wide"), WS("Wide"), MS("Medium"), MCU("Medium close-up"), CU("Close-up"), ECU("Extreme close-up"),
    INSERT("Insert"), POV("Point of view"), OTS("Over the shoulder"), TWO_SHOT("Two shot"), ESTABLISHING("Establishing");

    val label: String get() = if (this == TWO_SHOT) "TWO SHOT" else name
}

enum class CameraMovement {
    STATIC, PAN, TILT, PUSH_IN, PULL_OUT, TRACK, DOLLY, HANDHELD, GIMBAL, STEADICAM;

    val label: String get() = name.replace('_', ' ')
}

enum class MarkerType(val label: String) {
    CAMERA_POSITION("Camera"), SUBJECT_POSITION("Subject"), KEY_LIGHT("Key light"), FILL("Fill"),
    PRACTICAL("Practical"), WINDOW("Window"),
}

enum class LightingCondition {
    DAYLIGHT, GOLDEN_HOUR, BLUE_HOUR, NIGHT, INTERIOR_ARTIFICIAL, MIXED, OVERCAST, STUDIO;

    val label: String
        get() = when (this) {
            GOLDEN_HOUR -> "GOLDEN HOUR"
            BLUE_HOUR -> "BLUE HOUR"
            INTERIOR_ARTIFICIAL -> "INTERIOR"
            else -> name
        }
}

inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

fun newId(): String = UUID.randomUUID().toString()

// MARK: Saved/exported documents (same JSON layout as the iPhone app; timestamps are milliseconds since 1970)

data class RecceSession(
    val id: String = newId(),
    val locationName: String = "Location",
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val projectName: String = "Untitled project",
    val directorDopNotes: String = "",
    val generalLocationNotes: String = "",
    val scenes: List<RecceScene> = emptyList(),
    val creationTimestamp: Long = System.currentTimeMillis(),
    val modificationTimestamp: Long = System.currentTimeMillis(),
    val voiceNotes: List<RecceNote> = emptyList(),
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val shotCount: Int get() = scenes.sumOf { it.shots.size }
    val sortedScenes: List<RecceScene> get() = scenes.sortedWith(naturalOrder { it.sceneNumber })
    val sortedNotes: List<RecceNote> get() = voiceNotes.sortedBy { it.timestamp }
    fun touched(): RecceSession = copy(modificationTimestamp = System.currentTimeMillis())
}

data class RecceScene(
    val id: String = newId(),
    val sessionId: String = "",
    val sceneNumber: String = "1",
    val isInterior: Boolean = true,
    val isDay: Boolean = true,
    val locationDescription: String = "",
    val timeOfDay: String = "12:00",
    val lightingCondition: LightingCondition = LightingCondition.DAYLIGHT,
    val notes: String = "",
    val shots: List<RecceShot> = emptyList(),
) {
    val heading: String get() = "${if (isInterior) "INT." else "EXT."} ${locationDescription.ifEmpty { "Location" }} – ${if (isDay) "DAY" else "NIGHT"}"
    val sortedShots: List<RecceShot> get() = shots.sortedWith(naturalOrder { it.shotNumber })
}

data class RecceShot(
    val id: String = newId(),
    val sceneId: String = "",
    val shotNumber: String = "1",
    val shotType: ShotType = ShotType.MS,
    val cameraPosition: String = "",
    val subjectPosition: String = "",
    val cameraHeight: String = "1.5m",
    val cameraModel: String = "",
    val lensModel: String = "",
    val focalLength: String = "",
    val aperture: String = "",
    val aspectRatio: String = "16:9",
    val fps: String = "24",
    val shutter: String = "180°",
    val iso: String = "400",
    val nd: String = "None",
    val whiteBalance: String = "5600K",
    val cameraMovement: CameraMovement = CameraMovement.STATIC,
    val subjectMovement: String = "Static",
    val estimatedDistance: Double? = 1.8,
    val notes: String = "",
    val references: List<ShotReference> = emptyList(),
    val markers: List<ShotMarker> = emptyList(),
    val creationTimestamp: Long = System.currentTimeMillis(),
    val modificationTimestamp: Long = System.currentTimeMillis(),
    // Additions (ignored by older apps): exact library ids and the recording mode.
    val cameraId: String? = null,
    val lensId: String? = null,
    val sensorModeId: String? = null,
) {
    /** Library entries for this shot; falls back to matching the stored model names (imports). */
    val baseCamera: CameraProfile
        get() = cameraId?.let { Catalog.camera(it) } ?: Catalog.cameras.firstOrNull { it.model == cameraModel } ?: Catalog.defaultCamera
    val camera: CameraProfile get() = baseCamera.using(sensorModeId)
    val sensorMode: SensorMode get() = baseCamera.let { it.mode(sensorModeId) ?: it.sensorModes[0] }
    val lens: LensProfile
        get() = lensId?.let { Catalog.lens(it) } ?: Catalog.lenses.firstOrNull { it.model == lensModel } ?: Catalog.defaultLens
    val focalMm: Double get() = ShotPresets.focalValue(focalLength) ?: lens.focalLengthMin
    val aspectValue: Double? get() = ShotPresets.aspectValue(aspectRatio)
    val reference: ReferenceFrame? get() = Framing.reference(camera, lens, focalMm, aspectValue)

    fun withCamera(c: CameraProfile): RecceShot =
        copy(cameraId = c.id, cameraModel = c.model, sensorModeId = null, modificationTimestamp = System.currentTimeMillis())

    fun withLens(l: LensProfile): RecceShot = copy(
        lensId = l.id, lensModel = l.model,
        focalLength = ShotPresets.focalText(l.clampFocal(focalMm)),
        aperture = if (l.hasAperture) ShotPresets.apertureText(l.maximumAperture) else "",
        modificationTimestamp = System.currentTimeMillis(),
    )

    companion object {
        fun create(sceneId: String, number: String, camera: CameraProfile, lens: LensProfile): RecceShot = RecceShot(
            sceneId = sceneId, shotNumber = number, cameraId = camera.id, cameraModel = camera.model,
            lensId = lens.id, lensModel = lens.model, focalLength = ShotPresets.focalText(lens.focalLengthMin),
            aperture = if (lens.hasAperture) ShotPresets.apertureText(lens.maximumAperture) else "",
        )
    }
}

data class ShotReference(
    val id: String = newId(),
    val shotId: String = "",
    /** File name inside the app's references folder. */
    val filePath: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Where the frame lines were, as fractions of the photo. */
    val frameX: Double? = null,
    val frameY: Double? = null,
    val frameWidth: Double? = null,
    val frameHeight: Double? = null,
) {
    val fileName: String get() = filePath.substringAfterLast('/')
}

data class ShotMarker(
    val id: String = newId(),
    val shotId: String = "",
    val type: MarkerType,
    val x: Double,
    val y: Double,
    val label: String? = null,
)

data class RecceNote(
    val id: String = newId(),
    val sessionId: String = "",
    val shotId: String? = null,
    val rawTranscription: String,
    val detectedLanguage: String = NotesSorter.detectLanguage(rawTranscription),
    val timestamp: Long = System.currentTimeMillis(),
) {
    val sorted: SortedNote get() = NotesSorter.sort(rawTranscription)
}

/** Numbers first ("2" before "10"), then text, as people number scenes ("4", "4A", "12"). */
fun <T> naturalOrder(key: (T) -> String): Comparator<T> = Comparator { a, b ->
    val ka = key(a)
    val kb = key(b)
    val na = ka.takeWhile { it.isDigit() }.toIntOrNull()
    val nb = kb.takeWhile { it.isDigit() }.toIntOrNull()
    when {
        na != null && nb != null && na != nb -> na.compareTo(nb)
        else -> ka.compareTo(kb, ignoreCase = true)
    }
}

/** JSON <-> sessions, compatible with the iPhone app and the old Android export. */
object RecceJson {
    fun encode(s: RecceSession): String = Json.write(toMap(s), pretty = true)

    fun decode(text: String): RecceSession {
        val m = Json.parse(text) as? Map<*, *> ?: throw Json.ParseException("Not a recce file")
        return session(JObj(m.toStringKeys()))
    }

    fun toMap(s: RecceSession): Map<String, Any?> = linkedMapOf(
        "id" to s.id, "locationName" to s.locationName, "timestamp" to s.timestamp,
        "latitude" to s.latitude, "longitude" to s.longitude, "projectName" to s.projectName,
        "directorDopNotes" to s.directorDopNotes, "generalLocationNotes" to s.generalLocationNotes,
        "scenes" to s.sortedScenes.map { sc ->
            linkedMapOf(
                "id" to sc.id, "sessionId" to s.id, "sceneNumber" to sc.sceneNumber, "isInterior" to sc.isInterior,
                "isDay" to sc.isDay, "locationDescription" to sc.locationDescription, "timeOfDay" to sc.timeOfDay,
                "lightingCondition" to sc.lightingCondition.name, "notes" to sc.notes,
                "shots" to sc.sortedShots.map { sh -> shotMap(sh, sc.id) },
            )
        },
        "creationTimestamp" to s.creationTimestamp, "modificationTimestamp" to s.modificationTimestamp,
        "voiceNotes" to s.sortedNotes.map { n ->
            linkedMapOf(
                "id" to n.id, "sessionId" to s.id, "shotId" to n.shotId, "rawTranscription" to n.rawTranscription,
                "detectedLanguage" to n.detectedLanguage, "timestamp" to n.timestamp,
            )
        },
    )

    private fun shotMap(sh: RecceShot, sceneId: String): Map<String, Any?> = linkedMapOf(
        "id" to sh.id, "sceneId" to sceneId, "shotNumber" to sh.shotNumber, "shotType" to sh.shotType.name,
        "cameraPosition" to sh.cameraPosition, "subjectPosition" to sh.subjectPosition, "cameraHeight" to sh.cameraHeight,
        "cameraModel" to sh.cameraModel, "lensModel" to sh.lensModel, "focalLength" to sh.focalLength,
        "aperture" to sh.aperture, "aspectRatio" to sh.aspectRatio, "fps" to sh.fps, "shutter" to sh.shutter,
        "iso" to sh.iso, "nd" to sh.nd, "whiteBalance" to sh.whiteBalance, "cameraMovement" to sh.cameraMovement.name,
        "subjectMovement" to sh.subjectMovement, "estimatedDistance" to sh.estimatedDistance, "notes" to sh.notes,
        "references" to sh.references.sortedBy { it.timestamp }.map { r ->
            linkedMapOf(
                "id" to r.id, "shotId" to sh.id, "filePath" to r.filePath, "timestamp" to r.timestamp,
                "frameX" to r.frameX, "frameY" to r.frameY, "frameWidth" to r.frameWidth, "frameHeight" to r.frameHeight,
            )
        },
        "analysis" to null,
        "markers" to sh.markers.map { m ->
            linkedMapOf("id" to m.id, "shotId" to sh.id, "type" to m.type.name, "x" to m.x, "y" to m.y, "label" to m.label)
        },
        "creationTimestamp" to sh.creationTimestamp, "modificationTimestamp" to sh.modificationTimestamp,
        "cameraId" to sh.cameraId, "lensId" to sh.lensId, "sensorModeId" to sh.sensorModeId,
    )

    private fun session(o: JObj): RecceSession {
        val id = o.str("id").ifEmpty { newId() }
        return RecceSession(
            id = id, locationName = o.str("locationName", "Location"), timestamp = o.long("timestamp", System.currentTimeMillis()),
            latitude = o.dblOrNull("latitude"), longitude = o.dblOrNull("longitude"),
            projectName = o.str("projectName", "Untitled project"), directorDopNotes = o.str("directorDopNotes"),
            generalLocationNotes = o.str("generalLocationNotes"),
            scenes = o.objs("scenes").map { scene(it, id) },
            creationTimestamp = o.long("creationTimestamp", System.currentTimeMillis()),
            modificationTimestamp = o.long("modificationTimestamp", System.currentTimeMillis()),
            voiceNotes = o.objs("voiceNotes").map { n ->
                RecceNote(
                    id = n.str("id").ifEmpty { newId() }, sessionId = id, shotId = n.strOrNull("shotId"),
                    rawTranscription = n.str("rawTranscription"),
                    detectedLanguage = n.strOrNull("detectedLanguage") ?: NotesSorter.detectLanguage(n.str("rawTranscription")),
                    timestamp = n.long("timestamp"),
                )
            },
        )
    }

    private fun scene(o: JObj, sessionId: String): RecceScene {
        val id = o.str("id").ifEmpty { newId() }
        return RecceScene(
            id = id, sessionId = sessionId, sceneNumber = o.str("sceneNumber", "1"), isInterior = o.bool("isInterior", true),
            isDay = o.bool("isDay", true), locationDescription = o.str("locationDescription"), timeOfDay = o.str("timeOfDay", "12:00"),
            lightingCondition = enumOr(o.strOrNull("lightingCondition"), LightingCondition.DAYLIGHT), notes = o.str("notes"),
            shots = o.objs("shots").map { shot(it, id) },
        )
    }

    private fun shot(o: JObj, sceneId: String): RecceShot {
        val id = o.str("id").ifEmpty { newId() }
        return RecceShot(
            id = id, sceneId = sceneId, shotNumber = o.str("shotNumber", "1"), shotType = enumOr(o.strOrNull("shotType"), ShotType.MS),
            cameraPosition = o.str("cameraPosition"), subjectPosition = o.str("subjectPosition"),
            cameraHeight = o.str("cameraHeight", "1.5m"), cameraModel = o.str("cameraModel"), lensModel = o.str("lensModel"),
            focalLength = o.str("focalLength"), aperture = o.str("aperture"), aspectRatio = o.str("aspectRatio", "16:9"),
            fps = o.str("fps", "24"), shutter = o.str("shutter", "180°"), iso = o.str("iso", "400"), nd = o.str("nd", "None"),
            whiteBalance = o.str("whiteBalance", "5600K"),
            cameraMovement = enumOr(o.strOrNull("cameraMovement"), CameraMovement.STATIC),
            subjectMovement = o.str("subjectMovement", "Static"), estimatedDistance = o.dblOrNull("estimatedDistance"),
            notes = o.str("notes"),
            references = o.objs("references").map { r ->
                ShotReference(
                    id = r.str("id").ifEmpty { newId() }, shotId = id, filePath = r.str("filePath").substringAfterLast('/'),
                    timestamp = r.long("timestamp"), frameX = r.dblOrNull("frameX"), frameY = r.dblOrNull("frameY"),
                    frameWidth = r.dblOrNull("frameWidth"), frameHeight = r.dblOrNull("frameHeight"),
                )
            },
            markers = o.objs("markers").map { m ->
                ShotMarker(
                    id = m.str("id").ifEmpty { newId() }, shotId = id, type = enumOr(m.strOrNull("type"), MarkerType.SUBJECT_POSITION),
                    x = m.dbl("x"), y = m.dbl("y"), label = m.strOrNull("label"),
                )
            },
            creationTimestamp = o.long("creationTimestamp", System.currentTimeMillis()),
            modificationTimestamp = o.long("modificationTimestamp", System.currentTimeMillis()),
            cameraId = o.strOrNull("cameraId"), lensId = o.strOrNull("lensId"), sensorModeId = o.strOrNull("sensorModeId"),
        )
    }
}

/** Shot presets and parsing helpers. */
object ShotPresets {
    val aspectRatios = listOf("16:9", "17:9", "1.85:1", "2:1", "2.39:1", "2.40:1", "4:3", "1:1", "9:16")
    val frameRates = listOf("23.98", "24", "25", "29.97", "30", "48", "50", "59.94", "60", "120")
    val shutterAngles = listOf("45°", "90°", "135°", "172.8°", "180°", "270°", "360°")
    val isos = listOf("100", "200", "400", "800", "1250", "1600", "3200", "6400", "12800")
    val ndFilters = listOf(
        "None", "ND 0.3 (1 stop)", "ND 0.6 (2 stops)", "ND 0.9 (3 stops)", "ND 1.2 (4 stops)",
        "ND 1.5 (5 stops)", "ND 1.8 (6 stops)", "ND 2.1 (7 stops)",
    )
    val whiteBalances = listOf("2800K", "3200K", "4300K", "5000K", "5600K", "6500K", "7500K")
    val cameraHeights = listOf("Ground", "0.5m", "1.0m", "1.5m", "1.8m", "Overhead")

    /** "2.39:1" -> 2.39, "16:9" -> 1.777…, "1.85" -> 1.85. */
    fun aspectValue(text: String): Double? {
        val parts = text.replace(" ", "").split(":")
        if (parts.size == 2) {
            val a = parts[0].toDoubleOrNull()
            val b = parts[1].toDoubleOrNull()
            if (a != null && b != null && a > 0 && b > 0) return a / b
        }
        if (parts.size == 1) parts[0].toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        return null
    }

    /** "35mm" / "35 mm" / "35" -> 35. */
    fun focalValue(text: String): Double? = text.lowercase().replace("mm", "").trim().toDoubleOrNull()

    fun focalText(mm: Double): String =
        if (Math.rint(mm) == mm) "${mm.toInt()}mm" else String.format(Locale.US, "%.1fmm", mm)

    /** "f/1.8" style text. */
    fun apertureText(f: Double): String = if (Math.rint(f) == f) "f/${f.toInt()}.0" else "f/${trim(f)}"

    /** 2 -> "2.0", 1.9 -> "1.9", 2.25 -> "2.25". */
    fun apertureNumber(f: Double): String = if (Math.rint(f) == f) "${f.toInt()}.0" else trim(f)

    /** "T2.0", or "—" when unknown. */
    fun tStopText(t: Double): String = if (t > 0) "T${apertureNumber(t)}" else "—"

    /** 1.5 -> "1.5", 2.0 -> "2", like printf %g for short values. */
    fun trim(v: Double): String {
        val s = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        return s
    }
}
