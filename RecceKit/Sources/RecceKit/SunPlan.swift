import Foundation

/// A direction or vector in the local East-North-Up frame.
public struct Vec3: Equatable, Sendable {
    public var x: Double, y: Double, z: Double
    public init(_ x: Double, _ y: Double, _ z: Double) { self.x = x; self.y = y; self.z = z }
    public static func + (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x + b.x, a.y + b.y, a.z + b.z) }
    public static func * (a: Vec3, k: Double) -> Vec3 { Vec3(a.x * k, a.y * k, a.z * k) }
    public func dot(_ o: Vec3) -> Double { x * o.x + y * o.y + z * o.z }
    public var length: Double { dot(self).squareRoot() }
    public func normalized() -> Vec3 { let l = length; return l < 1e-12 ? self : Vec3(x / l, y / l, z / l) }
}

/// Planning with the sun: its path over a day, where it is at any time, drawing it over the camera picture, when it
/// lines up with (or rises above) a point the user aims at, and direct sun once buildings / hills (the recorded
/// `Skyline`) are taken into account. Same maths as the Android app.
public enum SunPlan {
    public struct Sample: Equatable, Sendable { public var time: Date; public var position: SolarPosition }

    private static func rad(_ d: Double) -> Double { d * .pi / 180 }
    private static func deg(_ r: Double) -> Double { r * 180 / .pi }

