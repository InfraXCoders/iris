import SwiftUI

/// Cinema-style dark theme. Orange is the Android app's Blackmagic accent; blue is used for frame lines.
enum Theme {
    static let accent = Color(red: 1.0, green: 0.333, blue: 0.0)          // #FF5500
    static let frameLine = Color(red: 0.25, green: 0.70, blue: 1.0)
    static let locked = Color(red: 0.95, green: 0.20, blue: 0.20)
    static let panel = Color.black.opacity(0.55)
    static let mono = Font.system(.caption, design: .monospaced)
}

extension View {
    /// Small rounded capsule used for HUD values.
    func hudChip(_ highlighted: Bool = false) -> some View {
        self.font(.caption.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(highlighted ? Theme.accent : Theme.panel, in: Capsule())
            .foregroundStyle(.white)
    }
}

extension Double {
    /// 65.37 -> "65.4°"
    var degreesText: String { String(format: "%.1f°", self) }
}
