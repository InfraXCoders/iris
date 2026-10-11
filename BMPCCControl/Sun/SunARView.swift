#if os(iOS)
import SwiftUI
import SwiftData
import CoreMotion
import UIKit
import RecceKit

/// A correction to the compass, in degrees (added to the heading), set by aligning on the real sun or nudging.
/// Kept while the app runs: magnetic interference changes from place to place, so it isn't saved.
enum CompassCorrection {
    static var offset = 0.0
}

/// Camera orientation in the world (East-North-Up).
struct CameraPose: Equatable {
    var forward: Vec3
    var right: Vec3
    var up: Vec3
}

/// Live camera orientation from Core Motion (gyro + accelerometer + compass), relative to true north when the phone
/// allows it (otherwise magnetic north). Also records the skyline while the user sweeps the crosshair along it.
@MainActor
final class PoseTracker: ObservableObject {
    @Published private(set) var pose: CameraPose?
    /// Core Motion's compass calibration (CMMagneticFieldCalibrationAccuracy raw value), nil before it reports.
    @Published private(set) var compassAccuracy: Int32?
    @Published private(set) var trueNorth = true
    @Published private(set) var recording = false
    @Published private(set) var recorded: [Int: Double] = [:]
    /// Degrees added to every heading (CompassCorrection).
    var headingOffset = CompassCorrection.offset

    /// Made on start (the view may build several trackers; only the one in use reads the sensors).
    private var manager: CMMotionManager?
    /// nil until worked out: whether the attitude matrix's rows (true) or columns hold the device axes.
    private var rowsAreAxes: Bool?
    private var started = false

    func start() {
        guard !started else { return }
        let manager = self.manager ?? CMMotionManager()
        self.manager = manager
        guard manager.isDeviceMotionAvailable else { return }
        started = true
        let frames = CMMotionManager.availableAttitudeReferenceFrames()
        if frames.contains(.xTrueNorthZVertical) {
            run(.xTrueNorthZVertical)
            // True north needs the location; if nothing arrives, fall back to magnetic north.
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard let self, self.started, self.pose == nil, frames.contains(.xMagneticNorthZVertical) else { return }
                self.manager?.stopDeviceMotionUpdates()
                self.run(.xMagneticNorthZVertical)
            }
        } else if frames.contains(.xMagneticNorthZVertical) {
            run(.xMagneticNorthZVertical)
        }
    }

    func stop() {
        started = false
        manager?.stopDeviceMotionUpdates()
    }

    private func run(_ frame: CMAttitudeReferenceFrame) {
        guard let manager else { return }
        trueNorth = frame == .xTrueNorthZVertical
        manager.deviceMotionUpdateInterval = 1.0 / 30
        manager.showsDeviceMovementDisplay = true
        manager.startDeviceMotionUpdates(using: frame, to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            MainActor.assumeIsolated { self.update(motion) }
        }
    }

    private func update(_ m: CMDeviceMotion) {
        let r = m.attitude.rotationMatrix
        // Which way round the matrix is: the device coordinates of "up" must point against gravity.
        if rowsAreAxes == nil {
            let g = Vec3(-m.gravity.x, -m.gravity.y, -m.gravity.z)
            let col3 = Vec3(r.m13, r.m23, r.m33), row3 = Vec3(r.m31, r.m32, r.m33)
            if g.length > 0.5 { rowsAreAxes = g.dot(col3) >= g.dot(row3) }
        }
        let rows = rowsAreAxes ?? true
        // Device vector → reference frame (x north, y west, z up) → East-North-Up, then the compass correction.
        let d = headingOffset * .pi / 180
        func world(_ v: Vec3) -> Vec3 {
            let x, y, z: Double
            if rows {
                x = r.m11 * v.x + r.m21 * v.y + r.m31 * v.z
                y = r.m12 * v.x + r.m22 * v.y + r.m32 * v.z
                z = r.m13 * v.x + r.m23 * v.y + r.m33 * v.z
            } else {
                x = r.m11 * v.x + r.m12 * v.y + r.m13 * v.z
                y = r.m21 * v.x + r.m22 * v.y + r.m23 * v.z
                z = r.m31 * v.x + r.m32 * v.y + r.m33 * v.z
            }
            let e = -y, n = x
            return Vec3(e * cos(d) + n * sin(d), n * cos(d) - e * sin(d), z)
        }
        // Device axes that point screen-right and screen-up for each interface rotation (as on Android).
        let rightDev: Vec3, upDev: Vec3
        switch Int(LevelMotion.screenRotation()) {
        case 90: rightDev = Vec3(0, -1, 0); upDev = Vec3(1, 0, 0)
        case 180: rightDev = Vec3(-1, 0, 0); upDev = Vec3(0, -1, 0)
        case 270: rightDev = Vec3(0, 1, 0); upDev = Vec3(-1, 0, 0)
        default: rightDev = Vec3(1, 0, 0); upDev = Vec3(0, 1, 0)
        }
        let f = world(Vec3(0, 0, -1)), rt = world(rightDev), up = world(upDev)
        let k = 0.25
        if let p = pose {
            pose = CameraPose(forward: (p.forward * (1 - k) + f * k).normalized(), right: (p.right * (1 - k) + rt * k).normalized(),
                              up: (p.up * (1 - k) + up * k).normalized())
        } else {
            pose = CameraPose(forward: f, right: rt, up: up)
        }
        let acc = m.magneticField.accuracy.rawValue
        if acc != compassAccuracy { compassAccuracy = acc }
        if recording, let p = pose {
            let dir = SunPlan.direction(p.forward)
            let b = Skyline.bin(dir.azimuth), v = min(max(dir.elevation, -10), 85)
            // Only real changes, so the screen doesn't redraw at sensor rate.
            if let old = recorded[b], abs(old - v) < 0.2 {} else { recorded[b] = v }
        }
    }

    // MARK: skyline recording

    func startRecording(_ seed: [SkyPoint]) {
        var r: [Int: Double] = [:]
        for p in seed { r[Skyline.bin(p.azimuth)] = p.elevation }
        recorded = r
        recording = true
    }

    func finishRecording() -> [SkyPoint] {
        recording = false
        return recorded.keys.sorted().map { SkyPoint(azimuth: Double($0), elevation: recorded[$0] ?? 0) }
    }

    func cancelRecording() { recording = false }

    var recordedPoints: [SkyPoint] { recorded.keys.sorted().map { SkyPoint(azimuth: Double($0), elevation: recorded[$0] ?? 0) } }
}

