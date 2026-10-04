package com.infraxcoders.bmpcc.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Mount(val label: String) {
    EF("EF"), PL("PL"), MFT("MFT"), E_MOUNT("E-Mount"), L_MOUNT("L-Mount"), RF("RF"), LPL("LPL"),
    X_MOUNT("X-Mount"), NIKON_F("Nikon F"), NIKON_Z("Nikon Z"), LEICA_M("Leica M"),
    PANAVISION_PV("Panavision PV"), PV_SP70("Panavision SP70"), B4("B4");

    companion object {
        fun fromLabel(label: String): Mount? = entries.firstOrNull { it.label == label }
    }
}

enum class LensType { SPHERICAL, ANAMORPHIC, ZOOM, PRIME, ADAPTER, SPEEDBOOSTER, OTHER }

enum class VerificationStatus { UNVERIFIED, REFERENCE, VERIFIED }

/** One recording mode of a camera and the part of the sensor it uses. */
data class SensorMode(
    val id: String,
    val name: String,
    val widthMm: Double,
    val heightMm: Double,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val notes: String? = null,
) {
    val diagonalMm: Double get() = sqrt(widthMm * widthMm + heightMm * heightMm)
    val aspect: Double get() = widthMm / heightMm
    val sizeText: String get() = String.format(java.util.Locale.US, "%.2f × %.2f mm", widthMm, heightMm)
    val resolutionText: String? get() = if (resolutionWidth != null && resolutionHeight != null) "$resolutionWidth × $resolutionHeight" else null
}

data class CameraProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val cameraType: String,
    val sensorFormatId: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val nativeAspectRatio: String = "16:9",
    val mount: Mount,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    /** Recording modes (database cameras); null for the original built-ins. */
    val modes: List<SensorMode>? = null,
    val mountNames: List<String>? = null,
    val sourceUrl: String? = null,
) {
    val displayName: String get() = "$manufacturer $model"
    val sensorDiagonalMm: Double get() = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
    val sensorAspect: Double get() = sensorWidthMm / sensorHeightMm

    /** Recording modes; a camera without listed modes has one mode covering its whole sensor. */
    val sensorModes: List<SensorMode>
        get() = modes?.takeIf { it.isNotEmpty() }
            ?: listOf(SensorMode("full", "Full sensor", sensorWidthMm, sensorHeightMm, resolutionWidth, resolutionHeight))

    fun mode(id: String?): SensorMode? = if (id == null) null else sensorModes.firstOrNull { it.id == id }

    /** This camera with its sensor area set to one recording mode (unchanged if not found). */
    fun using(modeId: String?): CameraProfile {
        val m = mode(modeId) ?: return this
        return copy(
            sensorWidthMm = m.widthMm, sensorHeightMm = m.heightMm,
            resolutionWidth = m.resolutionWidth ?: resolutionWidth, resolutionHeight = m.resolutionHeight ?: resolutionHeight,
        )
    }

    val allMountNames: List<String> get() = mountNames ?: listOf(mount.label)
}

data class LensProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val mount: Mount,
    val lensType: LensType,
    val focalLengthMin: Double,
    val focalLengthMax: Double,
    val availableFocalLengths: List<Double> = emptyList(),
    /** 0 when not published. */
    val maximumAperture: Double,
    val minimumFocusDistance: Double,
    val anamorphicSqueeze: Double = 1.0,
    /** 0 when not published. */
    val imageCircleMm: Double,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    val series: String? = null,
    val lengthMm: Double? = null,
    val weightG: Double? = null,
    val frontDiameterMm: Double? = null,
    val mountNames: List<String>? = null,
    /** MFT, APS-C, S35, FF, LF or 65. */
    val format: String? = null,
    val sourceUrl: String? = null,
    val notes: String? = null,
) {
    val displayName: String get() = "$manufacturer $model"
    val isZoom: Boolean get() = focalLengthMax > focalLengthMin
    val isAnamorphic: Boolean get() = anamorphicSqueeze != 1.0
    val hasImageCircle: Boolean get() = imageCircleMm > 0
    val hasAperture: Boolean get() = maximumAperture > 0
    val allMountNames: List<String> get() = mountNames ?: listOf(mount.label)

    /** Focal lengths to offer as quick buttons. */
    val quickFocalLengths: List<Double>
        get() = availableFocalLengths.ifEmpty { listOf(focalLengthMin, focalLengthMax).distinct().sorted() }

    fun clampFocal(f: Double): Double = min(max(f, focalLengthMin), focalLengthMax)
}

