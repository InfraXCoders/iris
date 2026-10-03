import Foundation
import AVFoundation
import Speech

/// Live speech-to-text for recce voice notes (English or Hindi). Uses on-device recognition when the
/// language supports it.
@MainActor
final class SpeechRecognizer: ObservableObject {
    @Published var transcript = ""
    @Published var isRecording = false
    @Published var error: String?

    /// Locales offered in the app. Hinglish is usually best recognised with the Indian English model.
    static let languages: [(id: String, name: String)] = [
        ("en-IN", "English (India) / Hinglish"),
        ("hi-IN", "Hindi"),
        ("en-US", "English (US)"),
        ("en-GB", "English (UK)"),
    ]

    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    func start(localeID: String) async {
        error = nil
        transcript = ""
        let speechStatus: SFSpeechRecognizerAuthorizationStatus = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0) }
        }
        guard speechStatus == .authorized else {
            error = "Speech recognition is not allowed. You can still type the note."
            return
        }
        guard await AVCaptureDevice.requestAccess(for: .audio) else {
            error = "Microphone access is off. You can still type the note."
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: localeID)), recognizer.isAvailable else {
            error = "Speech recognition for this language isn't available right now."
            return
        }
        do {
            #if os(iOS)
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.record, mode: .measurement, options: .duckOthers)
            try audio.setActive(true, options: .notifyOthersOnDeactivation)
            #endif
            let req = SFSpeechAudioBufferRecognitionRequest()
            req.shouldReportPartialResults = true
            if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
            request = req

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                req.append(buffer)
            }
            engine.prepare()
            try engine.start()
            isRecording = true

            task = recognizer.recognitionTask(with: req) { [weak self] result, err in
                let text = result?.bestTranscription.formattedString
                let done = result?.isFinal ?? false
                Task { @MainActor in
                    guard let self else { return }
                    if let text { self.transcript = text }
                    if err != nil || done { self.finishAudio() }
                }
            }
        } catch {
            self.error = error.localizedDescription
            finishAudio()
        }
    }

    /// Stop listening; the last words are still delivered to `transcript`.
    func stop() {
        request?.endAudio()
        finishAudio()
    }

    private func finishAudio() {
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        isRecording = false
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    func cancel() {
        task?.cancel()
        task = nil
        finishAudio()
    }
}
