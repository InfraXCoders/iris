import Foundation

/// One recording mode of a camera and the part of the sensor it uses.
public struct SensorMode: Identifiable, Hashable, Codable, Sendable {
    public var id: String
    public var name: String
    public var widthMm: Double
    public var heightMm: Double
    public var resolutionWidth: Int?
    public var resolutionHeight: Int?
    public var notes: String?

    public init(id: String, name: String, widthMm: Double, heightMm: Double,
                resolutionWidth: Int? = nil, resolutionHeight: Int? = nil, notes: String? = nil) {
        self.id = id; self.name = name; self.widthMm = widthMm; self.heightMm = heightMm
        self.resolutionWidth = resolutionWidth; self.resolutionHeight = resolutionHeight; self.notes = notes
    }

    public var diagonalMm: Double { (widthMm * widthMm + heightMm * heightMm).squareRoot() }
    public var aspect: Double { widthMm / heightMm }
    public var sizeText: String { String(format: "%.2f × %.2f mm", widthMm, heightMm) }
    public var resolutionText: String? {
        guard let w = resolutionWidth, let h = resolutionHeight else { return nil }
        return "\(w) × \(h)"
    }
}

/// Minimal CSV reader (RFC 4180: commas, double-quoted fields, "" for a quote inside quotes).
public enum CSV {
    public static func parse(_ text: String) -> [[String]] {
        var rows: [[String]] = []
        var row: [String] = []
        var field = ""
        var inQuotes = false
        var chars = Array(text.unicodeScalars)
        if chars.first == "\u{FEFF}" { chars.removeFirst() }
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if inQuotes {
                if c == "\"" {
                    if i + 1 < chars.count && chars[i + 1] == "\"" { field.unicodeScalars.append("\""); i += 1 }
                    else { inQuotes = false }
                } else { field.unicodeScalars.append(c) }
            } else {
                switch c {
                case "\"": inQuotes = true
                case ",": row.append(field); field = ""
                case "\r": break
                case "\n":
                    row.append(field); field = ""
                    if !(row.count == 1 && row[0].isEmpty) { rows.append(row) }
                    row = []
                default: field.unicodeScalars.append(c)
                }
            }
            i += 1
        }
        if !field.isEmpty || !row.isEmpty { row.append(field); rows.append(row) }
        return rows
    }

    /// Rows as dictionaries keyed by the header row.
    public static func records(_ text: String) -> [[String: String]] {
        let rows = parse(text)
        guard let header = rows.first else { return [] }
        return rows.dropFirst().map { r in
            var d: [String: String] = [:]
            for (i, k) in header.enumerated() { d[k] = i < r.count ? r[i].trimmingCharacters(in: .whitespaces) : "" }
            return d
        }
    }
}

/// The full camera and lens catalogue: the original built-in list plus the lens and camera database
/// (`Data/lenses.csv`, `Data/cameras.csv`, values from makers' own spec sheets with a source link per row).
/// A database entry with the same id as a built-in one replaces it.
public enum Catalog {
    public static let cameras: [CameraProfile] = merge(BuiltInLibrary.cameras, databaseCameras)
    public static let lenses: [LensProfile] = merge(BuiltInLibrary.lenses, databaseLenses)

    public static let databaseCameras: [CameraProfile] = loadCameras(text(resource: "cameras"))
    public static let databaseLenses: [LensProfile] = loadLenses(text(resource: "lenses"))

    public static func camera(id: String) -> CameraProfile? { cameras.first { $0.id == id } }
    public static func lens(id: String) -> LensProfile? { lenses.first { $0.id == id } }
    public static var defaultCamera: CameraProfile { camera(id: "bmpcc_6k_pro") ?? cameras[0] }
    public static var defaultLens: LensProfile { lens(id: "sigma_18_35_art") ?? lenses[0] }

    public static func searchCameras(_ query: String, in list: [CameraProfile] = cameras) -> [CameraProfile] {
        BuiltInLibrary.search(list, query) { "\($0.manufacturer) \($0.model) \($0.allMountNames.joined(separator: " "))" }
    }
    public static func searchLenses(_ query: String, in list: [LensProfile] = lenses) -> [LensProfile] {
        BuiltInLibrary.search(list, query) {
            "\($0.manufacturer) \($0.series ?? "") \($0.model) \($0.allMountNames.joined(separator: " ")) \($0.format ?? "")"
        }
    }

