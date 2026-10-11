import Foundation
import SwiftUI
#if os(iOS)
import CoreMotion
import UIKit
#endif

/// Live tilt of the phone from the gravity reading (Core Motion), relative to the screen's own "up": roll (what the
/// level line shows) and pitch (camera pointing up / down). One reading drives both the line and the text, so they
/// always agree. Same formulas as the Android app (whose gravity sensor has the opposite sign).
@MainActor
final class LevelMotion: ObservableObject {
    struct Tilt: Equatable { var roll: Double; var pitch: Double }
    @Published private(set) var tilt: Tilt?

    #if os(iOS)
    private let manager = CMMotionManager()
    private var smooth: (Double, Double, Double)?
    #endif

    func start() {
        #if os(iOS)
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 30
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let self, let g = motion?.gravity else { return }
            MainActor.assumeIsolated { self.update(g.x, g.y, g.z) }
        }
        #endif
    }

    func stop() {
        #if os(iOS)
        manager.stopDeviceMotionUpdates()
        #endif
    }

    #if os(iOS)
    private func update(_ x: Double, _ y: Double, _ z: Double) {
        let a = smooth == nil ? 1.0 : 0.15
        let s = smooth ?? (x, y, z)
        let v = (s.0 + a * (x - s.0), s.1 + a * (y - s.1), s.2 + a * (z - s.2))
        smooth = v
        let n = (v.0 * v.0 + v.1 * v.1 + v.2 * v.2).squareRoot()
        guard n > 0.1 else { return }
        // Roll of the phone, then relative to the screen's "up" (landscape turns the screen 90°).
        var roll = atan2(v.0, -v.1) * 180 / .pi + Self.screenRotation()
        while roll > 180 { roll -= 360 }
        while roll <= -180 { roll += 360 }
        let pitch = asin(min(max(v.2 / n, -1), 1)) * 180 / .pi
        if let old = tilt, abs(old.roll - roll) < 0.2, abs(old.pitch - pitch) < 0.2 { return }
        tilt = Tilt(roll: roll, pitch: pitch)
    }

    /// The interface rotation in degrees, as Android counts it (90 = turned counter-clockwise).
    static func screenRotation() -> Double {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        switch scene?.interfaceOrientation {
        case .landscapeRight: return 90
        case .portraitUpsideDown: return 180
        case .landscapeLeft: return 270
        default: return 0
        }
    }
    #endif
}