/** The original built-in list, identical to the old Android UniversalLibrarySeeder (checked in tests). */
object BuiltInLibrary {
    val cameras: List<CameraProfile> = listOf(

        CameraProfile(id = "bmpcc_6k_pro", manufacturer = "Blackmagic Design", model = "Pocket Cinema Camera 6K Pro", cameraType = "Cinema",
                      sensorFormatId = "super35", sensorWidthMm = 23.10, sensorHeightMm = 12.99, resolutionWidth = 6144, resolutionHeight = 3456,
                      mount = Mount.EF, verificationStatus = VerificationStatus.REFERENCE),
        CameraProfile(id = "ursa_cine_12k", manufacturer = "Blackmagic Design", model = "URSA Cine 12K", cameraType = "Cinema",
                      sensorFormatId = "large_format", sensorWidthMm = 35.64, sensorHeightMm = 23.32, resolutionWidth = 12288, resolutionHeight = 8040,
                      mount = Mount.PL, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "arri_alexa_35", manufacturer = "ARRI", model = "ALEXA 35", cameraType = "Cinema",
                      sensorFormatId = "super35_native", sensorWidthMm = 27.99, sensorHeightMm = 19.22, resolutionWidth = 4608, resolutionHeight = 3164,
                      mount = Mount.LPL, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "arri_alexa_lf", manufacturer = "ARRI", model = "ALEXA LF", cameraType = "Cinema",
                      sensorFormatId = "large_format", sensorWidthMm = 36.70, sensorHeightMm = 25.54, resolutionWidth = 4448, resolutionHeight = 3096,
                      mount = Mount.LPL, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "arri_alexa_mini", manufacturer = "ARRI", model = "ALEXA Mini", cameraType = "Cinema",
                      sensorFormatId = "super35", sensorWidthMm = 28.17, sensorHeightMm = 18.13, resolutionWidth = 3424, resolutionHeight = 2202,
                      mount = Mount.PL, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "red_v_raptor_8k_vv", manufacturer = "RED", model = "V-RAPTOR 8K VV", cameraType = "Cinema",
                      sensorFormatId = "vistavision", sensorWidthMm = 40.96, sensorHeightMm = 21.60, resolutionWidth = 8192, resolutionHeight = 4320,
                      mount = Mount.RF, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "red_komodo_6k", manufacturer = "RED", model = "KOMODO 6K", cameraType = "Cinema",
                      sensorFormatId = "super35", sensorWidthMm = 27.03, sensorHeightMm = 14.26, resolutionWidth = 6144, resolutionHeight = 3240,
                      mount = Mount.RF, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "sony_venice_2_8k", manufacturer = "Sony", model = "VENICE 2 8K", cameraType = "Cinema",
                      sensorFormatId = "fullframe_36x24", sensorWidthMm = 35.9, sensorHeightMm = 24.0, resolutionWidth = 8640, resolutionHeight = 5760,
                      mount = Mount.PL, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "sony_fx3", manufacturer = "Sony", model = "FX3", cameraType = "Cinema",
                      sensorFormatId = "fullframe", sensorWidthMm = 35.6, sensorHeightMm = 23.8, resolutionWidth = 4240, resolutionHeight = 2832,
                      mount = Mount.E_MOUNT, verificationStatus = VerificationStatus.REFERENCE),
        CameraProfile(id = "sony_fx6", manufacturer = "Sony", model = "FX6", cameraType = "Cinema",
                      sensorFormatId = "fullframe", sensorWidthMm = 35.7, sensorHeightMm = 20.1, resolutionWidth = 4096, resolutionHeight = 2160,
                      mount = Mount.E_MOUNT, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "canon_c500_mkii", manufacturer = "Canon", model = "EOS C500 Mark II", cameraType = "Cinema",
                      sensorFormatId = "fullframe", sensorWidthMm = 38.1, sensorHeightMm = 20.1, resolutionWidth = 5952, resolutionHeight = 3140,
                      mount = Mount.EF, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "canon_c70", manufacturer = "Canon", model = "EOS C70", cameraType = "Cinema",
                      sensorFormatId = "super35", sensorWidthMm = 26.2, sensorHeightMm = 13.8, resolutionWidth = 4096, resolutionHeight = 2160,
                      mount = Mount.RF, verificationStatus = VerificationStatus.VERIFIED),
        CameraProfile(id = "panasonic_s1h", manufacturer = "Panasonic", model = "LUMIX S1H", cameraType = "Mirrorless",
                      sensorFormatId = "fullframe", sensorWidthMm = 35.6, sensorHeightMm = 23.8, resolutionWidth = 6016, resolutionHeight = 4016,
                      mount = Mount.L_MOUNT, verificationStatus = VerificationStatus.VERIFIED)
    )

