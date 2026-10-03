import Foundation

// Enum raw values are the Android enum *names*, so JSON exported by either app reads in the other.

public enum ShotType: String, Codable, CaseIterable, Sendable, Identifiable {
    case ews = "EWS", ws = "WS", ms = "MS", mcu = "MCU", cu = "CU", ecu = "ECU", insert = "INSERT", pov = "POV",
         ots = "OTS", twoShot = "TWO_SHOT", establishing = "ESTABLISHING"
    public var id: String { rawValue }
    public var label: String { self == .twoShot ? "TWO SHOT" : rawValue }
    public var longName: String {
        switch self {
        case .ews: return "Extreme wide"
        case .ws: return "Wide"
        case .ms: return "Medium"
        case .mcu: return "Medium close-up"
        case .cu: return "Close-up"
        case .ecu: return "Extreme close-up"
        case .insert: return "Insert"
        case .pov: return "Point of view"
        case .ots: return "Over the shoulder"
        case .twoShot: return "Two shot"
        case .establishing: return "Establishing"
        }
    }
}

public enum CameraMovement: String, Codable, CaseIterable, Sendable, Identifiable {
    case staticShot = "STATIC", pan = "PAN", tilt = "TILT", pushIn = "PUSH_IN", pullOut = "PULL_OUT", track = "TRACK",
         dolly = "DOLLY", handheld = "HANDHELD", gimbal = "GIMBAL", steadicam = "STEADICAM"
    public var id: String { rawValue }
    public var label: String { rawValue.replacingOccurrences(of: "_", with: " ") }
}

public enum MarkerType: String, Codable, CaseIterable, Sendable, Identifiable {
    case cameraPosition = "CAMERA_POSITION", subjectPosition = "SUBJECT_POSITION", keyLight = "KEY_LIGHT", fill = "FILL",
         practical = "PRACTICAL", window = "WINDOW"
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .cameraPosition: return "Camera"
        case .subjectPosition: return "Subject"
        case .keyLight: return "Key light"
        case .fill: return "Fill"
        case .practical: return "Practical"
        case .window: return "Window"
        }
    }
    public var symbol: String {
        switch self {
        case .cameraPosition: return "video.fill"
        case .subjectPosition: return "person.fill"
        case .keyLight: return "light.max"
        case .fill: return "light.min"
        case .practical: return "lamp.desk.fill"
        case .window: return "window.casement"
        }
    }
}

public enum LightingCondition: String, Codable, CaseIterable, Sendable, Identifiable {
    case daylight = "DAYLIGHT", goldenHour = "GOLDEN_HOUR", blueHour = "BLUE_HOUR", night = "NIGHT",
         interiorArtificial = "INTERIOR_ARTIFICIAL", mixed = "MIXED", overcast = "OVERCAST", studio = "STUDIO"
    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .goldenHour: return "GOLDEN HOUR"
        case .blueHour: return "BLUE HOUR"
        case .interiorArtificial: return "INTERIOR"
        default: return rawValue
        }
    }
}

public enum DataSource: String, Codable, CaseIterable, Sendable {
    case aiEstimate = "AI_ESTIMATE", manual = "MANUAL", measured = "MEASURED"
}

// MARK: - Export documents (Android RecceSession JSON layout; timestamps are milliseconds since 1970)

public struct RecceSessionDoc: Codable, Equatable, Sendable {
    public var id: String
    public var locationName: String
    public var timestamp: Int64
    public var latitude: Double?
    public var longitude: Double?
    public var projectName: String
    public var directorDopNotes: String
    public var generalLocationNotes: String
    public var scenes: [RecceSceneDoc]
    public var creationTimestamp: Int64
    public var modificationTimestamp: Int64
    /// iOS addition: voice/text notes are saved (Android kept them in memory only). Optional, so Android
    /// exports without it still decode.
    public var voiceNotes: [RecceNoteDoc]?

    public init(id: String, locationName: String, timestamp: Int64, latitude: Double? = nil, longitude: Double? = nil,
                projectName: String, directorDopNotes: String = "", generalLocationNotes: String = "",
                scenes: [RecceSceneDoc] = [], creationTimestamp: Int64, modificationTimestamp: Int64,
                voiceNotes: [RecceNoteDoc]? = nil) {
        self.id = id; self.locationName = locationName; self.timestamp = timestamp; self.latitude = latitude
        self.longitude = longitude; self.projectName = projectName; self.directorDopNotes = directorDopNotes
        self.generalLocationNotes = generalLocationNotes; self.scenes = scenes
        self.creationTimestamp = creationTimestamp; self.modificationTimestamp = modificationTimestamp
        self.voiceNotes = voiceNotes
    }
}

