import SwiftUI
import RecceKit

/// Sunrise, sunset, golden and blue hour for a recce's location and date (NOAA solar algorithm, RecceKit).
/// Times are shown in this device's time zone.
struct SunCard: View {
    let latitude: Double
    let longitude: Double
    let date: Date

    var body: some View {
        let day = Solar.day(for: date, latitude: latitude, longitude: longitude, timeZone: .current)
        VStack(alignment: .leading, spacing: 8) {
            if day.isPolarDay {
                Label("The sun doesn't set on this day.", systemImage: "sun.max.fill")
            } else if day.isPolarNight {
                Label("The sun doesn't rise on this day.", systemImage: "moon.stars.fill")
            } else {
                row("sunrise.fill", "Sunrise", Self.time(day.sunrise))
                row("sunset.fill", "Sunset", Self.time(day.sunset))
            }
            row("sun.max", "Solar noon", "\(Self.time(day.solarNoon)) · \(day.noonElevation.degreesText) high")
            row("sun.haze.fill", "Golden hour (morning)", Self.range(day.goldenMorning))
            row("sun.haze.fill", "Golden hour (evening)", Self.range(day.goldenEvening))
            row("moon.haze", "Blue hour (morning)", Self.range(day.blueMorning))
            row("moon.haze", "Blue hour (evening)", Self.range(day.blueEvening))

            if Calendar.current.isDateInToday(date) {
                let now = Solar.position(at: .now, latitude: latitude, longitude: longitude)
                Divider()
                if now.elevation > Solar.horizonElevation {
                    row("location.north.line", "Sun now",
                        "\(now.elevation.degreesText) high, \(now.compass) (\(Int(now.azimuth.rounded()))°)")
                    Text("Face \(Solar.compass(now.backlightHeading)) to backlight the subject.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    row("moon.fill", "Sun now", "Below the horizon")
                }
            }
            Text("Golden hour: sun 6° above to 4° below the horizon. Blue hour: 4° to 6° below.")
                .font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func row(_ symbol: String, _ title: String, _ value: String) -> some View {
        HStack {
            Label(title, systemImage: symbol)
            Spacer()
            Text(value).font(Theme.mono).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
        }
    }

    static func time(_ d: Date?) -> String {
        guard let d else { return "—" }
        return d.formatted(date: .omitted, time: .shortened)
    }

    static func range(_ r: ClosedRange<Date>?) -> String {
        guard let r else { return "—" }
        return "\(time(r.lowerBound)) – \(time(r.upperBound))"
    }
}