    /// Start and end of the local calendar day containing `date`.
    public static func dayBounds(_ date: Date, timeZone: TimeZone) -> (start: Date, end: Date) {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = timeZone
        let start = cal.startOfDay(for: date)
        return (start, cal.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400))
    }

    /// Sun positions through the local day, every `stepMinutes`.
    public static func path(_ date: Date, latitude: Double, longitude: Double, timeZone: TimeZone, stepMinutes: Int = 10) -> [Sample] {
        let (start, end) = dayBounds(date, timeZone: timeZone)
        var out: [Sample] = []
        var t = start
        while t <= end {
            out.append(Sample(time: t, position: Solar.position(at: t, latitude: latitude, longitude: longitude)))
            t = t.addingTimeInterval(Double(stepMinutes) * 60)
        }
        return out
    }

    /// Unit vector (East, North, Up) for an azimuth (clockwise from north) and elevation, degrees.
    public static func vector(azimuth: Double, elevation: Double) -> Vec3 {
        let a = rad(azimuth), e = rad(elevation)
        return Vec3(sin(a) * cos(e), cos(a) * cos(e), sin(e))
    }

    /// Azimuth and elevation (degrees) of a direction.
    public static func direction(_ v: Vec3) -> (azimuth: Double, elevation: Double) {
        let n = v.normalized()
        return ((deg(atan2(n.x, n.y)) + 360).truncatingRemainder(dividingBy: 360), deg(asin(min(max(n.z, -1), 1))))
    }

    public static func angleBetween(_ az1: Double, _ el1: Double, _ az2: Double, _ el2: Double) -> Double {
        deg(acos(min(max(vector(azimuth: az1, elevation: el1).dot(vector(azimuth: az2, elevation: el2)), -1), 1)))
    }

    /// Signed smallest difference a − b in degrees (−180..180).
    public static func azimuthDifference(_ a: Double, _ b: Double) -> Double {
        let d = (a - b + 540).truncatingRemainder(dividingBy: 360)
        return (d < 0 ? d + 360 : d) - 180
    }

    /// When the sun meets a point the user aimed at (a window, a gap between buildings, a rooftop edge).
    public struct SpotReport: Sendable {
        public var closestTime: Date?
        public var closestDegrees: Double
        /// Times the sun passes the point's compass direction, with the sun's elevation minus the point's.
        public var sameDirection: [(Date, Double)]
        /// The sun is higher than the point from `aboveFrom` to `aboveUntil` (nil = not that day).
        public var aboveFrom: Date?
        public var aboveUntil: Date?
        /// Within 2°: the sun's disc passes the point itself.
        public var passesThrough: Bool { closestTime != nil && closestDegrees <= 2 }
    }

    public static func spot(azimuth: Double, elevation: Double, date: Date, latitude: Double, longitude: Double, timeZone: TimeZone) -> SpotReport {
        let (start, end) = dayBounds(date, timeZone: timeZone)
        func pos(_ t: Date) -> SolarPosition { Solar.position(at: t, latitude: latitude, longitude: longitude) }
        let samples = path(date, latitude: latitude, longitude: longitude, timeZone: timeZone, stepMinutes: 2)
            .filter { $0.position.elevation > Solar.horizonElevation }
        var best: Sample?, bestD = Double.greatestFiniteMagnitude
        for s in samples {
            let d = angleBetween(s.position.azimuth, s.position.elevation, azimuth, elevation)
            if d < bestD { bestD = d; best = s }
        }
        var closest = best?.time
        if let b = best {
            var t = b.time.addingTimeInterval(-120)
            while t <= b.time.addingTimeInterval(120) {
                let p = pos(t), d = angleBetween(p.azimuth, p.elevation, azimuth, elevation)
                if d < bestD, p.elevation > Solar.horizonElevation { bestD = d; closest = t }
                t = t.addingTimeInterval(10)
            }
        }
        var same: [(Date, Double)] = []
        if samples.count > 1 {
            for i in 1..<samples.count {
                let a = samples[i - 1], b = samples[i]
                if b.time.timeIntervalSince(a.time) > 150 { continue }
                let da = azimuthDifference(a.position.azimuth, azimuth), db = azimuthDifference(b.position.azimuth, azimuth)
                if abs(da) < 90, abs(db) < 90, da == 0 || (da < 0) != (db < 0) {
                    var lo = a.time.timeIntervalSince1970, hi = b.time.timeIntervalSince1970, flo = da
                    for _ in 0..<25 {
                        let mid = (lo + hi) / 2
                        let fm = azimuthDifference(pos(Date(timeIntervalSince1970: mid)).azimuth, azimuth)
                        if (fm < 0) == (flo < 0) { lo = mid; flo = fm } else { hi = mid }
                    }
                    let t = Date(timeIntervalSince1970: (lo + hi) / 2)
                    same.append((t, pos(t).elevation - elevation))
                }
            }
        }
        let target = max(elevation, Solar.horizonElevation)
        let rise = Solar.crossings(of: target, from: start, to: end, latitude: latitude, longitude: longitude, rising: true).first
        let set = Solar.crossings(of: target, from: start, to: end, latitude: latitude, longitude: longitude, rising: false).last
        let noonHigher = samples.contains { $0.position.elevation > target }
        return SpotReport(closestTime: closest, closestDegrees: closest == nil ? .greatestFiniteMagnitude : bestD, sameDirection: same,
                          aboveFrom: noonHigher ? (rise ?? start) : nil, aboveUntil: noonHigher ? (set ?? end) : nil)
    }

    /// Atmospheric refraction (degrees) for a geometric elevation (Sæmundsson's formula, standard conditions).
    public static func refraction(_ elevation: Double) -> Double {
        let h = max(elevation, -1)
        return 1.02 / tan(rad(h + 10.3 / (h + 5.11))) / 60
    }

    /// Half the sun's apparent diameter, degrees: the sun is "out" once its top edge clears the skyline.
    public static let sunRadius = 0.27

    public static func behindSkyline(_ p: SolarPosition, _ skyline: Skyline?) -> Bool {
        guard p.elevation > Solar.horizonElevation, let s = skyline, let top = s.elevation(p.azimuth) else { return false }
        return p.elevation + refraction(p.elevation) + sunRadius <= top
    }

    public static func directSun(_ p: SolarPosition, _ skyline: Skyline?) -> Bool { p.elevation > Solar.horizonElevation && !behindSkyline(p, skyline) }

    /// Periods of direct sun at the spot over the local day of `date`, refined to ~10 s.
    public static func directSunPeriods(_ date: Date, latitude: Double, longitude: Double, timeZone: TimeZone, skyline: Skyline?) -> [(Date, Date)] {
        let (start, end) = dayBounds(date, timeZone: timeZone)
        func lit(_ t: Double) -> Bool { directSun(Solar.position(at: Date(timeIntervalSince1970: t), latitude: latitude, longitude: longitude), skyline) }
        func edge(_ a: Double, _ b: Double, _ litAtA: Bool) -> Double {
            var lo = a, hi = b
            while hi - lo > 10 { let mid = (lo + hi) / 2; if lit(mid) == litAtA { lo = mid } else { hi = mid } }
            return (lo + hi) / 2
        }
        var out: [(Date, Date)] = []
        let e = end.timeIntervalSince1970
        var t = start.timeIntervalSince1970
        var prev = lit(t)
        var from: Double? = prev ? t : nil
        while t < e {
            let n = min(t + 120, e)
            let now = lit(n)
            if now != prev {
                let x = edge(t, n, prev)
                if now { from = x } else if let f = from { out.append((Date(timeIntervalSince1970: f), Date(timeIntervalSince1970: x))); from = nil }
            }
            t = n; prev = now
        }
        if let f = from { out.append((Date(timeIntervalSince1970: f), end)) }
        return out
    }
}

/// Where a world direction appears on screen for a camera oriented by forward/right/up (East-North-Up). Normalised
/// screen coordinates (−1...1 across the half-widths; y up), or nil behind the camera.
public enum SkyProjection {
    public static func project(_ d: Vec3, forward: Vec3, right: Vec3, up: Vec3, tanHalfH: Double, tanHalfV: Double) -> (Double, Double)? {
        let z = d.dot(forward)
        guard z > 1e-6, tanHalfH > 0, tanHalfV > 0 else { return nil }
        return (d.dot(right) / z / tanHalfH, d.dot(up) / z / tanHalfV)
    }
}

