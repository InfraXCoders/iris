import XCTest
@testable import RecceKit

final class SolarTests: XCTestCase {
    /// Positions agree with the astral library (independent NOAA implementation) to 0.05°.
    func testPositionsMatchReference() {
        for g in Fixtures.list(Fixtures.sun, "positions") {
            let p = Solar.position(at: Date(timeIntervalSince1970: g.d("epoch")), latitude: g.d("lat"), longitude: g.d("lon"))
            XCTAssertEqual(p.elevation, g.d("elevation"), accuracy: 0.05, "\(g.s("place")) \(g.d("epoch"))")
            if g.d("elevation") > -85 {      // azimuth is ill-defined right at the zenith/nadir
                let diff = abs(((p.azimuth - g.d("azimuth")) + 540).truncatingRemainder(dividingBy: 360) - 180)
                XCTAssertLessThan(diff, 0.1, "\(g.s("place")) \(g.d("epoch"))")
            }
        }
    }

    /// Sunrise/sunset within 1 minute; golden/blue hour edges within 3 minutes (astral applies a refraction model at
    /// those elevations, we use geometric elevation; the difference is about a minute).
    func testDayEventsMatchReference() {
        for g in Fixtures.list(Fixtures.sun, "events") {
            let tz = TimeZone(identifier: g.s("tz"))!
            let noonish = Date(timeIntervalSince1970: g.d("noon"))
            let day = Solar.day(for: noonish, latitude: g.d("lat"), longitude: g.d("lon"), timeZone: tz)
            let label = "\(g.s("place")) \(g.s("date"))"
            func close(_ d: Date?, _ key: String, _ tol: Double, file: StaticString = #filePath, line: UInt = #line) {
                guard let d else { return XCTFail("\(label): \(key) missing", file: file, line: line) }
                XCTAssertEqual(d.timeIntervalSince1970, g.d(key), accuracy: tol, "\(label) \(key)", file: file, line: line)
            }
            close(day.sunrise, "sunrise", 60)
            close(day.sunset, "sunset", 60)
            close(day.solarNoon, "noon", 30)
            close(day.goldenMorning?.lowerBound, "goldenMorningStart", 180)
            close(day.goldenMorning?.upperBound, "goldenMorningEnd", 180)
            close(day.goldenEvening?.lowerBound, "goldenEveningStart", 180)
            close(day.goldenEvening?.upperBound, "goldenEveningEnd", 180)
            close(day.blueEvening?.lowerBound, "blueEveningStart", 180)
            close(day.blueEvening?.upperBound, "blueEveningEnd", 180)
        }
    }

    func testAndroidSunTimesWereOffForDelhiMidsummer() {
        // Android: sunset "7:15 PM", golden hour "6:15 PM" (= sunset - 1 h). Reference: 19:21 and 18:48 IST.
        let android = Fixtures.list(Fixtures.android, "androidSun")[0]
        XCTAssertEqual(android.s("sunset"), "7:15 PM")
        XCTAssertEqual(android.s("goldenStart"), "6:15 PM")
        let ist = TimeZone(identifier: "Asia/Kolkata")!
        var cal = Calendar(identifier: .gregorian); cal.timeZone = ist
        let date = cal.date(from: DateComponents(year: 2026, month: 6, day: 21, hour: 12))!
        let day = Solar.day(for: date, latitude: 28.6139, longitude: 77.2090, timeZone: ist)
        let hm: (Date) -> String = { d in let c = cal.dateComponents([.hour, .minute], from: d); return String(format: "%02d:%02d", c.hour!, c.minute!) }
        XCTAssertEqual(hm(day.sunset!), "19:21")
        // 18:47-18:48: the 6° golden-hour edge, within the ~1 min refraction difference to the reference.
        XCTAssertTrue(["18:47", "18:48"].contains(hm(day.goldenEvening!.lowerBound)))
    }

    func testPolarNightAndDay() {
        let utc = TimeZone(identifier: "UTC")!
        var cal = Calendar(identifier: .gregorian); cal.timeZone = utc
        let midwinter = cal.date(from: DateComponents(year: 2026, month: 12, day: 21, hour: 12))!
        let midsummer = cal.date(from: DateComponents(year: 2026, month: 6, day: 21, hour: 12))!
        let night = Solar.day(for: midwinter, latitude: 78.22, longitude: 15.65, timeZone: utc)   // Svalbard
        XCTAssertTrue(night.isPolarNight)
        XCTAssertNil(night.sunrise)
        XCTAssertNil(night.goldenEvening)
        let day = Solar.day(for: midsummer, latitude: 78.22, longitude: 15.65, timeZone: utc)
        XCTAssertTrue(day.isPolarDay)
    }

    func testCompassMatchesAndroidLabels() {
        XCTAssertEqual(Solar.compass(0), "North")
        XCTAssertEqual(Solar.compass(22.4), "North")
        XCTAssertEqual(Solar.compass(22.5), "North-East")
        XCTAssertEqual(Solar.compass(180), "South")
        XCTAssertEqual(Solar.compass(-90), "West")
        XCTAssertEqual(Solar.compass(359), "North")
        XCTAssertEqual(SolarPosition(azimuth: 290, elevation: 5).backlightHeading, 110)
    }
}
