import SwiftUI
import SwiftData
import RecceKit

struct ShotDetailView: View {
    @Bindable var shot: RecceShot
    @Environment(\.modelContext) private var context
    @State private var viewfinderShot: RecceShot?
    @State private var showNote = false

    var body: some View {
        Form {
            Section {
                Button { viewfinderShot = shot } label: {
                    Label("Open director's viewfinder", systemImage: "viewfinder").font(.headline)
                }
            }

            Section("Shot") {
                TextField("Shot number", text: $shot.shotNumber)
                Picker("Shot type", selection: $shot.shotType) {
                    ForEach(ShotType.allCases) { Text("\($0.label) – \($0.longName)").tag($0) }
                }
                Picker("Camera movement", selection: $shot.movement) {
                    ForEach(CameraMovement.allCases) { Text($0.label).tag($0) }
                }
                TextField("Subject movement", text: $shot.subjectMovement)
                TextField("Camera position", text: $shot.cameraPosition)
                TextField("Subject position", text: $shot.subjectPosition)
                PresetPicker(title: "Camera height", value: $shot.cameraHeight, options: ShotPresets.cameraHeights)
                LabeledContent("Distance to subject (m)") {
                    TextField("m", value: $shot.estimatedDistance, format: .number)
                        .multilineTextAlignment(.trailing)
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                }
            }

            Section("Camera & lens") {
                NavigationLink { LibraryView(mode: .pickCamera { c in shot.apply(camera: c) }) } label: {
                    LabeledContent("Camera", value: shot.cameraModel)
                }
                NavigationLink { LibraryView(mode: .pickLens { l in shot.apply(lens: l) }) } label: {
                    LabeledContent("Lens", value: shot.lensModel)
                }
                FocalControl(shot: shot)
                TextField("Aperture / T-stop", text: $shot.aperture)
                PresetPicker(title: "Aspect ratio", value: $shot.aspectRatio, options: ShotPresets.aspectRatios)
            }

            Section("Exposure") {
                PresetPicker(title: "Frame rate", value: $shot.fps, options: ShotPresets.frameRates)
                PresetPicker(title: "Shutter", value: $shot.shutter, options: ShotPresets.shutterAngles)
                PresetPicker(title: "ISO", value: $shot.iso, options: ShotPresets.isos)
                PresetPicker(title: "ND", value: $shot.nd, options: ShotPresets.ndFilters)
                PresetPicker(title: "White balance", value: $shot.whiteBalance, options: ShotPresets.whiteBalances)
            }

            FramingSection(shot: shot)

            Section("Reference photos") {
                let refs = shot.references.sorted { $0.date < $1.date }
                if refs.isEmpty {
                    Text("Capture frames from the viewfinder; they're saved here with the frame lines.").foregroundStyle(.secondary)
                }
                ForEach(refs) { ref in ReferenceThumbnail(reference: ref) }
                    .onDelete { idx in
                        for i in idx {
                            try? FileManager.default.removeItem(at: refs[i].fileURL)
                            context.delete(refs[i])
                        }
                        try? context.save()
                    }
            }

            if !shot.markers.isEmpty {
                Section("Markers") {
                    ForEach(shot.markers) { m in
                        Label("\(m.markerType.label)  (\(Int(m.x * 100))%, \(Int(m.y * 100))%)", systemImage: m.markerType.symbol)
                    }
                    .onDelete { idx in
                        let list = shot.markers
                        for i in idx { context.delete(list[i]) }
                        try? context.save()
                    }
                }
            }

            Section("Notes") {
                TextField("Shot notes", text: $shot.notes, axis: .vertical).lineLimit(2...8)
                if let session = shot.scene?.session {
                    ForEach(session.sortedNotes.filter { $0.shotId == shot.id }) { NoteRow(note: $0) }
                    Button { showNote = true } label: { Label("Add voice or text note", systemImage: "mic.fill") }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Shot \(shot.shotNumber)")
        .viewfinderCover(shot: $viewfinderShot)
        .sheet(isPresented: $showNote) {
            if let session = shot.scene?.session { NoteComposer(session: session, shotId: shot.id) }
        }
        .onDisappear { shot.touch(); try? context.save() }
    }
}

/// A picker of common values that also keeps a custom value (e.g. one imported from Android).
struct PresetPicker: View {
    let title: String
    @Binding var value: String
    let options: [String]

    var body: some View {
        let all = options.contains(value) || value.isEmpty ? options : [value] + options
        Picker(title, selection: $value) {
            ForEach(all, id: \.self) { Text($0).tag($0) }
        }
    }
}

/// Focal length: a slider for zooms, fixed for primes, plus quick buttons.
struct FocalControl: View {
    @Bindable var shot: RecceShot

    var body: some View {
        let lens = shot.lens
        VStack(alignment: .leading, spacing: 8) {
            LabeledContent("Focal length", value: shot.focalLength)
            if lens.isZoom {
                Slider(value: Binding(get: { shot.focalMm },
                                      set: { shot.focalLength = ShotPresets.focalText($0.rounded()); shot.touch() }),
                       in: lens.focalLengthMin...lens.focalLengthMax, step: 1)
            }
            if lens.quickFocalLengths.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(lens.quickFocalLengths, id: \.self) { f in
                            Button(ShotPresets.focalText(f)) { shot.focalLength = ShotPresets.focalText(f); shot.touch() }
                                .buttonStyle(.bordered)
                                .tint(abs(shot.focalMm - f) < 0.5 ? Theme.accent : .gray)
                        }
                    }
                }
            }
        }
    }
}

