import SwiftUI
import RecceKit

/// Built-in cameras and lenses (same list as the Android app). Used to browse specs, mark favourites,
/// set the defaults for new shots, or pick a camera/lens for a shot.
struct LibraryView: View {
    enum Mode {
        case browse
        case pickCamera((CameraProfile) -> Void)
        case pickLens((LensProfile) -> Void)
    }

    enum Tab: String, CaseIterable, Identifiable {
        case cameras = "Cameras", lenses = "Lenses"
        var id: String { rawValue }
    }

    var mode: Mode = .browse

    @Environment(\.dismiss) private var dismiss
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"
    @AppStorage(Prefs.favoriteCameras) private var favCamerasCSV = ""
    @AppStorage(Prefs.favoriteLenses) private var favLensesCSV = ""
    @State private var tab: Tab = .cameras
    @State private var query = ""

    init(mode: Mode = .browse) {
        self.mode = mode
        switch mode {
        case .pickLens: _tab = State(initialValue: .lenses)
        default: _tab = State(initialValue: .cameras)
        }
    }

    private var isPicking: Bool {
        if case .browse = mode { return false }
        return true
    }

    var body: some View {
        List {
            if !isPicking {
                Picker("Show", selection: $tab) {
                    ForEach(Tab.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
            }
            if tab == .cameras { cameraRows } else { lensRows }
        }
        .searchable(text: $query, prompt: tab == .cameras ? "Search cameras" : "Search lenses")
        .navigationTitle(title)
    }

    private var title: String {
        switch mode {
        case .browse: return "Library"
        case .pickCamera: return "Choose camera"
        case .pickLens: return "Choose lens"
        }
    }

    // MARK: Cameras

    @ViewBuilder private var cameraRows: some View {
        let favs = Self.ids(favCamerasCSV)
        let found = BuiltInLibrary.searchCameras(query)
        let favourites = found.filter { favs.contains($0.id) }
        let others = found.filter { !favs.contains($0.id) }
        if !favourites.isEmpty {
            Section("Favourites") { ForEach(favourites) { cameraRow($0, favs: favs) } }
        }
        Section(favourites.isEmpty ? "Cameras" : "All cameras") { ForEach(others) { cameraRow($0, favs: favs) } }
        if found.isEmpty { Text("No camera matches “\(query)”.").foregroundStyle(.secondary) }
    }

    private func cameraRow(_ c: CameraProfile, favs: Set<String>) -> some View {
        Group {
            switch mode {
            case .pickCamera(let pick):
                Button { pick(c); dismiss() } label: { CameraLabel(camera: c, isDefault: c.id == defaultCameraId) }
                    .foregroundStyle(.primary)
            default:
                NavigationLink { CameraDetail(camera: c) } label: { CameraLabel(camera: c, isDefault: c.id == defaultCameraId) }
            }
        }
        .swipeActions(edge: .trailing) {
            Button { favCamerasCSV = Self.toggle(c.id, in: favCamerasCSV) } label: {
                Label(favs.contains(c.id) ? "Unfavourite" : "Favourite", systemImage: favs.contains(c.id) ? "star.slash" : "star")
            }.tint(.yellow)
        }
        .contextMenu {
            Button { favCamerasCSV = Self.toggle(c.id, in: favCamerasCSV) } label: {
                Label(favs.contains(c.id) ? "Remove from favourites" : "Add to favourites", systemImage: "star")
            }
            Button { defaultCameraId = c.id } label: { Label("Use for new shots", systemImage: "checkmark.circle") }
        }
    }

    // MARK: Lenses

    @ViewBuilder private var lensRows: some View {
        let favs = Self.ids(favLensesCSV)
        let found = BuiltInLibrary.searchLenses(query)
        let favourites = found.filter { favs.contains($0.id) }
        let others = found.filter { !favs.contains($0.id) }
        if !favourites.isEmpty {
            Section("Favourites") { ForEach(favourites) { lensRow($0, favs: favs) } }
        }
        Section(favourites.isEmpty ? "Lenses" : "All lenses") { ForEach(others) { lensRow($0, favs: favs) } }
        if found.isEmpty { Text("No lens matches “\(query)”.").foregroundStyle(.secondary) }
    }

    private func lensRow(_ l: LensProfile, favs: Set<String>) -> some View {
        let camera = BuiltInLibrary.camera(id: defaultCameraId) ?? BuiltInLibrary.cameras[0]
        return Group {
            switch mode {
            case .pickLens(let pick):
                Button { pick(l); dismiss() } label: { LensLabel(lens: l, camera: camera, isDefault: l.id == defaultLensId) }
                    .foregroundStyle(.primary)
            default:
                NavigationLink { LensDetail(lens: l) } label: { LensLabel(lens: l, camera: camera, isDefault: l.id == defaultLensId) }
            }
        }
        .swipeActions(edge: .trailing) {
            Button { favLensesCSV = Self.toggle(l.id, in: favLensesCSV) } label: {
                Label(favs.contains(l.id) ? "Unfavourite" : "Favourite", systemImage: favs.contains(l.id) ? "star.slash" : "star")
            }.tint(.yellow)
        }
        .contextMenu {
            Button { favLensesCSV = Self.toggle(l.id, in: favLensesCSV) } label: {
                Label(favs.contains(l.id) ? "Remove from favourites" : "Add to favourites", systemImage: "star")
            }
            Button { defaultLensId = l.id } label: { Label("Use for new shots", systemImage: "checkmark.circle") }
        }
    }

    // MARK: Favourites stored as comma-separated ids

    static func ids(_ csv: String) -> Set<String> {
        Set(csv.split(separator: ",").map { String($0) }.filter { !$0.isEmpty })
    }

    static func toggle(_ id: String, in csv: String) -> String {
        var set = ids(csv)
        if set.contains(id) { set.remove(id) } else { set.insert(id) }
        return set.sorted().joined(separator: ",")
    }
}

private struct CameraLabel: View {
    let camera: CameraProfile
    let isDefault: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(camera.displayName).font(.headline)
                if isDefault { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent) }
            }
            Text(String(format: "%.2f × %.2f mm · %@ · %@", camera.sensorWidthMm, camera.sensorHeightMm,
                        camera.mount.rawValue, camera.nativeAspectRatio))
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct LensLabel: View {
    let lens: LensProfile
    let camera: CameraProfile
    let isDefault: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(lens.displayName).font(.headline)
                if isDefault { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent) }
            }
            Text("\(LensDetail.rangeText(lens)) · T\(String(format: "%.1f", lens.maximumAperture)) · \(lens.mount.rawValue)"
                 + (lens.isAnamorphic ? String(format: " · %.1fx anamorphic", lens.anamorphicSqueeze) : ""))
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct CameraDetail: View {
    let camera: CameraProfile
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"

    var body: some View {
        let lens = BuiltInLibrary.lens(id: defaultLensId) ?? BuiltInLibrary.lenses[0]
        Form {
            Section("Sensor") {
                LabeledContent("Size", value: String(format: "%.2f × %.2f mm", camera.sensorWidthMm, camera.sensorHeightMm))
                LabeledContent("Diagonal", value: String(format: "%.2f mm", camera.sensorDiagonalMm))
                LabeledContent("Crop factor", value: String(format: "%.2fx", Optics.cropFactor(sensorWidthMm: camera.sensorWidthMm)))
                LabeledContent("Resolution", value: "\(camera.resolutionWidth) × \(camera.resolutionHeight)")
                LabeledContent("Native aspect", value: camera.nativeAspectRatio)
                LabeledContent("Mount", value: camera.mount.rawValue)
                LabeledContent("Type", value: camera.cameraType)
                LabeledContent("Data", value: camera.verificationStatus.rawValue.capitalized)
            }
            Section {
                FovTable(camera: camera, lens: lens)
            } header: { Text("Field of view with \(lens.model)") } footer: {
                Text("Horizontal × vertical angle for the default lens. Change the default lens in the Lenses list.")
            }
            Section {
                Button(camera.id == defaultCameraId ? "Default for new shots ✓" : "Use for new shots") { defaultCameraId = camera.id }
                    .disabled(camera.id == defaultCameraId)
            }
        }
        .formStyle(.grouped)
        .navigationTitle(camera.model)
    }
}

