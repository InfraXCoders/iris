import SwiftUI
import SwiftData

@main
struct BMPCCControlApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(Theme.accent)
                .preferredColorScheme(.dark)
        }
        .modelContainer(for: RecceSchema.models)
        #if os(macOS)
        .defaultSize(width: 1100, height: 760)
        #endif
    }
}

/// App-wide choices remembered between launches.
enum Prefs {
    static let defaultCamera = "defaultCameraId"
    static let defaultLens = "defaultLensId"
    static let favoriteCameras = "favoriteCameraIds"
    static let favoriteLenses = "favoriteLensIds"
    static let noteLanguage = "noteLanguage"
}

extension View {
    /// Full screen on iPhone/iPad, a large sheet on the Mac.
    @ViewBuilder
    func viewfinderCover(shot: Binding<RecceShot?>) -> some View {
        #if os(iOS)
        self.fullScreenCover(item: shot) { s in ViewfinderView(shot: s) }
        #else
        self.sheet(item: shot) { s in ViewfinderView(shot: s) }
        #endif
    }
}
