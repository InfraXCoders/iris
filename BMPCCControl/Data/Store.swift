import Foundation
import SwiftData
import RecceKit

/// Where reference photos and exports are kept.
enum FileStore {
    static var appFolder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("BMPCC Control", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    static var referencesFolder: URL {
        let url = appFolder.appendingPathComponent("References", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    static var exportsFolder: URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("BMPCC Exports", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Saves JPEG data as a new reference photo and returns its file name.
    static func saveReference(_ data: Data) throws -> String {
        let name = "\(UUID().uuidString).jpg"
        try data.write(to: referencesFolder.appendingPathComponent(name), options: .atomic)
        return name
    }

    /// "Old Fort / Day 1" -> "Old-Fort-Day-1" (safe for file names on both platforms).
    static func safeName(_ text: String) -> String {
        let parts = text.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
        return parts.isEmpty ? "Recce" : parts.joined(separator: "-")
    }
}

/// Converts between saved SwiftData objects and the Android-compatible JSON documents in RecceKit.
enum RecceExport {
    static func document(for s: RecceSession) -> RecceSessionDoc {
        RecceSessionDoc(
            id: s.id, locationName: s.locationName, timestamp: RecceJSON.millis(s.date),
            latitude: s.latitude, longitude: s.longitude, projectName: s.projectName,
            directorDopNotes: s.directorDopNotes, generalLocationNotes: s.generalLocationNotes,
            scenes: s.sortedScenes.map { scene in
                RecceSceneDoc(
                    id: scene.id, sessionId: s.id, sceneNumber: scene.sceneNumber, isInterior: scene.isInterior,
                    isDay: scene.isDay, locationDescription: scene.locationDescription, timeOfDay: scene.timeOfDay,
                    lightingCondition: scene.lighting, notes: scene.notes,
                    shots: scene.sortedShots.map { shot in
                        RecceShotDoc(
                            id: shot.id, sceneId: scene.id, shotNumber: shot.shotNumber, shotType: shot.shotType,
                            cameraPosition: shot.cameraPosition, subjectPosition: shot.subjectPosition,
                            cameraHeight: shot.cameraHeight, cameraModel: shot.cameraModel, lensModel: shot.lensModel,
                            focalLength: shot.focalLength, aperture: shot.aperture, aspectRatio: shot.aspectRatio,
                            fps: shot.fps, shutter: shot.shutter, iso: shot.iso, nd: shot.nd,
                            whiteBalance: shot.whiteBalance, cameraMovement: shot.movement,
                            subjectMovement: shot.subjectMovement, estimatedDistance: shot.estimatedDistance,
                            notes: shot.notes,
                            references: shot.references.sorted { $0.date < $1.date }.map {
                                ShotReferenceDoc(id: $0.id, shotId: shot.id, filePath: $0.fileName, timestamp: RecceJSON.millis($0.date))
                            },
                            analysis: nil,
                            markers: shot.markers.map {
                                ShotMarkerDoc(id: $0.id, shotId: shot.id, type: $0.markerType, x: $0.x, y: $0.y, label: $0.label)
                            },
                            creationTimestamp: RecceJSON.millis(shot.created),
                            modificationTimestamp: RecceJSON.millis(shot.modified))
                    })
            },
            creationTimestamp: RecceJSON.millis(s.created), modificationTimestamp: RecceJSON.millis(s.modified),
            voiceNotes: s.sortedNotes.map {
                RecceNoteDoc(id: $0.id, sessionId: s.id, shotId: $0.shotId, rawTranscription: $0.text,
                             detectedLanguage: $0.language, timestamp: RecceJSON.millis($0.date))
            })
    }

    /// Writes the session as JSON to a temporary file for sharing.
    static func jsonFile(for s: RecceSession) throws -> URL {
        let url = FileStore.exportsFolder.appendingPathComponent("\(FileStore.safeName(s.projectName))-\(FileStore.safeName(s.locationName)).json")
        try RecceJSON.encode(document(for: s)).write(to: url, options: .atomic)
        return url
    }

    enum ImportError: LocalizedError {
        case alreadyExists(String)
        var errorDescription: String? {
            switch self {
            case .alreadyExists(let name): return "“\(name)” is already in your recces."
            }
        }
    }

    /// Adds an exported session (from this app or the Android app) to the store.
    @discardableResult
    static func importDocument(_ doc: RecceSessionDoc, into context: ModelContext) throws -> RecceSession {
        let id = doc.id
        let existing = try context.fetch(FetchDescriptor<RecceSession>(predicate: #Predicate { $0.id == id }))
        if !existing.isEmpty { throw ImportError.alreadyExists(doc.projectName) }

        let session = RecceSession(id: doc.id, projectName: doc.projectName, locationName: doc.locationName,
                                   date: RecceJSON.date(doc.timestamp), latitude: doc.latitude, longitude: doc.longitude,
                                   directorDopNotes: doc.directorDopNotes, generalLocationNotes: doc.generalLocationNotes,
                                   created: RecceJSON.date(doc.creationTimestamp), modified: RecceJSON.date(doc.modificationTimestamp))
        context.insert(session)
        for sd in doc.scenes {
            let scene = RecceScene(id: sd.id, sceneNumber: sd.sceneNumber, isInterior: sd.isInterior, isDay: sd.isDay,
                                   locationDescription: sd.locationDescription, timeOfDay: sd.timeOfDay,
                                   lighting: sd.lightingCondition, notes: sd.notes)
            context.insert(scene)
            scene.session = session
            for d in sd.shots {
                let camera = Catalog.cameras.first { $0.model == d.cameraModel } ?? Catalog.defaultCamera
                let lens = Catalog.lenses.first { $0.model == d.lensModel } ?? Catalog.defaultLens
                let shot = RecceShot(id: d.id, shotNumber: d.shotNumber, shotType: d.shotType, camera: camera, lens: lens,
                                     created: RecceJSON.date(d.creationTimestamp))
                // Keep exactly what was recorded, even for cameras/lenses that aren't in the library.
                shot.cameraModel = d.cameraModel
                shot.lensModel = d.lensModel
                shot.focalLength = d.focalLength
                shot.aperture = d.aperture
                shot.aspectRatio = d.aspectRatio
                shot.cameraPosition = d.cameraPosition
                shot.subjectPosition = d.subjectPosition
                shot.cameraHeight = d.cameraHeight
                shot.fps = d.fps
                shot.shutter = d.shutter
                shot.iso = d.iso
                shot.nd = d.nd
                shot.whiteBalance = d.whiteBalance
                shot.movement = d.cameraMovement
                shot.subjectMovement = d.subjectMovement
                shot.estimatedDistance = d.estimatedDistance
                shot.notes = d.notes
                shot.modified = RecceJSON.date(d.modificationTimestamp)
                context.insert(shot)
                shot.scene = scene
                for m in d.markers {
                    let marker = ShotMarker(id: m.id, type: m.type, x: m.x, y: m.y, label: m.label)
                    context.insert(marker)
                    marker.shot = shot
                }
                for r in d.references {
                    let ref = ShotReference(id: r.id, fileName: URL(fileURLWithPath: r.filePath).lastPathComponent,
                                            date: RecceJSON.date(r.timestamp))
                    context.insert(ref)
                    ref.shot = shot
                }
            }
        }
        for n in doc.voiceNotes ?? [] {
            let note = RecceNote(id: n.id, text: n.rawTranscription, language: n.detectedLanguage,
                                 date: RecceJSON.date(n.timestamp), shotId: n.shotId)
            context.insert(note)
            note.session = session
        }
        try context.save()
        return session
    }

    static func importFile(_ url: URL, into context: ModelContext) throws -> RecceSession {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try importDocument(RecceJSON.decode(Data(contentsOf: url)), into: context)
    }
}

/// Creating new objects with sensible defaults (same defaults as the Android app).
enum RecceFactory {
    static func newSession(project: String, location: String, in context: ModelContext) -> RecceSession {
        let s = RecceSession(projectName: project.isEmpty ? "Untitled project" : project,
                             locationName: location.isEmpty ? "Location" : location)
        context.insert(s)
        return s
    }

    static func newScene(in session: RecceSession, context: ModelContext) -> RecceScene {
        let next = (session.scenes.compactMap { Int($0.sceneNumber) }.max() ?? 0) + 1
        let scene = RecceScene(sceneNumber: "\(next)")
        context.insert(scene)
        scene.session = session
        session.touch()
        return scene
    }

    static func newShot(in scene: RecceScene, camera: CameraProfile, lens: LensProfile, context: ModelContext) -> RecceShot {
        let next = (scene.shots.compactMap { Int($0.shotNumber) }.max() ?? 0) + 1
        let shot = RecceShot(shotNumber: "\(next)", camera: camera, lens: lens)
        context.insert(shot)
        shot.scene = scene
        scene.session?.touch()
        return shot
    }

    /// Android's "Quick Recce": one session/scene/shot ready for the viewfinder.
    static func quickRecceShot(camera: CameraProfile, lens: LensProfile, context: ModelContext) throws -> RecceShot {
        let sessions = try context.fetch(FetchDescriptor<RecceSession>(predicate: #Predicate { $0.projectName == "Quick Recce" }))
        let session = sessions.first ?? newSession(project: "Quick Recce", location: "Location scouting", in: context)
        let scene = session.sortedScenes.first ?? newScene(in: session, context: context)
        let shot = newShot(in: scene, camera: camera, lens: lens, context: context)
        try context.save()
        return shot
    }
}