struct LensDetail: View {
    let lens: LensProfile
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"

    static func rangeText(_ l: LensProfile) -> String {
        l.isZoom ? "\(ShotPresets.focalText(l.focalLengthMin))–\(ShotPresets.focalText(l.focalLengthMax))"
                 : ShotPresets.focalText(l.focalLengthMin)
    }

    var body: some View {
        let camera = BuiltInLibrary.camera(id: defaultCameraId) ?? BuiltInLibrary.cameras[0]
        Form {
            Section("Lens") {
                LabeledContent("Focal length", value: Self.rangeText(lens))
                LabeledContent("Maximum aperture", value: String(format: "T%.1f", lens.maximumAperture))
                LabeledContent("Minimum focus", value: String(format: "%.2f m", lens.minimumFocusDistance))
                LabeledContent("Image circle", value: String(format: "%.1f mm", lens.imageCircleMm))
                LabeledContent("Mount", value: lens.mount.rawValue)
                LabeledContent("Type", value: lens.lensType.rawValue.capitalized)
                if lens.isAnamorphic { LabeledContent("Squeeze", value: String(format: "%.1fx", lens.anamorphicSqueeze)) }
                LabeledContent("Data", value: lens.verificationStatus.rawValue.capitalized)
            }
            Section {
                LabeledContent("On \(camera.model)", value: Compatibility.check(camera: camera, lens: lens).label)
                FovTable(camera: camera, lens: lens)
            } header: { Text("On the default camera") }
            Section {
                Button(lens.id == defaultLensId ? "Default for new shots ✓" : "Use for new shots") { defaultLensId = lens.id }
                    .disabled(lens.id == defaultLensId)
            }
        }
        .formStyle(.grouped)
        .navigationTitle(lens.model)
    }
}

/// Field of view at each of the lens's useful focal lengths on a given camera.
struct FovTable: View {
    let camera: CameraProfile
    let lens: LensProfile
    var body: some View {
        ForEach(lens.quickFocalLengths, id: \.self) { f in
            if let ref = Framing.reference(camera: camera, lens: lens, focalLengthMm: f) {
                LabeledContent(ShotPresets.focalText(f)) {
                    Text("\(ref.deliveredFov.horizontal.degreesText) × \(ref.deliveredFov.vertical.degreesText)")
                        .font(Theme.mono)
                }
            }
        }
    }
}
