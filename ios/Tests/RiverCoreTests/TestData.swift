import Foundation

/// Shared fixtures in `<repo>/ci/testdata` (also used later by the Android port). Simulator tests run on the Mac,
/// so the repository path from `#filePath` is readable.
enum TestData {
    static let root: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // RiverCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repo
        .appendingPathComponent("ci/testdata", isDirectory: true)

    static func url(_ relative: String) -> URL { root.appendingPathComponent(relative) }

    /// Fresh temporary directory, removed by the caller's `tearDown` or left to the OS.
    static func tempDirectory() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rivem-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Copies a fixture project folder to a temporary location and adds the empty `frames/` git cannot store.
    static func projectCopy(_ relative: String) throws -> URL {
        let dest = try tempDirectory().appendingPathComponent(URL(fileURLWithPath: relative).lastPathComponent, isDirectory: true)
        try FileManager.default.copyItem(at: url(relative), to: dest)
        try FileManager.default.createDirectory(at: dest.appendingPathComponent("frames"), withIntermediateDirectories: true)
        return dest
    }
}
