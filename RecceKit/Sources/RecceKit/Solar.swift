import Foundation

public struct SolarPosition: Equatable, Sendable {
    /// Degrees clockwise from true north.
    public var azimuth: Double
    /// Degrees above the horizon, geometric (no atmospheric refraction).
    public var elevation: Double
    public var compass: String { Solar.compass(azimuth) }
    /// The heading to point the camera so the sun is directly behind the subject.
    /// Camera heading for backlight: towards the sun, so the sun is behind the subject.
    public var backlightHeading: Double { (azimuth.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) }
}

/// Sun times for one local calendar day. Any value is nil when it doesn't happen that day
/// (polar day / polar night).
public struct SunDay: Equatable, Sendable {
    public var sunrise: Date?
    public var sunset: Date?
    public var solarNoon: Date
    public var noonElevation: Double
    public var goldenMorning: ClosedRange<Date>?
    public var goldenEvening: ClosedRange<Date>?
    public var blueMorning: ClosedRange<Date>?
    public var blueEvening: ClosedRange<Date>?
    public var isPolarDay: Bool { sunrise == nil && sunset == nil && noonElevation > 0 }
    public var isPolarNight: Bool { sunrise == nil && sunset == nil && noonElevation <= 0 }
}

/// NOAA solar position algorithm (the one behind NOAA's Solar Calculator). Accurate to well under a minute
/// for sunrise/sunset between ±72° latitude. Replaces the Android approximation, which ignored the equation of
/// time (up to ±16 min) and defined golden hour as "sunset − 1 h".
public enum Solar {
    /// Sunrise/sunset: centre of the sun 0.833° below the horizon (refraction + solar radius).
    public static let horizonElevation = -0.833
    /// Golden hour: sun between 6° above and 4° below the horizon. Blue hour: 4° to 6° below.
    public static let goldenHigh = 6.0
    public static let goldenLow = -4.0
    public static let blueLow = -6.0

    private static func rad(_ d: Double) -> Double { d * .pi / 180 }
    private static func deg(_ r: Double) -> Double { r * 180 / .pi }
    private static func mod(_ a: Double, _ m: Double) -> Double { let r = a.truncatingRemainder(dividingBy: m); return r < 0 ? r + m : r }

