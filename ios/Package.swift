// swift-tools-version: 5.9
import PackageDescription

// SwiftPM package:
// - `RiverCore`: platform-neutral port of the RIVeR processing core (Foundation only). `swift test` runs its
//   tests on macOS, Linux or Windows; Android will port it to Kotlin against the same cases.
// - `RIVEM`: the SwiftUI app. CI builds it through XcodeGen + xcodebuild (project.yml, ../.github/workflows/ios.yml);
//   xtool (xtool.yml) can also build and install it from Linux/WSL.
var products: [Product] = [.library(name: "RiverCore", targets: ["RiverCore"])]
var targets: [Target] = [
    .target(name: "RiverCore"),
    .testTarget(name: "RiverCoreTests", dependencies: ["RiverCore"]),
]

// SwiftUI does not exist on Windows: there the package is just the core and its tests (`swift test`).
#if !os(Windows)
products.append(.library(name: "RIVEM", targets: ["RIVEM"]))
targets.append(.target(name: "RIVEM", dependencies: ["RiverCore"]))
#endif

let package = Package(
    name: "RIVEM",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: products,
    targets: targets
)
