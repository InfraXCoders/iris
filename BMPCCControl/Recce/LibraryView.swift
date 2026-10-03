import SwiftUI
import RecceKit

/// Lens database and camera database: browse with search and filters, see specs and coverage, mark
/// favourites, set the defaults for new shots, or pick a camera/lens for a shot.
struct LibraryView: View {
    enum Mode {
        case browse
        case pickCamera((CameraProfile) -> Void)
        case pickLens((LensProfile) -> Void)
    }

    enum Tab: String, CaseIterable, Identifiable {
        case lenses = "Lenses", cameras = "Cameras"
        var id: String { rawValue }
    }

    var mode: Mode = .browse

    @Environment(\.dismiss) private var dismiss
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"
    @AppStorage(Prefs.favoriteCameras) private var favCamerasCSV = ""
    @AppStorage(Prefs.favoriteLenses) private var favLensesCSV = ""
    @State private var tab: Tab
    @State private var query = ""
    @State private var filter = LensFilter()

    init(mode: Mode = .browse, tab: Tab = .lenses) {
        self.mode = mode
        switch mode {
        case .pickLens: _tab = State(initialValue: .lenses)
        case .pickCamera: _tab = State(initialValue: .cameras)
        case .browse: _tab = State(initialValue: tab)
        }
    }

    private var isPicking: Bool {
        if case .browse = mode { return false }
        return true
    }

    private var defaultCamera: CameraProfile { Catalog.camera(id: defaultCameraId) ?? Catalog.defaultCamera }

