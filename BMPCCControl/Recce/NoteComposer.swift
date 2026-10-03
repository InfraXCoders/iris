import SwiftUI
import SwiftData
import RecceKit

/// Dictate or type a recce note. The transcript stays editable, and it is saved with the session
/// (the Android app showed voice notes but never stored them).
struct NoteComposer: View {
    let session: RecceSession
    let shotId: String?

    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @AppStorage(Prefs.noteLanguage) private var languageID = "en-IN"
    @StateObject private var speech = SpeechRecognizer()
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Language", selection: $languageID) {
                        ForEach(SpeechRecognizer.languages, id: \.id) { Text($0.name).tag($0.id) }
                    }
                    .disabled(speech.isRecording)
                    Button {
                        if speech.isRecording { speech.stop() } else { Task { await speech.start(localeID: languageID) } }
                    } label: {
                        Label(speech.isRecording ? "Stop dictation" : "Dictate",
                              systemImage: speech.isRecording ? "stop.circle.fill" : "mic.circle.fill")
                            .font(.headline)
                            .foregroundStyle(speech.isRecording ? Theme.locked : Theme.accent)
                    }
                    if let e = speech.error { Text(e).font(.caption).foregroundStyle(.orange) }
                } footer: {
                    Text("Speak naturally in English, Hindi or Hinglish. You can edit the text before saving.")
                }

                Section("Note") {
                    TextField("e.g. Window light from the left, 35mm, dolly in slowly", text: $text, axis: .vertical)
                        .lineLimit(4...12)
                }

                if !trimmed.isEmpty {
                    let s = NotesSorter.sort(trimmed)
                    Section("Will be sorted as") {
                        HStack(spacing: 6) {
                            Tag(text: s.language)
                            if s.composition != nil { Tag(text: "Composition") }
                            if s.lighting != nil { Tag(text: "Lighting") }
                            if NotesSorter.isMovement(trimmed) { Tag(text: "Movement") }
                            if let f = s.focalLength { Tag(text: f) }
                        }
                        .font(.caption)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(shotId == nil ? "Recce note" : "Shot note")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { speech.cancel(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(trimmed.isEmpty)
                }
            }
            .onChange(of: speech.transcript) { _, t in
                if !t.isEmpty { text = t }
            }
            .onDisappear { speech.cancel() }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 420)
        #endif
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func save() {
        if speech.isRecording { speech.stop() }
        let note = RecceNote(text: trimmed, shotId: shotId)
        context.insert(note)
        note.session = session
        session.touch()
        try? context.save()
        dismiss()
    }
}