    static func merge<T: Identifiable>(_ builtIn: [T], _ database: [T]) -> [T] where T.ID == String {
        let ids = Set(database.map(\.id))
        return builtIn.filter { !ids.contains($0.id) } + database
    }

    static func text(resource: String) -> String {
        guard let url = Bundle.module.url(forResource: resource, withExtension: "csv", subdirectory: "Data")
                ?? Bundle.module.url(forResource: resource, withExtension: "csv"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return s
    }

    private static func num(_ s: String?) -> Double? {
        guard let s, !s.isEmpty else { return nil }
        return Double(s)
    }

    static func mount(from names: [String]) -> Mount {
        for n in names {
            if let m = Mount(rawValue: n) { return m }
            switch n.lowercased() {
            case "e": return .eMount
            case "l": return .lMount
            case "x": return .xMount
            default: continue
            }
        }
        return .pl
    }

    /// Lens rows: id, manufacturer, series, focal_mm, t_stop, squeeze, close_focus_m, image_circle_mm,
    /// length_mm, weight_g, front_diameter_mm, mounts, format, source_url, notes.
    public static func loadLenses(_ csv: String) -> [LensProfile] {
        CSV.records(csv).compactMap { r in
            guard let id = r["id"], !id.isEmpty, let focal = num(r["focal_mm"]), focal > 0 else { return nil }
            let squeeze = num(r["squeeze"]) ?? 1
            let t = num(r["t_stop"]) ?? 0
            let series = r["series"] ?? ""
            let mounts = (r["mounts"] ?? "").split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            let tText = t > 0 ? " T\(ShotPresets.apertureNumber(t))" : ""
            return LensProfile(
                id: id, manufacturer: r["manufacturer"] ?? "", model: "\(series) \(ShotPresets.focalText(focal))\(tText)",
                mount: mount(from: mounts), lensType: squeeze > 1 ? .anamorphic : .prime,
                focalLengthMin: focal, focalLengthMax: focal, availableFocalLengths: [focal],
                maximumAperture: t, minimumFocusDistance: num(r["close_focus_m"]) ?? 0, anamorphicSqueeze: squeeze,
                imageCircleMm: num(r["image_circle_mm"]) ?? 0, verificationStatus: .reference,
                series: series.isEmpty ? nil : series, lengthMm: num(r["length_mm"]), weightG: num(r["weight_g"]),
                frontDiameterMm: num(r["front_diameter_mm"]), mountNames: mounts.isEmpty ? nil : mounts,
                format: (r["format"] ?? "").isEmpty ? nil : r["format"],
                sourceURL: (r["source_url"] ?? "").isEmpty ? nil : r["source_url"],
                notes: (r["notes"] ?? "").isEmpty ? nil : r["notes"])
        }
    }

    /// Camera rows (one per mode, the first mode of each camera is its full sensor): camera_id, manufacturer,
    /// model, mount, mode_id, mode_name, sensor_w_mm, sensor_h_mm, res_w, res_h, source_url, notes.
    /// Modes without a published size are skipped, and so are cameras left with no modes.
    public static func loadCameras(_ csv: String) -> [CameraProfile] {
        var order: [String] = []
        var groups: [String: [[String: String]]] = [:]
        for r in CSV.records(csv) {
            guard let id = r["camera_id"], !id.isEmpty else { continue }
            if groups[id] == nil { order.append(id) }
            groups[id, default: []].append(r)
        }
        return order.compactMap { id in
            let rows = groups[id] ?? []
            let modes: [SensorMode] = rows.compactMap { r in
                guard let w = num(r["sensor_w_mm"]), let h = num(r["sensor_h_mm"]), w > 0, h > 0 else { return nil }
                return SensorMode(id: r["mode_id"] ?? UUID().uuidString, name: r["mode_name"] ?? "",
                                  widthMm: w, heightMm: h,
                                  resolutionWidth: num(r["res_w"]).map { Int($0) }, resolutionHeight: num(r["res_h"]).map { Int($0) },
                                  notes: (r["notes"] ?? "").isEmpty ? nil : r["notes"])
            }
            guard let first = rows.first, let full = modes.first else { return nil }
            let mounts = (first["mount"] ?? "").split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            return CameraProfile(
                id: id, manufacturer: first["manufacturer"] ?? "", model: first["model"] ?? id, cameraType: "Cinema",
                sensorFormatId: Coverage.formatName(widthMm: full.widthMm), sensorWidthMm: full.widthMm, sensorHeightMm: full.heightMm,
                resolutionWidth: full.resolutionWidth ?? 0, resolutionHeight: full.resolutionHeight ?? 0,
                nativeAspectRatio: String(format: "%.2f:1", full.aspect), mount: mount(from: mounts),
                verificationStatus: .reference, modes: modes, mountNames: mounts.isEmpty ? nil : mounts,
                sourceURL: (first["source_url"] ?? "").isEmpty ? nil : first["source_url"])
        }
    }
}

/// Does a lens's image circle cover a sensor area?
public enum Coverage: Equatable, Sendable {
    /// Image circle ≥ sensor diagonal: clean corners.
    case full
    /// Circle covers the width but not the corners: dark corners, usually fine for a wider delivery crop.
    case cornersVignette
    /// Circle narrower than the sensor width: heavy vignetting.
    case vignettes
    /// The lens maker doesn't publish the image circle (a nominal value for its format may be used instead).
    case unknown