/// One recorded point of the skyline: compass direction (true north) and elevation, degrees.
public struct SkyPoint: Codable, Equatable, Sendable {
    public var azimuth: Double
    public var elevation: Double
    public init(azimuth: Double, elevation: Double) { self.azimuth = azimuth; self.elevation = elevation }
}

/// Tops of buildings, trees and hills around a spot, recorded by sweeping the camera along them: elevation by
/// compass direction in 1° steps. Gaps up to `maxGap`° are bridged; unrecorded directions count as open sky.
public struct Skyline: Sendable {
    private var bins = [Double](repeating: .nan, count: 360)
    public static let maxGap = 15

    public init(_ points: [SkyPoint]) {
        for p in points {
            let i = Skyline.bin(p.azimuth)
            bins[i] = bins[i].isNaN ? p.elevation : max(bins[i], p.elevation)
        }
    }

    public var isEmpty: Bool { bins.allSatisfy(\.isNaN) }
    public var recordedDegrees: Int { bins.filter { !$0.isNaN }.count }

    public func elevation(_ azimuth: Double) -> Double? {
        let i = Skyline.bin(azimuth)
        if !bins[i].isNaN { return bins[i] }
        var l = -1, r = -1
        for k in 1...Skyline.maxGap where !bins[(i - k + 360) % 360].isNaN { l = k; break }
        for k in 1...Skyline.maxGap where !bins[(i + k) % 360].isNaN { r = k; break }
        guard l > 0, r > 0, l + r <= Skyline.maxGap else { return nil }
        let a = bins[(i - l + 360) % 360], b = bins[(i + r) % 360]
        return a + (b - a) * Double(l) / Double(l + r)
    }

    public func points() -> [SkyPoint] { bins.indices.filter { !bins[$0].isNaN }.map { SkyPoint(azimuth: Double($0), elevation: bins[$0]) } }

    public static func bin(_ azimuth: Double) -> Int { ((Int(azimuth.rounded()) % 360) + 360) % 360 }
}

/// Hourly cloud forecast (Open-Meteo, free, no key) and what it means for direct sun.
public enum CloudForecast {
    public struct Hour: Equatable, Sendable {
        public var time: Date, cloud: Int, cloudLow: Int?, rainChance: Int?
    }

    public static let attribution = "Weather data by Open-Meteo.com (CC BY 4.0)"
    public static let days = 16

    public static func url(latitude: Double, longitude: Double) -> URL {
        URL(string: String(format: "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=cloud_cover,cloud_cover_low,precipitation_probability&timeformat=unixtime&forecast_days=16&timezone=GMT",
                           latitude, longitude))!
    }

    public static func parse(_ data: Data) throws -> [Hour] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hourly = root["hourly"] as? [String: Any] else { throw CubeParser.CubeError.message("Not a forecast") }
        let times = hourly["time"] as? [Any] ?? []
        func ints(_ k: String) -> [Int?] { (hourly[k] as? [Any])?.map { ($0 as? NSNumber)?.intValue } ?? [] }
        let cloud = ints("cloud_cover"), low = ints("cloud_cover_low"), rain = ints("precipitation_probability")
        return times.indices.compactMap { i in
            guard let t = (times[i] as? NSNumber)?.doubleValue, i < cloud.count, let c = cloud[i] else { return nil }
            return Hour(time: Date(timeIntervalSince1970: t), cloud: c, cloudLow: i < low.count ? low[i] : nil, rainChance: i < rain.count ? rain[i] : nil)
        }
    }

    /// The forecast hour containing `time`.
    public static func at(_ hours: [Hour], _ time: Date) -> Hour? {
        guard let h = hours.last(where: { $0.time <= time }), time.timeIntervalSince(h.time) < 3600 else { return nil }
        return h
    }

    public static func sky(_ cloud: Int) -> String { cloud < 20 ? "Clear" : cloud < 50 ? "Partly cloudy" : cloud < 85 ? "Mostly cloudy" : "Overcast" }

    public static func sunChance(_ h: Hour) -> String {
        let low = h.cloudLow ?? h.cloud
        if h.cloud < 30 && low < 20 { return "direct sun likely" }
        if low < 50 && h.cloud < 80 { return "sun may come and go" }
        return "direct sun unlikely (soft light)"
    }

    public static func describe(_ h: Hour) -> String {
        var s = "\(sky(h.cloud)) (\(h.cloud)% cloud"
        if let l = h.cloudLow { s += ", \(l)% low" }
        s += ")"
        if let r = h.rainChance { s += ", rain \(r)%" }
        return s + " · " + sunChance(h)
    }
}
