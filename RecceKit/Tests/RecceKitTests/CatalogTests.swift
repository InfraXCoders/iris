import XCTest
@testable import RecceKit

final class CatalogTests: XCTestCase {
    func testCSVParserHandlesQuotes() {
        let rows = CSV.parse("a,b,c\n1,\"x, y\",\"say \"\"hi\"\"\"\n\n2,,3\n")
        XCTAssertEqual(rows, [["a", "b", "c"], ["1", "x, y", "say \"hi\""], ["2", "", "3"]])
        XCTAssertEqual(CSV.records("k,v\nx,1")[0], ["k": "x", "v": "1"])
    }

    func testDatabaseLoads() {
        XCTAssertGreaterThan(Catalog.databaseLenses.count, 250)
        XCTAssertGreaterThan(Catalog.databaseCameras.count, 45)
        // Ids are unique across built-ins and database.
        XCTAssertEqual(Set(Catalog.lenses.map(\.id)).count, Catalog.lenses.count)
        XCTAssertEqual(Set(Catalog.cameras.map(\.id)).count, Catalog.cameras.count)
        // Every database row names its source.
        XCTAssertTrue(Catalog.databaseLenses.allSatisfy { ($0.sourceURL ?? "").hasPrefix("http") })
        XCTAssertTrue(Catalog.databaseCameras.allSatisfy { ($0.sourceURL ?? "").hasPrefix("http") })
    }

    func testDatabaseValuesAreSane() {
        for l in Catalog.databaseLenses {
            XCTAssert((8...400).contains(l.focalLengthMin), l.id)
            XCTAssert((1...2.1).contains(l.anamorphicSqueeze), l.id)
            XCTAssert(l.maximumAperture == 0 || (0.9...8).contains(l.maximumAperture), l.id)
            XCTAssert(l.imageCircleMm == 0 || (15...80).contains(l.imageCircleMm), l.id)
        }
        for c in Catalog.databaseCameras {
            XCTAssertFalse(c.sensorModes.isEmpty, c.id)
            for m in c.sensorModes {
                XCTAssert((5...60).contains(m.widthMm) && (3...45).contains(m.heightMm), "\(c.id) \(m.id)")
                if let w = m.resolutionWidth, let h = m.resolutionHeight, w > 0, h > 0 {
                    // Photosites are square except in anamorphic de-squeezed listings: pixel aspect within 3 %.
                    let pixelAspect = (m.widthMm / Double(w)) / (m.heightMm / Double(h))
                    XCTAssertEqual(pixelAspect, 1, accuracy: 0.03, "\(c.id) \(m.id)")
                }
            }
            // The first mode is the largest.
            let first = c.sensorModes[0]
            XCTAssertTrue(c.sensorModes.allSatisfy { $0.widthMm * $0.heightMm <= first.widthMm * first.heightMm + 0.01 }, c.id)
        }
    }

    func testKnownLens() throws {
        let l = try XCTUnwrap(Catalog.lens(id: "arri_zeiss_master_anamorphic_50"))
        XCTAssertEqual(l.manufacturer, "ARRI/ZEISS")
        XCTAssertEqual(l.anamorphicSqueeze, 2)
        XCTAssertEqual(l.maximumAperture, 1.9)
        XCTAssertEqual(l.lensType, .anamorphic)
        XCTAssertEqual(l.model, "Master Anamorphic 50mm T1.9")
        // The built-in Atlas Orion 40 is replaced by the database row with the same id.
        XCTAssertEqual(Catalog.lens(id: "atlas_orion_40")?.series, "Orion")
    }

    func testCameraModes() throws {
        let c = try XCTUnwrap(Catalog.camera(id: "arri_alexa_35"))
        XCTAssertGreaterThan(c.sensorModes.count, 3)
        XCTAssertEqual(c.sensorWidthMm, 27.99, accuracy: 0.01)
        let crop = c.sensorModes.last!
        let used = c.using(modeId: crop.id)
        XCTAssertEqual(used.sensorWidthMm, crop.widthMm)
        XCTAssertEqual(c.using(modeId: "nope").sensorWidthMm, c.sensorWidthMm)
        // Built-ins without listed modes get one full-sensor mode.
        let builtIn = try XCTUnwrap(BuiltInLibrary.camera(id: "sony_fx6"))
        XCTAssertEqual(builtIn.sensorModes.count, 1)
        XCTAssertEqual(builtIn.sensorModes[0].widthMm, 35.7)
    }

    func testCoverage() {
        XCTAssertEqual(Coverage.evaluate(imageCircleMm: 43.3, widthMm: 36, heightMm: 24), .full)
        XCTAssertEqual(Coverage.evaluate(imageCircleMm: 40, widthMm: 36, heightMm: 24), .cornersVignette)
        XCTAssertEqual(Coverage.evaluate(imageCircleMm: 31, widthMm: 36, heightMm: 24), .vignettes)
        XCTAssertEqual(Coverage.evaluate(imageCircleMm: 0, widthMm: 36, heightMm: 24), .unknown)

        // Master Anamorphic (29.26 mm circle) on ALEXA 35: open gate 27.99 x 19.22 (diag 33.95) doesn't fully cover,
        // but some smaller mode does.
        let lens = Catalog.lens(id: "arri_zeiss_master_anamorphic_50")!
        let cam = Catalog.camera(id: "arri_alexa_35")!
        XCTAssertNotEqual(Coverage.evaluate(lens: lens, mode: cam.sensorModes[0]), .full)
        let best = Coverage.largestCoveredMode(lens: lens, camera: cam)
        XCTAssertNotNil(best)
        XCTAssertLessThanOrEqual(best!.diagonalMm, 29.27)
        XCTAssertTrue(Coverage.sharesMount(camera: cam, lens: lens) || lens.allMountNames == ["PL"])
    }

    func testNominalCircle() {
        let l = LensProfile(id: "t", manufacturer: "T", model: "T", mount: .pl, lensType: .anamorphic, focalLengthMin: 50,
                            focalLengthMax: 50, maximumAperture: 2, minimumFocusDistance: 1, anamorphicSqueeze: 2,
                            imageCircleMm: 0, format: "S35")
        XCTAssertEqual(Coverage.circle(for: l)?.nominal, true)
        XCTAssertEqual(Coverage.circle(for: l)?.mm, 31.1)
    }

    func testTStopText() {
        XCTAssertEqual(ShotPresets.tStopText(2), "T2.0")
        XCTAssertEqual(ShotPresets.tStopText(1.9), "T1.9")
        XCTAssertEqual(ShotPresets.tStopText(0), "—")
    }
}
