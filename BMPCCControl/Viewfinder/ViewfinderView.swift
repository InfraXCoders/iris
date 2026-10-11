import SwiftUI
import SwiftData
import UniformTypeIdentifiers
import RecceKit

/// How the phone picture's exposure follows the shot (same three choices as the Android app).
enum PhoneExposureMode: String, CaseIterable, Identifiable {
    case auto, follow, match
    var id: String { rawValue }
    var label: String { switch self { case .auto: return "Phone auto"; case .follow: return "Follow"; case .match: return "Match exactly" } }
    var detail: String {
        switch self {
        case .auto: return "The phone exposes on its own."
        case .follow: return "The phone's auto-exposure, made brighter or darker by the shot's ISO, shutter, ND and iris."
        case .match: return "The phone's ISO and shutter are set so its picture gets the same exposure as the cinema camera. Approximate: phone and camera ISO and tone differ."
        }
    }
}

/// Everything the overlay needs to draw, computed with RecceKit (tested there): the camera picture fills the screen,
/// the cinema frame sits in an area clear of the controls (or, when it is too wide for that, almost the whole screen).
struct ViewfinderLayout {
    var video = ScreenRect(x: 0, y: 0, width: 0, height: 0)
    var area = ScreenRect(x: 0, y: 0, width: 0, height: 0)
    var frame = ScreenRect(x: 0, y: 0, width: 0, height: 0)
    var lines = FrameLines(widthFraction: 1, heightFraction: 1)
    var zoom: Double = 1
    /// The frame's true size when it is wider than the phone shows (partly off screen).
    var trueFrame: ScreenRect?
    var view = RecceKit.PreviewView(horizontalFov: 70, verticalFov: 52)

    init() {}

    @MainActor init(size: CGSize, reference: ReferenceFrame?, camera: CameraController, margin: Double, fixedZoom: Double?) {
        let w = Double(size.width), h = Double(size.height)
        let landscape = w > h
        let pictureAspect = landscape ? camera.formatAspect : 1 / camera.formatAspect
        #if os(iOS)
        video = Monitor.aspectFill(contentAspect: pictureAspect, width: w, height: h)
        #else
        video = Viewfinder.aspectFit(contentAspect: pictureAspect, containerWidth: w, containerHeight: h)
        #endif
        let bar = min(200, h * 0.3)
        let controlArea = landscape ? Monitor.centredArea(width: w, height: h, side: 90, bar: 70)
                                    : Monitor.centredArea(width: w, height: h, side: 10, bar: bar)
        let fullArea = landscape ? Monitor.centredArea(width: w, height: h, side: 8, bar: 8)
                                 : Monitor.centredArea(width: w, height: h, side: 4, bar: bar)
        area = controlArea
        zoom = camera.zoom
        guard let ref = reference, w > 0, h > 0 else { frame = video; return }
        if camera.isCinemaCameraFeed {
            // The picture comes from the cinema camera itself: only the delivery-aspect mask applies.
            lines = FrameLines(widthFraction: ref.deliveredWidthMm / ref.captureWidthMm, heightFraction: ref.deliveredHeightMm / ref.captureHeightMm)
            area = video
            frame = Viewfinder.frameRect(lines, in: video)
            return
        }
        view = RecceKit.PreviewView.fromFormat(longSideFov: camera.baseLongSideFov, longOverShort: camera.formatAspect, portrait: !landscape)
        func layout(_ a: ScreenRect) -> Monitor.Layout {
            Monitor.layout(reference: ref, view: view, video: video, area: a, minZoom: camera.minZoom, maxZoom: max(camera.maxZoom, camera.minZoom),
                           margin: margin, fixedZoom: fixedZoom)
        }
        var l = layout(controlArea)
        if !l.lines.fits { l = layout(fullArea); area = fullArea }
        lines = l.lines; frame = l.frame; zoom = l.zoom
        if !l.lines.fits {
            let tw = area.width * l.lines.widthFraction, th = area.height * l.lines.heightFraction
            trueFrame = ScreenRect(x: area.midX - tw / 2, y: area.midY - th / 2, width: tw, height: th)
        }
    }

    var cgFrame: CGRect { CGRect(x: frame.x, y: frame.y, width: frame.width, height: frame.height) }
    var cgVideo: CGRect { CGRect(x: video.x, y: video.y, width: video.width, height: video.height) }
}

private let tStops: [Double] = [0.95, 1.0, 1.2, 1.3, 1.4, 1.5, 1.8, 1.9, 2.0, 2.2, 2.4, 2.5, 2.8, 3.5, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0]

struct ViewfinderView: View {
    @Bindable var shot: RecceShot
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @StateObject private var camera = CameraController()
    @StateObject private var motion = LevelMotion()
    @StateObject private var scopeModel = ScopeModel()
    @StateObject private var luts = LutLibrary.shared

    @AppStorage("vf.thirds") private var showThirds = true
    @AppStorage("vf.center") private var showCenter = false
    @AppStorage("vf.level") private var showLevel = true
    @AppStorage("vf.safe") private var showSafe = false
    @AppStorage("vf.autoZoom") private var autoZoom = true
    @AppStorage("vf.shotSizes") private var showShotSizes = true
    @AppStorage("vf.compare") private var compare = false
    @AppStorage("vf.surroundings") private var surroundings = false
    @AppStorage("vf.distortion") private var showDistortion = true
    @AppStorage("vf.phoneExposure") private var exposureModeRaw = PhoneExposureMode.match.rawValue
    @AppStorage(CameraController.macFovKey) private var macFov: Double = 70
    @State private var locked = false
    @State private var markerMode: MarkerType?
    @State private var showSettings = false
    @State private var showExposure = false
    @State private var flash = false
    @State private var message: String?
    @State private var chipOpen = false
    @State private var lastLayout = ViewfinderLayout()
    // Live picture tools (iPhone): LUT, false colour, zebras, focus peaking, scopes.
    @AppStorage("vf.falseColour") private var falseColourOn = false
    @AppStorage("vf.zebras") private var zebrasOn = false
    @AppStorage("vf.zebraLevel") private var zebraLevel = Zebra.defaultLevel
    @AppStorage("vf.peaking") private var peakingOn = false
    @AppStorage("vf.peakingColour") private var peakingColourRaw = PeakingColour.red.rawValue
    @AppStorage("vf.peakingLevel") private var peakingLevelRaw = PeakingLevel.medium.rawValue
    @AppStorage("vf.scope") private var scopeRaw = ScopeKind.none.rawValue
    @State private var loadedLut: LoadedLut?
    @State private var lutCompare = false
    @State private var lutSplit = 0.5
    @State private var importingLut = false

