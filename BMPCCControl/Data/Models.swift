import Foundation
import SwiftData
import RecceKit

// Saved recce data. Enum values are stored as their raw strings (the Android enum names), which keeps
// SwiftData simple and the JSON export compatible with the Android app.

@Model
final class RecceSession {
    @Attribute(.unique) var id: String
    var projectName: String
    var locationName: String
    var date: Date
    var latitude: Double?
    var longitude: Double?
    var directorDopNotes: String
    var generalLocationNotes: String
    var created: Date
    var modified: Date
    @Relationship(deleteRule: .cascade, inverse: \RecceScene.session) var scenes: [RecceScene] = []
    @Relationship(deleteRule: .cascade, inverse: \RecceNote.session) var notes: [RecceNote] = []

    init(id: String = UUID().uuidString, projectName: String, locationName: String, date: Date = .now,
         latitude: Double? = nil, longitude: Double? = nil, directorDopNotes: String = "", generalLocationNotes: String = "",
         created: Date = .now, modified: Date = .now) {
        self.id = id
        self.projectName = projectName
        self.locationName = locationName
        self.date = date
        self.latitude = latitude
        self.longitude = longitude
        self.directorDopNotes = directorDopNotes
        self.generalLocationNotes = generalLocationNotes
        self.created = created
        self.modified = modified
    }

    var sortedScenes: [RecceScene] { scenes.sorted { RecceSession.natural($0.sceneNumber, $1.sceneNumber, $0.created, $1.created) } }
    var sortedNotes: [RecceNote] { notes.sorted { $0.date < $1.date } }
    var shotCount: Int { scenes.reduce(0) { $0 + $1.shots.count } }
    var hasLocation: Bool { latitude != nil && longitude != nil }

    /// "2" before "10", "4A" after "4"; ties broken by creation time.
    static func natural(_ a: String, _ b: String, _ ca: Date, _ cb: Date) -> Bool {
        let r = a.localizedStandardCompare(b)
        return r == .orderedSame ? ca < cb : r == .orderedAscending
    }

    func touch() { modified = .now }
}

@Model
final class RecceScene {
    @Attribute(.unique) var id: String
    var sceneNumber: String
    var isInterior: Bool
    var isDay: Bool
    var locationDescription: String
    var timeOfDay: String
    var lightingRaw: String
    var notes: String
    var created: Date
    var session: RecceSession?
    @Relationship(deleteRule: .cascade, inverse: \RecceShot.scene) var shots: [RecceShot] = []

    init(id: String = UUID().uuidString, sceneNumber: String, isInterior: Bool = true, isDay: Bool = true,
         locationDescription: String = "", timeOfDay: String = "12:00", lighting: LightingCondition = .daylight,
         notes: String = "", created: Date = .now) {
        self.id = id
        self.sceneNumber = sceneNumber
        self.isInterior = isInterior
        self.isDay = isDay
        self.locationDescription = locationDescription
        self.timeOfDay = timeOfDay
        self.lightingRaw = lighting.rawValue
        self.notes = notes
        self.created = created
    }

    var lighting: LightingCondition {
        get { LightingCondition(rawValue: lightingRaw) ?? .daylight }
        set { lightingRaw = newValue.rawValue }
    }
    /// Screenplay-style heading, e.g. "EXT. COURTYARD – DAY".
    var heading: String {
        let place = locationDescription.isEmpty ? "LOCATION" : locationDescription.uppercased()
        return "\(isInterior ? "INT." : "EXT.") \(place) – \(isDay ? "DAY" : "NIGHT")"
    }
    var sortedShots: [RecceShot] { shots.sorted { RecceSession.natural($0.shotNumber, $1.shotNumber, $0.created, $1.created) } }
}

@Model
final class RecceShot {
    @Attribute(.unique) var id: String
    var shotNumber: String
    var shotTypeRaw: String
    var cameraPosition: String
    var subjectPosition: String
    var cameraHeight: String
    /// Library ids (iOS addition). The model names below are what the Android app stored.
    var cameraId: String
    var lensId: String
    var cameraModel: String
    var lensModel: String
    var focalLength: String
    var aperture: String
    var aspectRatio: String
    var fps: String
    var shutter: String
    var iso: String
    var nd: String
    var whiteBalance: String
    var movementRaw: String
    var subjectMovement: String
    var estimatedDistance: Double?
    var notes: String
    var created: Date
    var modified: Date
    var scene: RecceScene?
    @Relationship(deleteRule: .cascade, inverse: \ShotMarker.shot) var markers: [ShotMarker] = []
    @Relationship(deleteRule: .cascade, inverse: \ShotReference.shot) var references: [ShotReference] = []