    /// (declination degrees, equation of time minutes) at a Julian century.
    private static func declinationAndEoT(_ t: Double) -> (Double, Double) {
        let l0 = mod(280.46646 + t * (36000.76983 + t * 0.0003032), 360)
        let m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        let e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        let c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t))
            + sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        let omega = 125.04 - 1934.136 * t
        let appLong = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
        let eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        let eps = eps0 + 0.00256 * cos(rad(omega))
        let decl = deg(asin(sin(rad(eps)) * sin(rad(appLong))))
        let y = pow(tan(rad(eps / 2)), 2)
        let eot = 4 * deg(y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0))
                          - 0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)))
        return (decl, eot)
    }

    private static func julianCentury(_ date: Date) -> Double {
        let jd = date.timeIntervalSince1970 / 86400 + 2440587.5
        return (jd - 2451545) / 36525
    }

    public static func position(at date: Date, latitude: Double, longitude: Double) -> SolarPosition {
        let (decl, eot) = declinationAndEoT(julianCentury(date))
        let utcMinutes = mod(date.timeIntervalSince1970 / 60, 1440)
        let trueSolarTime = mod(utcMinutes + eot + 4 * longitude, 1440)
        var hourAngle = trueSolarTime / 4 - 180
        if hourAngle < -180 { hourAngle += 360 }
        let lat = rad(latitude), dec = rad(decl)
        let cosZen = max(-1, min(1, sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(rad(hourAngle))))
        let zenith = deg(acos(cosZen))
        var azimuth: Double
        let denom = cos(lat) * sin(rad(zenith))
        if abs(denom) > 1e-9 {
            let a = deg(acos(max(-1, min(1, (sin(lat) * cos(rad(zenith)) - sin(dec)) / denom))))
            azimuth = hourAngle > 0 ? mod(a + 180, 360) : mod(540 - a, 360)
        } else {
            azimuth = latitude > 0 ? 180 : 0
        }
        return SolarPosition(azimuth: azimuth, elevation: 90 - zenith)
    }

    /// Times within [start, end) where the elevation crosses `target`, upward (rising) or downward.
    static func crossings(of target: Double, from start: Date, to end: Date, latitude: Double, longitude: Double,
                          rising: Bool) -> [Date] {
        let f: (Date) -> Double = { position(at: $0, latitude: latitude, longitude: longitude).elevation - target }
        let step: TimeInterval = 600
        var out: [Date] = []
        var a = start, fa = f(a)
        while a < end {
            let b = min(a.addingTimeInterval(step), end)
            let fb = f(b)
            if (rising && fa < 0 && fb >= 0) || (!rising && fa > 0 && fb <= 0) {
                var lo = a, hi = b, flo = fa
                for _ in 0..<40 {                         // bisection to well under a second
                    let mid = Date(timeIntervalSince1970: (lo.timeIntervalSince1970 + hi.timeIntervalSince1970) / 2)
                    let fm = f(mid)
                    if (fm < 0) == (flo < 0) { lo = mid; flo = fm } else { hi = mid }
                }
                out.append(Date(timeIntervalSince1970: (lo.timeIntervalSince1970 + hi.timeIntervalSince1970) / 2))
            }
            a = b; fa = fb
        }
        return out
    }

    /// Sun times for the local calendar day containing `date` in `timeZone`.
    public static func day(for date: Date, latitude: Double, longitude: Double, timeZone: TimeZone) -> SunDay {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let start = cal.startOfDay(for: date)
        let end = cal.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86400)
        let first: (Double, Bool) -> Date? = { target, rising in
            crossings(of: target, from: start, to: end, latitude: latitude, longitude: longitude, rising: rising).first
        }
        let last: (Double, Bool) -> Date? = { target, rising in
            crossings(of: target, from: start, to: end, latitude: latitude, longitude: longitude, rising: rising).last
        }
        // Solar noon: the sun crosses the meridian (hour angle 0). NOAA: UTC minutes = 720 - 4·longitude - EoT,
        // iterated so the equation of time is taken at the noon instant itself.
        let utcMidnight = floor(start.timeIntervalSince1970 / 86400) * 86400
        var noonMinutes = 720 - 4 * longitude
        for _ in 0..<3 {
            let guess = Date(timeIntervalSince1970: utcMidnight + noonMinutes * 60)
            noonMinutes = 720 - 4 * longitude - declinationAndEoT(julianCentury(guess)).1
        }
        var noon = Date(timeIntervalSince1970: utcMidnight + noonMinutes * 60)
        if noon < start { noon = noon.addingTimeInterval(86400) }      // keep it inside the local day
        if noon >= end { noon = noon.addingTimeInterval(-86400) }
        let noonEl = position(at: noon, latitude: latitude, longitude: longitude).elevation

        func range(_ a: Date?, _ b: Date?) -> ClosedRange<Date>? {
            guard let a, let b, a <= b else { return nil }
            return a...b
        }
        return SunDay(
            sunrise: first(horizonElevation, true),
            sunset: last(horizonElevation, false),
            solarNoon: noon, noonElevation: noonEl,
            goldenMorning: range(first(goldenLow, true), first(goldenHigh, true)),
            goldenEvening: range(last(goldenHigh, false), last(goldenLow, false)),
            blueMorning: range(first(blueLow, true), first(goldenLow, true)),
            blueEvening: range(last(goldenLow, false), last(blueLow, false)))
    }

    /// 8-point compass name, same labels and boundaries as the Android app.
    public static func compass(_ degrees: Double) -> String {
        let names = ["North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West"]
        let n = mod(degrees, 360)
        return names[Int((n + 22.5) / 45) % 8]
    }
}
