import Foundation

/// Watches the macOS screenshot directory for newly created screenshot files.
///
/// Only touches the filesystem once `baseline()` / `pollNewScreenshots()` is called (when the user
/// explicitly opts in), so keeping the feature off never triggers a Desktop permission prompt.
final class ScreenshotWatcher {
    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "heic", "tiff"]
    private static let namePrefixes: [String] = [
        "Screenshot",
        "Screen Shot",
        "스크린샷",
        "スクリーンショット",
        "截屏",
        "Captura de pantalla",
        "Capture d’écran",
        "Capture d'écran",
        "Bildschirmfoto",
    ]

    private let directoryProvider: () -> URL
    private var knownFiles: Set<String> = []
    private var initialized = false

    init(directoryProvider: @escaping () -> URL = ScreenshotWatcher.defaultScreenshotDirectory) {
        self.directoryProvider = directoryProvider
    }

    static func defaultScreenshotDirectory() -> URL {
        if let raw = UserDefaults(suiteName: "com.apple.screencapture")?.string(forKey: "location"),
           !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let expanded = (raw as NSString).expandingTildeInPath
            return URL(fileURLWithPath: expanded, isDirectory: true)
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Desktop", isDirectory: true)
    }

    static func isScreenshotFilename(_ name: String) -> Bool {
        guard !name.hasPrefix(".") else { return false }
        let ext = (name as NSString).pathExtension.lowercased()
        guard imageExtensions.contains(ext) else { return false }
        return namePrefixes.contains { name.hasPrefix($0) }
    }

    /// Snapshots existing files so pre-existing screenshots are never sent.
    func baseline() {
        knownFiles = Set(currentMatchingFiles().map(\.lastPathComponent))
        initialized = true
    }

    func reset() {
        knownFiles.removeAll()
        initialized = false
    }

    /// Returns newly appeared screenshot files since the baseline or previous poll.
    func pollNewScreenshots() -> [URL] {
        let matches = currentMatchingFiles()
        if !initialized {
            knownFiles = Set(matches.map(\.lastPathComponent))
            initialized = true
            return []
        }

        var newlyCreated: [URL] = []
        for url in matches {
            let name = url.lastPathComponent
            if knownFiles.contains(name) { continue }
            // Skip zero-byte placeholders that macOS screencapture has not finished writing yet.
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            guard size > 0 else { continue }
            knownFiles.insert(name)
            newlyCreated.append(url)
        }
        return newlyCreated
    }

    private func currentMatchingFiles() -> [URL] {
        let dir = directoryProvider()
        guard let items = try? FileManager.default.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }
        return items
            .filter { url in
                let isFile = (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
                return isFile && Self.isScreenshotFilename(url.lastPathComponent)
            }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
}