    val lenses: List<LensProfile> = listOf(

        LensProfile(id = "arri_signature_18", manufacturer = "ARRI", model = "Signature Prime 18mm T1.8", mount = Mount.LPL, lensType = LensType.PRIME,
                    focalLengthMin = 18.0, focalLengthMax = 18.0, availableFocalLengths = listOf(18.0), maximumAperture = 1.8, minimumFocusDistance = 0.35,
                    imageCircleMm = 46.0, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "arri_signature_35", manufacturer = "ARRI", model = "Signature Prime 35mm T1.8", mount = Mount.LPL, lensType = LensType.PRIME,
                    focalLengthMin = 35.0, focalLengthMax = 35.0, availableFocalLengths = listOf(35.0), maximumAperture = 1.8, minimumFocusDistance = 0.35,
                    imageCircleMm = 46.0, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "arri_signature_85", manufacturer = "ARRI", model = "Signature Prime 85mm T1.8", mount = Mount.LPL, lensType = LensType.PRIME,
                    focalLengthMin = 85.0, focalLengthMax = 85.0, availableFocalLengths = listOf(85.0), maximumAperture = 1.8, minimumFocusDistance = 0.65,
                    imageCircleMm = 46.0, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "cooke_s4i_25", manufacturer = "Cooke", model = "S4/i 25mm T2.0", mount = Mount.PL, lensType = LensType.PRIME,
                    focalLengthMin = 25.0, focalLengthMax = 25.0, availableFocalLengths = listOf(25.0), maximumAperture = 2.0, minimumFocusDistance = 0.25,
                    imageCircleMm = 33.5, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "cooke_s4i_32", manufacturer = "Cooke", model = "S4/i 32mm T2.0", mount = Mount.PL, lensType = LensType.PRIME,
                    focalLengthMin = 32.0, focalLengthMax = 32.0, availableFocalLengths = listOf(32.0), maximumAperture = 2.0, minimumFocusDistance = 0.3,
                    imageCircleMm = 33.5, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "cooke_s4i_50", manufacturer = "Cooke", model = "S4/i 50mm T2.0", mount = Mount.PL, lensType = LensType.PRIME,
                    focalLengthMin = 50.0, focalLengthMax = 50.0, availableFocalLengths = listOf(50.0), maximumAperture = 2.0, minimumFocusDistance = 0.5,
                    imageCircleMm = 33.5, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "zeiss_supreme_35", manufacturer = "Zeiss", model = "Supreme Prime 35mm T1.5", mount = Mount.PL, lensType = LensType.PRIME,
                    focalLengthMin = 35.0, focalLengthMax = 35.0, availableFocalLengths = listOf(35.0), maximumAperture = 1.5, minimumFocusDistance = 0.33,
                    imageCircleMm = 46.3, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "angenieux_optimo_24_290", manufacturer = "Angenieux", model = "Optimo 24-290mm T2.8", mount = Mount.PL, lensType = LensType.ZOOM,
                    focalLengthMin = 24.0, focalLengthMax = 290.0, availableFocalLengths = listOf(24.0, 35.0, 50.0, 75.0, 100.0, 150.0, 200.0, 290.0),
                    maximumAperture = 2.8, minimumFocusDistance = 1.22, imageCircleMm = 30.0, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "sigma_18_35_cine", manufacturer = "Sigma", model = "18-35mm T2.0 Cine", mount = Mount.PL, lensType = LensType.ZOOM,
                    focalLengthMin = 18.0, focalLengthMax = 35.0, availableFocalLengths = listOf(18.0, 21.0, 24.0, 28.0, 35.0), maximumAperture = 2.0,
                    minimumFocusDistance = 0.28, imageCircleMm = 28.4, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "atlas_orion_40", manufacturer = "Atlas Lens Co.", model = "Orion 40mm T2.0 (2x Anamorphic)", mount = Mount.PL,
                    lensType = LensType.ANAMORPHIC, focalLengthMin = 40.0, focalLengthMax = 40.0, availableFocalLengths = listOf(40.0), maximumAperture = 2.0,
                    minimumFocusDistance = 0.56, anamorphicSqueeze = 2.0, imageCircleMm = 31.0, verificationStatus = VerificationStatus.VERIFIED),
        LensProfile(id = "sigma_18_35_art", manufacturer = "Sigma", model = "18-35mm f/1.8 Art", mount = Mount.EF, lensType = LensType.ZOOM,
                    focalLengthMin = 18.0, focalLengthMax = 35.0, availableFocalLengths = listOf(18.0, 20.0, 24.0, 28.0, 32.0, 35.0), maximumAperture = 1.8,
                    minimumFocusDistance = 0.28, imageCircleMm = 28.4, verificationStatus = VerificationStatus.REFERENCE),
        LensProfile(id = "canon_50_18_stm", manufacturer = "Canon", model = "EF 50mm f/1.8 STM", mount = Mount.EF, lensType = LensType.PRIME,
                    focalLengthMin = 50.0, focalLengthMax = 50.0, availableFocalLengths = listOf(50.0), maximumAperture = 1.8, minimumFocusDistance = 0.35,
                    imageCircleMm = 43.3, verificationStatus = VerificationStatus.REFERENCE)
    )

