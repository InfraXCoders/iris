import XCTest
@testable import RecceKit

final class FramingTests: XCTestCase {
    let bm = BuiltInLibrary.camera(id: "bmpcc_6k_pro")!
    let sigma = BuiltInLibrary.lens(id: "sigma_18_35_art")!

    func testSphericalReferenceEqualsPlainFov() {
        let r = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 18)!
        XCTAssertEqual(r.deliveredFov, Optics.fieldOfView(focalLengthMm: 18, widthMm: 23.10, heightMm: 12.99))
        XCTAssertEqual(r.deliveredAspect, 23.10 / 12.99, accuracy: 1e-12)
        XCTAssertEqual(r.cropFactor, 36 / 23.10, accuracy: 1e-12)
    }

    func testAndroidHorizontalAndVerticalAgreeForSphericalLenses() {
        // For spherical lenses Android's horizontal/vertical FOV were right; only scale/diagonal/anamorphic differ.
        for g in Fixtures.list(Fixtures.android, "androidFraming") where g.s("lens") != "atlas_orion_40" {
            let r = Framing.reference(camera: BuiltInLibrary.camera(id: g.s("camera"))!, lens: BuiltInLibrary.lens(id: g.s("lens"))!,
                                      focalLengthMm: g.d("focal"))!
            XCTAssertEqual(r.deliveredFov.horizontal, g.d("hFov"), accuracy: 1e-9)
            XCTAssertEqual(r.deliveredFov.vertical, g.d("vFov"), accuracy: 1e-9)
            XCTAssertEqual(r.cropFactor, g.d("cropFactor"), accuracy: 1e-9)
        }
    }

    func testAndroidDiagonalWasWrong() {
        let g = Fixtures.list(Fixtures.android, "androidFraming")[0]          // BMPCC 18 mm
        let r = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 18)!
        XCTAssertEqual(g.d("diagonal"), 76.47, accuracy: 0.01)               // sqrt(h² + v²) of the angles
        XCTAssertEqual(r.deliveredFov.diagonal, 72.72, accuracy: 0.01)       // real diagonal angle
    }

    func testAnamorphicDesqueeze() {
        let alexa = BuiltInLibrary.camera(id: "arri_alexa_35")!, atlas = BuiltInLibrary.lens(id: "atlas_orion_40")!
        let r = Framing.reference(camera: alexa, lens: atlas, focalLengthMm: 40)!
        // 2x squeeze: the scene width is 2·27.99 mm at 40 mm -> 2·atan(55.98/80) = 69.96°.
        XCTAssertEqual(r.captureFov.horizontal, 69.96, accuracy: 0.01)
        XCTAssertEqual(r.captureFov.vertical, Optics.angle(dimensionMm: 19.22, focalLengthMm: 40), accuracy: 1e-9)
        let android = Fixtures.list(Fixtures.android, "androidFraming").first { $0.s("lens") == "atlas_orion_40" }!
        XCTAssertEqual(android.d("hFov"), 77.13, accuracy: 0.01)              // Android multiplied the angle by 2
        XCTAssertEqual(r.deliveredAspect, 55.98 / 19.22, accuracy: 1e-9)      // 2.91:1 before any delivery mask
    }

    func testAspectMaskLetterboxAndPillarbox() {
        let wide = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 24, aspectRatio: 2.39)!
        XCTAssertEqual(wide.deliveredWidthMm, 23.10, accuracy: 1e-9)           // full width
        XCTAssertEqual(wide.deliveredHeightMm, 23.10 / 2.39, accuracy: 1e-9)   // letterboxed
        XCTAssertEqual(wide.deliveredAspect, 2.39, accuracy: 1e-9)
        let square = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 24, aspectRatio: 1)!
        XCTAssertEqual(square.deliveredHeightMm, 12.99, accuracy: 1e-9)        // full height
        XCTAssertEqual(square.deliveredWidthMm, 12.99, accuracy: 1e-9)         // pillarboxed
    }

    func testSpeedBoosterWidensView() {
        let normal = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 18)!
        let boosted = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 18, focalLengthMultiplier: 0.71)!
        XCTAssertEqual(boosted.effectiveFocalLengthMm, 12.78, accuracy: 1e-9)
        XCTAssertGreaterThan(boosted.deliveredFov.horizontal, normal.deliveredFov.horizontal)
    }

    func testInvalidInputs() {
        XCTAssertNil(Framing.reference(sensorWidthMm: 23, sensorHeightMm: 13, focalLengthMm: 0))
        XCTAssertNil(Framing.reference(sensorWidthMm: 0, sensorHeightMm: 13, focalLengthMm: 18))
    }

    // MARK: viewfinder frame lines

    func testFrameLinesUseTangentsNotAngles() {
        let r = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 18)!
        let phone = PreviewView(horizontalFov: 70, verticalFov: 40)
        let lines = Viewfinder.frameLines(r, on: phone)
        XCTAssertEqual(lines.widthFraction, 0.9166, accuracy: 0.0005)          // tan(32.69°)/tan(35°)
        XCTAssertTrue(lines.fits == (lines.heightFraction <= 1))
        // Android: previewScale = 70/65.37 = 1.07, capped to 1.0 -> frame lines filled the whole preview.
        let android = Fixtures.list(Fixtures.android, "androidFraming")[0]
        XCTAssertEqual(android.d("previewScale"), 1.0708, accuracy: 0.0001)
    }

    func testFrameShrinksWithLongerLens() {
        let phone = PreviewView(horizontalFov: 70, verticalFov: 55)
        let w18 = Viewfinder.frameLines(Framing.reference(camera: bm, lens: sigma, focalLengthMm: 18)!, on: phone).widthFraction
        let w35 = Viewfinder.frameLines(Framing.reference(camera: bm, lens: sigma, focalLengthMm: 35)!, on: phone).widthFraction
        XCTAssertEqual(w35 / w18, 18.0 / 35.0, accuracy: 1e-9)                 // exact for rectilinear lenses
    }

    func testFrameLinesRespectZoom() {
        let r = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 35)!
        let base = PreviewView(horizontalFov: 70, verticalFov: 55)
        let unzoomed = Viewfinder.frameLines(r, on: base).widthFraction
        let zoomed = Viewfinder.frameLines(r, on: base.zoomed(1.5)).widthFraction
        XCTAssertEqual(zoomed, unzoomed * 1.5, accuracy: 1e-9)                 // 1.5x zoom -> frame 1.5x larger on screen
    }

    func testBestZoomFillsPreviewWithMargin() {
        let r = Framing.reference(camera: bm, lens: sigma, focalLengthMm: 35, aspectRatio: 2.39)!
        let base = PreviewView(horizontalFov: 70, verticalFov: 55)
        let z = Viewfinder.bestZoom(for: r, base: base, minZoom: 1, maxZoom: 10, margin: 1.1)
        let lines = Viewfinder.frameLines(r, on: base.zoomed(z))
        XCTAssertTrue(lines.fits)
        XCTAssertEqual(max(lines.widthFraction, lines.heightFraction), 1 / 1.1, accuracy: 1e-9)
    }

    func testBestZoomClampsWhenCinemaLensIsWiderThanPhone() {
        let lf = BuiltInLibrary.camera(id: "arri_alexa_lf")!, sig18 = BuiltInLibrary.lens(id: "arri_signature_18")!
        let r = Framing.reference(camera: lf, lens: sig18, focalLengthMm: 18)!     // ~91° horizontal
        let base = PreviewView(horizontalFov: 70, verticalFov: 55)
        XCTAssertEqual(Viewfinder.bestZoom(for: r, base: base, minZoom: 1, maxZoom: 10), 1)
        XCTAssertFalse(Viewfinder.frameLines(r, on: base).fits)                // app must warn: use the ultra-wide
        let ultraWide = PreviewView(horizontalFov: 106, verticalFov: 85)
        XCTAssertTrue(Viewfinder.frameLines(r, on: ultraWide).fits)
    }

    func testPreviewFromFormatPortraitSwapsAxes() {
        let land = PreviewView.fromFormat(longSideFov: 70, longOverShort: 16.0 / 9.0, portrait: false)
        let port = PreviewView.fromFormat(longSideFov: 70, longOverShort: 16.0 / 9.0, portrait: true)
        XCTAssertEqual(land.horizontalFov, 70)
        XCTAssertEqual(port.verticalFov, 70)
        XCTAssertEqual(land.verticalFov, port.horizontalFov)
        XCTAssertEqual(Optics.halfTan(land.horizontalFov) / Optics.halfTan(land.verticalFov), 16.0 / 9.0, accuracy: 1e-9)
    }
}
