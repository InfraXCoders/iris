import Foundation

/// Finding recces: free-text search across everything in a recce, filters and sorting (same rules as Android).
public enum RecceSearch {
    public enum When: String, CaseIterable, Sendable {
        case any, week, month, year
        public var label: String { switch self { case .any: return "Any time"; case .week: return "Last 7 days"; case .month: return "Last 30 days"; case .year: return "Last 12 months" } }
        public var days: Int? { switch self { case .any: return nil; case .week: return 7; case .month: return 30; case .year: return 365 } }
    }
    public enum SceneKind: String, CaseIterable, Sendable {
        case any, int, ext, day, night
        public var label: String { switch self { case .any: return "Any scene"; case .int: return "INT."; case .ext: return "EXT."; case .day: return "Day"; case .night: return "Night" } }
    }
    public enum Sort: String, CaseIterable, Sendable {
        case recent, date, name
        public var label: String { switch self { case .recent: return "Recently changed"; case .date: return "Recce date"; case .name: return "Project name" } }
    }

    public struct Filters: Equatable, Sendable {
        public var query = ""
        public var time: When = .any
        public var scene: SceneKind = .any
        public var withLocation = false
        public var sort: Sort = .recent
        public init() {}
        public var active: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty || time != .any || scene != .any || withLocation }
    }

    public struct Hit: Sendable {
        public var session: RecceSessionDoc
        /// Where the words were found ("Shot 1A notes"…).
        public var matches: [String]
    }

    public static func search(_ sessions: [RecceSessionDoc], _ f: Filters, now: Date = Date()) -> [Hit] {
        let words = normalise(f.query).split(separator: " ").map(String.init)
        let nowMs = RecceJSON.millis(now)
        var hits: [Hit] = []
        for s in sessions {
            if f.withLocation && (s.latitude == nil || s.longitude == nil) { continue }
            if let d = f.time.days, s.timestamp < nowMs - Int64(d) * 86_400_000 { continue }
            if f.scene != .any && !s.scenes.contains(where: { sc in
                switch f.scene { case .int: return sc.isInterior; case .ext: return !sc.isInterior; case .day: return sc.isDay; case .night: return !sc.isDay; case .any: return true }
            }) { continue }
            if words.isEmpty { hits.append(Hit(session: s, matches: [])); continue }
            let fields = fieldsOf(s)
            let hay = fields.map { normalise($0.1) }.joined(separator: "\n")
            if words.contains(where: { !hay.contains($0) }) { continue }
            var seen = Set<String>()
            let where_ = fields.filter { fld in let t = normalise(fld.1); return words.contains { t.contains($0) } }
                .map(\.0).filter { seen.insert($0).inserted }
            hits.append(Hit(session: s, matches: Array(where_.prefix(3))))
        }
        switch f.sort {
        case .recent: return hits.sorted { $0.session.modificationTimestamp > $1.session.modificationTimestamp }
        case .date: return hits.sorted { $0.session.timestamp > $1.session.timestamp }
        case .name: return hits.sorted { $0.session.projectName.lowercased() < $1.session.projectName.lowercased() }
        }
    }

    public static func fieldsOf(_ s: RecceSessionDoc) -> [(String, String)] {
        var out: [(String, String)] = [("Project", s.projectName), ("Location", s.locationName),
                                       ("Director / DoP notes", s.directorDopNotes), ("Location notes", s.generalLocationNotes)]
        for sc in s.scenes.sorted(by: { $0.sceneNumber.localizedStandardCompare($1.sceneNumber) == .orderedAscending }) {
            let name = "Scene \(sc.sceneNumber)"
            let heading = "\(sc.isInterior ? "INT." : "EXT.") \(sc.locationDescription.isEmpty ? "Location" : sc.locationDescription) – \(sc.isDay ? "DAY" : "NIGHT")"
            out.append(("\(name): \(heading)", "\(heading) \(sc.locationDescription) \(sc.lightingCondition.label)"))
            out.append(("\(name) notes", sc.notes))
            for sh in sc.shots {
                let shot = "Shot \(sh.shotNumber)"
                out.append(("\(shot) notes", sh.notes))
                out.append(("\(shot) (\(sh.shotType.label))", "\(sh.shotType.label) \(sh.shotType.longName) \(sh.subjectPosition) \(sh.cameraPosition) \(sh.subjectMovement)"))
                out.append(("\(shot) lens", "\(sh.cameraModel) \(sh.lensModel) \(sh.focalLength)"))
                out.append(("\(shot) LUT", sh.lut.map { $0.components(separatedBy: ":").last ?? $0 } ?? ""))
            }
        }
        for n in s.voiceNotes ?? [] { out.append(("Voice note", n.rawTranscription)) }
        return out.filter { !$0.1.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    /// Lower case, accents removed, punctuation as spaces.
    public static func normalise(_ text: String) -> String {
        let folded = text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX")).lowercased()
        let mapped = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == "." ? Character($0) : " " }
        return String(mapped).split(separator: " ").joined(separator: " ")
    }
}

/// A short place name from an address lookup, e.g. "Hauz Khas, New Delhi".
public enum PlaceName {
    public static func format(feature: String?, subLocality: String?, locality: String?, subAdmin: String?, admin: String?, country: String?) -> String? {
        var parts: [String] = []
        func add(_ v: String?) {
            guard let t = v?.trimmingCharacters(in: .whitespaces), !t.isEmpty, !t.contains("+"),
                  !t.allSatisfy({ $0.isNumber || $0 == " " || $0 == "-" }) else { return }
            if !parts.contains(where: { $0.caseInsensitiveCompare(t) == .orderedSame }) { parts.append(t) }
        }
        add(subLocality ?? feature)
        add(locality ?? subAdmin)
        if parts.count < 2 { add(admin) }
        if parts.isEmpty { add(country) }
        return parts.isEmpty ? nil : parts.prefix(2).joined(separator: ", ")
    }
}
