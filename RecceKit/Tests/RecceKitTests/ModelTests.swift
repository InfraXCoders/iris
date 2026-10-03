import XCTest
@testable import RecceKit

final class ModelTests: XCTestCase {
    /// A session exactly as the Android app writes it with Gson (field names, enum names, millisecond timestamps).
    let androidJSON = """
    {"id":"s1","locationName":"Old Fort","timestamp":1790000000000,"latitude":28.61,"longitude":77.24,
     "projectName":"Short Film","directorDopNotes":"Moody","generalLocationNotes":"Power at gate",
     "scenes":[{"id":"sc1","sessionId":"s1","sceneNumber":"4A","isInterior":false,"isDay":true,
       "locationDescription":"Courtyard","timeOfDay":"17:30","lightingCondition":"GOLDEN_HOUR","notes":"",
       "shots":[{"id":"sh1","sceneId":"sc1","shotNumber":"1","shotType":"TWO_SHOT","cameraPosition":"North wall",
         "subjectPosition":"Fountain","cameraHeight":"1.5m","cameraModel":"Pocket Cinema Camera 6K Pro",
         "lensModel":"18-35mm f/1.8 Art","focalLength":"24mm","aperture":"f/2.8","aspectRatio":"2.39:1","fps":"24",
         "shutter":"180°","iso":"400","nd":"None","whiteBalance":"5600K","cameraMovement":"PUSH_IN",
         "subjectMovement":"Walks in","estimatedDistance":3.5,"notes":"",
         "references":[{"id":"r1","shotId":"sh1","filePath":"/x.jpg","timestamp":1790000000500}],
         "analysis":null,
         "markers":[{"id":"m1","shotId":"sh1","type":"KEY_LIGHT","x":0.25,"y":0.75,"label":null}],
         "creationTimestamp":1790000000000,"modificationTimestamp":1790000001000}]}],
     "creationTimestamp":1790000000000,"modificationTimestamp":1790000002000}
    """

    func testDecodesAndroidExport() throws {
        let s = try RecceJSON.decode(Data(androidJSON.utf8))
        XCTAssertEqual(s.projectName, "Short Film")
        XCTAssertNil(s.voiceNotes)
        let shot = s.scenes[0].shots[0]
        XCTAssertEqual(s.scenes[0].lightingCondition, .goldenHour)
        XCTAssertEqual(shot.shotType, .twoShot)
        XCTAssertEqual(shot.cameraMovement, .pushIn)
        XCTAssertEqual(shot.markers[0].type, .keyLight)
        XCTAssertEqual(shot.markers[0].x, 0.25)
        XCTAssertEqual(shot.references.count, 1)
        XCTAssertEqual(RecceJSON.date(s.timestamp), Date(timeIntervalSince1970: 1_790_000_000))
    }

    func testRoundTripKeepsEverything() throws {
        var s = try RecceJSON.decode(Data(androidJSON.utf8))
        s.voiceNotes = [RecceNoteDoc(id: "n1", sessionId: "s1", shotId: "sh1", rawTranscription: "khidki se roshni",
                                     detectedLanguage: "Hinglish", timestamp: 1)]
        let again = try RecceJSON.decode(RecceJSON.encode(s))
        XCTAssertEqual(again, s)
        let text = String(decoding: try RecceJSON.encode(s), as: UTF8.self)
        XCTAssertTrue(text.contains("\"shotType\" : \"TWO_SHOT\""))           // Android-readable enum names
        XCTAssertTrue(text.contains("\"lightingCondition\" : \"GOLDEN_HOUR\""))
    }

    func testEnumLabelsMatchAndroid() {
        XCTAssertEqual(ShotType.twoShot.label, "TWO SHOT")
        XCTAssertEqual(CameraMovement.pushIn.label, "PUSH IN")
        XCTAssertEqual(LightingCondition.interiorArtificial.label, "INTERIOR")
        XCTAssertEqual(LightingCondition.goldenHour.label, "GOLDEN HOUR")
        XCTAssertEqual(ShotType.allCases.count, 11)
        XCTAssertEqual(CameraMovement.allCases.count, 10)
    }

    func testParsers() {
        XCTAssertEqual(ShotPresets.aspectValue("2.39:1")!, 2.39, accuracy: 1e-12)
        XCTAssertEqual(ShotPresets.aspectValue("16:9")!, 16.0 / 9.0, accuracy: 1e-12)
        XCTAssertEqual(ShotPresets.aspectValue("1.85")!, 1.85, accuracy: 1e-12)
        XCTAssertNil(ShotPresets.aspectValue("wide"))
        XCTAssertNil(ShotPresets.aspectValue("0:9"))
        XCTAssertEqual(ShotPresets.focalValue("35mm"), 35)
        XCTAssertEqual(ShotPresets.focalValue(" 24 MM "), 24)
        XCTAssertEqual(ShotPresets.focalText(35), "35mm")
        XCTAssertEqual(ShotPresets.focalText(12.5), "12.5mm")
        XCTAssertEqual(ShotPresets.apertureText(1.8), "f/1.8")
        XCTAssertEqual(ShotPresets.apertureText(2), "f/2.0")              // matches Android "f/${2.0}"
        for a in ShotPresets.aspectRatios { XCTAssertNotNil(ShotPresets.aspectValue(a), a) }
    }
}
