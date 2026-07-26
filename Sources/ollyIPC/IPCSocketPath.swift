import Foundation

public struct IPCSocketPath: Equatable, Hashable, Sendable {
    public let rawValue: String

    public init(_ rawValue: String) {
        self.rawValue = rawValue
    }

    public var url: URL {
        URL(fileURLWithPath: rawValue)
    }

    public var directoryURL: URL {
        url.deletingLastPathComponent()
    }

    public static func resolved(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser
    ) -> IPCSocketPath {
        if let runtimeDirectory = environment["XDG_RUNTIME_DIR"], !runtimeDirectory.isEmpty {
            let url = URL(fileURLWithPath: runtimeDirectory, isDirectory: true)
            return IPCSocketPath(url.appendingPathComponent("olly.sock").path)
        }

        let fallback = homeDirectory
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("olly", isDirectory: true)
            .appendingPathComponent("olly.sock")
        return IPCSocketPath(fallback.path)
    }
}