    init(id: String = UUID().uuidString, shotNumber: String, shotType: ShotType = .ms, camera: CameraProfile, lens: LensProfile,
         focalLength: Double? = nil, created: Date = .now) {
        self.id = id
        self.shotNumber = shotNumber
        self.shotTypeRaw = shotType.rawValue
        self.cameraPosition = ""
        self.subjectPosition = ""
        self.cameraHeight = "1.5m"
        self.cameraId = camera.id
        self.lensId = lens.id
        self.cameraModel = camera.model
        self.lensModel = lens.model
        self.focalLength = ShotPresets.focalText(lens.clampFocal(focalLength ?? lens.focalLengthMin))
        self.aperture = ShotPresets.apertureText(lens.maximumAperture)
        self.aspectRatio = "16:9"
        self.fps = "24"
        self.shutter = "180°"
        self.iso = "400"
        self.nd = "None"
        self.whiteBalance = "5600K"
        self.movementRaw = CameraMovement.staticShot.rawValue
        self.subjectMovement = "Static"
        self.estimatedDistance = 1.8
        self.notes = ""
        self.created = created
        self.modified = created
    }

    var shotType: ShotType {
        get { ShotType(rawValue: shotTypeRaw) ?? .ms }
        set { shotTypeRaw = newValue.rawValue }
    }
    var movement: CameraMovement {
        get { CameraMovement(rawValue: movementRaw) ?? .staticShot }
        set { movementRaw = newValue.rawValue }
    }
    /// Library entries for this shot. Falls back to matching the stored model names (Android imports).
    var camera: CameraProfile {
        BuiltInLibrary.camera(id: cameraId)
            ?? BuiltInLibrary.cameras.first { $0.model == cameraModel }
            ?? BuiltInLibrary.cameras[0]
    }
    var lens: LensProfile {
        BuiltInLibrary.lens(id: lensId)
            ?? BuiltInLibrary.lenses.first { $0.model == lensModel }
            ?? BuiltInLibrary.lenses[0]
    }
    var focalMm: Double { ShotPresets.focalValue(focalLength) ?? lens.focalLengthMin }
    var aspectValue: Double? { ShotPresets.aspectValue(aspectRatio) }

    /// What the cinema camera records for this shot (nil if the numbers are invalid).
    var reference: ReferenceFrame? {
        Framing.reference(camera: camera, lens: lens, focalLengthMm: focalMm, aspectRatio: aspectValue)
    }

    func apply(camera c: CameraProfile) {
        cameraId = c.id
        cameraModel = c.model
        touch()
    }
    func apply(lens l: LensProfile) {
        lensId = l.id
        lensModel = l.model
        focalLength = ShotPresets.focalText(l.clampFocal(focalMm))
        aperture = ShotPresets.apertureText(l.maximumAperture)
        touch()
    }
    func touch() { modified = .now }
}

@Model
final class ShotMarker {
    @Attribute(.unique) var id: String
    var typeRaw: String
    /// Normalised 0...1 inside the cinema frame.
    var x: Double
    var y: Double
    var label: String?
    var shot: RecceShot?

    init(id: String = UUID().uuidString, type: MarkerType, x: Double, y: Double, label: String? = nil) {
        self.id = id
        self.typeRaw = type.rawValue
        self.x = x
        self.y = y
        self.label = label
    }
    var markerType: MarkerType { MarkerType(rawValue: typeRaw) ?? .subjectPosition }
}

@Model
final class ShotReference {
    @Attribute(.unique) var id: String
    /// File name inside the app's References folder.
    var fileName: String
    var date: Date
    /// Where the cinema frame was in the photo, as fractions of the photo (0...1). Nil for imported references.
    var frameX: Double?
    var frameY: Double?
    var frameWidth: Double?
    var frameHeight: Double?
    var shot: RecceShot?

    init(id: String = UUID().uuidString, fileName: String, date: Date = .now) {
        self.id = id
        self.fileName = fileName
        self.date = date
    }

    var fileURL: URL { FileStore.referencesFolder.appendingPathComponent(fileName) }
}

@Model
final class RecceNote {
    @Attribute(.unique) var id: String
    var text: String
    var language: String
    var date: Date
    /// The shot this note was taken on, if any (stored as an id so deleting a shot keeps the note).
    var shotId: String?
    var session: RecceSession?

    init(id: String = UUID().uuidString, text: String, language: String? = nil, date: Date = .now, shotId: String? = nil) {
        self.id = id
        self.text = text
        self.language = language ?? NotesSorter.detectLanguage(text)
        self.date = date
        self.shotId = shotId
    }

    var sorted: SortedNote { NotesSorter.sort(text) }
}

enum RecceSchema {
    static let models: [any PersistentModel.Type] = [
        RecceSession.self, RecceScene.self, RecceShot.self, ShotMarker.self, ShotReference.self, RecceNote.self,
    ]
}
