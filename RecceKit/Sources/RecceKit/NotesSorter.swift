import Foundation

/// The result of sorting one recce note into categories.
public struct SortedNote: Equatable, Codable, Sendable {
    public var language: String
    public var composition: String?
    public var lighting: String?
    public var focalLength: String?
    public var general: String?
}

/// Notes grouped for the report.
public struct NotesSummary: Equatable, Codable, Sendable {
    public var composition: [String]
    public var lighting: [String]
    public var movement: [String]
    public var general: [String]
}

/// Keyword-based organiser for spoken recce notes (English, Romanized Hinglish, Devanagari Hindi).
/// A faithful port of the Android AiRecceAnalyzer: it is keyword matching, not an AI model, so the app labels
/// its output "auto-sorted".
public enum NotesSorter {
    public static let unknown = "Not recorded / Unknown"

    static let hindiRomanKeywords: Set<String> = [
        "hai", "hein", "rahi", "raha", "rahe", "thoda", "bahut", "achhi", "achha",
        "rakho", "rakhna", "karo", "karna", "aaye", "aagaya", "aajaye", "se", "ko",
        "pe", "par", "mein", "me", "aur", "ya", "yahan", "wahan", "kaafi", "chahiye", "lag",
    ]
    static let compositionKeywords = [
        "framing", "frame", "thirds", "left", "right", "center", "subject", "background",
        "foreground", "crop", "wide", "close up", "close-up", "tight", "angle", "medium shot",
        "composition", "depth of field", "rakho", "rakhna", "side", "left side", "right side",
        "piche", "aage", "thoda left", "thoda right", "achhi", "achha", "set karo", "chauda",
        "फ्रेम", "फ़्रेम", "सब्जेक्ट", "बैकग्राउंड", "लेफ्ट", "राइट", "सेंटर", "कोण", "चौड़ा", "रखो",
    ]
    static let lightingKeywords = [
        "light", "window", "hard light", "soft light", "shadow", "contrast", "bright", "dark",
        "sun", "flare", "highlight", "diffuse", "diffusion", "exposure", "roshni", "dhoop",
        "dhup", "ujala", "khidki", "khidkee", "लाइट", "रोशनी", "धूप", "उजाला", "छाया", "खिड़की",
    ]
    static let movementKeywords = [
        "move", "pan", "tilt", "dolly", "zoom", "track", "tracking", "handheld", "gimbal", "tripod",
        "ghumao", "chalao", "hilao", "shift", "move karo", "hatao", "घुमाओ", "चलाओ", "मूव",
    ]

    /// "Hindi" if any Devanagari character, "Hinglish" if any whole word is a common Romanized Hindi word,
    /// otherwise "English".
    public static func detectLanguage(_ text: String) -> String {
        if text.unicodeScalars.contains(where: { (0x0900...0x097F).contains($0.value) }) { return "Hindi" }
        let words = text.lowercased().split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return words.contains(where: { hindiRomanKeywords.contains($0) }) ? "Hinglish" : "English"
    }

    /// Kotlin's String.contains is a plain (literal, case-sensitive) substring test; match it exactly so the
    /// iOS app sorts notes the same way the Android app did.
    private static func containsLiteral(_ text: String, _ needle: String) -> Bool {
        text.range(of: needle, options: .literal) != nil
    }

    public static func sort(_ raw: String) -> SortedNote {
        let text = raw.lowercased()
        var focal: String?
        if let r = text.range(of: #"(\d+(?:\.\d+)?)\s*(mm|millimeter)"#, options: .regularExpression) {
            let match = String(text[r])
            if let num = match.range(of: #"\d+(?:\.\d+)?"#, options: .regularExpression) {
                focal = "\(match[num])mm"
            }
        }
        return SortedNote(
            language: detectLanguage(raw),
            composition: compositionKeywords.contains(where: { containsLiteral(text, $0) }) ? raw : nil,
            lighting: lightingKeywords.contains(where: { containsLiteral(text, $0) }) ? raw : nil,
            focalLength: focal,
            general: raw)
    }

    public static func isMovement(_ raw: String) -> Bool {
        let text = raw.lowercased()
        return movementKeywords.contains(where: { containsLiteral(text, $0) })
    }

    public static func summarize(_ notes: [String]) -> NotesSummary {
        var comp: [String] = [], light: [String] = [], move: [String] = [], general: [String] = []
        for n in notes {
            let s = sort(n)
            if let c = s.composition { comp.append(c) }
            if let l = s.lighting { light.append(l) }
            if let g = s.general { general.append(g) }
            if isMovement(n) { move.append(n) }
        }
        let orUnknown: ([String]) -> [String] = { $0.isEmpty ? [unknown] : $0 }
        return NotesSummary(composition: orUnknown(comp), lighting: orUnknown(light),
                            movement: orUnknown(move), general: orUnknown(general))
    }
}
