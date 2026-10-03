import Foundation

public enum Mount: String, Codable, CaseIterable, Sendable {
    case ef = "EF", pl = "PL", mft = "MFT", eMount = "E-Mount", lMount = "L-Mount", rf = "RF", lpl = "LPL",
         xMount = "X-Mount", nikonF = "Nikon F", nikonZ = "Nikon Z", leicaM = "Leica M",
         panavisionPV = "Panavision PV", pvSP70 = "Panavision SP70", b4 = "B4"
}

public enum LensType: String, Codable, CaseIterable, Sendable {
    case spherical = "SPHERICAL", anamorphic = "ANAMORPHIC", zoom = "ZOOM", prime = "PRIME",
         adapter = "ADAPTER", speedbooster = "SPEEDBOOSTER", other = "OTHER"
}

public enum VerificationStatus: String, Codable, CaseIterable, Sendable {
    case unverified = "UNVERIFIED", reference = "REFERENCE", verified = "VERIFIED"
}

public struct CameraProfile: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var manufacturer: String
    public var model: String
    public var cameraType: String
    public var sensorFormatId: String
    public var sensorWidthMm: Double
    public var sensorHeightMm: Double
    public var resolutionWidth: Int
    public var resolutionHeight: Int
    public var nativeAspectRatio: String
    public var mount: Mount
    public var verificationStatus: VerificationStatus

    public init(id: String, manufacturer: String, model: String, cameraType: String, sensorFormatId: String,
                sensorWidthMm: Double, sensorHeightMm: Double, resolutionWidth: Int, resolutionHeight: Int,
                nativeAspectRatio: String = "16:9", mount: Mount, verificationStatus: VerificationStatus = .unverified) {
        self.id = id; self.manufacturer = manufacturer; self.model = model; self.cameraType = cameraType
        self.sensorFormatId = sensorFormatId; self.sensorWidthMm = sensorWidthMm; self.sensorHeightMm = sensorHeightMm
        self.resolutionWidth = resolutionWidth; self.resolutionHeight = resolutionHeight
        self.nativeAspectRatio = nativeAspectRatio; self.mount = mount; self.verificationStatus = verificationStatus
    }

    public var displayName: String { "\(manufacturer) \(model)" }
    public var sensorDiagonalMm: Double { (sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm).squareRoot() }
    public var sensorAspect: Double { sensorWidthMm / sensorHeightMm }
}

public struct LensProfile: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var manufacturer: String
    public var model: String
    public var mount: Mount
    public var lensType: LensType
    public var focalLengthMin: Double
    public var focalLengthMax: Double
    public var availableFocalLengths: [Double]
    public var maximumAperture: Double
    public var minimumFocusDistance: Double
    public var anamorphicSqueeze: Double
    public var imageCircleMm: Double
    public var verificationStatus: VerificationStatus

    public init(id: String, manufacturer: String, model: String, mount: Mount, lensType: LensType,
                focalLengthMin: Double, focalLengthMax: Double, availableFocalLengths: [Double] = [],
                maximumAperture: Double, minimumFocusDistance: Double, anamorphicSqueeze: Double = 1,
                imageCircleMm: Double, verificationStatus: VerificationStatus = .unverified) {
        self.id = id; self.manufacturer = manufacturer; self.model = model; self.mount = mount; self.lensType = lensType
        self.focalLengthMin = focalLengthMin; self.focalLengthMax = focalLengthMax
        self.availableFocalLengths = availableFocalLengths; self.maximumAperture = maximumAperture
        self.minimumFocusDistance = minimumFocusDistance; self.anamorphicSqueeze = anamorphicSqueeze
        self.imageCircleMm = imageCircleMm; self.verificationStatus = verificationStatus
    }

    public var displayName: String { "\(manufacturer) \(model)" }
    public var isZoom: Bool { focalLengthMax > focalLengthMin }
    public var isAnamorphic: Bool { anamorphicSqueeze != 1 }
    /// Focal lengths to offer as quick buttons.
    public var quickFocalLengths: [Double] {
        availableFocalLengths.isEmpty ? Array(Set([focalLengthMin, focalLengthMax])).sorted() : availableFocalLengths
    }
    public func clampFocal(_ f: Double) -> Double { min(max(f, focalLengthMin), focalLengthMax) }
}