/// The sun over the live camera: the day's path with hour marks, the sun at the planner's time, compass points on the
/// horizon, the skyline, and "Mark this spot" to find when the sun lines up with, or rises above, what the crosshair
/// points at. Same as the Android app's camera view.
struct SunARView: View {
    @Binding var time: Date
    @Binding var skyline: [SkyPoint]
    let latitude: Double
    let longitude: Double
    let session: RecceSession?
    let shot: RecceShot?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @StateObject private var camera = CameraController()
    @StateObject private var locator = LocationProvider()
    // Not observed here: only the overlays redraw with the sensors.
    @State private var tracker = PoseTracker()
    @State private var report: String?
    @State private var offset = CompassCorrection.offset

    var body: some View {
        let path = SunPlan.path(time, latitude: latitude, longitude: longitude, timeZone: .current, stepMinutes: 10)
        let sun = Solar.position(at: time, latitude: latitude, longitude: longitude)
        ZStack {
            GeometryReader { geo in
                ZStack {
                    Color.black
                    CameraPreview(controller: camera)
                    SkyOverlay(tracker: tracker, size: geo.size, formatAspect: camera.formatAspect, longSideFov: camera.baseLongSideFov,
                               zoom: camera.zoom, path: path, time: time, latitude: latitude, longitude: longitude, skyline: skyline)
                    Canvas { ctx, size in
                        let c = CGPoint(x: size.width / 2, y: size.height / 2), l: CGFloat = 16
                        var p = Path()
                        p.move(to: CGPoint(x: c.x - l, y: c.y)); p.addLine(to: CGPoint(x: c.x + l, y: c.y))
                        p.move(to: CGPoint(x: c.x, y: c.y - l)); p.addLine(to: CGPoint(x: c.x, y: c.y + l))
                        ctx.stroke(p, with: .color(.white), lineWidth: 1.5)
                    }
                }
                .allowsHitTesting(false)
            }
            .ignoresSafeArea()

            RecordingHint(tracker: tracker)

            VStack(spacing: 0) {
                topBar(sun)
                Spacer()
                bottomPanel
            }
            if camera.status == .denied {
                Text("Camera access is off. Allow it for BMPCC Control in Settings › Privacy.").foregroundStyle(.white)
                    .padding().background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .preferredColorScheme(.dark)
        .task {
            locator.request()          // true north needs the location
            await camera.start()
            tracker.headingOffset = offset
            tracker.start()
        }
        .onDisappear { tracker.stop(); camera.stop() }
    }

    private func topBar(_ sun: SolarPosition) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left").font(.headline).padding(10).background(Theme.panel, in: Circle())
            }
            .buttonStyle(.plain).foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 2) {
                Text("Sun at \(SunText.clock(time))").font(.headline).foregroundStyle(SunText.gold)
                Text(SunText.position(sun)).font(.caption).foregroundStyle(.white)
            }
            Spacer()
            AimText(tracker: tracker)
        }
        .padding(.horizontal, 14).padding(.top, 6)
    }

    private var bottomPanel: some View {
        let (start, end) = SunPlan.dayBounds(time, timeZone: .current)
        let last = max(end.timeIntervalSince(start) / 60 - 1, 1)
        let realSun = Solar.position(at: .now, latitude: latitude, longitude: longitude)
        return VStack(alignment: .leading, spacing: 8) {
            if let report { Text(report).font(.footnote).foregroundStyle(.white).fixedSize(horizontal: false, vertical: true) }
            Slider(value: Binding(get: { min(max(time.timeIntervalSince(start) / 60, 0), last) },
                                  set: { time = start.addingTimeInterval($0.rounded() * 60) }),
                   in: 0...last)
                .tint(SunText.golden)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    pill("Mark this spot") { markSpot() }
                    if let shot {
                        pill("Use \(SunText.clock(time)) for shot \(shot.shotNumber)", prominent: true) {
                            shot.plannedTime = time
                            shot.touch()
                            try? context.save()
                            report = "Saved \(SunText.clock(time)) with shot \(shot.shotNumber)."
                        }
                    }
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if realSun.elevation > 0 { pill("Align on the sun") { alignOnSun(realSun) } }
                    pill("−1°") { setOffset(offset - 1) }
                    pill("+1°") { setOffset(offset + 1) }
                    if offset != 0 { pill(String(format: "%+.0f° · reset", offset)) { setOffset(0) } }
                    SkylineControls(tracker: tracker, hasSkyline: !skyline.isEmpty, seed: skyline) { pts in saveSkyline(pts) }
                }
            }
            CompassNote(tracker: tracker)
        }
        .padding(12)
        .frame(maxWidth: 640, alignment: .leading)
        .background(Color(red: 0.04, green: 0.12, blue: 0.27).opacity(0.82), in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 12).padding(.bottom, 8)
    }

    private func pill(_ title: String, prominent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(prominent ? Theme.accent : Color.white.opacity(0.88), in: Capsule())
                .foregroundStyle(prominent ? Color.white : Color.black)
        }
        .buttonStyle(.plain)
    }

    // MARK: actions

    private func markSpot() {
        guard let p = tracker.pose else { report = "No compass on this phone."; return }
        let dir = SunPlan.direction(p.forward)
        let r = SunPlan.spot(azimuth: dir.azimuth, elevation: dir.elevation, date: time, latitude: latitude, longitude: longitude, timeZone: .current)
        report = Self.spotText(r, dir.azimuth, dir.elevation)
    }

    private func alignOnSun(_ realSun: SolarPosition) {
        guard let p = tracker.pose else { report = "No compass on this phone."; return }
        let az = SunPlan.direction(p.forward).azimuth
        let fix = SunPlan.azimuthDifference(realSun.azimuth, az)
        setOffset(SunPlan.azimuthDifference(offset + fix, 0))
        report = String(format: "Compass corrected by %+.0f°. (Put the crosshair on the real sun first, then tap.)", fix)
    }

    private func setOffset(_ v: Double) {
        offset = v
        CompassCorrection.offset = v
        tracker.headingOffset = v
    }

    private func saveSkyline(_ pts: [SkyPoint]) {
        skyline = pts
        if let s = session { s.skyline = pts; s.touch(); try? context.save() }
        let sky = Skyline(pts)
        let periods = SunPlan.directSunPeriods(time, latitude: latitude, longitude: longitude, timeZone: .current, skyline: sky)
        report = "Skyline saved (\(sky.recordedDegrees)°). Direct sun: "
            + (periods.isEmpty ? "none that day." : periods.map { "\(SunText.clock($0.0))–\(SunText.clock($0.1))" }.joined(separator: ", "))
            + (session == nil ? " (Not part of a recce, so it's kept only until you leave the planner.)" : "")
    }

    static func spotText(_ r: SunPlan.SpotReport, _ az: Double, _ el: Double) -> String {
        var lines = [String(format: "Spot: %.0f° %@, %.0f° %@.", az, Solar.compass(az), abs(el), el >= 0 ? "up" : "down")]
        if let t = r.closestTime, r.passesThrough {
            lines.append("The sun passes right through it at \(SunText.clock(t)).")
        } else if let t = r.closestTime {
            lines.append(String(format: "Closest at %@, %.0f° away.", SunText.clock(t), r.closestDegrees))
        }
        for (t, diff) in r.sameDirection {
            lines.append(String(format: "In this direction at %@, %.0f° %@ it.", SunText.clock(t), abs(diff), diff >= 0 ? "above" : "below"))
        }
        if let from = r.aboveFrom, let until = r.aboveUntil {
            lines.append("The sun is higher than this spot from \(SunText.clock(from)) to \(SunText.clock(until)).")
        } else {
            lines.append("The sun never gets as high as this spot today.")
        }
        return lines.joined(separator: "\n")
    }
}