    public static func evaluate(imageCircleMm circle: Double, widthMm: Double, heightMm: Double) -> Coverage {
        guard circle > 0, widthMm > 0, heightMm > 0 else { return .unknown }
        let diag = (widthMm * widthMm + heightMm * heightMm).squareRoot()
        if circle >= diag - 0.05 { return .full }
        if circle >= widthMm { return .cornersVignette }
        return .vignettes
    }

    /// Typical image circle for a format, used only when the maker gives none.
    public static func nominalCircle(format: String?) -> Double? {
        switch format?.uppercased() {
        case "MFT": return 21.6
        case "APS-C": return 28.4
        case "S35": return 31.1
        case "FF": return 43.3
        case "LF": return 46.3
        case "65": return 60.0
        default: return nil
        }
    }

    /// The circle to judge coverage with, and whether it is nominal (from the format) rather than published.
    public static func circle(for lens: LensProfile) -> (mm: Double, nominal: Bool)? {
        if lens.hasImageCircle { return (lens.imageCircleMm, false) }
        if let n = nominalCircle(format: lens.format) { return (n, true) }
        return nil
    }

    public static func evaluate(lens: LensProfile, mode: SensorMode) -> Coverage {
        guard let c = circle(for: lens) else { return .unknown }
        return evaluate(imageCircleMm: c.mm, widthMm: mode.widthMm, heightMm: mode.heightMm)
    }

    /// The largest recording mode the lens covers fully (by area), if any.
    public static func largestCoveredMode(lens: LensProfile, camera: CameraProfile) -> SensorMode? {
        camera.sensorModes.filter { evaluate(lens: lens, mode: $0) == .full }
            .max { $0.widthMm * $0.heightMm < $1.widthMm * $1.heightMm }
    }

    /// Lens and camera share a mount (by the makers' mount names). Otherwise an adapter is needed, if one exists.
    public static func sharesMount(camera: CameraProfile, lens: LensProfile) -> Bool {
        let a = Set(camera.allMountNames.map { $0.lowercased() })
        return lens.allMountNames.contains { a.contains($0.lowercased()) }
    }

    public static func formatName(widthMm w: Double) -> String {
        switch w {
        case ..<20: return "mft"
        case ..<24.5: return "aps_c"
        case ..<31: return "super35"
        case ..<38.5: return "fullframe"
        case ..<46: return "large_format"
        default: return "65mm"
        }
    }

    public var label: String {
        switch self {
        case .full: return "Covers"
        case .cornersVignette: return "Corners vignette"
        case .vignettes: return "Vignettes"
        case .unknown: return "Image circle not published"
        }
    }
}
