import XCTest
@testable import RecceKit

final class ScreenGeometryTests: XCTestCase {
    func testAspectFitPillarboxAndLetterbox() {
        // 4:3 video on a 16:9-ish landscape phone screen -> pillarbox.
        let p = Viewfinder.aspectFit(contentAspect: 4.0 / 3.0, containerWidth: 844, containerHeight: 390)
        XCTAssertEqual(p.height, 390); XCTAssertEqual(p.width, 520, accuracy: 1e-9); XCTAssertEqual(p.x, 162, accuracy: 1e-9)
        // 4:3 landscape video on a portrait screen -> letterbox.
        let l = Viewfinder.aspectFit(contentAspect: 4.0 / 3.0, containerWidth: 390, containerHeight: 844)
        XCTAssertEqual(l.width, 390); XCTAssertEqual(l.height, 292.5, accuracy: 1e-9); XCTAssertEqual(l.y, 275.75, accuracy: 1e-9)
        XCTAssertEqual(Viewfinder.aspectFit(contentAspect: 0, containerWidth: 1, containerHeight: 1).width, 0)
    }

    func testFrameRectCentredAndClamped() {
        let video = ScreenRect(x: 100, y: 0, width: 400, height: 300)
        let r = Viewfinder.frameRect(FrameLines(widthFraction: 0.5, heightFraction: 0.25), in: video)
        XCTAssertEqual(r, ScreenRect(x: 200, y: 112.5, width: 200, height: 75))
        let tooWide = Viewfinder.frameRect(FrameLines(widthFraction: 1.4, heightFraction: 0.6), in: video)
        XCTAssertEqual(tooWide.width, 400)                       // clamped to the visible video
        XCTAssertEqual(tooWide.x, 100)
    }

    func testSafeAreaAndTapMapping() {
        let f = ScreenRect(x: 0, y: 0, width: 200, height: 100)
        XCTAssertEqual(Viewfinder.safeArea(f, fraction: 0.9), ScreenRect(x: 10, y: 5, width: 180, height: 90))
        let p = Viewfinder.normalisedPoint(x: 50, y: 75, in: f)!
        XCTAssertEqual(p.x, 0.25); XCTAssertEqual(p.y, 0.75)
        XCTAssertNil(Viewfinder.normalisedPoint(x: 201, y: 10, in: f))
    }

    /// End-to-end: BMPCC 6K Pro + Sigma 35 mm at 2.39:1 on a typical iPhone wide camera (4:3 format, ~70° across
    /// the long side), landscape, after the auto zoom. The frame must be fully visible and sit inside the video.
    func testEndToEndViewfinderLayout() {
        let ref = Framing.reference(camera: BuiltInLibrary.camera(id: "bmpcc_6k_pro")!, lens: BuiltInLibrary.lens(id: "sigma_18_35_art")!,
                                    focalLengthMm: 35, aspectRatio: 2.39)!
        let base = PreviewView.fromFormat(longSideFov: 70, longOverShort: 4.0 / 3.0, portrait: false)
        let zoom = Viewfinder.bestZoom(for: ref, base: base, minZoom: 1, maxZoom: 6)
        let lines = Viewfinder.frameLines(ref, on: base.zoomed(zoom))
        let video = Viewfinder.aspectFit(contentAspect: 4.0 / 3.0, containerWidth: 844, containerHeight: 390)
        let frame = Viewfinder.frameRect(lines, in: video)
        XCTAssertTrue(lines.fits)
        XCTAssertEqual(frame.width / frame.height, 2.39, accuracy: 0.03)     // the drawn frame has the delivery aspect
        XCTAssertGreaterThanOrEqual(frame.x, video.x); XCTAssertLessThanOrEqual(frame.x + frame.width, video.x + video.width)
        XCTAssertGreaterThan(zoom, 1)                                      // 35 mm on S35 is narrower than the phone
    }
}