/// Built-in camera and lens library. Values are identical to the Android app's UniversalLibrarySeeder
/// (checked against it in tests).
public enum BuiltInLibrary {
    public static let cameras: [CameraProfile] = [
        CameraProfile(id: "bmpcc_6k_pro", manufacturer: "Blackmagic Design", model: "Pocket Cinema Camera 6K Pro", cameraType: "Cinema",
                      sensorFormatId: "super35", sensorWidthMm: 23.10, sensorHeightMm: 12.99, resolutionWidth: 6144, resolutionHeight: 3456,
                      mount: .ef, verificationStatus: .reference),
        CameraProfile(id: "ursa_cine_12k", manufacturer: "Blackmagic Design", model: "URSA Cine 12K", cameraType: "Cinema",
                      sensorFormatId: "large_format", sensorWidthMm: 35.64, sensorHeightMm: 23.32, resolutionWidth: 12288, resolutionHeight: 8040,
                      mount: .pl, verificationStatus: .verified),
        CameraProfile(id: "arri_alexa_35", manufacturer: "ARRI", model: "ALEXA 35", cameraType: "Cinema",
                      sensorFormatId: "super35_native", sensorWidthMm: 27.99, sensorHeightMm: 19.22, resolutionWidth: 4608, resolutionHeight: 3164,
                      mount: .lpl, verificationStatus: .verified),
        CameraProfile(id: "arri_alexa_lf", manufacturer: "ARRI", model: "ALEXA LF", cameraType: "Cinema",
                      sensorFormatId: "large_format", sensorWidthMm: 36.70, sensorHeightMm: 25.54, resolutionWidth: 4448, resolutionHeight: 3096,
                      mount: .lpl, verificationStatus: .verified),
        CameraProfile(id: "arri_alexa_mini", manufacturer: "ARRI", model: "ALEXA Mini", cameraType: "Cinema",
                      sensorFormatId: "super35", sensorWidthMm: 28.17, sensorHeightMm: 18.13, resolutionWidth: 3424, resolutionHeight: 2202,
                      mount: .pl, verificationStatus: .verified),
        CameraProfile(id: "red_v_raptor_8k_vv", manufacturer: "RED", model: "V-RAPTOR 8K VV", cameraType: "Cinema",
                      sensorFormatId: "vistavision", sensorWidthMm: 40.96, sensorHeightMm: 21.60, resolutionWidth: 8192, resolutionHeight: 4320,
                      mount: .rf, verificationStatus: .verified),
        CameraProfile(id: "red_komodo_6k", manufacturer: "RED", model: "KOMODO 6K", cameraType: "Cinema",
                      sensorFormatId: "super35", sensorWidthMm: 27.03, sensorHeightMm: 14.26, resolutionWidth: 6144, resolutionHeight: 3240,
                      mount: .rf, verificationStatus: .verified),
        CameraProfile(id: "sony_venice_2_8k", manufacturer: "Sony", model: "VENICE 2 8K", cameraType: "Cinema",
                      sensorFormatId: "fullframe_36x24", sensorWidthMm: 35.9, sensorHeightMm: 24.0, resolutionWidth: 8640, resolutionHeight: 5760,
                      mount: .pl, verificationStatus: .verified),
        CameraProfile(id: "sony_fx3", manufacturer: "Sony", model: "FX3", cameraType: "Cinema",
                      sensorFormatId: "fullframe", sensorWidthMm: 35.6, sensorHeightMm: 23.8, resolutionWidth: 4240, resolutionHeight: 2832,
                      mount: .eMount, verificationStatus: .reference),
        CameraProfile(id: "sony_fx6", manufacturer: "Sony", model: "FX6", cameraType: "Cinema",
                      sensorFormatId: "fullframe", sensorWidthMm: 35.7, sensorHeightMm: 20.1, resolutionWidth: 4096, resolutionHeight: 2160,
                      mount: .eMount, verificationStatus: .verified),
        CameraProfile(id: "canon_c500_mkii", manufacturer: "Canon", model: "EOS C500 Mark II", cameraType: "Cinema",
                      sensorFormatId: "fullframe", sensorWidthMm: 38.1, sensorHeightMm: 20.1, resolutionWidth: 5952, resolutionHeight: 3140,
                      mount: .ef, verificationStatus: .verified),
        CameraProfile(id: "canon_c70", manufacturer: "Canon", model: "EOS C70", cameraType: "Cinema",
                      sensorFormatId: "super35", sensorWidthMm: 26.2, sensorHeightMm: 13.8, resolutionWidth: 4096, resolutionHeight: 2160,
                      mount: .rf, verificationStatus: .verified),
        CameraProfile(id: "panasonic_s1h", manufacturer: "Panasonic", model: "LUMIX S1H", cameraType: "Mirrorless",
                      sensorFormatId: "fullframe", sensorWidthMm: 35.6, sensorHeightMm: 23.8, resolutionWidth: 6016, resolutionHeight: 4016,
                      mount: .lMount, verificationStatus: .verified),
    ]