public struct RecceSceneDoc: Codable, Equatable, Sendable {
    public var id: String
    public var sessionId: String
    public var sceneNumber: String
    public var isInterior: Bool
    public var isDay: Bool
    public var locationDescription: String
    public var timeOfDay: String
    public var lightingCondition: LightingCondition
    public var notes: String
    public var shots: [RecceShotDoc]

    public init(id: String, sessionId: String, sceneNumber: String, isInterior: Bool = true, isDay: Bool = true,
                locationDescription: String = "", timeOfDay: String = "12:00", lightingCondition: LightingCondition = .daylight,
                notes: String = "", shots: [RecceShotDoc] = []) {
        self.id = id; self.sessionId = sessionId; self.sceneNumber = sceneNumber; self.isInterior = isInterior
        self.isDay = isDay; self.locationDescription = locationDescription; self.timeOfDay = timeOfDay
        self.lightingCondition = lightingCondition; self.notes = notes; self.shots = shots
    }
}

public struct RecceShotDoc: Codable, Equatable, Sendable {
    public var id: String
    public var sceneId: String
    public var shotNumber: String
    public var shotType: ShotType
    public var cameraPosition: String
    public var subjectPosition: String
    public var cameraHeight: String
    public var cameraModel: String
    public var lensModel: String
    public var focalLength: String
    public var aperture: String
    public var aspectRatio: String
    public var fps: String
    public var shutter: String
    public var iso: String
    public var nd: String
    public var whiteBalance: String
    public var cameraMovement: CameraMovement
    public var subjectMovement: String
    public var estimatedDistance: Double?
    public var notes: String
    public var references: [ShotReferenceDoc]
    public var analysis: ShotAnalysisDoc?
    public var markers: [ShotMarkerDoc]
    public var creationTimestamp: Int64
    public var modificationTimestamp: Int64

    public init(id: String, sceneId: String, shotNumber: String, shotType: ShotType = .ms, cameraPosition: String = "",
                subjectPosition: String = "", cameraHeight: String = "1.5m", cameraModel: String, lensModel: String,
                focalLength: String, aperture: String, aspectRatio: String = "16:9", fps: String = "24",
                shutter: String = "180°", iso: String = "400", nd: String = "None", whiteBalance: String = "5600K",
                cameraMovement: CameraMovement = .staticShot, subjectMovement: String = "Static",
                estimatedDistance: Double? = 1.8, notes: String = "", references: [ShotReferenceDoc] = [],
                analysis: ShotAnalysisDoc? = nil, markers: [ShotMarkerDoc] = [], creationTimestamp: Int64,
                modificationTimestamp: Int64) {
        self.id = id; self.sceneId = sceneId; self.shotNumber = shotNumber; self.shotType = shotType
        self.cameraPosition = cameraPosition; self.subjectPosition = subjectPosition; self.cameraHeight = cameraHeight
        self.cameraModel = cameraModel; self.lensModel = lensModel; self.focalLength = focalLength; self.aperture = aperture
        self.aspectRatio = aspectRatio; self.fps = fps; self.shutter = shutter; self.iso = iso; self.nd = nd
        self.whiteBalance = whiteBalance; self.cameraMovement = cameraMovement; self.subjectMovement = subjectMovement
        self.estimatedDistance = estimatedDistance; self.notes = notes; self.references = references
        self.analysis = analysis; self.markers = markers
        self.creationTimestamp = creationTimestamp; self.modificationTimestamp = modificationTimestamp
    }
}

public struct ShotReferenceDoc: Codable, Equatable, Sendable {
    public var id: String
    public var shotId: String
    public var filePath: String
    public var timestamp: Int64
    public init(id: String, shotId: String, filePath: String, timestamp: Int64) {
        self.id = id; self.shotId = shotId; self.filePath = filePath; self.timestamp = timestamp
    }
}

