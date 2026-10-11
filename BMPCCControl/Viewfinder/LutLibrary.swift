import Foundation
import SwiftUI
import RecceKit

/// One LUT in the library: a built-in look or an imported .cube file. Ids match the Android app:
/// "builtin:<name>" or "file:<file name>".
struct LutEntry: Identifiable, Hashable {
    let id: String
    let name: String
    let builtIn: Bool
    let size: Int?
}

struct LutImportError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// The LUT files themselves: parsed once and cached, safe to use from any thread. Also remembers which input each
/// LUT expects (Rec.709 or Blackmagic Film Gen 5).
enum LutFiles {
    private static let lock = NSLock()
    private static var cache: [String: Lut3D] = [:]

    static var folder: URL {
        let url = FileStore.appFolder.appendingPathComponent("LUTs", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The LUT (nil if missing or unreadable). Reads the file the first time: call off the main thread.
    static func load(_ id: String?) -> Lut3D? {
        guard let id else { return nil }
        lock.lock()
        if let hit = cache[id] { lock.unlock(); return hit }
        lock.unlock()
        var lut: Lut3D?
        if id.hasPrefix("builtin:") {
            let name = String(id.dropFirst("builtin:".count))
            lut = BuiltInLooks.all.first { $0.name == name }
        } else if id.hasPrefix("file:") {
            let url = folder.appendingPathComponent(String(id.dropFirst("file:".count)))
            if let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) {
                lut = try? CubeParser.parse(text, fallbackName: url.deletingPathExtension().lastPathComponent)
            }
        }
        if let lut { lock.lock(); cache[id] = lut; lock.unlock() }
        return lut
    }

    static func input(_ id: String?) -> LutInput {
        LutInput.of(id.flatMap { UserDefaults.standard.string(forKey: "lut.input.\($0)") })
    }

    static func setInput(_ input: LutInput, for id: String) {
        UserDefaults.standard.set(input.rawValue, forKey: "lut.input.\(id)")
    }

    /// Checks a .cube file and copies it into the library. Returns its id.
    static func importCube(from url: URL) throws -> String {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let limit = 20_000_000
        if let bytes = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, bytes > limit {
            throw LutImportError(message: "This file is too large for a LUT (over 20 MB).")
        }
        let data: Data
        do { data = try Data(contentsOf: url) } catch { throw LutImportError(message: "Couldn't read the file.") }
        if data.count > limit { throw LutImportError(message: "This file is too large for a LUT (over 20 MB).") }
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            throw LutImportError(message: "Couldn't read the file.")
        }
        let original = url.deletingPathExtension().lastPathComponent
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: " ._-"))
        var base = String(original.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }).trimmingCharacters(in: .whitespaces)
        if base.isEmpty { base = "LUT" }
        let lut: Lut3D
        do { lut = try CubeParser.parse(text, fallbackName: base) } catch {
            throw LutImportError(message: "Not a usable .cube LUT: \(error.localizedDescription)")
        }
        var file = folder.appendingPathComponent("\(base).cube")
        var n = 2
        while FileManager.default.fileExists(atPath: file.path) {
            file = folder.appendingPathComponent("\(base) (\(n)).cube")
            n += 1
        }
        try data.write(to: file, options: .atomic)
        let d = UserDefaults.standard
        d.set(lut.name.trimmingCharacters(in: .whitespaces).isEmpty ? base : lut.name, forKey: "lut.name.\(file.lastPathComponent)")
        d.set(lut.size, forKey: "lut.size.\(file.lastPathComponent)")
        let id = "file:\(file.lastPathComponent)"
        // Log-to-Rec.709 LUTs usually say so in their name.
        if original.range(of: "film|log|gen ?5|braw|bmd", options: [.regularExpression, .caseInsensitive]) != nil {
            setInput(.bmdFilmGen5, for: id)
        }
        lock.lock(); cache[id] = lut; lock.unlock()
        return id
    }
}

/// The LUT list shown in the viewfinder: built-in looks plus imported .cube files.
@MainActor
final class LutLibrary: ObservableObject {
    static let shared = LutLibrary()

    @Published private(set) var entries: [LutEntry] = []
    /// Bumped when a LUT's input choice changes.
    @Published private(set) var version = 0

    init() { refresh() }

    func refresh() {
        var list = BuiltInLooks.all.map { LutEntry(id: "builtin:\($0.name)", name: $0.name, builtIn: true, size: $0.size) }
        let files = (try? FileManager.default.contentsOfDirectory(at: LutFiles.folder, includingPropertiesForKeys: nil)) ?? []
        let d = UserDefaults.standard
        for f in files.filter({ $0.pathExtension.lowercased() == "cube" })
            .sorted(by: { $0.lastPathComponent.lowercased() < $1.lastPathComponent.lowercased() }) {
            let key = f.lastPathComponent
            let size = d.integer(forKey: "lut.size.\(key)")
            list.append(LutEntry(id: "file:\(key)", name: d.string(forKey: "lut.name.\(key)") ?? f.deletingPathExtension().lastPathComponent,
                                 builtIn: false, size: size > 0 ? size : nil))
        }
        entries = list
    }

    func entry(_ id: String?) -> LutEntry? { entries.first { $0.id == id } }

    func name(_ id: String?) -> String? {
        guard let id else { return nil }
        return entry(id)?.name ?? id.split(separator: ":", maxSplits: 1).last.map(String.init)
    }

    func input(_ id: String?) -> LutInput {
        _ = version
        return LutFiles.input(id)
    }

    func setInput(_ input: LutInput, for id: String) {
        LutFiles.setInput(input, for: id)
        version += 1
    }

    /// Copies a .cube file into the library (checked first, in the background). Returns the new id.
    func importCube(from url: URL) async throws -> String {
        let id = try await Task.detached(priority: .userInitiated) { try LutFiles.importCube(from: url) }.value
        refresh()
        version += 1
        return id
    }
}
