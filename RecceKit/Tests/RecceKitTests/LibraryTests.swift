import XCTest
@testable import RecceKit

final class LibraryTests: XCTestCase {
    func testCamerasMatchAndroidSeeder() {
        let golden = Fixtures.list(Fixtures.android, "cameras")
        XCTAssertEqual(golden.count, BuiltInLibrary.cameras.count)
        for (g, c) in zip(golden, BuiltInLibrary.cameras) {
            XCTAssertEqual(c.id, g.s("id"))
            XCTAssertEqual(c.manufacturer, g.s("manufacturer"))
            XCTAssertEqual(c.model, g.s("model"))
            XCTAssertEqual(c.cameraType, g.s("cameraType"))
            XCTAssertEqual(c.sensorFormatId, g.s("sensorFormatId"))
            XCTAssertEqual(c.sensorWidthMm, g.d("sensorWidthMm"))
            XCTAssertEqual(c.sensorHeightMm, g.d("sensorHeightMm"))
            XCTAssertEqual(c.resolutionWidth, Int(g.d("resolutionWidth")))
            XCTAssertEqual(c.resolutionHeight, Int(g.d("resolutionHeight")))
            XCTAssertEqual(c.nativeAspectRatio, g.s("nativeAspectRatio"))
            XCTAssertEqual(c.mount.rawValue, g.s("mount"))
            XCTAssertEqual(c.verificationStatus.rawValue, g.s("verificationStatus"))
        }
    }

    func testLensesMatchAndroidSeeder() {
        let golden = Fixtures.list(Fixtures.android, "lenses")
        XCTAssertEqual(golden.count, BuiltInLibrary.lenses.count)
        for (g, l) in zip(golden, BuiltInLibrary.lenses) {
            XCTAssertEqual(l.id, g.s("id"))
            XCTAssertEqual(l.manufacturer, g.s("manufacturer"))
            XCTAssertEqual(l.model, g.s("model"))
            XCTAssertEqual(l.mount.rawValue, g.s("mount"))
            XCTAssertEqual(l.lensType.rawValue, g.s("lensType"))
            XCTAssertEqual(l.focalLengthMin, g.d("focalLengthMin"))
            XCTAssertEqual(l.focalLengthMax, g.d("focalLengthMax"))
            XCTAssertEqual(l.availableFocalLengths, (g["availableFocalLengths"] as! [NSNumber]).map(\.doubleValue))
            XCTAssertEqual(l.maximumAperture, g.d("maximumAperture"))
            XCTAssertEqual(l.minimumFocusDistance, g.d("minimumFocusDistance"))
            XCTAssertEqual(l.anamorphicSqueeze, g.d("anamorphicSqueeze"))
            XCTAssertEqual(l.imageCircleMm, g.d("imageCircleMm"))
            XCTAssertEqual(l.verificationStatus.rawValue, g.s("verificationStatus"))
        }
    }

    func testCompatibilityMatchesAndroidForAllCameraLensPairs() {
        let golden = Fixtures.list(Fixtures.android, "compatibility")
        XCTAssertEqual(golden.count, BuiltInLibrary.cameras.count * BuiltInLibrary.lenses.count * 2)
        for g in golden {
            let c = BuiltInLibrary.camera(id: g.s("camera"))!, l = BuiltInLibrary.lens(id: g.s("lens"))!
            let adapter = g["adapter"] as! Bool
            XCTAssertEqual(Compatibility.check(camera: c, lens: l, adapter: adapter).rawValue, g.s("status"),
                           "\(c.id) + \(l.id) adapter=\(adapter)")
        }
    }

    func testSigmaArtOnBMPCC6KProIsCompatible() {
        let r = Compatibility.check(camera: BuiltInLibrary.camera(id: "bmpcc_6k_pro")!, lens: BuiltInLibrary.lens(id: "sigma_18_35_art")!)
        XCTAssertEqual(r, .compatible)       // EF on EF, 28.4 mm circle covers the 26.5 mm Super 35 diagonal
    }

    func testSearch() {
        XCTAssertEqual(BuiltInLibrary.searchCameras("arri").map(\.id), ["arri_alexa_35", "arri_alexa_lf", "arri_alexa_mini"])
        XCTAssertEqual(BuiltInLibrary.searchCameras("pocket 6k").map(\.id), ["bmpcc_6k_pro"])
        XCTAssertEqual(BuiltInLibrary.searchLenses("SIGMA").count, 2)
        XCTAssertEqual(BuiltInLibrary.searchLenses("  ").count, BuiltInLibrary.lenses.count)
        XCTAssertTrue(BuiltInLibrary.searchLenses("zzz").isEmpty)
    }

    func testQuickFocalLengths() {
        XCTAssertEqual(BuiltInLibrary.lens(id: "sigma_18_35_art")!.quickFocalLengths, [18, 20, 24, 28, 32, 35])
        XCTAssertEqual(BuiltInLibrary.lens(id: "sigma_18_35_art")!.clampFocal(50), 35)
        let custom = LensProfile(id: "x", manufacturer: "X", model: "Y", mount: .ef, lensType: .zoom, focalLengthMin: 24,
                                 focalLengthMax: 70, maximumAperture: 2.8, minimumFocusDistance: 0.4, imageCircleMm: 43)
        XCTAssertEqual(custom.quickFocalLengths, [24, 70])
    }
}
