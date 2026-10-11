import XCTest
@testable import RecceKit

/// The shared maths ported from the Android app (same expected values as its Kotlin tests).
final class ParityTests: XCTestCase {
    private func grey(_ v: UInt32) -> UInt32 { 0xFF00_0000 | (v << 16) | (v << 8) | v }

    // MARK: LUTs

    func testCubeParseIdentityAndApply() throws {
        var text = "TITLE \"Id\"\nLUT_3D_SIZE 2\n"
        for b in 0..<2 { for g in 0..<2 { for r in 0..<2 { text += "\(r) \(g) \(b)\n" } } }
        let lut = try CubeParser.parse(text, fallbackName: "x")
        XCTAssertEqual(lut.name, "Id")
        let o = lut.apply(0.25, 0.5, 0.75)
        XCTAssertEqual(o.0, 0.25, accuracy: 1e-6); XCTAssertEqual(o.1, 0.5, accuracy: 1e-6); XCTAssertEqual(o.2, 0.75, accuracy: 1e-6)
        XCTAssertEqual(lut.cubeRGBA().count, 2 * 2 * 2 * 4)
    }

    func testCubeErrorsAnd1D() throws {
        XCTAssertThrowsError(try CubeParser.parse("0 0 0\nLUT_3D_SIZE 2", fallbackName: "x"))
        XCTAssertThrowsError(try CubeParser.parse("LUT_3D_SIZE 129", fallbackName: "x"))
        XCTAssertThrowsError(try CubeParser.parse("LUT_3D_SIZE 2\n0 0 0", fallbackName: "x"))
        let inv = try CubeParser.parse("LUT_1D_SIZE 2\n1 1 1\n0 0 0", fallbackName: "inv")
        XCTAssertEqual(inv.size, 33)
        XCTAssertEqual(inv.apply(0.2, 0.2, 0.2).0, 0.8, accuracy: 1e-3)
    }

    func testGen5AndGamut() {
        XCTAssertEqual(InputTransform.gen5Oetf(0.18), 0.3836, accuracy: 1e-3)
        let m = InputTransform.rec709ToWideGamut
        for i in 0..<3 { XCTAssertEqual(m[i * 3] + m[i * 3 + 1] + m[i * 3 + 2], 1, accuracy: 1e-6) }
        XCTAssertTrue(m.allSatisfy { $0 > -1e-9 })
        XCTAssertEqual(InputTransform.displayToGen5(1, 1, 1).1, InputTransform.rec709ToGen5(1), accuracy: 1e-4)
        let bw = BuiltInLooks.all.first { $0.name == "Black & white" }!
        let o = bw.apply(1, 0, 0)
        XCTAssertEqual(o.0, o.1, accuracy: 1e-6)
    }

    // MARK: Exposure tools

    func testFalseColourZebraHistogramWaveform() {
        XCTAssertEqual(FalseColour.band(for: 0.40)?.name, "Green")
        XCTAssertEqual(FalseColour.band(for: 1.0)?.name, "Red")
        XCTAssertNil(FalseColour.band(for: 0.2))
        XCTAssertEqual(FalseColour.bands.last?.label, "99–100%")
        XCTAssertEqual(FalseColour.bands[1].label, "2.5–4%")
        XCTAssertTrue(Zebra.striped(luma: 0.995, percent: 100)); XCTAssertFalse(Zebra.striped(luma: 0.985, percent: 100))
        let px = (0..<100).map { $0 < 10 ? grey(255) : $0 < 30 ? grey(0) : grey(128) }
        let h = Scopes.histogram(ScopeFrame(width: 10, height: 10, pixels: px), bins: 64)
        XCTAssertEqual(h.y[63], 10); XCTAssertEqual(h.y[0], 20)
        XCTAssertEqual(h.clipped, 0.1, accuracy: 1e-9); XCTAssertEqual(h.crushed, 0.2, accuracy: 1e-9)
        let wpx = (0..<32).map { $0 % 8 < 4 ? grey(0) : grey(255) }
        let w = Scopes.waveform(ScopeFrame(width: 8, height: 4, pixels: wpx), columns: 2, levels: 10)
        XCTAssertEqual(w.counts[0][0], 16); XCTAssertEqual(w.counts[0][10 + 9], 16)
        let c = Scopes.crop(frame: ScreenRect(x: 100, y: 200, width: 800, height: 400), video: ScreenRect(x: 0, y: -100, width: 1000, height: 1000))
        XCTAssertEqual(c.0, 0.1, accuracy: 1e-9); XCTAssertEqual(c.1, 0.3, accuracy: 1e-9); XCTAssertEqual(c.3, 0.7, accuracy: 1e-9)
    }

