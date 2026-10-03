import SwiftUI
import SwiftData
import RecceKit

struct SessionDetailView: View {
    @Bindable var session: RecceSession
    @Environment(\.modelContext) private var context
    @StateObject private var locator = LocationProvider()
    @State private var showNote = false
    @State private var exportURL: URL?
    @State private var exportError: String?
    @State private var exporting = false

    var body: some View {
        Form {
            Section("Recce") {
                TextField("Project", text: $session.projectName)
                TextField("Location", text: $session.locationName)
                DatePicker("Date", selection: $session.date)
                HStack {
                    if let lat = session.latitude, let lon = session.longitude {
                        Text(String(format: "%.5f, %.5f", lat, lon)).font(Theme.mono).foregroundStyle(.secondary)
                    } else {
                        Text("No location saved").foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(locator.isLocating ? "Locating…" : "Use my location") { locator.request() }
                        .disabled(locator.isLocating)
                }
                if let e = locator.error { Text(e).font(.caption).foregroundStyle(.orange) }
            }

            Section("Sun") {
                if let lat = session.latitude, let lon = session.longitude {
                    SunCard(latitude: lat, longitude: lon, date: session.date)
                } else {
                    Text("Save a location to see sunrise, sunset, golden hour and blue hour.").foregroundStyle(.secondary)
                }
            }

            Section("Scenes") {
                ForEach(session.sortedScenes) { scene in
                    NavigationLink { SceneDetailView(scene: scene) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Scene \(scene.sceneNumber)").font(.headline)
                            Text(scene.heading).font(.caption).foregroundStyle(.secondary)
                            Text("\(scene.shots.count) shot(s) · \(scene.lighting.label)").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { idx in
                    let list = session.sortedScenes
                    for i in idx { context.delete(list[i]) }
                    session.touch()
                    try? context.save()
                }
                Button { _ = RecceFactory.newScene(in: session, context: context); try? context.save() } label: {
                    Label("Add scene", systemImage: "plus")
                }
            }

            Section {
                ForEach(session.sortedNotes) { note in NoteRow(note: note) }
                    .onDelete { idx in
                        let list = session.sortedNotes
                        for i in idx { context.delete(list[i]) }
                        try? context.save()
                    }
                Button { showNote = true } label: { Label("Add voice or text note", systemImage: "mic.fill") }
            } header: { Text("Notes") } footer: {
                Text("Notes are auto-sorted into composition, lighting and movement for the report (English, Hindi and Hinglish).")
            }

            Section("Director / DoP notes") {
                TextField("Look, references, intentions…", text: $session.directorDopNotes, axis: .vertical).lineLimit(3...8)
            }
            Section("Location notes") {
                TextField("Power, parking, permissions, noise…", text: $session.generalLocationNotes, axis: .vertical).lineLimit(3...8)
            }

            Section("Share") {
                Button { makePDF() } label: { Label(exporting ? "Creating report…" : "Create PDF report", systemImage: "doc.richtext") }
                    .disabled(exporting)
                Button { makeJSON() } label: { Label("Export JSON (works with the Android app)", systemImage: "curlybraces") }
                if let url = exportURL {
                    ShareLink(item: url) { Label("Share \(url.lastPathComponent)", systemImage: "square.and.arrow.up") }
                }
                if let e = exportError { Text(e).foregroundStyle(.red) }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(session.projectName)
        .sheet(isPresented: $showNote) { NoteComposer(session: session, shotId: nil) }
        .onChange(of: locator.location) { _, loc in
            guard let loc else { return }
            session.latitude = loc.coordinate.latitude
            session.longitude = loc.coordinate.longitude
            session.touch()
        }
        .onDisappear { session.touch(); try? context.save() }
    }

    private func makePDF() {
        exporting = true
        exportError = nil
        defer { exporting = false }
        do { exportURL = try ReportPDF.make(for: session) } catch { exportError = error.localizedDescription }
    }

    private func makeJSON() {
        exportError = nil
        do { exportURL = try RecceExport.jsonFile(for: session) } catch { exportError = error.localizedDescription }
    }
}

struct NoteRow: View {
    let note: RecceNote
    var body: some View {
        let s = note.sorted
        VStack(alignment: .leading, spacing: 4) {
            Text(note.text)
            HStack(spacing: 6) {
                Text(note.date.formatted(date: .omitted, time: .shortened))
                Text(note.language)
                if s.composition != nil { Tag(text: "Composition") }
                if s.lighting != nil { Tag(text: "Lighting") }
                if NotesSorter.isMovement(note.text) { Tag(text: "Movement") }
                if let f = s.focalLength { Tag(text: f) }
            }
            .font(.caption2).foregroundStyle(.secondary)
        }
    }
}

struct Tag: View {
    let text: String
    var body: some View {
        Text(text).padding(.horizontal, 6).padding(.vertical, 2)
            .background(Theme.accent.opacity(0.25), in: Capsule())
    }
}
