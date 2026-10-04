package com.infraxcoders.bmpcc.core

import java.util.Locale
import kotlin.math.sqrt

/** Minimal CSV reader (RFC 4180: commas, double-quoted fields, "" for a quote inside quotes). */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        val s = text.removePrefix("﻿")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> {}
                '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    /** Rows as maps keyed by the header row. */
    fun records(text: String): List<Map<String, String>> {
        val rows = parse(text)
        val header = rows.firstOrNull() ?: return emptyList()
        return rows.drop(1).map { r -> header.mapIndexed { i, k -> k to (r.getOrNull(i)?.trim() ?: "") }.toMap() }
    }
}

/**
 * The full camera and lens catalogue: the original built-ins plus the lens and camera database
 * (data/lenses.csv, data/cameras.csv: values from makers' own spec sheets, with a source link per row).
 * A database entry with the same id as a built-in one replaces it.
 */
object Catalog {
    val databaseCameras: List<CameraProfile> by lazy { loadCameras(resource("cameras")) }
    val databaseLenses: List<LensProfile> by lazy { loadLenses(resource("lenses")) }
    val cameras: List<CameraProfile> by lazy { merge(BuiltInLibrary.cameras, databaseCameras) { it.id } }
    val lenses: List<LensProfile> by lazy { merge(BuiltInLibrary.lenses, databaseLenses) { it.id } }

    fun camera(id: String): CameraProfile? = cameras.firstOrNull { it.id == id }
    fun lens(id: String): LensProfile? = lenses.firstOrNull { it.id == id }
    val defaultCamera: CameraProfile get() = camera("bmpcc_6k_pro") ?: cameras[0]
    val defaultLens: LensProfile get() = lens("sigma_18_35_art") ?: lenses[0]

    fun searchCameras(query: String, list: List<CameraProfile> = cameras): List<CameraProfile> =
        BuiltInLibrary.search(list, query) { "${it.manufacturer} ${it.model} ${it.allMountNames.joinToString(" ")}" }

    fun searchLenses(query: String, list: List<LensProfile> = lenses): List<LensProfile> =
        BuiltInLibrary.search(list, query) {
            "${it.manufacturer} ${it.series ?: ""} ${it.model} ${it.allMountNames.joinToString(" ")} ${it.format ?: ""}"
        }

    private fun <T> merge(builtIn: List<T>, database: List<T>, id: (T) -> String): List<T> {
        val ids = database.map(id).toSet()
        return builtIn.filter { id(it) !in ids } + database
    }

    private fun resource(name: String): String =
        Catalog::class.java.classLoader?.getResourceAsStream("data/$name.csv")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    private fun num(s: String?): Double? = s?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    private fun blankToNull(s: String?): String? = s?.takeIf { it.isNotEmpty() }

    fun mountFrom(names: List<String>): Mount {
        for (n in names) {
            Mount.fromLabel(n)?.let { return it }
            when (n.lowercase()) {
                "e" -> return Mount.E_MOUNT
                "l" -> return Mount.L_MOUNT
                "x" -> return Mount.X_MOUNT
            }
        }
        return Mount.PL
    }

    private fun splitMounts(s: String?): List<String> =
        (s ?: "").split(";").map { it.trim() }.filter { it.isNotEmpty() }