    func testCpuPictureToolsAndSampling() {
        let f = ScopeFrame(width: 4, height: 3, pixels: (0..<12).map { $0 % 4 < 2 ? grey(0) : grey(255) })
        let fc = PictureProcessor.process(f, lut: nil, input: .rec709, splitFraction: 0, falseColour: true, zebraLevel: nil, peaking: nil, peakingColour: .red)
        XCTAssertEqual(fc[0], FalseColour.bands.first!.argb)
        let pk = PictureProcessor.process(f, lut: nil, input: .rec709, splitFraction: 0, falseColour: false, zebraLevel: nil, peaking: .high, peakingColour: .green)
        XCTAssertEqual(pk[5], PeakingColour.green.argb)
        let z = PictureProcessor.process(f, lut: nil, input: .rec709, splitFraction: 0, falseColour: false, zebraLevel: 95, peaking: nil, peakingColour: .red, stripePx: 2)
        XCTAssertEqual(z[7], 0xFFFF_FFFF); XCTAssertEqual(z[6], grey(89))
        // BGRA sampling: one blue pixel.
        let bytes: [UInt8] = [255, 0, 0, 255]
        let s = bytes.withUnsafeBytes { ScopeFrame.sample($0.baseAddress!, bytesPerRow: 4, width: 1, height: 1, bgra: true, outW: 1, outH: 1) }
        XCTAssertEqual(s.pixels[0], 0xFF00_00FF)
    }

    func testExposureMatch() {
        XCTAssertEqual(ExposureMatch.exposureSeconds(shutter: "180°", fps: "24")!, 1 / 48.0, accuracy: 1e-12)
        XCTAssertEqual(ExposureMatch.exposureSeconds(shutter: "1/50", fps: "24")!, 1 / 50.0, accuracy: 1e-12)
        let k = ExposureMatch.target(iso: "800", shutter: "180°", fps: "24", nd: "None", aperture: "T2.8", phoneAperture: 1.8)!
        XCTAssertEqual(k, 800 / 48.0 * (1.8 / 2.8) * (1.8 / 2.8), accuracy: 1e-9)
        let p = ExposureMatch.phone(isoTimesSeconds: k, isoMin: 50, isoMax: 3200, minSeconds: 1e-5, maxSeconds: 0.2, preferredSeconds: 1 / 48.0)
        XCTAssertEqual(p.seconds, 1 / 48.0, accuracy: 2e-4); XCTAssertEqual(p.stopsOff, 0, accuracy: 0.05)
        XCTAssertEqual(ExposureMatch.target(iso: "800", shutter: "180°", fps: "24", nd: "ND 1.8 (6 stops)", aperture: "T2.8", phoneAperture: 1.8)!, k / 64, accuracy: 1e-9)
        XCTAssertLessThan(ExposureMatch.phone(isoTimesSeconds: 1e-7, isoMin: 50, isoMax: 3200, minSeconds: 1e-5, maxSeconds: 0.2).stopsOff, -5)
        XCTAssertEqual(Exposure.ndStops("ND 0.6 (2 stops)"), 2); XCTAssertEqual(Exposure.stop("T2.8"), 2.8)
    }

    // MARK: Sun

