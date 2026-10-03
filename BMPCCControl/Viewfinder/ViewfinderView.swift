import SwiftUI
import SwiftData
import RecceKit

/// Everything the overlay needs to draw, computed with RecceKit (tested there).
struct ViewfinderLayout {
    var video: ScreenRect = ScreenRect(x: 0, y: 0, width: 0, height: 0)
    var frame: ScreenRect = ScreenRect(x: 0, y: 0, width: 0, height: 0)
    var lines = FrameLines(widthFraction: 1, heightFraction: 1)

    init() {}

    @MainActor init(size: CGSize, reference: ReferenceFrame?, camera: CameraController) {
        let videoAspect = camera.isPortrait ? 1 / camera.formatAspect : camera.formatAspect
        video = Viewfinder.aspectFit(contentAspect: videoAspect, containerWidth: Double(size.width), containerHeight: Double(size.height))
        guard let ref = reference else { frame = video; return }
        if camera.isCinemaCameraFeed {
            // The picture comes from the cinema camera itself: only the delivery-aspect mask applies.
            lines = FrameLines(widthFraction: ref.deliveredWidthMm / ref.captureWidthMm,
                               heightFraction: ref.deliveredHeightMm / ref.captureHeightMm)
        } else {
            let base = RecceKit.PreviewView.fromFormat(longSideFov: camera.baseLongSideFov, longOverShort: camera.formatAspect,
                                                       portrait: camera.isPortrait)
            lines = Viewfinder.frameLines(ref, on: base.zoomed(camera.zoom))
        }
        frame = Viewfinder.frameRect(lines, in: video)
    }

    var cgFrame: CGRect { CGRect(x: frame.x, y: frame.y, width: frame.width, height: frame.height) }
    var cgVideo: CGRect { CGRect(x: video.x, y: video.y, width: video.width, height: video.height) }
}

struct ViewfinderView: View {
    @Bindable var shot: RecceShot
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @StateObject private var camera = CameraController()

    @AppStorage("vf.thirds") private var showThirds = true
    @AppStorage("vf.center") private var showCenter = true
    @AppStorage("vf.horizon") private var showHorizon = false
    @AppStorage("vf.safe") private var showSafe = false
    @AppStorage("vf.autoZoom") private var autoZoom = true
    @AppStorage(CameraController.macFovKey) private var macFov: Double = 70
    @State private var locked = false
    @State private var markerMode: MarkerType?
    @State private var showSettings = false
    @State private var flash = false
    @State private var message: String?
    @State private var lastLayout = ViewfinderLayout()