    private struct LoadedLut { let id: String; let lut: Lut3D }

    private var exposureMode: PhoneExposureMode { PhoneExposureMode(rawValue: exposureModeRaw) ?? .match }
    private var scopeKind: ScopeKind { ScopeKind(rawValue: scopeRaw) ?? ScopeKind.none }
    private var toolsOn: Bool { falseColourOn || zebrasOn || peakingOn || scopeKind != ScopeKind.none }
    private var focals: [Double] { FocalOptions.focals(shot.lens) }
    private var margin: Double {
        if compare, let minOther = compareFocals().min() { return max(1, shot.focalMm / minOther) * 1.03 }
        return surroundings ? 1.35 : 1
    }

    var body: some View {
        GeometryReader { geo in
            let landscape = geo.size.width > geo.size.height
            let layout = ViewfinderLayout(size: geo.size, reference: shot.reference, camera: camera, margin: margin,
                                          fixedZoom: (locked || !autoZoom) ? camera.zoom : nil)
            let picture = pictureSettings(layout, width: Double(geo.size.width))
            ZStack {
                Color.black
                CameraPreview(controller: camera)
                #if os(iOS)
                if picture.drawsPicture && camera.status == .running {
                    LivePictureView(feed: camera.feed).allowsHitTesting(false)
                }
                #endif
                FrameOverlay(layout: layout, locked: locked, showThirds: showThirds, showCenter: showCenter, showSafe: showSafe,
                             maskOpacity: surroundings ? 0.3 : 0.55, label: frameLabel, approximate: camera.fovSource == .assumed,
                             markers: shot.markers)
                    .allowsHitTesting(false)
                extraOverlays(layout).allowsHitTesting(false)
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .local) { point in addMarker(at: point, layout: layout) }
                    .allowsHitTesting(markerMode != nil)
                Group {
                    if lutCompare && picture.lut != nil { SplitHandle(fraction: $lutSplit) }
                    if flash { Color.white.opacity(0.6).allowsHitTesting(false) }
                }
                if landscape { landscapeHUD(layout) } else { portraitHUD(layout) }
                if picture.scope != ScopeKind.none && camera.status == .running {
                    VStack {
                        Spacer()
                        HStack { ScopePanel(model: scopeModel, kind: picture.scope); Spacer() }
                    }
                    .padding(.leading, landscape ? 70 : 10).padding(.bottom, landscape ? 60 : 230)
                }
                statusOverlay
            }
            .onAppear { lastLayout = layout; applyPicture(picture) }
            .onChange(of: picture) { _, p in applyPicture(p) }
            .onChange(of: layout.cgFrame) { _, _ in lastLayout = layout }
            .onChange(of: layout.zoom) { _, z in if autoZoom && !locked { camera.setZoom(z) } }
            .onChange(of: camera.status) { _, s in if s == .running, autoZoom, !locked { camera.setZoom(layout.zoom, animated: false) } }
        }
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .task {
            camera.feed.attach(scopes: scopeModel)
            await camera.start()
            motion.start()
            applyExposure()
        }
        .task(id: shot.lut) { await loadLut() }
        .onDisappear { camera.setPictureActive(false); camera.stop(); motion.stop() }
        .fileImporter(isPresented: $importingLut, allowedContentTypes: [UTType(filenameExtension: "cube") ?? .data, .plainText]) { result in
            switch result {
            case .success(let url): Task { await importLut(url) }
            case .failure(let error): show(error.localizedDescription)
            }
        }
        .onChange(of: exposureKey) { _, _ in applyExposure() }
        .onChange(of: camera.status) { _, _ in applyExposure() }
        .sheet(isPresented: $showSettings) { settingsSheet }
        .sheet(isPresented: $showExposure) { exposureSheet }
        #if os(macOS)
        .frame(minWidth: 900, minHeight: 560)
        #endif
    }

    // MARK: - HUD

    private func portraitHUD(_ layout: ViewfinderLayout) -> some View {
        ZStack {
            VStack(spacing: 6) {
                topRow(landscape: false)
                statusColumn(layout)
                Spacer()
                VStack(spacing: 4) {
                    exposureLine
                    focusLine
                }
                focalChips.padding(.horizontal, 8)
                HStack {
                    markerMenu
                    Spacer()
                    captureButton
                    Spacer()
                    lockButton
                }
                .padding(.horizontal, 24)
            }
            .padding(.top, 52).padding(.bottom, 30)
            HStack {
                VStack(spacing: 8) { shotSizeButtons }.padding(.leading, 10)
                Spacer()
                VStack(spacing: 10) { toolButtons }.padding(.trailing, 10)
            }
        }
    }

    private func landscapeHUD(_ layout: ViewfinderLayout) -> some View {
        ZStack {
            VStack(spacing: 2) {
                topRow(landscape: true)
                Spacer()
                HStack(spacing: 8) {
                    shotSizeButtons
                    focalChips
                }
                .padding(.leading, 70).padding(.trailing, 96)
            }
            .padding(.vertical, 10)
            HStack {
                VStack(spacing: 8) { toolButtons }.padding(.leading, 14)
                Spacer()
                VStack(spacing: 16) {
                    lockButton
                    captureButton
                    markerMenu
                }
                .padding(.trailing, 14)
            }
            VStack {
                HStack { Spacer(); statusColumn(layout).frame(maxWidth: 320, alignment: .trailing) }
                    .padding(.top, 50).padding(.trailing, 84)
                Spacer()
            }
        }
    }

    private func topRow(landscape: Bool) -> some View {
        HStack(spacing: 8) {
            Button { dismiss() } label: { Image(systemName: "xmark").font(.headline).padding(8).background(Theme.panel, in: Circle()) }
                .buttonStyle(.plain).foregroundStyle(.white)
            if !landscape { cameraPill }
            Spacer()
            VStack(spacing: 1) {
                Text(shot.focalLength).font(.system(size: landscape ? 22 : 30, weight: .regular, design: .monospaced)).foregroundStyle(Color(red: 0.79, green: 0.85, blue: 1))
                if landscape { exposureLine }
                let sub = subtitle(landscape: landscape)
                if !sub.isEmpty {
                    Text(sub).font(.caption2.monospaced()).foregroundStyle(Color(red: 0.69, green: 0.75, blue: 0.9)).lineLimit(1)
                }
            }
            Spacer()
            if landscape { cameraPill }
            aspectMenu
        }
        .padding(.horizontal, 14)
    }

    /// "H-FOV 51° · Teal & Orange" (the angle is in the exposure line's place in landscape).
    private func subtitle(landscape: Bool) -> String {
        var parts: [String] = []
        if !landscape, let ref = shot.reference { parts.append("H-FOV \(Int(ref.deliveredFov.horizontal.rounded()))°") }
        if let name = luts.name(shot.lut) { parts.append(name) }
        return parts.joined(separator: " · ")
    }

    private var cameraPill: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(shot.camera.model).font(.caption.bold()).lineLimit(1)
            Text(shot.lens.model).font(.caption2).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
        }
        .padding(.horizontal, 10).padding(.vertical, 5).background(Theme.panel, in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(.white)
        .frame(maxWidth: 170)
    }

    private var aspectMenu: some View {
        Menu {
            ForEach(ShotPresets.aspectRatios, id: \.self) { a in
                Button { shot.aspectRatio = a; shot.touch() } label: {
                    if a == shot.aspectRatio { Label(a, systemImage: "checkmark") } else { Text(a) }
                }
            }
        } label: { Text(shot.aspectRatio).font(.caption.monospaced().weight(.semibold)).hudChip() }
            .disabled(locked)
    }

    // MARK: status chip (lens, calibration, warnings)

    private struct Note: Identifiable { let id = UUID(); let short: String; let full: String; let warning: Bool }

    private func notes(_ layout: ViewfinderLayout) -> [Note] {
        var out: [Note] = []
        if shot.reference != nil, !layout.lines.fits, !camera.isCinemaCameraFeed {
            let wider = Int(((max(layout.lines.widthFraction, layout.lines.heightFraction) - 1) * 100).rounded())
            out.append(Note(short: "Wider than the phone", full: "Wider than this phone's widest lens can see (\(wider)% more): the frame shows the phone's widest view.", warning: true))
        }
        if let d = dof, d.tooClose {
            out.append(Note(short: "Too close to focus", full: String(format: "Closer than this lens can focus (%.2f m).", shot.lens.minimumFocusDistance), warning: true))
        }
        if let m = matchSettings, abs(m.stopsOff) > 0.5 {
            let text = m.stopsOff > 0 ? String(format: "%.1f stops dark", m.stopsOff) : String(format: "%.1f stops bright", -m.stopsOff)
            out.append(Note(short: text, full: "Phone picture \(text)er than the real exposure (the phone's limit).", warning: false))
        }
        return out
    }

    private func statusColumn(_ layout: ViewfinderLayout) -> some View {
        let list = notes(layout)
        let effective = Optics.fov(fromHalfTan: Optics.halfTan(camera.baseLongSideFov) / max(camera.zoom, 0.1))
        let base = "\(camera.currentLensName) · \(Int(effective.rounded()))° · \(camera.fovSource.short)"
        let warn = list.contains { $0.warning }
        return VStack(spacing: 6) {
            if camera.status == .running || !list.isEmpty {
                VStack(spacing: 4) {
                    Group {
                        if let first = list.first {
                            Text((first.warning ? "⚠ " : "") + first.short + (list.count > 1 ? "  +\(list.count - 1)" : ""))
                        } else { Text(base) }
                    }
                    .font(.caption.weight(.semibold)).lineLimit(1)
                    if chipOpen {
                        ForEach(list) { n in Text(n.full).font(.caption) }
                        Text("\(base)\n\(camera.fovSource.label)").font(.caption2).foregroundStyle(Color(red: 0.79, green: 0.85, blue: 1))
                            .multilineTextAlignment(.center)
                    }
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(warn ? Color(red: 0.54, green: 0.23, blue: 0).opacity(0.85) : Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 14))
                .onTapGesture { chipOpen.toggle() }
            }
            if falseColourOn && !camera.isCinemaCameraFeed { FalseColourLegend() }
            if let message { Text(message).hudChip().transition(.opacity) }
            if let mode = markerMode { Label("Tap inside the frame: \(mode.label)", systemImage: mode.symbol).hudChip(true) }
        }
        .frame(maxWidth: 340)
    }

    // MARK: exposure / focus lines

    private var exposureLine: some View {
        let parts = [Exposure.stop(shot.aperture).map { ShotPresets.tStopText($0) }, "ISO \(shot.iso)", "\(shot.fps)p", shot.shutter]
            .compactMap { $0 }.joined(separator: " · ")
        var text = Text(parts)
        if let t = motion.tilt {
            let level = abs(t.roll) < 1
            text = text + Text(" · ") + Text(level ? "Level ✓" : "Level \(Int(t.roll.rounded()))°").foregroundColor(level ? .green : .white)
                + Text(" · Pitch \(Int(t.pitch.rounded()))°")
        }
        return Button { showExposure = true } label: {
            text.font(.system(size: 13, design: .monospaced)).lineLimit(1).truncationMode(.tail)
                .foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 6).background(Color.black.opacity(0.4), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private var dof: Focus.Result? {
        guard let d = shot.estimatedDistance else { return nil }
        let stop = Exposure.stop(shot.aperture) ?? (shot.lens.maximumAperture > 0 ? shot.lens.maximumAperture : 0)
        return Focus.depthOfField(focalMm: shot.focalMm, stop: stop, distanceM: d, cocMm: Focus.circleOfConfusionMm(shot.sensorMode),
                                  closeFocusM: shot.lens.minimumFocusDistance)
    }

    @ViewBuilder private var focusLine: some View {
        if let d = shot.estimatedDistance {
            Button { showExposure = true } label: {
                Text((["Focus \(Focus.distanceText(d))", dof.map(Focus.dofText)].compactMap { $0 }).joined(separator: " · "))
                    .font(.system(size: 11, design: .monospaced)).lineLimit(1)
                    .foregroundStyle(dof?.tooClose == true ? Color.orange : Color(red: 0.79, green: 0.85, blue: 1))
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: buttons

    private var focalChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(focals, id: \.self) { f in
                    Button { setFocal(f) } label: {
                        Text(f.rounded() == f ? "\(Int(f))" : String(format: "%.1f", f)).font(.callout.monospacedDigit().weight(.semibold))
                            .frame(minWidth: 40).padding(.vertical, 8).padding(.horizontal, 6)
                            .background(abs(shot.focalMm - f) < 0.5 ? Color.blue : Color.white.opacity(0.85), in: RoundedRectangle(cornerRadius: 9))
                            .foregroundStyle(abs(shot.focalMm - f) < 0.5 ? Color.white : Color.black)
                    }
                    .buttonStyle(.plain)
                    .disabled(locked)
                }
            }
        }
    }

    @ViewBuilder private var shotSizeButtons: some View {
        if showShotSizes {
            ForEach(ShotSize.allCases, id: \.self) { size in
                let target = FocalOptions.focal(for: size, focals: focals, mode: shot.sensorMode, squeeze: shot.lens.anamorphicSqueeze)
                let active = target.map { abs($0 - shot.focalMm) < 0.5 } ?? false
                Button {
                    if let f = target, !locked { setFocal(f); shot.shotType = size.shotType; shot.touch() }
                } label: {
                    Text(String(size.label.prefix(1))).font(.caption.bold()).frame(width: 32, height: 32)
                        .background(active ? Color.blue : Color.white.opacity(0.85), in: Circle())
                        .foregroundStyle(active ? Color.white : Color.black)
                }
                .buttonStyle(.plain)
                .opacity(0.72)
            }
        }
    }

    @ViewBuilder private var toolButtons: some View {
        roundButton("Grid", on: showThirds) { showThirds.toggle() }
        roundButton("Level", on: showLevel) { showLevel.toggle() }
        #if os(iOS)
        lutMenu
        expoMenu
        #else
        roundButton("Expo", on: exposureMode == .match) { showExposure = true }
        #endif
        roundButton("Setup", on: false) { showSettings = true }
    }

    private func roundButton(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { roundLabel(title, on: on) }
            .buttonStyle(.plain)
    }

    private func roundLabel(_ title: String, on: Bool) -> some View {
        Text(title).font(.system(size: 10, weight: .bold)).frame(width: 42, height: 42)
            .background(on ? Color.blue : Color.white.opacity(0.9), in: Circle())
            .foregroundStyle(on ? Color.white : Color.black)
    }

    /// The shot's look: built-in looks and imported .cube files, before / after, and what the LUT expects as input.
    private var lutMenu: some View {
        Menu {
            Button { setLut(nil) } label: {
                if shot.lut == nil { Label("No LUT", systemImage: "checkmark") } else { Text("No LUT") }
            }
            ForEach(luts.entries) { e in
                Button { setLut(e.id) } label: {
                    let title = e.name + (e.builtIn ? "" : " (.cube)")
                    if e.id == shot.lut { Label(title, systemImage: "checkmark") } else { Text(title) }
                }
            }
            Divider()
            if let id = shot.lut {
                Toggle("Before / after", isOn: $lutCompare)
                Picker("LUT expects", selection: Binding(get: { luts.input(id) }, set: { luts.setInput($0, for: id) })) {
                    ForEach(LutInput.allCases, id: \.self) { Text($0.label).tag($0) }
                }
            }
            Button("Import a .cube LUT…") { importingLut = true }
        } label: { roundLabel("LUT", on: shot.lut != nil) }
    }

    /// False colour, zebras, focus peaking and the scopes, plus the exposure settings.
    private var expoMenu: some View {
        Menu {
            Toggle("False colour", isOn: $falseColourOn)
            Toggle("Zebras", isOn: $zebrasOn)
            if zebrasOn {
                Picker("Zebra level", selection: $zebraLevel) {
                    ForEach(Zebra.levels, id: \.self) { Text("\($0)%").tag($0) }
                }
            }
            Toggle("Focus peaking", isOn: $peakingOn)
            if peakingOn {
                Picker("Peaking colour", selection: $peakingColourRaw) {
                    ForEach(PeakingColour.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
                }
                Picker("Peaking sensitivity", selection: $peakingLevelRaw) {
                    ForEach(PeakingLevel.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
                }
            }
            Divider()
            Picker("Scope", selection: $scopeRaw) {
                ForEach(ScopeKind.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
            }
            Divider()
            Button("Exposure settings…") { showExposure = true }
            Text("Measures the phone's picture (exposed to follow ISO, shutter, ND and iris), through the LUT. A guide for the scene, not the camera's own signal. Peaking shows what is sharp for the phone's lens.")
        } label: { roundLabel("Expo", on: toolsOn) }
    }

    private var markerMenu: some View {
        Menu {
            Button("Stop placing markers") { markerMode = nil }
            ForEach(MarkerType.allCases) { t in
                Button { markerMode = t } label: { Label(t.label, systemImage: t.symbol) }
            }
            if !shot.markers.isEmpty {
                Divider()
                Button("Remove all markers", role: .destructive) { removeMarkers() }
            }
        } label: { Image(systemName: "mappin.and.ellipse").hudChip(markerMode != nil) }
    }

    private var captureButton: some View {
        Button { Task { await capture() } } label: {
            Circle().strokeBorder(.white, lineWidth: 4).frame(width: 66, height: 66)
                .overlay(Circle().fill(.white).padding(7))
        }
        .buttonStyle(.plain)
        .disabled(camera.status != .running)
        .accessibilityLabel("Capture reference photo")
    }

    private var lockButton: some View {
        Button { locked.toggle() } label: { Image(systemName: locked ? "lock.fill" : "lock.open").hudChip(locked) }
            .buttonStyle(.plain)
            .accessibilityLabel(locked ? "Unlock zoom" : "Lock zoom")
    }

    @ViewBuilder private var statusOverlay: some View {
        switch camera.status {
        case .denied:
            ContentUnavailableView("Camera access is off", systemImage: "camera.fill",
                                   description: Text("Allow camera access for BMPCC Control in Settings › Privacy."))
                .foregroundStyle(.white)
        case .unavailable(let why):
            ContentUnavailableView("Camera unavailable", systemImage: "video.slash", description: Text(why))
                .foregroundStyle(.white)
        case .starting:
            ProgressView().tint(.white)
        default:
            EmptyView()
        }
    }

    // MARK: extra overlays: level, distortion, compare

    private func extraOverlays(_ layout: ViewfinderLayout) -> some View {
        let frame = layout.cgFrame
        let tilt = motion.tilt
        let distortion = distortionPoints(layout)
        let others = compare ? compareFrames(layout) : []
        return Canvas { ctx, _ in
            guard frame.width > 1 else { return }
            if showLevel, let t = tilt {
                let c = CGPoint(x: frame.midX, y: frame.midY), half = frame.width * 0.3
                let level = abs(t.roll) < 1
                let a = CGFloat(-t.roll * .pi / 180)
                var p = Path()
                p.move(to: CGPoint(x: c.x - half * cos(a), y: c.y - half * sin(a)))
                p.addLine(to: CGPoint(x: c.x + half * cos(a), y: c.y + half * sin(a)))
                ctx.stroke(p, with: .color(level ? .green : .white), lineWidth: 2)
                var ticks = Path()
                ticks.move(to: CGPoint(x: c.x - half - 18, y: c.y)); ticks.addLine(to: CGPoint(x: c.x - half - 6, y: c.y))
                ticks.move(to: CGPoint(x: c.x + half + 6, y: c.y)); ticks.addLine(to: CGPoint(x: c.x + half + 18, y: c.y))
                ctx.stroke(ticks, with: .color(.white.opacity(0.7)), lineWidth: 2)
            }
            if distortion.count > 2 {
                var p = Path()
                p.move(to: distortion[0]); for q in distortion.dropFirst() { p.addLine(to: q) }; p.closeSubpath()
                ctx.stroke(p, with: .color(.orange), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
            }
            for (f, r) in others {
                let colour = Color(red: 1, green: 0.88, blue: 0.54)
                ctx.stroke(Path(r), with: .color(colour.opacity(0.85)), style: StrokeStyle(lineWidth: 1.2, dash: [10, 6]))
                ctx.draw(Text(ShotPresets.focalText(f)).font(.caption2.monospaced().bold()).foregroundColor(colour),
                         at: CGPoint(x: r.minX + 4, y: r.minY + 2), anchor: .topLeading)
            }
        }
    }

    /// Where the frame edges really fall for a lens with a distortion profile (screen points).
    private func distortionPoints(_ layout: ViewfinderLayout) -> [CGPoint] {
        guard showDistortion, layout.lines.fits, let ref = shot.reference,
              let model = Distortion.model(for: shot.lens, focalMm: shot.focalMm),
              Distortion.withinCalibration(shot.lens, widthMm: ref.deliveredWidthMm, heightMm: ref.deliveredHeightMm),
              ref.tanHalfH > 0, ref.tanHalfV > 0 else { return [] }
        let f = layout.frame
        return Distortion.frameOutline(model, widthMm: ref.deliveredWidthMm, heightMm: ref.deliveredHeightMm, focalMm: ref.effectiveFocalLengthMm)
            .map { CGPoint(x: f.midX + $0.0 / ref.tanHalfH * f.width / 2, y: f.midY + $0.1 / ref.tanHalfV * f.height / 2) }
    }

    private func compareFocals() -> [Double] {
        let others = focals.filter { abs($0 - shot.focalMm) >= 0.5 }
        if others.count <= 6 { return others }
        return Array(Set((0..<6).map { others[$0 * (others.count - 1) / 5] })).sorted()
    }

    private func compareFrames(_ layout: ViewfinderLayout) -> [(Double, CGRect)] {
        let zoomed = Monitor.viewAcross(layout.view, video: layout.video, area: layout.area).zoomed(layout.zoom)
        return compareFocals().compactMap { f in
            let lens = FocalOptions.lens(for: shot.lens, focal: f)
            guard let ref = Framing.reference(camera: shot.camera, lens: lens, focalLengthMm: f, aspectRatio: shot.aspectValue) else { return nil }
            let lines = Viewfinder.frameLines(ref, on: zoomed)
            guard lines.fits else { return nil }
            let r = Viewfinder.frameRect(lines, in: layout.area)
            return (f, CGRect(x: r.x, y: r.y, width: r.width, height: r.height))
        }
    }

    private var frameLabel: String {
        [shot.focalLength.isEmpty ? nil : shot.focalLength, shot.aspectRatio, shot.reference.map { "\(Int($0.deliveredFov.horizontal.rounded()))°" }]
            .compactMap { $0 }.joined(separator: " · ")
    }

    // MARK: - live picture

    private func pictureSettings(_ layout: ViewfinderLayout, width: Double) -> PictureSettings {
        var p = PictureSettings()
        #if os(iOS)
        guard !camera.isCinemaCameraFeed else { return p }
        if let id = shot.lut, let loaded = loadedLut, loaded.id == id {
            p.lut = loaded.lut
            p.input = luts.input(id)
            p.lutKey = "\(id)|\(p.input.rawValue)"
            if lutCompare, layout.video.width > 0 { p.split = (lutSplit * width - layout.video.x) / layout.video.width }
        }
        p.falseColour = falseColourOn
        p.zebraLevel = zebrasOn ? zebraLevel : nil
        p.peaking = peakingOn ? (PeakingLevel(rawValue: peakingLevelRaw) ?? .medium) : nil
        p.peakingColour = PeakingColour(rawValue: peakingColourRaw) ?? .red
        p.scope = scopeKind
        let c = Scopes.crop(frame: shot.reference == nil ? nil : layout.frame, video: layout.video)
        p.crop = CGRect(x: c.0, y: c.1, width: c.2 - c.0, height: c.3 - c.1)
        #endif
        return p
    }

    private func applyPicture(_ p: PictureSettings) {
        camera.feed.update(p)
        camera.setPictureActive(p.active)
    }

    private func setLut(_ id: String?) {
        shot.lut = id
        shot.touch()
        if id == nil { lutCompare = false }
    }

    /// Reads the shot's LUT in the background (files are parsed once, then cached).
    private func loadLut() async {
        guard let id = shot.lut else { loadedLut = nil; lutCompare = false; return }
        if loadedLut?.id == id { return }
        let lut = await Task.detached(priority: .userInitiated) { LutFiles.load(id) }.value
        guard shot.lut == id else { return }
        loadedLut = lut.map { LoadedLut(id: id, lut: $0) }
        if lut == nil { show("This LUT couldn't be read.") }
    }

    private func importLut(_ url: URL) async {
        do {
            let id = try await luts.importCube(from: url)
            setLut(id)
            show("LUT added: \(luts.name(id) ?? "LUT")")
        } catch {
            show(error.localizedDescription)
        }
    }

    // MARK: - exposure

    /// Changes whenever anything that affects the phone's exposure changes.
    private var exposureKey: String { [shot.iso, shot.shutter, shot.fps, shot.nd, shot.aperture, exposureModeRaw].joined(separator: "|") }

    private var matchSettings: ExposureMatch.Phone? {
        guard exposureMode == .match, camera.customExposureSupported,
              let k = ExposureMatch.target(iso: shot.iso, shutter: shot.shutter, fps: shot.fps, nd: shot.nd, aperture: shot.aperture,
                                           phoneAperture: camera.lensAperture) else { return nil }
        return ExposureMatch.phone(isoTimesSeconds: k, isoMin: camera.isoRange.lowerBound, isoMax: camera.isoRange.upperBound,
                                   minSeconds: camera.durationRange.lowerBound, maxSeconds: camera.durationRange.upperBound,
                                   preferredSeconds: ExposureMatch.exposureSeconds(shutter: shot.shutter, fps: shot.fps))
    }

    private func applyExposure() {
        guard camera.status == .running, !camera.isCinemaCameraFeed else { return }
        if let m = matchSettings { camera.setManualExposure(iso: m.iso, seconds: m.seconds); return }
        let ev = Exposure.ev(iso: shot.iso, shutter: shot.shutter, nd: shot.nd, aperture: shot.aperture)
        camera.setAutoExposure(bias: exposureMode == .auto ? 0 : ev)
    }

    private func binding(_ key: ReferenceWritableKeyPath<RecceShot, String>) -> Binding<String> {
        Binding(get: { shot[keyPath: key] }, set: { shot[keyPath: key] = $0; shot.touch() })
    }

    private var exposureSheet: some View {
        NavigationStack {
            Form {
                Section("Cinema camera") {
                    Picker("Frame rate", selection: binding(\.fps)) { ForEach(withCurrent(ShotPresets.frameRates, shot.fps), id: \.self) { Text($0).tag($0) } }
                    Picker("Shutter", selection: binding(\.shutter)) { ForEach(withCurrent(ShotPresets.shutterAngles, shot.shutter), id: \.self) { Text($0).tag($0) } }
                    Picker("ISO", selection: binding(\.iso)) { ForEach(withCurrent(ShotPresets.isos, shot.iso), id: \.self) { Text($0).tag($0) } }
                    Picker("ND", selection: binding(\.nd)) { ForEach(withCurrent(ShotPresets.ndFilters, shot.nd), id: \.self) { Text($0).tag($0) } }
                    Picker("Iris", selection: binding(\.aperture)) {
                        Text("Not set").tag("")
                        ForEach(irisOptions, id: \.self) { Text($0).tag($0) }
                    }
                    Picker("Focus distance", selection: Binding(get: { shot.estimatedDistance ?? 0 }, set: { shot.estimatedDistance = $0 > 0 ? $0 : nil; shot.touch() })) {
                        Text("Not set").tag(0.0)
                        ForEach(withCurrent(Focus.presetsM, shot.estimatedDistance ?? 0).filter { $0 > 0 }, id: \.self) { Text(Focus.distanceText($0)).tag($0) }
                    }
                    if let d = dof {
                        Text(Focus.dofText(d) + " · hyperfocal " + Focus.distanceText(d.hyperfocalM)).font(.caption.monospaced())
                            .foregroundStyle(d.tooClose ? Color.orange : Color.secondary)
                    }
                }
                #if os(iOS)
                Section {
                    Picker("Phone picture", selection: $exposureModeRaw) {
                        ForEach(PhoneExposureMode.allCases) { Text($0.label).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                } header: { Text("Phone picture exposure") } footer: {
                    if exposureMode == .match && !camera.customExposureSupported {
                        Text(exposureMode.detail + " This camera doesn't allow manual exposure, so it follows instead.")
                    } else if let m = matchSettings {
                        Text(exposureMode.detail + String(format: "\nPhone set to ISO %.0f · 1/%.0f s.", m.iso, 1 / max(m.seconds, 1e-6)))
                    } else if exposureMode == .match {
                        Text(exposureMode.detail + " Needs ISO, shutter, frame rate and iris set.")
                    } else { Text(exposureMode.detail) }
                }
                #endif
            }
            .formStyle(.grouped)
            .navigationTitle("Exposure")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showExposure = false } } }
        }
        .presentationDetents([.medium, .large])
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }

    /// T-stops this lens can reach, plus the shot's own value (older shots store "f/2.8").
    private var irisOptions: [String] {
        let list = tStops.filter { !shot.lens.hasAperture || $0 >= shot.lens.maximumAperture - 0.001 }.map { ShotPresets.tStopText($0) }
        return shot.aperture.isEmpty ? list : withCurrent(list, shot.aperture)
    }

    private func withCurrent<T: Hashable>(_ list: [T], _ current: T) -> [T] { list.contains(current) ? list : [current] + list }

    // MARK: settings

    private var settingsSheet: some View {
        NavigationStack {
            Form {
                Section("Guides") {
                    Toggle("Rule of thirds", isOn: $showThirds)
                    Toggle("Centre cross", isOn: $showCenter)
                    Toggle("Level", isOn: $showLevel)
                    Toggle("Safe areas (90% / 80%)", isOn: $showSafe)
                    Toggle("Show outside the frame", isOn: $surroundings)
                    Toggle("Compare focal lengths", isOn: $compare)
                    if Distortion.hasProfile(shot.lens) { Toggle("Lens distortion", isOn: $showDistortion) }
                    Toggle("W / M / C buttons", isOn: $showShotSizes)
                }
                #if os(iOS)
                Section {
                    Toggle("Auto zoom to the frame", isOn: $autoZoom)
                    if !autoZoom {
                        Slider(value: Binding(get: { camera.zoom }, set: { camera.setZoom($0, animated: false) }),
                               in: camera.minZoom...max(camera.maxZoom, camera.minZoom + 0.01)) { Text("Zoom") }
                    }
                } header: { Text("Phone camera") } footer: {
                    Text("\(camera.currentLensName) lens in use (\(String(format: "%.1f", camera.displayZoom))×). Field of view \(camera.baseLongSideFov.degreesText) at the widest zoom: \(camera.fovSource.label.lowercased()). The phone switches to its ultra-wide by itself when the frame needs it.")
                }
                #else
                Section {
                    if camera.availableDevices.count > 1 {
                        Picker("Camera", selection: Binding(get: { camera.availableDevices.first { $0.localizedName == camera.deviceName }?.uniqueID ?? "" },
                                                            set: { id in Task { await camera.switchTo(deviceID: id) } })) {
                            ForEach(camera.availableDevices, id: \.uniqueID) { d in Text(d.localizedName).tag(d.uniqueID) }
                        }
                    }
                    VStack(alignment: .leading) {
                        Text("Horizontal field of view: \(Int(macFov))°")
                        Slider(value: $macFov, in: 30...120, step: 1)
                            .onChange(of: macFov) { _, _ in camera.reloadMacFov() }
                    }
                } header: { Text("Mac camera") } footer: {
                    Text("A Mac can't report its camera's field of view. Most built-in FaceTime cameras are about 65–80°. A Blackmagic camera connected over USB shows its own picture, so only the aspect mask and guides are drawn.")
                }
                #endif
            }
            .formStyle(.grouped)
            .navigationTitle("Viewfinder")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSettings = false } } }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 420)
        #endif
    }

    // MARK: actions

    /// Sets the focal length; for a prime set this switches to the set's lens at that focal length.
    private func setFocal(_ f: Double) {
        guard !locked else { show("Zoom is locked"); return }
        let target = FocalOptions.lens(for: shot.lens, focal: f)
        if target.id != shot.lens.id { shot.apply(lens: target) }
        shot.focalLength = ShotPresets.focalText(target.isZoom ? target.clampFocal(f) : target.focalLengthMin)
        shot.touch()
    }

    private func addMarker(at point: CGPoint, layout: ViewfinderLayout) {
        guard let type = markerMode else { return }
        guard let p = Viewfinder.normalisedPoint(x: Double(point.x), y: Double(point.y), in: layout.frame) else {
            show("Tap inside the frame lines")
            return
        }
        let marker = ShotMarker(type: type, x: p.x, y: p.y)
        context.insert(marker)
        marker.shot = shot
        shot.touch()
        try? context.save()
    }

    private func removeMarkers() {
        for m in shot.markers { context.delete(m) }
        shot.touch()
        try? context.save()
    }

    private func capture() async {
        do {
            let data = try await camera.capturePhoto()
            let name = try FileStore.saveReference(data)
            let ref = ShotReference(fileName: name)
            let l = lastLayout
            if l.video.width > 0, l.video.height > 0 {
                let r = Monitor.frameInPicture(l.frame, video: l.video)
                ref.frameX = r.x; ref.frameY = r.y; ref.frameWidth = r.width; ref.frameHeight = r.height
            }
            context.insert(ref)
            ref.shot = shot
            shot.touch()
            try context.save()
            withAnimation(.easeOut(duration: 0.15)) { flash = true }
            try? await Task.sleep(nanoseconds: 150_000_000)
            withAnimation { flash = false }
            show("Frame saved to shot \(shot.shotNumber)")
        } catch {
            show(error.localizedDescription)
        }
    }

    private func show(_ text: String) {
        withAnimation { message = text }
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            await MainActor.run { withAnimation { if message == text { message = nil } } }
        }
    }
}