    /** Lens rows: id, manufacturer, series, focal_mm, t_stop, squeeze, close_focus_m, image_circle_mm, length_mm,
     * weight_g, front_diameter_mm, mounts (;), format, source_url, notes. */
    fun loadLenses(csv: String): List<LensProfile> = Csv.records(csv).mapNotNull { r ->
        val id = r["id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val focal = num(r["focal_mm"])?.takeIf { it > 0 } ?: return@mapNotNull null
        val squeeze = num(r["squeeze"]) ?: 1.0
        val t = num(r["t_stop"]) ?: 0.0
        val series = r["series"] ?: ""
        val mounts = splitMounts(r["mounts"])
        val tText = if (t > 0) " T${ShotPresets.apertureNumber(t)}" else ""
        LensProfile(
            id = id, manufacturer = r["manufacturer"] ?: "", model = "$series ${ShotPresets.focalText(focal)}$tText",
            mount = mountFrom(mounts), lensType = if (squeeze > 1) LensType.ANAMORPHIC else LensType.PRIME,
            focalLengthMin = focal, focalLengthMax = focal, availableFocalLengths = listOf(focal),
            maximumAperture = t, minimumFocusDistance = num(r["close_focus_m"]) ?: 0.0, anamorphicSqueeze = squeeze,
            imageCircleMm = num(r["image_circle_mm"]) ?: 0.0, verificationStatus = VerificationStatus.REFERENCE,
            series = series.ifEmpty { null }, lengthMm = num(r["length_mm"]), weightG = num(r["weight_g"]),
            frontDiameterMm = num(r["front_diameter_mm"]), mountNames = mounts.ifEmpty { null },
            format = blankToNull(r["format"]), sourceUrl = blankToNull(r["source_url"]), notes = blankToNull(r["notes"]),
        )
    }

    /** Camera rows, one per mode (the first is the full sensor): camera_id, manufacturer, model, mount, mode_id,
     * mode_name, sensor_w_mm, sensor_h_mm, res_w, res_h, source_url, notes. Modes without sizes are skipped. */
    fun loadCameras(csv: String): List<CameraProfile> {
        val groups = linkedMapOf<String, MutableList<Map<String, String>>>()
        for (r in Csv.records(csv)) {
            val id = r["camera_id"]?.takeIf { it.isNotEmpty() } ?: continue
            groups.getOrPut(id) { mutableListOf() }.add(r)
        }
        return groups.mapNotNull { (id, rows) ->
            val modes = rows.mapNotNull { r ->
                val w = num(r["sensor_w_mm"])?.takeIf { it > 0 } ?: return@mapNotNull null
                val h = num(r["sensor_h_mm"])?.takeIf { it > 0 } ?: return@mapNotNull null
                SensorMode(
                    id = r["mode_id"]?.ifEmpty { null } ?: "mode${rows.indexOf(r)}", name = r["mode_name"] ?: "",
                    widthMm = w, heightMm = h,
                    resolutionWidth = num(r["res_w"])?.toInt(), resolutionHeight = num(r["res_h"])?.toInt(),
                    notes = blankToNull(r["notes"]),
                )
            }
            val first = rows.first()
            val full = modes.firstOrNull() ?: return@mapNotNull null
            val mounts = splitMounts(first["mount"])
            CameraProfile(
                id = id, manufacturer = first["manufacturer"] ?: "", model = first["model"] ?: id, cameraType = "Cinema",
                sensorFormatId = Coverage.formatName(full.widthMm), sensorWidthMm = full.widthMm, sensorHeightMm = full.heightMm,
                resolutionWidth = full.resolutionWidth ?: 0, resolutionHeight = full.resolutionHeight ?: 0,
                nativeAspectRatio = String.format(Locale.US, "%.2f:1", full.aspect), mount = mountFrom(mounts),
                verificationStatus = VerificationStatus.REFERENCE, modes = modes, mountNames = mounts.ifEmpty { null },
                sourceUrl = blankToNull(first["source_url"]),
            )
        }
    }
}

/** Does a lens's image circle cover a sensor area? */
enum class Coverage(val label: String) {
    /** Image circle >= sensor diagonal: clean corners. */
    FULL("Covers"),
    /** Covers the width but not the corners. */
    CORNERS_VIGNETTE("Corners vignette"),
    /** Narrower than the sensor width. */
    VIGNETTES("Vignettes"),
    /** The maker doesn't publish the image circle. */
    UNKNOWN("Image circle not published");

    data class Circle(val mm: Double, val nominal: Boolean)

    companion object {
        fun evaluate(imageCircleMm: Double, widthMm: Double, heightMm: Double): Coverage {
            if (imageCircleMm <= 0 || widthMm <= 0 || heightMm <= 0) return UNKNOWN
            val diag = sqrt(widthMm * widthMm + heightMm * heightMm)
            if (imageCircleMm >= diag - 0.05) return FULL
            if (imageCircleMm >= widthMm) return CORNERS_VIGNETTE
            return VIGNETTES
        }

        /** Typical image circle for a format, used only when the maker gives none. */
        fun nominalCircle(format: String?): Double? = when (format?.uppercase()) {
            "MFT" -> 21.6
            "APS-C" -> 28.4
            "S35" -> 31.1
            "FF" -> 43.3
            "LF" -> 46.3
            "65" -> 60.0
            else -> null
        }

        fun circle(lens: LensProfile): Circle? {
            if (lens.hasImageCircle) return Circle(lens.imageCircleMm, false)
            return nominalCircle(lens.format)?.let { Circle(it, true) }
        }

        fun evaluate(lens: LensProfile, mode: SensorMode): Coverage {
            val c = circle(lens) ?: return UNKNOWN
            return evaluate(c.mm, mode.widthMm, mode.heightMm)
        }

        /** The largest recording mode (by area) the lens covers fully. */
        fun largestCoveredMode(lens: LensProfile, camera: CameraProfile): SensorMode? =
            camera.sensorModes.filter { evaluate(lens, it) == FULL }.maxByOrNull { it.widthMm * it.heightMm }

        /** Lens and camera share a mount (by the makers' mount names). */
        fun sharesMount(camera: CameraProfile, lens: LensProfile): Boolean {
            val a = camera.allMountNames.map { it.lowercase() }.toSet()
            return lens.allMountNames.any { it.lowercase() in a }
        }

        fun formatName(widthMm: Double): String = when {
            widthMm < 20 -> "mft"
            widthMm < 24.5 -> "aps_c"
            widthMm < 31 -> "super35"
            widthMm < 38.5 -> "fullframe"
            widthMm < 46 -> "large_format"
            else -> "65mm"
        }
    }
}