    func testSkylineAndDirectSun() {
        let sky = Skyline([SkyPoint(azimuth: 100, elevation: 10), SkyPoint(azimuth: 110, elevation: 20)])
        XCTAssertEqual(sky.elevation(105)!, 15, accuracy: 1e-9)
        XCTAssertNil(sky.elevation(200))
        let tz = TimeZone(identifier: "Asia/Kolkata")!
        var cal = Calendar(identifier: .gregorian); cal.timeZone = tz
        let day = cal.date(from: DateComponents(year: 2026, month: 6, day: 21, hour: 12))!
        let (lat, lon) = (28.6139, 77.2090)
        let sun = Solar.day(for: day, latitude: lat, longitude: lon, timeZone: tz)
        let open = SunPlan.directSunPeriods(day, latitude: lat, longitude: lon, timeZone: tz, skyline: nil)
        XCTAssertEqual(open.count, 1)
        XCTAssertEqual(open[0].0.timeIntervalSince1970, sun.sunrise!.timeIntervalSince1970, accuracy: 30)
        let wall = Skyline((0..<360).map { SkyPoint(azimuth: Double($0), elevation: 20) })
        let walled = SunPlan.directSunPeriods(day, latitude: lat, longitude: lon, timeZone: tz, skyline: wall)
        XCTAssertEqual(walled.count, 1)
        XCTAssertGreaterThan(walled[0].0.timeIntervalSince(sun.sunrise!), 3600)
        XCTAssertEqual(SunPlan.refraction(0), 0.48, accuracy: 0.03)
        // The sun at 16:00 is found again by "Mark this spot".
        let t16 = cal.date(from: DateComponents(year: 2026, month: 6, day: 21, hour: 16))!
        let p = Solar.position(at: t16, latitude: lat, longitude: lon)
        let r = SunPlan.spot(azimuth: p.azimuth, elevation: p.elevation, date: day, latitude: lat, longitude: lon, timeZone: tz)
        XCTAssertTrue(r.passesThrough)
        XCTAssertEqual(r.closestTime!.timeIntervalSince1970, t16.timeIntervalSince1970, accuracy: 60)
    }

    func testCloudForecast() throws {
        let json = #"{"hourly":{"time":[1790000000,1790003600],"cloud_cover":[10,90],"cloud_cover_low":[0,80],"precipitation_probability":[0,60]}}"#
        let h = try CloudForecast.parse(Data(json.utf8))
        XCTAssertEqual(h.count, 2)
        XCTAssertEqual(CloudForecast.sunChance(h[0]), "direct sun likely")
        XCTAssertEqual(CloudForecast.describe(h[1]), "Overcast (90% cloud, 80% low), rain 60% · direct sun unlikely (soft light)")
        XCTAssertEqual(CloudForecast.at(h, Date(timeIntervalSince1970: 1_790_003_601))?.cloud, 90)
    }

    // MARK: Lens character, phone lenses, viewfinder layout

    func testFocusAndDistortion() throws {
        let r = try XCTUnwrap(Focus.depthOfField(focalMm: 50, stop: 2.8, distanceM: 3, cocMm: 0.03))
        XCTAssertLessThan(r.nearM, 3); XCTAssertGreaterThan(r.farM, 3)
        XCTAssertEqual(Focus.distanceText(0.85), "85 cm")
        XCTAssertFalse(Distortion.points.isEmpty)
        let lens = try XCTUnwrap(Catalog.lens(id: "arri_signature_prime_47"))
        XCTAssertFalse(Distortion.hasProfile(lens))
        let k1 = Distortion.k1(cornerPercent: -2, widthMm: 23.1, heightMm: 12.99, focalMm: 47)
        Distortion.custom = [lens.id: [(47, k1)]]
        defer { Distortion.custom = [:] }
        XCTAssertTrue(Distortion.isCustom(lens))
        let m = try XCTUnwrap(Distortion.model(for: lens, focalMm: 47))
        XCTAssertEqual(Distortion.cornerPercent(m, widthMm: 23.1, heightMm: 12.99, focalMm: 47), -2, accuracy: 1e-6)
        let sigma = try XCTUnwrap(Catalog.lens(id: "sigma_art_18_35_f18"))
        XCTAssertNotNil(Distortion.model(for: sigma, focalMm: 24))
    }