/// Frame mask, frame line, corners, label, guides and markers.
struct FrameOverlay: View {
    let layout: ViewfinderLayout
    let locked: Bool
    let showThirds: Bool
    let showCenter: Bool
    let showSafe: Bool
    let maskOpacity: Double
    let label: String
    /// The phone's angle is assumed (not reported): the frame line is dashed to show it is approximate.
    let approximate: Bool
    let markers: [ShotMarker]

    var body: some View {
        ZStack {
            Canvas { ctx, size in
                let frame = layout.cgFrame
                guard frame.width > 1, frame.height > 1 else { return }
                // See-through mask outside the cinema frame (the live picture stays visible).
                var mask = Path(CGRect(origin: .zero, size: size))
                mask.addRect(frame)
                ctx.fill(mask, with: .color(.black.opacity(maskOpacity)), style: FillStyle(eoFill: true))
                let guide = Color.white.opacity(0.45)
                if showThirds {
                    var p = Path()
                    for i in 1...2 {
                        let x = frame.minX + frame.width * CGFloat(i) / 3
                        let y = frame.minY + frame.height * CGFloat(i) / 3
                        p.move(to: CGPoint(x: x, y: frame.minY)); p.addLine(to: CGPoint(x: x, y: frame.maxY))
                        p.move(to: CGPoint(x: frame.minX, y: y)); p.addLine(to: CGPoint(x: frame.maxX, y: y))
                    }
                    ctx.stroke(p, with: .color(guide), lineWidth: 0.8)
                }
                if showCenter {
                    let c = CGPoint(x: frame.midX, y: frame.midY), s: CGFloat = 12
                    var p = Path()
                    p.move(to: CGPoint(x: c.x - s, y: c.y)); p.addLine(to: CGPoint(x: c.x + s, y: c.y))
                    p.move(to: CGPoint(x: c.x, y: c.y - s)); p.addLine(to: CGPoint(x: c.x, y: c.y + s))
                    ctx.stroke(p, with: .color(.white.opacity(0.9)), lineWidth: 1.5)
                }
                if showSafe {
                    for f in [0.9, 0.8] {
                        let r = Viewfinder.safeArea(layout.frame, fraction: f)
                        ctx.stroke(Path(CGRect(x: r.x, y: r.y, width: r.width, height: r.height)),
                                   with: .color(guide), style: StrokeStyle(lineWidth: 0.8, dash: [4, 4]))
                    }
                }
                // Frame line with a dark edge (visible on bright scenes); orange when wider than the phone sees.
                let clipped = !layout.lines.fits
                let colour: Color = locked ? Theme.locked : (clipped ? .orange : Theme.frameLine)
                ctx.stroke(Path(frame), with: .color(.black.opacity(0.55)), lineWidth: 3.5)
                ctx.stroke(Path(frame), with: .color(colour), style: StrokeStyle(lineWidth: 1.5, dash: approximate ? [14, 8] : []))
                // The true frame edges continue off screen when wider than the phone sees.
                if let t = layout.trueFrame {
                    ctx.stroke(Path(CGRect(x: t.x, y: t.y, width: t.width, height: t.height)), with: .color(.orange.opacity(0.8)),
                               style: StrokeStyle(lineWidth: 1.5, dash: [10, 10]))
                    let wider = Int(((max(layout.lines.widthFraction, layout.lines.heightFraction) - 1) * 100).rounded())
                    if wider > 0 {
                        ctx.draw(Text("Frame \(wider)% wider than the phone sees").font(.caption2.weight(.semibold)).foregroundColor(.orange),
                                 at: CGPoint(x: frame.minX + 6, y: frame.maxY - 6), anchor: .bottomLeading)
                    }
                }
                // Corner marks on the true corners, outlined.
                let b: CGFloat = 18
                for (col, width) in [(Color.black.opacity(0.6), CGFloat(5)), (Color.white, CGFloat(3))] {
                    var p = Path()
                    let corners: [(CGPoint, CGFloat, CGFloat)] = [
                        (CGPoint(x: frame.minX, y: frame.minY), 1, 1), (CGPoint(x: frame.maxX, y: frame.minY), -1, 1),
                        (CGPoint(x: frame.minX, y: frame.maxY), 1, -1), (CGPoint(x: frame.maxX, y: frame.maxY), -1, -1)]
                    for (pt, dx, dy) in corners {
                        p.move(to: CGPoint(x: pt.x - dx * width / 2, y: pt.y)); p.addLine(to: CGPoint(x: pt.x + dx * b, y: pt.y))
                        p.move(to: CGPoint(x: pt.x, y: pt.y - dy * width / 2)); p.addLine(to: CGPoint(x: pt.x, y: pt.y + dy * b))
                    }
                    ctx.stroke(p, with: .color(col), lineWidth: width)
                }
                if !label.isEmpty {
                    ctx.draw(Text(label).font(.caption2.monospaced()).foregroundColor(.white.opacity(0.9)),
                             at: CGPoint(x: frame.minX + 8, y: frame.minY + 6), anchor: .topLeading)
                }
            }
            ForEach(markers) { m in
                Image(systemName: m.markerType.symbol)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(6)
                    .background(Theme.accent, in: Circle())
                    .position(x: layout.cgFrame.minX + layout.cgFrame.width * m.x,
                              y: layout.cgFrame.minY + layout.cgFrame.height * m.y)
            }
        }
    }
}