    public static let lenses: [LensProfile] = [
        LensProfile(id: "arri_signature_18", manufacturer: "ARRI", model: "Signature Prime 18mm T1.8", mount: .lpl, lensType: .prime,
                    focalLengthMin: 18, focalLengthMax: 18, availableFocalLengths: [18], maximumAperture: 1.8, minimumFocusDistance: 0.35,
                    imageCircleMm: 46.0, verificationStatus: .verified),
        LensProfile(id: "arri_signature_35", manufacturer: "ARRI", model: "Signature Prime 35mm T1.8", mount: .lpl, lensType: .prime,
                    focalLengthMin: 35, focalLengthMax: 35, availableFocalLengths: [35], maximumAperture: 1.8, minimumFocusDistance: 0.35,
                    imageCircleMm: 46.0, verificationStatus: .verified),
        LensProfile(id: "arri_signature_85", manufacturer: "ARRI", model: "Signature Prime 85mm T1.8", mount: .lpl, lensType: .prime,
                    focalLengthMin: 85, focalLengthMax: 85, availableFocalLengths: [85], maximumAperture: 1.8, minimumFocusDistance: 0.65,
                    imageCircleMm: 46.0, verificationStatus: .verified),
        LensProfile(id: "cooke_s4i_25", manufacturer: "Cooke", model: "S4/i 25mm T2.0", mount: .pl, lensType: .prime,
                    focalLengthMin: 25, focalLengthMax: 25, availableFocalLengths: [25], maximumAperture: 2.0, minimumFocusDistance: 0.25,
                    imageCircleMm: 33.5, verificationStatus: .verified),
        LensProfile(id: "cooke_s4i_32", manufacturer: "Cooke", model: "S4/i 32mm T2.0", mount: .pl, lensType: .prime,
                    focalLengthMin: 32, focalLengthMax: 32, availableFocalLengths: [32], maximumAperture: 2.0, minimumFocusDistance: 0.3,
                    imageCircleMm: 33.5, verificationStatus: .verified),
        LensProfile(id: "cooke_s4i_50", manufacturer: "Cooke", model: "S4/i 50mm T2.0", mount: .pl, lensType: .prime,
                    focalLengthMin: 50, focalLengthMax: 50, availableFocalLengths: [50], maximumAperture: 2.0, minimumFocusDistance: 0.5,
                    imageCircleMm: 33.5, verificationStatus: .verified),
        LensProfile(id: "zeiss_supreme_35", manufacturer: "Zeiss", model: "Supreme Prime 35mm T1.5", mount: .pl, lensType: .prime,
                    focalLengthMin: 35, focalLengthMax: 35, availableFocalLengths: [35], maximumAperture: 1.5, minimumFocusDistance: 0.33,
                    imageCircleMm: 46.3, verificationStatus: .verified),
        LensProfile(id: "angenieux_optimo_24_290", manufacturer: "Angenieux", model: "Optimo 24-290mm T2.8", mount: .pl, lensType: .zoom,
                    focalLengthMin: 24, focalLengthMax: 290, availableFocalLengths: [24, 35, 50, 75, 100, 150, 200, 290],
                    maximumAperture: 2.8, minimumFocusDistance: 1.22, imageCircleMm: 30.0, verificationStatus: .verified),
        LensProfile(id: "sigma_18_35_cine", manufacturer: "Sigma", model: "18-35mm T2.0 Cine", mount: .pl, lensType: .zoom,
                    focalLengthMin: 18, focalLengthMax: 35, availableFocalLengths: [18, 21, 24, 28, 35], maximumAperture: 2.0,
                    minimumFocusDistance: 0.28, imageCircleMm: 28.4, verificationStatus: .verified),
        LensProfile(id: "atlas_orion_40", manufacturer: "Atlas Lens Co.", model: "Orion 40mm T2.0 (2x Anamorphic)", mount: .pl,
                    lensType: .anamorphic, focalLengthMin: 40, focalLengthMax: 40, availableFocalLengths: [40], maximumAperture: 2.0,
                    minimumFocusDistance: 0.56, anamorphicSqueeze: 2.0, imageCircleMm: 31.0, verificationStatus: .verified),
        LensProfile(id: "sigma_18_35_art", manufacturer: "Sigma", model: "18-35mm f/1.8 Art", mount: .ef, lensType: .zoom,
                    focalLengthMin: 18, focalLengthMax: 35, availableFocalLengths: [18, 20, 24, 28, 32, 35], maximumAperture: 1.8,
                    minimumFocusDistance: 0.28, imageCircleMm: 28.4, verificationStatus: .reference),
        LensProfile(id: "canon_50_18_stm", manufacturer: "Canon", model: "EF 50mm f/1.8 STM", mount: .ef, lensType: .prime,
                    focalLengthMin: 50, focalLengthMax: 50, availableFocalLengths: [50], maximumAperture: 1.8, minimumFocusDistance: 0.35,
                    imageCircleMm: 43.3, verificationStatus: .reference),
    ]

