import XCTest
import SwiftData
import CoreGraphics
import RecceKit
@testable import BMPCCControl

/// Checks the app's saved data, the Android-compatible JSON import/export and the PDF report,
/// using an in-memory store so nothing touches your real recces.
@MainActor
final class AppDataTests: XCTestCase {
    var container: ModelContainer!
    var context: ModelContext!

    override func setUp() async throws {
        let schema = Schema(RecceSchema.models)
        container = try ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        context = container.mainContext
    }

    private let androidJSON = """
    {"id":"s1","locationName":"Old Fort","timestamp":1790000000000,"latitude":28.61,"longitude":77.24,
     "projectName":"Short Film","directorDopNotes":"Moody","generalLocationNotes":"Power at gate",
     "scenes":[{"id":"sc1","sessionId":"s1","sceneNumber":"4A","isInterior":false,"isDay":true,
       "locationDescription":"Courtyard","timeOfDay":"17:30","lightingCondition":"GOLDEN_HOUR","notes":"",
       "shots":[{"id":"sh1","sceneId":"sc1","shotNumber":"1","shotType":"TWO_SHOT","cameraPosition":"North wall",
         "subjectPosition":"Fountain","cameraHeight":"1.5m","cameraModel":"Pocket Cinema Camera 6K Pro",
         "lensModel":"18-35mm f/1.8 Art","focalLength":"24mm","aperture":"f/2.8","aspectRatio":"2.39:1","fps":"24",
         "shutter":"180°","iso":"400","nd":"None","whiteBalance":"5600K","cameraMovement":"PUSH_IN",
         "subjectMovement":"Walks in","estimatedDistance":3.5,"notes":"",
         "references":[{"id":"r1","shotId":"sh1","filePath":"/storage/emulated/0/x.jpg","timestamp":1790000000500}],
         "analysis":null,
         "markers":[{"id":"m1","shotId":"sh1","type":"KEY_LIGHT","x":0.25,"y":0.75,"label":null}],
         "creationTimestamp":1790000000000,"modificationTimestamp":1790000001000}]}],
     "creationTimestamp":1790000000000,"modificationTimestamp":1790000002000}
    """

    func testImportsAndroidExport() throws {
        let session = try RecceExport.importDocument(RecceJSON.decode(Data(androidJSON.utf8)), into: context)
        XCTAssertEqual(session.projectName, "Short Film")
        XCTAssertEqual(session.scenes.count, 1)
        let scene = session.sortedScenes[0]
        XCTAssertEqual(scene.lighting, .goldenHour)
        let shot = scene.sortedShots[0]
        XCTAssertEqual(shot.shotType, .twoShot)
        XCTAssertEqual(shot.movement, .pushIn)
        XCTAssertEqual(shot.focalMm, 24)
        XCTAssertEqual(shot.markers.first?.markerType, .keyLight)
        XCTAssertEqual(shot.references.first?.fileName, "x.jpg")
        XCTAssertNotNil(shot.reference)
    }

    func testRejectsDuplicateImport() throws {
        let doc = try RecceJSON.decode(Data(androidJSON.utf8))
        try RecceExport.importDocument(doc, into: context)
        XCTAssertThrowsError(try RecceExport.importDocument(doc, into: context))
    }

    func testExportImportRoundTrip() throws {
        let session = RecceFactory.newSession(project: "Round Trip", location: "Studio", in: context)
        session.latitude = 28.6
        session.longitude = 77.2
        let scene = RecceFactory.newScene(in: session, context: context)
        let shot = RecceFactory.newShot(in: scene, camera: BuiltInLibrary.cameras[0], lens: BuiltInLibrary.lenses[0], context: context)
        shot.notes = "Wide establishing"
        let note = RecceNote(text: "khidki se roshni aa rahi hai", shotId: shot.id)
        context.insert(note)
        note.session = session
        try context.save()

        let doc = RecceExport.document(for: session)
        let data = try RecceJSON.encode(doc)

        // Import into a fresh store.
        let other = try ModelContainer(for: Schema(RecceSchema.models), configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let copy = try RecceExport.importDocument(RecceJSON.decode(data), into: other.mainContext)
        XCTAssertEqual(RecceExport.document(for: copy), doc)
        XCTAssertEqual(copy.notes.first?.language, "Hinglish")
    }

    func testNewShotNumbering() throws {
        let session = RecceFactory.newSession(project: "", location: "", in: context)
        XCTAssertEqual(session.projectName, "Untitled project")
        let scene = RecceFactory.newScene(in: session, context: context)
        let a = RecceFactory.newShot(in: scene, camera: BuiltInLibrary.cameras[0], lens: BuiltInLibrary.lenses[0], context: context)
        let b = RecceFactory.newShot(in: scene, camera: BuiltInLibrary.cameras[0], lens: BuiltInLibrary.lenses[0], context: context)
        XCTAssertEqual(a.shotNumber, "1")
        XCTAssertEqual(b.shotNumber, "2")
        XCTAssertEqual(RecceFactory.newScene(in: session, context: context).sceneNumber, "2")
    }

    func testRecordingModeChangesFraming() throws {
        let session = RecceFactory.newSession(project: "Modes", location: "Studio", in: context)
        let scene = RecceFactory.newScene(in: session, context: context)
        let camera = try XCTUnwrap(Catalog.camera(id: "arri_alexa_35"))
        let lens = try XCTUnwrap(Catalog.lens(id: "arri_zeiss_master_anamorphic_50"))
        let shot = RecceFactory.newShot(in: scene, camera: camera, lens: lens, context: context)
        XCTAssertEqual(shot.aperture, "f/1.9")
        let fullWidth = try XCTUnwrap(shot.reference).captureWidthMm
        let smaller = try XCTUnwrap(camera.sensorModes.min { $0.widthMm < $1.widthMm })
        shot.sensorModeId = smaller.id
        XCTAssertEqual(shot.sensorMode.id, smaller.id)
        XCTAssertLessThan(try XCTUnwrap(shot.reference).captureWidthMm, fullWidth)
        // Changing camera goes back to the new camera's largest mode.
        shot.apply(camera: Catalog.defaultCamera)
        XCTAssertNil(shot.sensorModeId)
    }

    func testPDFReport() throws {
        let session = try RecceExport.importDocument(RecceJSON.decode(Data(androidJSON.utf8)), into: context)
        let note = RecceNote(text: "Window light from the left, 35mm, dolly in")
        context.insert(note)
        note.session = session
        try context.save()

        let url = try ReportPDF.make(for: session)
        let pdf = try XCTUnwrap(CGPDFDocument(url as CFURL))
        XCTAssertEqual(pdf.numberOfPages, ReportPDF.pageCount(for: session))
        XCTAssertEqual(pdf.numberOfPages, 3)   // summary + 1 shot + notes
    }
}