// MARK: - live picture views

private func colour(_ argb: UInt32) -> Color {
    Color(red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255, blue: Double(argb & 0xFF) / 255)
}

/// The false colour key: band colours and their levels (ARRI's published bands).
struct FalseColourLegend: View {
    var body: some View {
        HStack(spacing: 4) {
            ForEach(FalseColour.bands, id: \.name) { b in
                RoundedRectangle(cornerRadius: 2).fill(colour(b.argb)).frame(width: 9, height: 9)
                Text(b.label.replacingOccurrences(of: "%", with: "")).font(.system(size: 9, design: .monospaced)).foregroundStyle(.white)
            }
            Text("%").font(.system(size: 9)).foregroundStyle(.white)
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
    }
}

/// Waveform, RGB parade or histogram of the picture inside the frame, with the share of clipped and crushed pixels.
/// Tap to enlarge.
struct ScopePanel: View {
    @ObservedObject var model: ScopeModel
    let kind: ScopeKind
    @State private var big = false

    var body: some View {
        let w: CGFloat = big ? 250 : 140, h: CGFloat = big ? 130 : 70
        let result = model.result?.kind == kind ? model.result : nil
        VStack(alignment: .leading, spacing: 2) {
            ZStack(alignment: .topLeading) {
                Canvas { ctx, size in
                    let grid = Color.white.opacity(0.33)
                    if kind == .histogram {
                        for f in [0.25, 0.5, 0.75] {
                            var p = Path(); p.move(to: CGPoint(x: size.width * f, y: 0)); p.addLine(to: CGPoint(x: size.width * f, y: size.height))
                            ctx.stroke(p, with: .color(grid), lineWidth: 1)
                        }
                        if let hist = result?.histogram {
                            let peak = Double(hist.peak)
                            func trace(_ bins: [Int]) -> Path {
                                var p = Path()
                                let step = size.width / CGFloat(max(bins.count - 1, 1))
                                p.move(to: CGPoint(x: 0, y: size.height))
                                for (i, n) in bins.enumerated() { p.addLine(to: CGPoint(x: CGFloat(i) * step, y: size.height * CGFloat(1 - Double(n) / peak))) }
                                p.addLine(to: CGPoint(x: size.width, y: size.height)); p.closeSubpath()
                                return p
                            }
                            ctx.fill(trace(hist.r), with: .color(Color(red: 1, green: 0.25, blue: 0.25).opacity(0.4)))
                            ctx.fill(trace(hist.g), with: .color(Color(red: 0.25, green: 1, blue: 0.25).opacity(0.4)))
                            ctx.fill(trace(hist.b), with: .color(Color(red: 0.25, green: 0.5, blue: 1).opacity(0.4)))
                            ctx.stroke(trace(hist.y), with: .color(.white.opacity(0.87)), lineWidth: 1.5)
                        }
                    } else {
                        for f in [0.0, 0.25, 0.5, 0.75, 1.0] {
                            let y = size.height * (1 - f)
                            var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
                            ctx.stroke(p, with: .color(grid), style: StrokeStyle(lineWidth: 1, dash: f == 0 || f == 1 ? [] : [4, 4]))
                        }
                        if kind == .parade {
                            for f in [1.0 / 3, 2.0 / 3] {
                                var p = Path(); p.move(to: CGPoint(x: size.width * f, y: 0)); p.addLine(to: CGPoint(x: size.width * f, y: size.height))
                                ctx.stroke(p, with: .color(grid), lineWidth: 1)
                            }
                        }
                    }
                }
                if kind != .histogram, let img = result?.image {
                    Image(decorative: img, scale: 1).resizable().interpolation(.medium)
                }
                if result == nil { Text("…").foregroundStyle(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
                if kind != .histogram {
                    Text("100").font(.system(size: 8)).foregroundStyle(.white.opacity(0.6))
                }
            }
            .frame(width: w, height: h)
            if let hist = result?.histogram {
                Text((big ? kind.label + " · " : "") + String(format: "Clip %.1f%% · Crush %.1f%%", hist.clipped * 100, hist.crushed * 100))
                    .font(.system(size: big ? 10 : 8, design: .monospaced)).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
            }
        }
        .padding(4)
        .background(Color.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
        .onTapGesture { big.toggle() }
    }
}

/// Draggable divider for the LUT before / after view (left: no LUT, right: LUT).
struct SplitHandle: View {
    @Binding var fraction: Double

    var body: some View {
        GeometryReader { g in
            let x = CGFloat(fraction) * g.size.width, midY = g.size.height / 2
            ZStack {
                Path { p in p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: g.size.height)) }
                    .stroke(.white, lineWidth: 2)
                    .allowsHitTesting(false)
                Circle().fill(Theme.accent).frame(width: 22, height: 22).overlay(Circle().stroke(.white, lineWidth: 3))
                    .position(x: x, y: midY).allowsHitTesting(false)
                Text("BEFORE").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                    .position(x: max(30, x - 40), y: midY + 28).allowsHitTesting(false)
                Text("AFTER").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                    .position(x: x + 34, y: midY + 28).allowsHitTesting(false)
                Color.clear
                    .frame(width: 48, height: g.size.height * 0.6)
                    .contentShape(Rectangle())
                    .position(x: x, y: midY)
                    .gesture(DragGesture(coordinateSpace: .named("split")).onChanged { v in
                        fraction = min(max(Double(v.location.x / max(g.size.width, 1)), 0.05), 0.95)
                    })
            }
            .coordinateSpace(.named("split"))
        }
    }
}
