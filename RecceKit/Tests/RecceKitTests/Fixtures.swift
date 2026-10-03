import Foundation

/// Reference values. android_golden.json was produced by running the ORIGINAL Android Kotlin code
/// (UniversalLibrarySeeder, FieldOfViewCalculator, CameraLensCompatibilityEngine, UniversalFramingEngine,
/// AiRecceAnalyzer, SunTrackingManager) on the JVM. sun_reference.json comes from the `astral` Python library.
enum Fixtures {
    static func json(_ name: String) -> [String: Any] {
        let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")!
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
    static let android = json("android_golden")
    static let sun = json("sun_reference")
    static func list(_ dict: [String: Any], _ key: String) -> [[String: Any]] { dict[key] as! [[String: Any]] }
}

extension Dictionary where Key == String, Value == Any {
    func d(_ k: String) -> Double { (self[k] as! NSNumber).doubleValue }
    func s(_ k: String) -> String { self[k] as! String }
    func optS(_ k: String) -> String? { self[k] as? String }
}
