import Foundation
import os

/// App log: unified logging (OSLog, visible in Console and in the CI's `log show`) plus a text file in
/// `Documents/logs/rivem.log` that the user can export from the Diagnostics screen (no Mac needed to debug).
enum AppLog {
    private static let logger = Logger(subsystem: "com.rivem.app", category: "app")
    private static let queue = DispatchQueue(label: "com.rivem.applog")
    private static let maxBytes: Int = 2_000_000

    static var folder: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("logs", isDirectory: true)
    }

    static var fileURL: URL { folder.appendingPathComponent("rivem.log") }
    static var previousFileURL: URL { folder.appendingPathComponent("rivem.old.log") }

    static func info(_ message: String) {
        logger.info("\(message, privacy: .public)")
        append("INFO", message)
    }

    static func error(_ message: String) {
        logger.error("\(message, privacy: .public)")
        append("ERROR", message)
    }

    private static func append(_ level: String, _ message: String) {
        let stamp: String = ISO8601DateFormatter().string(from: Date())
        let line = "\(stamp) [\(level)] \(message)\n"
        queue.async {
            let fm = FileManager.default
            try? fm.createDirectory(at: AppLog.folder, withIntermediateDirectories: true)
            let url: URL = AppLog.fileURL
            if let attrs = try? fm.attributesOfItem(atPath: url.path),
               let size = (attrs[.size] as? NSNumber)?.intValue, size > AppLog.maxBytes {
                try? fm.removeItem(at: AppLog.previousFileURL)
                try? fm.moveItem(at: url, to: AppLog.previousFileURL)
            }
            let data = Data(line.utf8)
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            } else {
                try? data.write(to: url)
            }
        }
    }

    /// Waits until pending lines are on disk.
    static func flush() {
        queue.sync {}
    }

    /// Last `maxCharacters` of the log file.
    static func tail(maxCharacters: Int = 60_000) -> String {
        flush()
        guard let data = try? Data(contentsOf: fileURL) else { return "" }
        let text = String(decoding: data, as: UTF8.self)
        if text.count <= maxCharacters { return text }
        return String(text.suffix(maxCharacters))
    }

    static func clear() {
        queue.sync {
            try? FileManager.default.removeItem(at: AppLog.fileURL)
            try? FileManager.default.removeItem(at: AppLog.previousFileURL)
        }
    }

    /// Device and app information written at launch.
    static func logLaunch() {
        let dict: [String: Any] = Bundle.main.infoDictionary ?? [:]
        let version: String = dict["CFBundleShortVersionString"] as? String ?? "?"
        let build: String = dict["CFBundleVersion"] as? String ?? "?"
        let osVersion: String = ProcessInfo.processInfo.operatingSystemVersionString
        let cores: Int = ProcessInfo.processInfo.activeProcessorCount
        let memory: Double = Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824
        AppLog.info("RIVEM \(version) (\(build)), \(osVersion), \(cores) núcleos, \(String(format: "%.1f", memory)) GB")
    }
}