    func testLensChoiceFor24mmPortrait() throws {
        // Pocket 6K Pro, 24 mm, 16:9, on a portrait phone (1080 × 2400 px points-ish): the main lens can't fit the
        // frame (the screen crops the picture's width); a 110° ultra-wide can.
        let cam = try XCTUnwrap(BuiltInLibrary.camera(id: "bmpcc_6k_pro"))
        let ref = try XCTUnwrap(Framing.reference(sensorWidthMm: cam.sensorWidthMm, sensorHeightMm: cam.sensorHeightMm, focalLengthMm: 24, aspectRatio: 16.0 / 9))
        let video = Monitor.aspectFill(contentAspect: 3.0 / 4, width: 393, height: 852)
        let area = Monitor.centredArea(width: 393, height: 852, side: 10, bar: 210)
        func fits(_ l: PhoneOptics.Lens) -> Bool {
            let v = PreviewView.fromFormat(longSideFov: l.longSideFov, longOverShort: 4.0 / 3, portrait: true)
            return Monitor.layout(reference: ref, view: v, video: video, area: area, minZoom: l.minZoom, maxZoom: l.maxZoom).lines.fits
        }
        let main = PhoneOptics.Lens(id: "wide", name: "Main", longSideFov: 72, source: .reported, minZoom: 1, maxZoom: 5, isDefault: true)
        let uw = PhoneOptics.Lens(id: "uw", name: "Ultra Wide", longSideFov: 110, source: .reported, minZoom: 1, maxZoom: 5, isDefault: false)
        XCTAssertFalse(fits(main))
        XCTAssertEqual(PhoneOptics.choose([main, uw], fits: fits)?.id, "uw")
        XCTAssertEqual(PhoneOptics.checked(nil).source, .assumed)
        XCTAssertEqual(PhoneOptics.checked(180).fov, 70)
    }

    // MARK: Search, place names, export

    func testSearchPlaceNamesAndExport() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func doc(_ name: String, _ place: String, ageDays: Int, gps: Bool, note: String) -> RecceSessionDoc {
            let shot = RecceShotDoc(id: UUID().uuidString, sceneId: "s", shotNumber: "1A", cameraModel: "", lensModel: "", focalLength: "35mm",
                                    aperture: "T2.8", notes: note, creationTimestamp: 0, modificationTimestamp: 0, plannedTime: 123, lut: "builtin:Warm")
            let scene = RecceSceneDoc(id: "s", sessionId: name, sceneNumber: "1", isInterior: false, locationDescription: place, shots: [shot])
            let ms = RecceJSON.millis(now) - Int64(ageDays) * 86_400_000
            return RecceSessionDoc(id: name, locationName: place, timestamp: ms, latitude: gps ? 28.5 : nil, longitude: gps ? 77.2 : nil,
                                   projectName: name, scenes: [scene], creationTimestamp: ms, modificationTimestamp: ms,
                                   skyline: [SkyPoint(azimuth: 90, elevation: 12.5)])
        }
        let all = [doc("Monsoon", "Hauz Khas", ageDays: 2, gps: true, note: "Backlit by the café window"),
                   doc("Ad film", "Gurgaon", ageDays: 20, gps: false, note: "Generator near parking")]
        var f = RecceSearch.Filters(); f.query = "cafe window"
        let r = RecceSearch.search(all, f, now: now)
        XCTAssertEqual(r.map(\.session.projectName), ["Monsoon"])
        XCTAssertTrue(r[0].matches.contains("Shot 1A notes"))
        var g = RecceSearch.Filters(); g.withLocation = true
        XCTAssertEqual(RecceSearch.search(all, g, now: now).count, 1)
        XCTAssertEqual(PlaceName.format(feature: "12", subLocality: "Hauz Khas", locality: "New Delhi", subAdmin: nil, admin: "Delhi", country: "India"), "Hauz Khas, New Delhi")
        XCTAssertEqual(PlaceName.format(feature: "8Q7J+2M", subLocality: nil, locality: "Shimla", subAdmin: nil, admin: "Himachal Pradesh", country: "India"), "Shimla, Himachal Pradesh")
        // New fields survive JSON (and old files without them still read).
        let back = try RecceJSON.decode(RecceJSON.encode(all[0]))
        XCTAssertEqual(back.skyline, all[0].skyline)
        XCTAssertEqual(back.scenes[0].shots[0].plannedTime, 123)
        XCTAssertEqual(back.scenes[0].shots[0].lut, "builtin:Warm")
        // Android export keys read in.
        let android = #"{"id":"a","locationName":"L","timestamp":1,"projectName":"P","directorDopNotes":"","generalLocationNotes":"","scenes":[],"creationTimestamp":1,"modificationTimestamp":1,"skyline":[{"azimuth":10,"elevation":5}]}"#
        XCTAssertEqual(try RecceJSON.decode(Data(android.utf8)).skyline?.first?.elevation, 5)
    }
}