/// Day path, hour marks, sun disc, horizon compass points and the skyline, projected with the live orientation.
private struct SkyOverlay: View {
    @ObservedObject var tracker: PoseTracker
    let size: CGSize
    let formatAspect: Double
    let longSideFov: Double
    let zoom: Double
    let path: [SunPlan.Sample]
    let time: Date
    let latitude: Double
    let longitude: Double
    let skyline: [SkyPoint]

    var body: some View {
        let landscape = size.width > size.height
        let video = Monitor.aspectFill(contentAspect: landscape ? formatAspect : 1 / formatAspect, width: Double(size.width), height: Double(size.height))
        let view = RecceKit.PreviewView.fromFormat(longSideFov: longSideFov, longOverShort: formatAspect, portrait: !landscape)
        let th = Optics.halfTan(view.horizontalFov) / max(zoom, 0.1)
        let tv = Optics.halfTan(view.verticalFov) / max(zoom, 0.1)
        let pose = tracker.pose
        let points = tracker.recording ? tracker.recordedPoints : skyline
        let sun = Solar.position(at: time, latitude: latitude, longitude: longitude)
        Canvas { ctx, _ in
            guard let p = pose else { return }
            func scr(_ az: Double, _ el: Double) -> CGPoint? {
                guard let xy = SkyProjection.project(SunPlan.vector(azimuth: az, elevation: el), forward: p.forward, right: p.right, up: p.up,
                                                     tanHalfH: th, tanHalfV: tv),
                      abs(xy.0) <= 4, abs(xy.1) <= 4 else { return nil }
                return CGPoint(x: video.midX + xy.0 * video.width / 2, y: video.midY - xy.1 * video.height / 2)
            }
            func disc(_ c: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)) }
            // Horizon with compass points.
            var horizon = Path(); var on = false
            for az in stride(from: 0.0, through: 360, by: 2) {
                guard let o = scr(az, 0) else { on = false; continue }
                if on { horizon.addLine(to: o) } else { horizon.move(to: o); on = true }
            }
            ctx.stroke(horizon, with: .color(.white.opacity(0.55)), lineWidth: 1)
            for (label, az) in [("N", 0.0), ("NE", 45.0), ("E", 90.0), ("SE", 135.0), ("S", 180.0), ("SW", 225.0), ("W", 270.0), ("NW", 315.0)] {
                if let o = scr(az, 0) {
                    ctx.draw(Text(label).font(.caption.bold()).foregroundColor(.white), at: CGPoint(x: o.x, y: o.y + 4), anchor: .top)
                }
            }
            // The skyline (breaks across unrecorded gaps).
            if !points.isEmpty {
                var sky = Path(); var drawing = false; var lastAz = -1000
                for pt in points.sorted(by: { $0.azimuth < $1.azimuth }) {
                    let az = Int(pt.azimuth)
                    let o = scr(pt.azimuth, pt.elevation)
                    if o == nil || az - lastAz > Skyline.maxGap { drawing = false }
                    if let o { if drawing { sky.addLine(to: o) } else { sky.move(to: o); drawing = true } }
                    lastAz = az
                }
                ctx.stroke(sky, with: .color(Color(red: 0.35, green: 0.78, blue: 0.98)), lineWidth: 2.5)
            }
            // The day's path (above −2°), golden hour orange, hour marks.
            var line = Path(); on = false
            let cal = Calendar.current
            for s in path {
                guard s.position.elevation > -2, let o = scr(s.position.azimuth, s.position.elevation) else { on = false; continue }
                if on { line.addLine(to: o) } else { line.move(to: o); on = true }
                if s.position.elevation <= Solar.goldenHigh { ctx.fill(disc(o, 3), with: .color(SunText.golden)) }
                if cal.component(.minute, from: s.time) == 0 {
                    ctx.fill(disc(o, 3), with: .color(.white))
                    ctx.draw(Text("\(cal.component(.hour, from: s.time)):00").font(.system(size: 10)).foregroundColor(.white),
                             at: CGPoint(x: o.x + 5, y: o.y - 4), anchor: .bottomLeading)
                }
            }
            ctx.stroke(line, with: .color(SunText.gold), lineWidth: 2)
            // The sun at the chosen time.
            if let o = scr(sun.azimuth, sun.elevation) {
                ctx.fill(disc(o, 22), with: .color(Color(red: 1, green: 0.88, blue: 0.4).opacity(0.4)))
                ctx.fill(disc(o, 12), with: .color(Color(red: 1, green: 0.88, blue: 0.4)))
            }
        }
    }
}

