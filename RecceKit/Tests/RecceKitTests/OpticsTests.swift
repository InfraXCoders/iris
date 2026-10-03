import XCTest
@testable import RecceKit

final class OpticsTests: XCTestCase {
    func testFovMatchesAndroidForEveryCameraAndFocalLength() {
        for g in Fixtures.list(Fixtures.android, "fov") {
            let id = g.s("camera")
            let fov: FieldOfView
            if id == "zero" {
                fov = Optics.fieldOfView(focalLengthMm: 0, widthMm: 23.1, heightMm: 12.99)
            } else {
                let c = BuiltInLibrary.camera(id: id)!
                fov = Optics.fieldOfView(focalLengthMm: g.d("focal"), widthMm: c.sensorWidthMm, heightMm: c.sensorHeightMm)
            }
            XCTAssertEqual(fov.horizontal, g.d("h"), accuracy: 1e-9, "\(id) \(g.d("focal"))")
            XCTAssertEqual(fov.vertical, g.d("v"), accuracy: 1e-9)
            XCTAssertEqual(fov.diagonal, g.d("d"), accuracy: 1e-9)
        }
    }

    func testEquivalentFocalMatchesAndroid() {
        for g in Fixtures.list(Fixtures.android, "equivalentFocal") {
            let c = BuiltInLibrary.camera(id: g.s("camera"))!
            // Android computed this in Float; 1e-4 relative tolerance covers single precision.
            XCTAssertEqual(Optics.equivalentFocalLength(18, sensorWidthMm: c.sensorWidthMm), g.d("equivalent"),
                           accuracy: g.d("equivalent") * 1e-4)
        }
    }

    func testKnownValues() {
        // BMPCC 6K Pro, 18 mm: 65.4° horizontal, crop factor 1.558, 28 mm equivalent.
        let f = Optics.fieldOfView(focalLengthMm: 18, widthMm: 23.10, heightMm: 12.99)
        XCTAssertEqual(f.horizontal, 65.37, accuracy: 0.01)
        XCTAssertEqual(Optics.cropFactor(sensorWidthMm: 23.10), 1.558, accuracy: 0.001)
        XCTAssertEqual(Optics.equivalentFocalLength(18, sensorWidthMm: 23.10), 28.05, accuracy: 0.01)
        // Full frame 50 mm: 39.6° horizontal, 46.8° diagonal (textbook values).
        let ff = Optics.fieldOfView(focalLengthMm: 50, widthMm: 36, heightMm: 24)
        XCTAssertEqual(ff.horizontal, 39.6, accuracy: 0.05)
        XCTAssertEqual(ff.diagonal, 46.8, accuracy: 0.05)
    }

    func testHalfTanRoundTrip() {
        for a in stride(from: 1.0, through: 170.0, by: 7.0) {
            XCTAssertEqual(Optics.fov(fromHalfTan: Optics.halfTan(a)), a, accuracy: 1e-9)
        }
    }
}
