import SwiftUI
import RecceKit

/// Lens coverage tool: the lens's image circle drawn over the camera's sensor for a recording mode,
/// with a verdict, the field of view and the mount match.
struct CoverageView: View {
    @State private var cameraId: String
    @State private var lensId: String
    @State private var modeId: String?

    init(cameraId: String? = nil, lensId: String? = nil, modeId: String? = nil) {
        let defaults = UserDefaults.standard
        _modeId = State(initialValue: modeId)
        _cameraId = State(initialValue: cameraId ?? defaults.string(forKey: Prefs.defaultCamera) ?? Catalog.defaultCamera.id)
        _lensId = State(initialValue: lensId ?? defaults.string(forKey: Prefs.defaultLens) ?? Catalog.defaultLens.id)
    }

    private var camera: CameraProfile { Catalog.camera(id: cameraId) ?? Catalog.defaultCamera }
    private var lens: LensProfile { Catalog.lens(id: lensId) ?? Catalog.defaultLens }
    private var mode: SensorMode { camera.mode(id: modeId) ?? camera.sensorModes[0] }

    var body: some View {
        let camera = self.camera, lens = self.lens, mode = self.mode
        let coverage = Coverage.evaluate(lens: lens, mode: mode)
        let circle = Coverage.circle(for: lens)
        Form {
            Section {
                NavigationLink { LibraryView(mode: .pickCamera { c in cameraId = c.id; modeId = nil }) } label: {
                    LabeledContent("Camera", value: camera.model)
                }
                if camera.sensorModes.count > 1 {
                    Picker("Recording mode", selection: Binding(get: { mode.id }, set: { modeId = $0 })) {
                        ForEach(camera.sensorModes) { Text($0.name).tag($0.id) }
                    }
                }
                NavigationLink { LibraryView(mode: .pickLens { l in lensId = l.id }) } label: {
                    LabeledContent("Lens", value: lens.series.map { "\(lens.manufacturer) \($0) \(ShotPresets.focalText(lens.focalLengthMin))" } ?? lens.displayName)
                }
            }

            Section {
                CoverageDiagram(fullSensor: camera.sensorModes[0], mode: mode, circleMm: circle?.mm, nominal: circle?.nominal ?? false)
                    .frame(height: 260)
                    .listRowInsets(EdgeInsets())
                HStack {
                    CoverageBadge(coverage: coverage, nominal: circle?.nominal ?? false).font(.title2)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(coverage.label).font(.headline)
                        Text(explanation(coverage, circle: circle, mode: mode)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            } footer: {
                HStack(spacing: 14) {
                    Legend(color: Theme.frameLine, text: "Image inside the circle")
                    Legend(color: .red, text: "Vignetted")
                    Legend(color: .gray, text: "Full sensor")
                }
            }

            Section("Numbers") {
                LabeledContent("Mode area", value: mode.sizeText)
                LabeledContent("Mode diagonal", value: String(format: "%.2f mm", mode.diagonalMm))
                LabeledContent("Image circle", value: circle.map { String(format: "%.1f mm", $0.mm) + ($0.nominal ? " (typical \(lens.format ?? ""))" : "") } ?? "Not published")
                if let r = Framing.reference(camera: camera.using(modeId: mode.id), lens: lens, focalLengthMm: lens.focalLengthMin) {
                    LabeledContent("Field of view", value: "\(r.deliveredFov.horizontal.degreesText) × \(r.deliveredFov.vertical.degreesText)")
                    if lens.isAnamorphic {
                        LabeledContent("Desqueezed aspect", value: String(format: "%.2f:1", r.deliveredAspect))
                    }
                    LabeledContent("Full-frame equivalent",
                                   value: "\(Int(Optics.equivalentFocalLength(lens.focalLengthMin, sensorWidthMm: mode.widthMm).rounded()))mm")
                }
                if let best = Coverage.largestCoveredMode(lens: lens, camera: camera), best.id != mode.id {
                    LabeledContent("Largest mode fully covered", value: best.name)
                }
                LabeledContent("Mount", value: Coverage.sharesMount(camera: camera, lens: lens)
                               ? "Fits (\(lens.allMountNames.joined(separator: "/")))"
                               : "Adapter needed: \(lens.allMountNames.joined(separator: "/")) → \(camera.allMountNames.joined(separator: "/"))")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Lens coverage")
    }

    private func explanation(_ c: Coverage, circle: (mm: Double, nominal: Bool)?, mode: SensorMode) -> String {
        let nominalNote = circle?.nominal == true ? " Based on a typical image circle; the maker doesn't publish one." : ""
        switch c {
        case .full: return "The image circle covers the whole \(mode.name) area." + nominalNote
        case .cornersVignette: return "Covers the width, but the corners go dark. A wider delivery aspect or a smaller mode may hide it." + nominalNote
        case .vignettes: return "The image circle is narrower than the sensor area. Choose a smaller recording mode." + nominalNote
        case .unknown: return "The maker doesn't publish this lens's image circle or format."
        }
    }
}

private struct Legend: View {
    let color: Color
    let text: String
    var body: some View {
        HStack(spacing: 4) {
            RoundedRectangle(cornerRadius: 2).fill(color.opacity(0.6)).frame(width: 10, height: 10)
            Text(text)
        }
        .font(.caption2)
    }
}

/// The sensor rectangle(s) and the lens's image circle, to scale.
struct CoverageDiagram: View {
    let fullSensor: SensorMode
    let mode: SensorMode
    let circleMm: Double?
    let nominal: Bool

    var body: some View {
        Canvas { ctx, size in
            let span = max(fullSensor.diagonalMm, circleMm ?? 0) * 1.08
            let scale = min(size.width, size.height) / span
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            func rect(_ w: Double, _ h: Double) -> CGRect {
                CGRect(x: center.x - w * scale / 2, y: center.y - h * scale / 2, width: w * scale, height: h * scale)
            }
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black))

            let full = rect(fullSensor.widthMm, fullSensor.heightMm)
            ctx.stroke(Path(full), with: .color(.gray), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))

            let area = rect(mode.widthMm, mode.heightMm)
            if let c = circleMm {
                let d = c * scale
                let circle = Path(ellipseIn: CGRect(x: center.x - d / 2, y: center.y - d / 2, width: d, height: d))
                ctx.fill(Path(area), with: .color(.red.opacity(0.45)))
                var inside = ctx
                inside.clip(to: circle)
                inside.fill(Path(area), with: .color(Theme.frameLine.opacity(0.45)))
                ctx.stroke(circle, with: .color(.green), style: StrokeStyle(lineWidth: 2, dash: nominal ? [6, 4] : []))
            } else {
                ctx.fill(Path(area), with: .color(.gray.opacity(0.35)))
            }
            ctx.stroke(Path(area), with: .color(Theme.frameLine), lineWidth: 2)

            ctx.draw(Text(String(format: "%.1f × %.1f mm", mode.widthMm, mode.heightMm)).font(.caption2).foregroundColor(.white),
                     at: CGPoint(x: area.midX, y: area.maxY + 10))
            if let c = circleMm {
                ctx.draw(Text(String(format: "⌀ %.1f mm", c)).font(.caption2).foregroundColor(.green),
                         at: CGPoint(x: center.x, y: center.y - c * scale / 2 - 9))
            }
        }
        .accessibilityLabel("Image circle over the sensor area")
    }
}
