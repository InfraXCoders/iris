// swift-tools-version:5.9
// RecceKit: the platform-independent core of BMPCC Control's Recce mode
// (optics, framing, camera/lens library, sun, notes, export). No UI code, so it is
// tested with `swift test` on macOS (and Linux) without a simulator.
import PackageDescription

let package = Package(
    name: "RecceKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "RecceKit", targets: ["RecceKit"])],
    targets: [
        .target(name: "RecceKit"),
        .testTarget(name: "RecceKitTests", dependencies: ["RecceKit"], resources: [.copy("Fixtures")]),
    ]
)