/// "Aim 245° SW / 12° up" for the crosshair.
private struct AimText: View {
    @ObservedObject var tracker: PoseTracker
    var body: some View {
        if let p = tracker.pose {
            let d = SunPlan.direction(p.forward)
            Text(String(format: "Aim %.0f° %@\n%.0f° %@", d.azimuth, Solar.compass(d.azimuth), abs(d.elevation), d.elevation >= 0 ? "up" : "down"))
                .font(.system(size: 11, design: .monospaced)).foregroundStyle(.white)
                .padding(6).background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
        }
    }
}

private struct RecordingHint: View {
    @ObservedObject var tracker: PoseTracker
    var body: some View {
        if tracker.recording {
            Text("Sweep the crosshair slowly along the tops of buildings, trees and hills (\(tracker.recorded.count)° recorded). Tap Done when you're round.")
                .font(.footnote).foregroundStyle(.white).multilineTextAlignment(.center)
                .padding(10).background(Color(red: 0, green: 0.3, blue: 0.4).opacity(0.8), in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 24).padding(.top, 140)
                .allowsHitTesting(false)
        }
    }
}

private struct SkylineControls: View {
    @ObservedObject var tracker: PoseTracker
    let hasSkyline: Bool
    let seed: [SkyPoint]
    let onDone: ([SkyPoint]) -> Void

