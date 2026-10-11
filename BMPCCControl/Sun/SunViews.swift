import SwiftUI
import RecceKit

/// Sun texts shared by the planner and the camera view (same wording as the Android app).
enum SunText {
    private static let clockFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    /// "16:30" in this device's time zone.
    static func clock(_ d: Date) -> String { clockFormatter.string(from: d) }

    /// "245° SW, 18° high" for a sun position.
    static func position(_ p: SolarPosition) -> String {
        p.elevation < Solar.horizonElevation
            ? String(format: "below the horizon (%.0f°)", p.elevation)
            : String(format: "%.0f° %@, %.0f° high", p.azimuth, p.compass, p.elevation)
    }

    static let gold = Color(red: 1, green: 0.82, blue: 0.48)
    static let golden = Color(red: 1, green: 0.62, blue: 0.04)
}

/// Polar sun-path diagram: centre = straight up, edge = horizon, north at the top. Dots every hour, golden hour in
/// orange, the recorded skyline in grey.
struct SunDial: View {
    let latitude: Double
    let longitude: Double
    let time: Date
    var skyline: Skyline?

    var body: some View {
        let path = SunPlan.path(time, latitude: latitude, longitude: longitude, timeZone: .current, stepMinutes: 5)
        let day = Solar.day(for: time, latitude: latitude, longitude: longitude, timeZone: .current)
        let now = Solar.position(at: time, latitude: latitude, longitude: longitude)
        let rise = day.sunrise.map { Solar.position(at: $0, latitude: latitude, longitude: longitude) }
        let set = day.sunset.map { Solar.position(at: $0, latitude: latitude, longitude: longitude) }
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let r = min(size.width, size.height) / 2 - 22
            func pt(_ az: Double, _ el: Double) -> CGPoint {
                let rr = r * CGFloat(90 - min(max(el, 0), 90)) / 90
                let a = az * .pi / 180
                return CGPoint(x: c.x + rr * CGFloat(sin(a)), y: c.y - rr * CGFloat(cos(a)))
            }
            func disc(_ p: CGPoint, _ radius: CGFloat) -> Path { Path(ellipseIn: CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2)) }
            let ring = Color.white.opacity(0.33)
            for el in [0.0, 30, 60] {
                let rr = r * CGFloat(90 - el) / 90
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: rr * 2, height: rr * 2)), with: .color(ring), lineWidth: 1)
            }
            for az in stride(from: 0.0, to: 360, by: 45) {
                var p = Path(); p.move(to: c); p.addLine(to: pt(az, 0))
                ctx.stroke(p, with: .color(ring), lineWidth: 1)
            }
            // The skyline: from the horizon up to the recorded tops.
            if let skyline {
                var sky = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
                for az in 0...360 {
                    let p = pt(Double(az), max(skyline.elevation(Double(az)) ?? 0, 0))
                    if az == 0 { sky.move(to: p) } else { sky.addLine(to: p) }
                }
                sky.closeSubpath()
                ctx.fill(sky, with: .color(Color(red: 0.42, green: 0.48, blue: 0.6).opacity(0.55)), style: FillStyle(eoFill: true))
            }
            for (label, az) in [("N", 0.0), ("E", 90.0), ("S", 180.0), ("W", 270.0)] {
                let p = pt(az, 0)
                let q = CGPoint(x: c.x + (p.x - c.x) * 1.12, y: c.y + (p.y - c.y) * 1.12)
                ctx.draw(Text(label).font(.caption.bold()).foregroundColor(.white), at: q)
            }
            // The day's path above the horizon; golden hour in orange; hour marks.
            let up = path.filter { $0.position.elevation >= 0 }
            if up.count > 1 {
                var line = Path()
                for (i, s) in up.enumerated() {
                    let p = pt(s.position.azimuth, s.position.elevation)
                    if i == 0 { line.move(to: p) } else { line.addLine(to: p) }
                }
                ctx.stroke(line, with: .color(SunText.gold), lineWidth: 2)
                for s in up where s.position.elevation <= Solar.goldenHigh {
                    ctx.fill(disc(pt(s.position.azimuth, s.position.elevation), 3), with: .color(SunText.golden))
                }
                let cal = Calendar.current
                for s in up where cal.component(.minute, from: s.time) == 0 {
                    let p = pt(s.position.azimuth, s.position.elevation)
                    ctx.fill(disc(p, 2.5), with: .color(.white))
                    ctx.draw(Text("\(cal.component(.hour, from: s.time))").font(.system(size: 9)).foregroundColor(.white.opacity(0.8)),
                             at: CGPoint(x: p.x + 4, y: p.y - 6), anchor: .bottomLeading)
                }
            }
            if let rise { ctx.fill(disc(pt(rise.azimuth, 0), 5), with: .color(SunText.golden)) }
            if let set { ctx.fill(disc(pt(set.azimuth, 0), 5), with: .color(Color(red: 1, green: 0.42, blue: 0.24))) }
            if now.elevation > Solar.horizonElevation {
                let p = pt(now.azimuth, now.elevation)
                ctx.fill(disc(p, 9), with: .color(Color(red: 1, green: 0.88, blue: 0.4)))
                ctx.stroke(disc(p, 9), with: .color(.white), lineWidth: 2)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .background(Color(red: 0.05, green: 0.08, blue: 0.16), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Hourly cloud forecast from Open-Meteo (free, no key; needs internet). Cached for 30 minutes per place.
actor WeatherService {
    static let shared = WeatherService()
    private var cache: [String: (at: Date, hours: [CloudForecast.Hour])] = [:]

    func forecast(latitude: Double, longitude: Double) async throws -> [CloudForecast.Hour] {
        let key = String(format: "%.2f,%.2f", latitude, longitude)
        if let c = cache[key], Date().timeIntervalSince(c.at) < 30 * 60 { return c.hours }
        var request = URLRequest(url: CloudForecast.url(latitude: latitude, longitude: longitude), timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { throw URLError(.badServerResponse) }
        let hours = try CloudForecast.parse(data)
        cache[key] = (Date(), hours)
        return hours
    }
}

/// The cloud forecast for the chosen hour and day. Off until the user turns it on (it sends the location to Open-Meteo).
struct WeatherCard: View {
    let latitude: Double
    let longitude: Double
    let time: Date
    @AppStorage("sun.weatherOn") private var weatherOn = false
    @State private var hours: [CloudForecast.Hour]?
    @State private var loadedFor = ""
    @State private var error: String?
    @State private var retry = 0

    var body: some View {
        let place = String(format: "%.3f,%.3f", latitude, longitude)
        VStack(alignment: .leading, spacing: 6) {
            if !weatherOn {
                Text("Cloud forecast").font(.headline)
                Text("Hourly cloud cover for the next 16 days from Open-Meteo (free). Sends this location to their server; needs internet.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Get the cloud forecast") { weatherOn = true }.buttonStyle(.borderless)
            } else {
                let list = loadedFor == place ? hours : nil
                if let error {
                    Text(error).font(.callout).foregroundStyle(.secondary)
                    Button("Try again") { retry += 1 }.buttonStyle(.borderless)
                } else if let list {
                    if let h = CloudForecast.at(list, time) {
                        Text("\(SunText.clock(h.time))  \(CloudForecast.describe(h))").font(.callout)
                        CloudStrip(hours: list, time: time).frame(height: 44)
                    } else {
                        Text("No forecast for this time: forecasts cover the next \(CloudForecast.days) days.").font(.callout).foregroundStyle(.secondary)
                    }
                } else {
                    Text("Getting the forecast…").font(.callout).foregroundStyle(.secondary)
                }
                HStack {
                    Text(CloudForecast.attribution).font(.caption2).foregroundStyle(.secondary)
                    Spacer()
                    Button("Turn off") { weatherOn = false }.font(.caption).buttonStyle(.borderless)
                }
            }
        }
        .task(id: "\(place)|\(retry)|\(weatherOn)") {
            guard weatherOn else { return }
            error = nil
            do {
                let result = try await WeatherService.shared.forecast(latitude: latitude, longitude: longitude)
                hours = result
                loadedFor = place
            } catch {
                if Task.isCancelled { return }
                self.error = "Couldn't get the forecast. Check the internet connection."
            }
        }
    }
}

/// The chosen day's cloud cover hour by hour (taller = cloudier), the chosen hour highlighted.
struct CloudStrip: View {
    let hours: [CloudForecast.Hour]
    let time: Date

    var body: some View {
        let (start, end) = SunPlan.dayBounds(time, timeZone: .current)
        let day = hours.filter { $0.time >= start && $0.time < end }
        Canvas { ctx, size in
            let w = size.width / 24
            for h in day {
                let i = min(max(Int(h.time.timeIntervalSince(start) / 3600), 0), 23)
                let bh = max(size.height * CGFloat(h.cloud) / 100, 1)
                let selected = time >= h.time && time < h.time.addingTimeInterval(3600)
                ctx.fill(Path(CGRect(x: CGFloat(i) * w + 1, y: size.height - bh, width: w - 2, height: bh)),
                         with: .color(selected ? SunText.gold : Color(red: 0.69, green: 0.75, blue: 0.9).opacity(0.6)))
            }
        }
    }
}