    public static func camera(id: String) -> CameraProfile? { cameras.first { $0.id == id } }
    public static func lens(id: String) -> LensProfile? { lenses.first { $0.id == id } }

    /// Case-insensitive search over manufacturer + model (same fields the Android search used).
    public static func search<T>(_ items: [T], _ query: String, text: (T) -> String) -> [T] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return items }
        let words = q.split(separator: " ").map(String.init)
        return items.filter { item in let t = text(item).lowercased(); return words.allSatisfy { t.contains($0) } }
    }
    public static func searchCameras(_ query: String, in list: [CameraProfile] = cameras) -> [CameraProfile] {
        search(list, query) { "\($0.manufacturer) \($0.model) \($0.mount.rawValue)" }
    }
    public static func searchLenses(_ query: String, in list: [LensProfile] = lenses) -> [LensProfile] {
        search(list, query) { "\($0.manufacturer) \($0.model) \($0.mount.rawValue)" }
    }
}

public enum Compatibility: String, Codable, Sendable {
    case compatible = "COMPATIBLE"
    case compatibleWithAdapter = "COMPATIBLE_WITH_ADAPTER"
    case partialCoverage = "PARTIAL_COVERAGE"
    case incompatible = "INCOMPATIBLE"
    case unknown = "UNKNOWN"

    /// Same rule as the Android CameraLensCompatibilityEngine:
    /// different mount without an adapter -> incompatible; otherwise image circle vs sensor diagonal
    /// (>= diagonal compatible, >= 90 % partial coverage, else incompatible).
    public static func check(camera: CameraProfile, lens: LensProfile, adapter: Bool = false,
                             imageCircleModifier: Double = 1) -> Compatibility {
        if camera.mount != lens.mount && !adapter { return .incompatible }
        let circle = lens.imageCircleMm * imageCircleModifier
        let diag = camera.sensorDiagonalMm
        if circle >= diag { return .compatible }
        if circle >= diag * 0.9 { return .partialCoverage }
        return .incompatible
    }

    public var label: String {
        switch self {
        case .compatible: return "Compatible"
        case .compatibleWithAdapter: return "Compatible with adapter"
        case .partialCoverage: return "Partial coverage (vignetting likely)"
        case .incompatible: return "Incompatible"
        case .unknown: return "Unknown"
        }
    }
}
