import SwiftUI
import SwiftData
import UniformTypeIdentifiers
import RecceKit

struct RootView: View {
    @Environment(\.modelContext) private var context
    @Query(sort: \RecceSession.modified, order: .reverse) private var sessions: [RecceSession]
    @AppStorage(Prefs.defaultCamera) private var defaultCameraId = "bmpcc_6k_pro"
    @AppStorage(Prefs.defaultLens) private var defaultLensId = "sigma_18_35_art"
    @State private var showNewSession = false
    @State private var viewfinderShot: RecceShot?
    @State private var importing = false
    @State private var alert: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { quickRecce() } label: {
                        Label { VStack(alignment: .leading) {
                            Text("Quick recce").font(.headline)
                            Text("Open the viewfinder with \(BuiltInLibrary.camera(id: defaultCameraId)?.model ?? "your camera")")
                                .font(.caption).foregroundStyle(.secondary)
                        } } icon: { Image(systemName: "viewfinder").foregroundStyle(Theme.accent) }
                    }
                    Button { showNewSession = true } label: { Label("New recce", systemImage: "plus.circle.fill") }
                }

                Section("Recces") {
                    if sessions.isEmpty {
                        Text("No recces yet. Start one above, or import a JSON file from the Android app.")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    ForEach(sessions) { s in
                        NavigationLink { SessionDetailView(session: s) } label: { SessionRow(session: s) }
                    }
                    .onDelete { idx in
                        for i in idx { context.delete(sessions[i]) }
                        try? context.save()
                    }
                }

                Section("More") {
                    NavigationLink { LibraryView() } label: { Label("Camera & lens library", systemImage: "camera.aperture") }
                    Button { importing = true } label: { Label("Import recce (JSON)", systemImage: "square.and.arrow.down") }
                    Label { VStack(alignment: .leading) {
                        Text("Camera control")
                        Text("Bluetooth control of the BMPCC: coming in the next version").font(.caption).foregroundStyle(.secondary)
                    } } icon: { Image(systemName: "antenna.radiowaves.left.and.right") }
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("BMPCC Control")
            .sheet(isPresented: $showNewSession) { NewSessionSheet() }
            .viewfinderCover(shot: $viewfinderShot)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                do {
                    let s = try RecceExport.importFile(try result.get(), into: context)
                    alert = "Imported “\(s.projectName)” with \(s.scenes.count) scene(s) and \(s.shotCount) shot(s)."
                } catch {
                    alert = error.localizedDescription
                }
            }
            .alert(alert ?? "", isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    private func quickRecce() {
        let cam = BuiltInLibrary.camera(id: defaultCameraId) ?? BuiltInLibrary.cameras[0]
        let lens = BuiltInLibrary.lens(id: defaultLensId) ?? BuiltInLibrary.lenses[0]
        do { viewfinderShot = try RecceFactory.quickRecceShot(camera: cam, lens: lens, context: context) }
        catch { alert = error.localizedDescription }
    }
}

struct SessionRow: View {
    let session: RecceSession
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(session.projectName).font(.headline)
            Text(session.locationName).font(.subheadline).foregroundStyle(.secondary)
            Text("\(session.date.formatted(date: .abbreviated, time: .shortened)) · \(session.scenes.count) scene(s) · \(session.shotCount) shot(s)")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

struct NewSessionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @StateObject private var locator = LocationProvider()
    @State private var project = ""
    @State private var location = ""
    @State private var saveLocation = true

    var body: some View {
        NavigationStack {
            Form {
                TextField("Project name", text: $project)
                TextField("Location name", text: $location)
                Toggle("Save my current location (for sun times)", isOn: $saveLocation)
                if let e = locator.error { Text(e).font(.caption).foregroundStyle(.orange) }
            }
            .formStyle(.grouped)
            .navigationTitle("New recce")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Create") { create() } }
            }
            .onAppear { if saveLocation { locator.request() } }
            .onChange(of: saveLocation) { _, on in if on { locator.request() } }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 260)
        #endif
    }

    private func create() {
        let s = RecceFactory.newSession(project: project, location: location, in: context)
        if saveLocation, let loc = locator.location {
            s.latitude = loc.coordinate.latitude
            s.longitude = loc.coordinate.longitude
        }
        _ = RecceFactory.newScene(in: s, context: context)
        try? context.save()
        dismiss()
    }
}