    var body: some View {
        GeometryReader { geo in
            let layout = ViewfinderLayout(size: geo.size, reference: shot.reference, camera: camera)
            ZStack {
                Color.black
                CameraPreview(controller: camera)
                FrameOverlay(layout: layout, locked: locked, showThirds: showThirds, showCenter: showCenter,
                             showHorizon: showHorizon, showSafe: showSafe, markers: shot.markers)
                    .allowsHitTesting(false)
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .local) { point in addMarker(at: point, layout: layout) }
                    .allowsHitTesting(markerMode != nil)
                if flash { Color.white.opacity(0.6).allowsHitTesting(false) }
                VStack(spacing: 0) {
                    topBar(layout)
                    Spacer()
                    if let message { Text(message).hudChip().padding(.bottom, 6).transition(.opacity) }
                    bottomBar
                }
                .padding(12)
                statusOverlay
            }
            .onAppear { lastLayout = layout }
            .onChange(of: layout.cgFrame) { _, _ in lastLayout = layout }
        }
        .ignoresSafeArea(edges: .bottom)
        .preferredColorScheme(.dark)
        .task {
            await camera.start()
            applyAutoZoom()
        }
        .onDisappear { camera.stop() }
        .onChange(of: shot.focalLength) { _, _ in applyAutoZoom() }
        .onChange(of: shot.aspectRatio) { _, _ in applyAutoZoom() }
        .onChange(of: shot.lensId) { _, _ in applyAutoZoom() }
        .onChange(of: shot.cameraId) { _, _ in applyAutoZoom() }
        .onChange(of: autoZoom) { _, _ in applyAutoZoom() }
        .onChange(of: camera.isPortrait) { _, _ in applyAutoZoom() }
        .sheet(isPresented: $showSettings) { settingsSheet }
        #if os(macOS)
        .frame(minWidth: 900, minHeight: 560)
        #endif
    }

    // MARK: top HUD

    private func topBar(_ layout: ViewfinderLayout) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Button { dismiss() } label: { Image(systemName: "xmark").font(.headline).padding(8).background(Theme.panel, in: Circle()) }
                    .buttonStyle(.plain).foregroundStyle(.white)
                VStack(alignment: .leading, spacing: 1) {
                    Text(shot.camera.model).font(.caption.bold())
                    Text(shot.lens.model).font(.caption2).foregroundStyle(.white.opacity(0.75))
                }
                .padding(.horizontal, 10).padding(.vertical, 5).background(Theme.panel, in: RoundedRectangle(cornerRadius: 8))
                .foregroundStyle(.white)
                Spacer()
                Text(shot.focalLength).font(.title3.monospacedDigit().weight(.heavy)).hudChip(true)
                if let ref = shot.reference {
                    Text("H \(ref.deliveredFov.horizontal.degreesText)").hudChip()
                    Text("≈\(Int(Optics.equivalentFocalLength(shot.focalMm, sensorWidthMm: shot.camera.sensorWidthMm).rounded()))mm FF").hudChip()
                }
                Text(shot.aspectRatio).hudChip()
            }
            HStack(spacing: 8) {
                if camera.isCinemaCameraFeed {
                    Label("Live from \(camera.deviceName)", systemImage: "video.fill").hudChip(true)
                } else {
                    Text("Phone zoom \(String(format: "%.1f", camera.zoom))x").hudChip()
                    Text(camera.fovIsMeasured ? "FOV measured" : "FOV \(Int(camera.baseLongSideFov))° (set in ⚙︎)").hudChip()
                }
                if !layout.lines.fits && !camera.isCinemaCameraFeed {
                    Label("Lens is wider than this camera: frame shows only what fits", systemImage: "exclamationmark.triangle.fill")
                        .hudChip().foregroundStyle(.yellow)
                }
                if let mode = markerMode { Label("Tap to place: \(mode.label)", systemImage: mode.symbol).hudChip(true) }
                Spacer()
            }
        }
    }

    // MARK: bottom dock

    private var bottomBar: some View {
        HStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(shot.lens.quickFocalLengths, id: \.self) { f in
                        Button(ShotPresets.focalText(f)) { setFocal(f) }
                            .buttonStyle(.plain)
                            .hudChip(abs(shot.focalMm - f) < 0.5)
                            .disabled(locked)
                    }
                }
            }
            .frame(maxWidth: 360)

            Menu {
                ForEach(ShotPresets.aspectRatios, id: \.self) { a in
                    Button { shot.aspectRatio = a; shot.touch() } label: {
                        if a == shot.aspectRatio { Label(a, systemImage: "checkmark") } else { Text(a) }
                    }
                }
            } label: { Image(systemName: "aspectratio").hudChip() }
                .disabled(locked)

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

            Spacer()

            Button { Task { await capture() } } label: {
                Circle().strokeBorder(.white, lineWidth: 4).frame(width: 62, height: 62)
                    .overlay(Circle().fill(Theme.accent).padding(8))
            }
            .buttonStyle(.plain)
            .disabled(camera.status != .running)
            .accessibilityLabel("Capture reference photo")

            Spacer()

            Button { locked.toggle() } label: { Image(systemName: locked ? "lock.fill" : "lock.open").hudChip(locked) }
                .buttonStyle(.plain)
            Button { showSettings = true } label: { Image(systemName: "gearshape").hudChip() }
                .buttonStyle(.plain)
        }
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

    // MARK: settings

    private var settingsSheet: some View {
        NavigationStack {
            Form {
                Section("Guides") {
                    Toggle("Rule of thirds", isOn: $showThirds)
                    Toggle("Centre cross", isOn: $showCenter)
                    Toggle("Horizon line", isOn: $showHorizon)
                    Toggle("Safe areas (90% / 80%)", isOn: $showSafe)
                }
                #if os(iOS)
                Section {
                    Toggle("Auto zoom to the frame", isOn: $autoZoom)
                    if !autoZoom {
                        Slider(value: Binding(get: { camera.zoom }, set: { camera.setZoom($0, animated: false) }),
                               in: camera.minZoom...max(camera.maxZoom, camera.minZoom + 0.01)) { Text("Zoom") }
                    }
                } header: { Text("Phone camera") } footer: {
                    Text("Field of view \(camera.baseLongSideFov.degreesText) at zoom 1, measured by the phone. Frame lines follow the zoom actually applied.")
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
        .frame(minWidth: 420, minHeight: 380)
        #endif
    }

    // MARK: actions

    private func setFocal(_ f: Double) {
        guard !locked else { return }
        shot.focalLength = ShotPresets.focalText(shot.lens.clampFocal(f))
        shot.touch()
    }

    private func applyAutoZoom() {
        guard autoZoom, !camera.isCinemaCameraFeed, camera.maxZoom > camera.minZoom, let ref = shot.reference else { return }
        let base = RecceKit.PreviewView.fromFormat(longSideFov: camera.baseLongSideFov, longOverShort: camera.formatAspect,
                                                   portrait: camera.isPortrait)
        camera.setZoom(Viewfinder.bestZoom(for: ref, base: base, minZoom: camera.minZoom, maxZoom: camera.maxZoom))
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
                ref.frameX = (l.frame.x - l.video.x) / l.video.width
                ref.frameY = (l.frame.y - l.video.y) / l.video.height
                ref.frameWidth = l.frame.width / l.video.width
                ref.frameHeight = l.frame.height / l.video.height
            }
            context.insert(ref)
            ref.shot = shot
            shot.touch()
            try context.save()
            withAnimation(.easeOut(duration: 0.15)) { flash = true }
            try? await Task.sleep(nanoseconds: 150_000_000)
            withAnimation { flash = false }
            show("Reference photo saved to shot \(shot.shotNumber)")
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

/// Frame lines, aspect mask, guides and markers.
struct FrameOverlay: View {
    let layout: ViewfinderLayout
    let locked: Bool
    let showThirds: Bool
    let showCenter: Bool
    let showHorizon: Bool
    let showSafe: Bool
    let markers: [ShotMarker]

    var body: some View {
        ZStack {
            Canvas { ctx, size in
                let frame = layout.cgFrame
                guard frame.width > 1, frame.height > 1 else { return }
                // Darken everything outside the cinema frame.
                var mask = Path(CGRect(origin: .zero, size: size))
                mask.addRect(frame)
                ctx.fill(mask, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))
                let line = Color.white.opacity(0.55)
                if showThirds {
                    var p = Path()
                    for i in 1...2 {
                        let x = frame.minX + frame.width * CGFloat(i) / 3
                        let y = frame.minY + frame.height * CGFloat(i) / 3
                        p.move(to: CGPoint(x: x, y: frame.minY)); p.addLine(to: CGPoint(x: x, y: frame.maxY))
                        p.move(to: CGPoint(x: frame.minX, y: y)); p.addLine(to: CGPoint(x: frame.maxX, y: y))
                    }
                    ctx.stroke(p, with: .color(line), lineWidth: 0.8)
                }
                if showCenter {
                    let c = CGPoint(x: frame.midX, y: frame.midY), s: CGFloat = 12
                    var p = Path()
                    p.move(to: CGPoint(x: c.x - s, y: c.y)); p.addLine(to: CGPoint(x: c.x + s, y: c.y))
                    p.move(to: CGPoint(x: c.x, y: c.y - s)); p.addLine(to: CGPoint(x: c.x, y: c.y + s))
                    ctx.stroke(p, with: .color(.white.opacity(0.9)), lineWidth: 1.5)
                }
                if showHorizon {
                    var p = Path()
                    p.move(to: CGPoint(x: frame.minX, y: frame.midY)); p.addLine(to: CGPoint(x: frame.maxX, y: frame.midY))
                    ctx.stroke(p, with: .color(.yellow.opacity(0.8)), style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
                }
                if showSafe {
                    for f in [0.9, 0.8] {
                        let r = Viewfinder.safeArea(layout.frame, fraction: f)
                        ctx.stroke(Path(CGRect(x: r.x, y: r.y, width: r.width, height: r.height)),
                                   with: .color(.white.opacity(0.45)), style: StrokeStyle(lineWidth: 0.8, dash: [4, 4]))
                    }
                }
                ctx.stroke(Path(frame), with: .color(locked ? Theme.locked : Theme.frameLine), lineWidth: 2)
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