/// Calculated framing for the shot (RecceKit).
struct FramingSection: View {
    let shot: RecceShot

    var body: some View {
        Section {
            if let ref = shot.reference {
                LabeledContent("Horizontal view", value: ref.deliveredFov.horizontal.degreesText)
                LabeledContent("Vertical view", value: ref.deliveredFov.vertical.degreesText)
                LabeledContent("Diagonal view", value: ref.deliveredFov.diagonal.degreesText)
                LabeledContent("Full-frame equivalent",
                               value: "\(Int(Optics.equivalentFocalLength(shot.focalMm, sensorWidthMm: shot.camera.sensorWidthMm).rounded()))mm")
                LabeledContent("Crop factor", value: String(format: "%.2fx", ref.cropFactor))
                if shot.lens.isAnamorphic {
                    LabeledContent("Anamorphic", value: String(format: "%.1fx squeeze, desqueezed %.2f:1", shot.lens.anamorphicSqueeze,
                                                                ref.captureWidthMm / ref.captureHeightMm))
                }
                LabeledContent("Lens on camera", value: Compatibility.check(camera: shot.camera, lens: shot.lens).label)
                LabeledContent("Minimum focus", value: String(format: "%.2f m", shot.lens.minimumFocusDistance))
            } else {
                Text("Check the focal length and aspect ratio.").foregroundStyle(.secondary)
            }
        } header: { Text("Framing") } footer: {
            Text("Calculated from the \(shot.camera.model)'s sensor (\(String(format: "%.2f × %.2f mm", shot.camera.sensorWidthMm, shot.camera.sensorHeightMm))) with rectilinear lens geometry.")
        }
    }
}

struct ReferenceThumbnail: View {
    let reference: ShotReference
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let img = ImageLoader.load(reference.fileURL) {
                Image(platformImage: img).resizable().scaledToFit()
                    .overlay {
                        GeometryReader { geo in
                            if let x = reference.frameX, let y = reference.frameY, let w = reference.frameWidth, let h = reference.frameHeight {
                                Rectangle().stroke(Theme.frameLine, lineWidth: 2)
                                    .frame(width: geo.size.width * w, height: geo.size.height * h)
                                    .position(x: geo.size.width * (x + w / 2), y: geo.size.height * (y + h / 2))
                            }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            } else {
                Label("Photo not on this device (\(reference.fileName))", systemImage: "photo.badge.exclamationmark")
                    .foregroundStyle(.secondary)
            }
            Text(reference.date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
        }
    }
}