    fun camera(id: String): CameraProfile? = cameras.firstOrNull { it.id == id }
    fun lens(id: String): LensProfile? = lenses.firstOrNull { it.id == id }

    /** Case-insensitive search: every word of the query must appear. */
    fun <T> search(items: List<T>, query: String, text: (T) -> String): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        val words = q.split(" ").filter { it.isNotEmpty() }
        return items.filter { item -> val t = text(item).lowercase(); words.all { t.contains(it) } }
    }

    fun searchCameras(query: String, list: List<CameraProfile> = cameras): List<CameraProfile> =
        search(list, query) { "${it.manufacturer} ${it.model} ${it.mount.label}" }

    fun searchLenses(query: String, list: List<LensProfile> = lenses): List<LensProfile> =
        search(list, query) { "${it.manufacturer} ${it.model} ${it.mount.label}" }
}

/** The old Android compatibility rule (kept for parity tests). New screens use [Coverage]. */
enum class Compatibility(val label: String) {
    COMPATIBLE("Compatible"),
    COMPATIBLE_WITH_ADAPTER("Compatible with adapter"),
    PARTIAL_COVERAGE("Partial coverage (vignetting likely)"),
    INCOMPATIBLE("Incompatible"),
    UNKNOWN("Unknown");

    companion object {
        fun check(camera: CameraProfile, lens: LensProfile, adapter: Boolean = false, imageCircleModifier: Double = 1.0): Compatibility {
            if (camera.mount != lens.mount && !adapter) return INCOMPATIBLE
            val circle = lens.imageCircleMm * imageCircleModifier
            val diag = camera.sensorDiagonalMm
            if (circle >= diag) return COMPATIBLE
            if (circle >= diag * 0.9) return PARTIAL_COVERAGE
            return INCOMPATIBLE
        }
    }
}