    var body: some View {
        List {
            if !isPicking {
                Picker("Show", selection: $tab) {
                    ForEach(Tab.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
            }
            if tab == .lenses {
                LensFilterBar(filter: $filter, camera: defaultCamera)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
                lensRows
            } else {
                cameraRows
            }
        }
        .searchable(text: $query, prompt: tab == .cameras ? "Search cameras" : "Search lenses, series, mounts")
        .navigationTitle(title)
    }

    private var title: String {
        switch mode {
        case .browse: return tab == .lenses ? "Lens database" : "Camera database"
        case .pickCamera: return "Choose camera"
        case .pickLens: return "Choose lens"
        }
    }

    // MARK: Cameras

    @ViewBuilder private var cameraRows: some View {
        let favs = Self.ids(favCamerasCSV)
        let found = Catalog.searchCameras(query)
        let favourites = found.filter { favs.contains($0.id) }
        if !favourites.isEmpty {
            Section("Favourites") { ForEach(favourites) { cameraRow($0, favs: favs) } }
        }
        let groups = Dictionary(grouping: found.filter { !favs.contains($0.id) }, by: \.manufacturer)
        ForEach(groups.keys.sorted(), id: \.self) { maker in
            Section(maker) {
                ForEach(groups[maker]!.sorted { $0.model.localizedStandardCompare($1.model) == .orderedAscending }) {
                    cameraRow($0, favs: favs)
                }
            }
        }
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
        let camera = defaultCamera
        let found = filter.apply(Catalog.searchLenses(query), camera: camera)
        let favourites = found.filter { favs.contains($0.id) }
        if !favourites.isEmpty {
            Section("Favourites") { ForEach(favourites) { lensRow($0, favs: favs, camera: camera) } }
        }
        let rest = found.filter { !favs.contains($0.id) }
        let groups = Dictionary(grouping: rest) { LensGroup(lens: $0) }
        ForEach(groups.keys.sorted(), id: \.self) { g in
            Section {
                ForEach(groups[g]!.sorted { $0.focalLengthMin < $1.focalLengthMin }) { lensRow($0, favs: favs, camera: camera) }
            } header: {
                Text(g.title)
            }
        }
        if found.isEmpty {
            Text(query.isEmpty ? "No lens matches these filters." : "No lens matches “\(query)” with these filters.")
                .foregroundStyle(.secondary)
        } else {
            Text("\(found.count) lenses · specs from makers' published data (source link on each lens)")
                .font(.caption2).foregroundStyle(.secondary).listRowBackground(Color.clear)
        }
    }

    private func lensRow(_ l: LensProfile, favs: Set<String>, camera: CameraProfile) -> some View {
        Group {
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

/// "ARRI/ZEISS · Master Anamorphic" section of the lens list.
struct LensGroup: Hashable, Comparable {
    let manufacturer: String
    let series: String
    init(lens: LensProfile) {
        manufacturer = lens.manufacturer
        series = lens.series ?? "Other lenses"
    }
    var title: String { "\(manufacturer) · \(series)" }
    static func < (a: LensGroup, b: LensGroup) -> Bool {
        a.title.localizedStandardCompare(b.title) == .orderedAscending
    }
}

// MARK: - Filters

struct LensFilter: Equatable {
    enum Kind: String, CaseIterable { case all = "All", anamorphic = "Anamorphic", spherical = "Spherical" }
    var kind: Kind = .all
    var manufacturer: String?
    var squeeze: Double?
    var format: String?
    var mount: String?
    var coversDefaultCamera = false

    var isActive: Bool { self != LensFilter() }

    func apply(_ lenses: [LensProfile], camera: CameraProfile) -> [LensProfile] {
        lenses.filter { l in
            switch kind {
            case .all: break
            case .anamorphic: if !l.isAnamorphic { return false }
            case .spherical: if l.isAnamorphic { return false }
            }
            if let m = manufacturer, l.manufacturer != m { return false }
            if let s = squeeze, abs(l.anamorphicSqueeze - s) > 0.001 { return false }
            if let f = format, l.format != f { return false }
            if let m = mount, !l.allMountNames.contains(m) { return false }
            if coversDefaultCamera {
                let c = Coverage.evaluate(lens: l, mode: camera.sensorModes[0])
                if c != .full { return false }
            }
            return true
        }
    }

    static let manufacturers: [String] = Array(Set(Catalog.lenses.map(\.manufacturer))).sorted()
    static let squeezes: [Double] = Array(Set(Catalog.lenses.map(\.anamorphicSqueeze))).sorted()
    static let formats: [String] = ["MFT", "APS-C", "S35", "FF", "LF", "65"].filter { (f: String) -> Bool in Catalog.lenses.contains { $0.format == f } }
    static let mounts: [String] = {
        var counts: [String: Int] = [:]
        for l in Catalog.lenses { for m in l.allMountNames { counts[m, default: 0] += 1 } }
        return counts.keys.sorted { counts[$0]! > counts[$1]! }
    }()
}

struct LensFilterBar: View {
    @Binding var filter: LensFilter
    let camera: CameraProfile

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Picker("Type", selection: $filter.kind) {
                    ForEach(LensFilter.Kind.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .fixedSize()

                chipMenu(filter.manufacturer ?? "Maker", active: filter.manufacturer != nil) {
                    Button("Any maker") { filter.manufacturer = nil }
                    ForEach(LensFilter.manufacturers, id: \.self) { (m: String) in Button(m) { filter.manufacturer = Optional(m) } }
                }
                chipMenu(filter.squeeze.map { String(format: "%gx", $0) } ?? "Squeeze", active: filter.squeeze != nil) {
                    Button("Any squeeze") { filter.squeeze = nil }
                    ForEach(LensFilter.squeezes, id: \.self) { (s: Double) in
                        Button(s == 1 ? "1x (spherical)" : String(format: "%gx", s)) { filter.squeeze = Optional(s) }
                    }
                }
                chipMenu(filter.format ?? "Format", active: filter.format != nil) {
                    Button("Any format") { filter.format = nil }
                    ForEach(LensFilter.formats, id: \.self) { (f: String) in Button(f) { filter.format = Optional(f) } }
                }
                chipMenu(filter.mount ?? "Mount", active: filter.mount != nil) {
                    Button("Any mount") { filter.mount = nil }
                    ForEach(LensFilter.mounts, id: \.self) { (m: String) in Button(m) { filter.mount = Optional(m) } }
                }
                Toggle(isOn: $filter.coversDefaultCamera) { Text("Covers \(camera.model)") }
                    .toggleStyle(.button)
                    .tint(Theme.accent)
                if filter.isActive {
                    Button { filter = LensFilter() } label: { Image(systemName: "arrow.counterclockwise") }
                        .buttonStyle(.bordered)
                        .help("Clear filters")
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func chipMenu<Content: View>(_ title: String, active: Bool, @ViewBuilder content: () -> Content) -> some View {
        Menu {
            content()
        } label: {
            HStack(spacing: 4) {
                Text(title).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption2)
            }
            .font(.callout)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(active ? Theme.accent.opacity(0.85) : Color.gray.opacity(0.2), in: Capsule())
            .foregroundStyle(active ? .white : .primary)
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .fixedSize()
    }
}

// MARK: - Rows

private struct CameraLabel: View {
    let camera: CameraProfile
    let isDefault: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(camera.model).font(.headline)
                if isDefault { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent) }
            }
            let modes = camera.sensorModes.count
            Text(String(format: "%.2f × %.2f mm", camera.sensorWidthMm, camera.sensorHeightMm)
                 + " · \(camera.allMountNames.joined(separator: "/"))"
                 + (modes > 1 ? " · \(modes) modes" : ""))
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct LensLabel: View {
    let lens: LensProfile
    let camera: CameraProfile
    let isDefault: Bool
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(LensDetail.rangeText(lens)).font(.headline.monospacedDigit())
                    Text(ShotPresets.tStopText(lens.maximumAperture)).font(.subheadline.monospacedDigit())
                    if lens.isAnamorphic {
                        Text(String(format: "%gx", lens.anamorphicSqueeze)).font(.caption.bold())
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Theme.accent.opacity(0.3), in: Capsule())
                    }
                    if isDefault { Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.accent) }
                }
                Text(lens.series == nil ? lens.displayName : details)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            CoverageBadge(coverage: Coverage.evaluate(lens: lens, mode: camera.sensorModes[0]),
                          nominal: Coverage.circle(for: lens)?.nominal ?? false)
        }
    }

    private var details: String {
        var parts: [String] = []
        if lens.hasImageCircle { parts.append(String(format: "IC %.1f mm", lens.imageCircleMm)) } else if let f = lens.format { parts.append(f) }
        if let w = lens.weightG { parts.append(w >= 1000 ? String(format: "%.2f kg", w / 1000) : "\(Int(w)) g") }
        if let d = lens.frontDiameterMm { parts.append("Ø\(Int(d))") }
        if let m = lens.mountNames { parts.append(m.joined(separator: "/")) }
        return parts.joined(separator: " · ")
    }
}

/// Small coloured verdict for a lens on a sensor area.
struct CoverageBadge: View {
    let coverage: Coverage
    var nominal = false
    var body: some View {
        Image(systemName: symbol).foregroundStyle(color).opacity(nominal ? 0.6 : 1)
            .help(coverage.label + (nominal ? " (nominal image circle for its format)" : ""))
            .accessibilityLabel(coverage.label)
    }

    private var symbol: String {
        switch coverage {
        case .full: return "checkmark.circle.fill"
        case .cornersVignette: return "circle.lefthalf.filled"
        case .vignettes: return "xmark.circle.fill"
        case .unknown: return "questionmark.circle"
        }
    }

    private var color: Color {
        switch coverage {
        case .full: return .green
        case .cornersVignette: return .yellow
        case .vignettes: return .red
        case .unknown: return .gray
        }
    }
}

// MARK: - Details

struct CameraDetail: View {
    let camera: CameraProfile
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"

    var body: some View {
        let lens = Catalog.lens(id: defaultLensId) ?? Catalog.defaultLens
        Form {
            Section("Camera") {
                LabeledContent("Maker", value: camera.manufacturer)
                LabeledContent("Mount", value: camera.allMountNames.joined(separator: ", "))
                LabeledContent("Full sensor", value: String(format: "%.2f × %.2f mm", camera.sensorWidthMm, camera.sensorHeightMm))
                LabeledContent("Crop factor", value: String(format: "%.2fx", Optics.cropFactor(sensorWidthMm: camera.sensorWidthMm)))
                if camera.resolutionWidth > 0 { LabeledContent("Resolution", value: "\(camera.resolutionWidth) × \(camera.resolutionHeight)") }
                if let url = camera.sourceURL.flatMap(URL.init(string:)) { Link("Maker's specifications", destination: url) }
            }
            Section {
                ForEach(camera.sensorModes) { m in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(m.name)
                            Spacer()
                            CoverageBadge(coverage: Coverage.evaluate(lens: lens, mode: m),
                                          nominal: Coverage.circle(for: lens)?.nominal ?? false)
                        }
                        Text([m.sizeText, m.resolutionText].compactMap { $0 }.joined(separator: " · "))
                            .font(Theme.mono).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("Recording modes") } footer: {
                Text("Sensor area each mode uses. The badge shows whether \(lens.displayName) covers it.")
            }
            Section {
                NavigationLink { CoverageView(cameraId: camera.id, lensId: lens.id) } label: {
                    Label("Open in coverage tool", systemImage: "circle.rectangle.dashed")
                }
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

    static func feetInches(_ m: Double) -> String {
        let inches = (m / 0.0254).rounded()
        return "\(Int(inches) / 12)′ \(Int(inches) % 12)″"
    }

    var body: some View {
        let camera = Catalog.camera(id: defaultCameraId) ?? Catalog.defaultCamera
        Form {
            Section("Lens") {
                LabeledContent("Maker", value: lens.manufacturer)
                if let s = lens.series { LabeledContent("Series", value: s) }
                LabeledContent("Focal length", value: Self.rangeText(lens))
                LabeledContent("Maximum aperture", value: lens.hasAperture ? ShotPresets.tStopText(lens.maximumAperture) : "Not published")
                LabeledContent("Squeeze", value: lens.isAnamorphic ? String(format: "%gx anamorphic", lens.anamorphicSqueeze) : "Spherical")
                if lens.minimumFocusDistance > 0 {
                    LabeledContent("Close focus", value: String(format: "%.2f m · ", lens.minimumFocusDistance) + Self.feetInches(lens.minimumFocusDistance))
                }
                LabeledContent("Image circle", value: lens.hasImageCircle ? String(format: "%.1f mm", lens.imageCircleMm) : "Not published")
                if let f = lens.format { LabeledContent("Designed for", value: f) }
                if let l = lens.lengthMm { LabeledContent("Length", value: "\(Int(l.rounded())) mm") }
                if let w = lens.weightG { LabeledContent("Weight", value: w >= 1000 ? String(format: "%.2f kg", w / 1000) : "\(Int(w)) g") }
                if let d = lens.frontDiameterMm { LabeledContent("Front diameter", value: "\(Int(d.rounded())) mm") }
                LabeledContent("Mount", value: lens.allMountNames.joined(separator: ", "))
            }
            if lens.sourceURL != nil || lens.notes != nil {
                Section("Data source") {
                    if let url = lens.sourceURL.flatMap(URL.init(string:)) { Link("Maker's specifications", destination: url) }
                    if let n = lens.notes { Text(n).font(.caption).foregroundStyle(.secondary) }
                }
            }
            Section {
                ForEach(camera.sensorModes) { m in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.name)
                            if let r = Framing.reference(camera: camera.using(modeId: m.id), lens: lens, focalLengthMm: lens.focalLengthMin) {
                                Text("\(r.deliveredFov.horizontal.degreesText) × \(r.deliveredFov.vertical.degreesText) view")
                                    .font(Theme.mono).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        let c = Coverage.evaluate(lens: lens, mode: m)
                        Text(c.label).font(.caption).foregroundStyle(.secondary)
                        CoverageBadge(coverage: c, nominal: Coverage.circle(for: lens)?.nominal ?? false)
                    }
                }
                if !Coverage.sharesMount(camera: camera, lens: lens) {
                    Label("Different mount: needs an adapter, if one exists for \(lens.allMountNames.joined(separator: "/")) → \(camera.allMountNames.joined(separator: "/")).",
                          systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange)
                }
                NavigationLink { CoverageView(cameraId: camera.id, lensId: lens.id) } label: {
                    Label("Open in coverage tool", systemImage: "circle.rectangle.dashed")
                }
            } header: { Text("On \(camera.model)") } footer: {
                if Coverage.circle(for: lens)?.nominal == true {
                    Text("The maker doesn't publish this lens's image circle, so coverage uses a typical value for \(lens.format ?? "its format"). Test before the shoot.")
                }
            }
            if lens.quickFocalLengths.count > 1 {
                Section("Field of view on \(camera.model)") { FovTable(camera: camera, lens: lens) }
            }
            Section {
                Button(lens.id == defaultLensId ? "Default for new shots ✓" : "Use for new shots") { defaultLensId = lens.id }
                    .disabled(lens.id == defaultLensId)
            }
        }
        .formStyle(.grouped)
        .navigationTitle(lens.series.map { "\($0) \(ShotPresets.focalText(lens.focalLengthMin))" } ?? lens.model)
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