public struct ShotAnalysisDoc: Codable, Equatable, Sendable {
    public var id: String
    public var shotId: String
    public var dominantLightDirection: String?
    public var brightAreas: String?
    public var shadowAreas: String?
    public var contrastLevel: String?
    public var backgroundDepth: String?
    public var subjectBackgroundSeparation: String?
    public var dataSource: DataSource
}

public struct ShotMarkerDoc: Codable, Equatable, Sendable {
    public var id: String
    public var shotId: String
    public var type: MarkerType
    public var x: Double
    public var y: Double
    public var label: String?
    public init(id: String, shotId: String, type: MarkerType, x: Double, y: Double, label: String? = nil) {
        self.id = id; self.shotId = shotId; self.type = type; self.x = x; self.y = y; self.label = label
    }
}

public struct RecceNoteDoc: Codable, Equatable, Sendable {
    public var id: String
    public var sessionId: String
    public var shotId: String?
    public var rawTranscription: String
    public var detectedLanguage: String
    public var timestamp: Int64
    public init(id: String, sessionId: String, shotId: String?, rawTranscription: String, detectedLanguage: String,
                timestamp: Int64) {
        self.id = id; self.sessionId = sessionId; self.shotId = shotId; self.rawTranscription = rawTranscription
        self.detectedLanguage = detectedLanguage; self.timestamp = timestamp
    }
}

public enum RecceJSON {
    public static func encode(_ session: RecceSessionDoc) throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return try e.encode(session)
    }
    public static func decode(_ data: Data) throws -> RecceSessionDoc {
        try JSONDecoder().decode(RecceSessionDoc.self, from: data)
    }
    public static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    public static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }
}

// MARK: - Shot presets and parsing helpers

public enum ShotPresets {
    public static let aspectRatios = ["16:9", "17:9", "1.85:1", "2:1", "2.39:1", "2.40:1", "4:3", "1:1", "9:16"]
    public static let frameRates = ["23.98", "24", "25", "29.97", "30", "48", "50", "59.94", "60", "120"]
    public static let shutterAngles = ["45°", "90°", "135°", "172.8°", "180°", "270°", "360°"]
    public static let isos = ["100", "200", "400", "800", "1250", "1600", "3200", "6400", "12800"]
    public static let ndFilters = ["None", "ND 0.3 (1 stop)", "ND 0.6 (2 stops)", "ND 0.9 (3 stops)", "ND 1.2 (4 stops)",
                                   "ND 1.5 (5 stops)", "ND 1.8 (6 stops)", "ND 2.1 (7 stops)"]
    public static let whiteBalances = ["2800K", "3200K", "4300K", "5000K", "5600K", "6500K", "7500K"]
    public static let cameraHeights = ["Ground", "0.5m", "1.0m", "1.5m", "1.8m", "Overhead"]

    /// "2.39:1" -> 2.39, "16:9" -> 1.777…, "1.85" -> 1.85. nil if it can't be read.
    public static func aspectValue(_ text: String) -> Double? {
        let t = text.replacingOccurrences(of: " ", with: "")
        let parts = t.split(separator: ":").map(String.init)
        if parts.count == 2, let a = Double(parts[0]), let b = Double(parts[1]), a > 0, b > 0 { return a / b }
        if parts.count == 1, let a = Double(parts[0]), a > 0 { return a }
        return nil
    }

    /// "35mm" / "35 mm" / "35" -> 35.
    public static func focalValue(_ text: String) -> Double? {
        Double(text.lowercased().replacingOccurrences(of: "mm", with: "").trimmingCharacters(in: .whitespaces))
    }

    public static func focalText(_ mm: Double) -> String {
        mm.rounded() == mm ? "\(Int(mm))mm" : String(format: "%.1fmm", mm)
    }

    /// "f/1.8" style text for an aperture.
    public static func apertureText(_ f: Double) -> String {
        f.rounded() == f ? "f/\(Int(f)).0" : "f/\(f)"
    }

    /// 2 -> "2.0", 1.9 -> "1.9", 2.25 -> "2.25" (for "T2.0" style labels).
    public static func apertureNumber(_ f: Double) -> String {
        f.rounded() == f ? "\(Int(f)).0" : String(format: "%g", f)
    }

    /// "T2.0" for a cine lens, "—" when unknown.
    public static func tStopText(_ t: Double) -> String { t > 0 ? "T\(apertureNumber(t))" : "—" }
}
