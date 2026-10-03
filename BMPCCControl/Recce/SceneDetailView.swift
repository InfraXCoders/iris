import SwiftUI
import SwiftData
import RecceKit

struct SceneDetailView: View {
    @Bindable var scene: RecceScene
    @Environment(\.modelContext) private var context
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"
    @State private var viewfinderShot: RecceShot?

    var body: some View {
        Form {
            Section("Scene") {
                TextField("Scene number", text: $scene.sceneNumber)
                Picker("Interior / exterior", selection: $scene.isInterior) {
                    Text("INT.").tag(true)
                    Text("EXT.").tag(false)
                }
                .pickerStyle(.segmented)
                Picker("Day / night", selection: $scene.isDay) {
                    Text("DAY").tag(true)
                    Text("NIGHT").tag(false)
                }
                .pickerStyle(.segmented)
                TextField("Location description", text: $scene.locationDescription)
                TextField("Time of day (e.g. 17:30)", text: $scene.timeOfDay)
                Picker("Lighting", selection: $scene.lighting) {
                    ForEach(LightingCondition.allCases) { Text($0.label).tag($0) }
                }
                TextField("Scene notes", text: $scene.notes, axis: .vertical).lineLimit(2...6)
            }

            Section("Shots") {
                ForEach(scene.sortedShots) { shot in
                    NavigationLink { ShotDetailView(shot: shot) } label: { ShotRow(shot: shot) }
                        .swipeActions(edge: .leading) {
                            Button { viewfinderShot = shot } label: { Label("Viewfinder", systemImage: "viewfinder") }.tint(Theme.accent)
                        }
                }
                .onDelete { idx in
                    let list = scene.sortedShots
                    for i in idx { context.delete(list[i]) }
                    scene.session?.touch()
                    try? context.save()
                }
                Button { addShot() } label: { Label("Add shot", systemImage: "plus") }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Scene \(scene.sceneNumber)")
        .viewfinderCover(shot: $viewfinderShot)
        .onDisappear { scene.session?.touch(); try? context.save() }
    }

    private func addShot() {
        let cam = Catalog.camera(id: defaultCameraId) ?? Catalog.defaultCamera
        let lens = Catalog.lens(id: defaultLensId) ?? Catalog.defaultLens
        _ = RecceFactory.newShot(in: scene, camera: cam, lens: lens, context: context)
        try? context.save()
    }
}

struct ShotRow: View {
    let shot: RecceShot
    var body: some View {
        HStack(spacing: 12) {
            if let first = shot.references.sorted(by: { $0.date < $1.date }).first, let img = ImageLoader.load(first.fileURL) {
                Image(platformImage: img).resizable().scaledToFill().frame(width: 64, height: 40).clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            } else {
                RoundedRectangle(cornerRadius: 4).fill(.quaternary).frame(width: 64, height: 40)
                    .overlay(Image(systemName: "camera.viewfinder").foregroundStyle(.secondary))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Shot \(shot.shotNumber) · \(shot.shotType.label)").font(.headline)
                Text("\(shot.focalLength) · \(shot.aspectRatio) · \(shot.movement.label)").font(.caption).foregroundStyle(.secondary)
                Text(shot.lens.model).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}
