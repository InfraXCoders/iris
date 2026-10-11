import SwiftUI
import SwiftData
import RecceKit

/// Sun planner: pick a date and slide the time; see where the sun is, its path over the day, golden and blue hour,
/// the cloud forecast, when buildings and hills block it, and open the camera view (AR, iPhone). From a shot,
/// "Use for shot" saves the time with it. Same as the Android app's planner.
struct SunPlannerView: View {
    let session: RecceSession?
    let shot: RecceShot?
    @Environment(\.modelContext) private var context
    @StateObject private var locator = LocationProvider()
    @State private var time: Date
    @State private var latitude: Double?
    @State private var longitude: Double?
    @State private var skyline: [SkyPoint]
    @State private var status: String?
    @State private var showAR = false

    init(session: RecceSession? = nil, shot: RecceShot? = nil) {
        self.session = session
        self.shot = shot
        _time = State(initialValue: shot?.plannedTime ?? .now)
        _latitude = State(initialValue: session?.latitude)
        _longitude = State(initialValue: session?.longitude)
        _skyline = State(initialValue: session?.skyline ?? [])
    }

    private var sky: Skyline? { let s = Skyline(skyline); return s.isEmpty ? nil : s }

    var body: some View {
        Form {
            locationSection
            if let lat = latitude, let lon = longitude {
                timeSection(lat, lon)
                actionsSection
                Section("Weather") { WeatherCard(latitude: lat, longitude: lon, time: time) }
                skylineSection(lat, lon)
                Section {
                    SunDial(latitude: lat, longitude: lon, time: time, skyline: sky)
                } header: { Text("Sun path") } footer: {
                    Text("Centre = straight up, edge = horizon, north at the top. Dots every hour; orange = golden hour. Grey = the recorded skyline (buildings, hills).")
                }
                Section("Sun times") { SunCard(latitude: lat, longitude: lon, date: time, skyline: sky) }
            } else {
                Section {
                    Text("The sun's position depends on where you are. Use your location (outdoors works best).").foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Sun planner")
        .onChange(of: locator.location) { _, loc in
            guard let loc else { return }
            latitude = loc.coordinate.latitude
            longitude = loc.coordinate.longitude
            status = nil
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $showAR) {
            if let lat = latitude, let lon = longitude {
                SunARView(time: $time, skyline: $skyline, latitude: lat, longitude: lon, session: session, shot: shot)
            }
        }
        #endif
    }

    // MARK: sections

    private var locationSection: some View {
        Section("Location") {
            VStack(alignment: .leading, spacing: 4) {
                Text(session.map { $0.locationName.isEmpty ? $0.projectName : $0.locationName } ?? "This location").font(.headline)
                if let lat = latitude, let lon = longitude {
                    Text(String(format: "%.5f, %.5f", lat, lon)).font(Theme.mono).foregroundStyle(.secondary)
                } else {
                    Text("No location yet").foregroundStyle(.secondary)
                }
                HStack(spacing: 16) {
                    Button { locator.request() } label: {
                        Label(locator.isLocating ? "Locating…" : "Use my location", systemImage: "location.fill")
                    }
                    .disabled(locator.isLocating)
                    .buttonStyle(.borderless)
                    if let s = session, let lat = latitude, let lon = longitude, s.latitude != lat || s.longitude != lon {
                        Button("Save to this recce") {
                            s.latitude = lat; s.longitude = lon; s.touch()
                            try? context.save()
                            status = "Saved to “\(s.projectName)”."
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .padding(.top, 2)
                if let e = locator.error { Text(e).font(.caption).foregroundStyle(.orange) }
                if let status { Text(status).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }

    private func timeSection(_ lat: Double, _ lon: Double) -> some View {
        let pos = Solar.position(at: time, latitude: lat, longitude: lon)
        let day = Solar.day(for: time, latitude: lat, longitude: lon, timeZone: .current)
        let (start, end) = SunPlan.dayBounds(time, timeZone: .current)
        let last = max(end.timeIntervalSince(start) / 60 - 1, 1)
        return Section("Date and time") {
            HStack {
                Button { shiftDay(-1) } label: { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                Spacer()
                DatePicker("Date", selection: dateBinding, displayedComponents: .date).labelsHidden()
                Spacer()
                Button { shiftDay(1) } label: { Image(systemName: "chevron.right") }.buttonStyle(.borderless)
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(SunText.clock(time)).font(.system(size: 28, design: .monospaced)).foregroundStyle(SunText.gold)
                    Text(SunText.position(pos) + (SunPlan.behindSkyline(pos, sky) ? " · behind the skyline" : "")).font(.callout)
                }
                Slider(value: Binding(get: { min(max(time.timeIntervalSince(start) / 60, 0), last) },
                                      set: { time = start.addingTimeInterval($0.rounded() * 60) }),
                       in: 0...last)
                    .tint(SunText.golden)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(quickTimes(day)) { item in
                            Button(item.label) { time = item.time }
                                .buttonStyle(.bordered).controlSize(.small)
                        }
                    }
                }
                if pos.elevation > 0.5 {
                    let shadow = (pos.azimuth + 180).truncatingRemainder(dividingBy: 360)
                    Text(String(format: "Shadows %.1f× as long as objects are tall, pointing %.0f° (%@). For backlight, point the camera towards %@.",
                                1 / tan(pos.elevation * .pi / 180), shadow, Solar.compass(shadow), Solar.compass(pos.backlightHeading)))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var actionsSection: some View {
        Section {
            #if os(iOS)
            Button { showAR = true } label: { Label("See the sun in the camera (AR)", systemImage: "arkit") }
            #endif
            if let shot {
                Button {
                    shot.plannedTime = time
                    shot.touch()
                    try? context.save()
                    status = "Saved \(SunText.clock(time)) with shot \(shot.shotNumber)."
                } label: {
                    Label("Use \(SunText.clock(time)) for shot \(shot.shotNumber)", systemImage: "clock.badge.checkmark")
                }
                if let planned = shot.plannedTime {
                    Text("Shot \(shot.shotNumber): planned for \(planned.formatted(date: .abbreviated, time: .omitted)), \(SunText.clock(planned))")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func skylineSection(_ lat: Double, _ lon: Double) -> some View {
        Section("Buildings and hills") {
            if let sky {
                let periods = SunPlan.directSunPeriods(time, latitude: lat, longitude: lon, timeZone: .current, skyline: sky)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Direct sun here").font(.headline)
                    Text(periods.isEmpty ? "None on this day: the sun stays behind the skyline."
                                         : periods.map { "\(SunText.clock($0.0)) – \(SunText.clock($0.1))" }.joined(separator: "\n"))
                        .font(.system(.body, design: .monospaced)).foregroundStyle(SunText.gold)
                    Text("Skyline recorded over \(sky.recordedDegrees)° of the horizon; the rest counts as open sky.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Button("Clear the skyline", role: .destructive) {
                    skyline = []
                    if let s = session { s.skyline = []; s.touch(); try? context.save() }
                }
            } else {
                Text("Not recorded yet. In the camera view (AR), tap “Record skyline” and sweep the crosshair along the tops of buildings, trees and hills, standing where the camera will be. Then you'll see when they block the sun.")
                    .font(.callout).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: helpers

    /// The chosen day, keeping the time of day.
    private var dateBinding: Binding<Date> {
        Binding(get: { time }, set: { newDate in
            let cal = Calendar.current
            let t = cal.dateComponents([.hour, .minute], from: time)
            var d = cal.dateComponents([.year, .month, .day], from: newDate)
            d.hour = t.hour; d.minute = t.minute
            time = cal.date(from: d) ?? newDate
        })
    }

    private func shiftDay(_ n: Int) {
        time = Calendar.current.date(byAdding: .day, value: n, to: time) ?? time
    }

    private struct QuickTime: Identifiable {
        let label: String
        let time: Date
        var id: String { label }
        init(_ label: String, _ time: Date) { self.label = label; self.time = time }
    }

    private func quickTimes(_ day: SunDay) -> [QuickTime] {
        func mid(_ r: ClosedRange<Date>) -> Date { r.lowerBound.addingTimeInterval(r.upperBound.timeIntervalSince(r.lowerBound) / 2) }
        var out: [QuickTime] = []
        if let b = day.blueMorning { out.append(QuickTime("Blue AM", b.lowerBound)) }
        if let s = day.sunrise { out.append(QuickTime("Sunrise", s)) }
        if let g = day.goldenMorning { out.append(QuickTime("Golden AM", mid(g))) }
        out.append(QuickTime("Noon", day.solarNoon))
        if let g = day.goldenEvening { out.append(QuickTime("Golden PM", mid(g))) }
        if let s = day.sunset { out.append(QuickTime("Sunset", s)) }
        if let b = day.blueEvening { out.append(QuickTime("Blue PM", mid(b))) }
        out.append(QuickTime("Now", .now))
        return out
    }
}