    var body: some View {
        if tracker.recording {
            button("Done (\(tracker.recorded.count)°)", prominent: true) { onDone(tracker.finishRecording()) }
            button("Cancel") { tracker.cancelRecording() }
        } else {
            button(hasSkyline ? "Re-record skyline" : "Record skyline") { tracker.startRecording(seed) }
        }
    }

    private func button(_ title: String, prominent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(prominent ? Theme.accent : Color.white.opacity(0.88), in: Capsule())
                .foregroundStyle(prominent ? Color.white : Color.black)
        }
        .buttonStyle(.plain)
    }
}

private struct CompassNote: View {
    @ObservedObject var tracker: PoseTracker
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            let acc = tracker.compassAccuracy
            if acc == CMMagneticFieldCalibrationAccuracy.uncalibrated.rawValue || acc == CMMagneticFieldCalibrationAccuracy.low.rawValue {
                Text("Compass needs calibrating: move the phone in a figure-8 a few times, away from metal and cars.")
                    .font(.caption.weight(.semibold)).foregroundStyle(Color(red: 1, green: 0.7, blue: 0.28))
            }
            Text((tracker.pose == nil ? "Waiting for the motion sensors. " : "")
                 + (tracker.trueNorth ? "" : "Using magnetic north (allow location for true north). ")
                 + "If the drawn sun is off from the real one, put the crosshair on the real sun and tap “Align on the sun”, or nudge ±1°. Record the skyline to include buildings and hills.")
                .font(.caption2).foregroundStyle(.white.opacity(0.7))
        }
    }
}
#endif
