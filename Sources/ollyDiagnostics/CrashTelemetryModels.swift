import Foundation

public enum CrashTelemetryError: Error, CustomStringConvertible, Equatable {
    case invalidEndpoint(String)
    case missingEndpoint
    case uploadFailed(String)
    case utf8EncodingFailed

    public var description: String {
        switch self {
        case let .invalidEndpoint(value):
            return "invalid telemetry endpoint: \(value)"
        case .missingEndpoint:
            return "telemetry endpoint is not configured"
        case let .uploadFailed(message):
            return "telemetry upload failed: \(message)"
        case .utf8EncodingFailed:
            return "failed to encode telemetry payload as UTF-8"
        }
    }
}

public struct CrashTelemetryRuntimeSettings: Equatable, Sendable {
    public let enabled: Bool
    public let endpoint: URL?
    public let scrubbedBundleIDs: Bool

    public init(enabled: Bool = false, endpoint: URL? = nil, scrubbedBundleIDs: Bool = true) {
        self.enabled = enabled
        self.endpoint = endpoint
        self.scrubbedBundleIDs = scrubbedBundleIDs
    }

    public init(
        configEnabled: Bool,
        configEndpoint: String?,
        configScrubbedBundleIDs: Bool,
        userSettings: CrashTelemetryUserSettings = CrashTelemetryUserSettings()
    ) {
        let endpointString = userSettings.endpoint ?? configEndpoint
        self.init(
            enabled: userSettings.enabled ?? configEnabled,
            endpoint: endpointString.flatMap(URL.init(string:)),
            scrubbedBundleIDs: userSettings.scrubbedBundleIDs ?? configScrubbedBundleIDs
        )
    }
}

public struct CrashTelemetryUserSettings: Codable, Equatable, Sendable {
    public var enabled: Bool?
    public var endpoint: String?
    public var scrubbedBundleIDs: Bool?

    public init(enabled: Bool? = nil, endpoint: String? = nil, scrubbedBundleIDs: Bool? = nil) {
        self.enabled = enabled
        self.endpoint = endpoint
        self.scrubbedBundleIDs = scrubbedBundleIDs
    }
}

public struct CrashTelemetryContext: Codable, Equatable, Sendable {
    public let appVersion: String
    public let dslVersion: String
    public let configHash: String?
    public let displayCount: Int
    public let tagCount: Int
    public let scrubbedBundleIDs: Bool

    public init(
        appVersion: String,
        dslVersion: String,
        configHash: String?,
        displayCount: Int,
        tagCount: Int,
        scrubbedBundleIDs: Bool
    ) {
        self.appVersion = appVersion
        self.dslVersion = dslVersion
        self.configHash = configHash
        self.displayCount = displayCount
        self.tagCount = tagCount
        self.scrubbedBundleIDs = scrubbedBundleIDs
    }
}

public struct CrashTelemetryReport: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let timestamp: Date
    public let signalName: String?
    public let signalCode: String?
    public let exceptionName: String?
    public let frames: [String]
    public let appVersion: String
    public let dslVersion: String
    public let configHash: String?
    public let displayCount: Int
    public let tagCount: Int
    public let scrubbedBundleIDs: Bool

    public init(
        schemaVersion: Int = 1,
        timestamp: Date,
        signalName: String?,
        signalCode: String?,
        exceptionName: String?,
        frames: [String],
        context: CrashTelemetryContext
    ) {
        self.schemaVersion = schemaVersion
        self.timestamp = timestamp
        self.signalName = signalName
        self.signalCode = signalCode
        self.exceptionName = exceptionName
        self.frames = Array(frames.prefix(30))
        appVersion = context.appVersion
        dslVersion = context.dslVersion
        configHash = context.configHash
        displayCount = context.displayCount
        tagCount = context.tagCount
        scrubbedBundleIDs = context.scrubbedBundleIDs
    }
}

public struct CrashTelemetryInstallResult: Equatable, Sendable {
    public let enabled: Bool
    public let wroteReportURL: URL?

    public init(enabled: Bool, wroteReportURL: URL?) {
        self.enabled = enabled
        self.wroteReportURL = wroteReportURL
    }
}

public struct CrashTelemetryStatusSnapshot: Equatable, Sendable {
    public let enabled: Bool
    public let endpoint: URL?
    public let scrubbedBundleIDs: Bool
    public let pendingReportCount: Int
    public let logDirectory: URL
    public let settingsURL: URL

    public init(
        enabled: Bool,
        endpoint: URL?,
        scrubbedBundleIDs: Bool,
        pendingReportCount: Int,
        logDirectory: URL,
        settingsURL: URL
    ) {
        self.enabled = enabled
        self.endpoint = endpoint
        self.scrubbedBundleIDs = scrubbedBundleIDs
        self.pendingReportCount = pendingReportCount
        self.logDirectory = logDirectory
        self.settingsURL = settingsURL
    }
}

public struct CrashTelemetryFlushResult: Codable, Equatable, Sendable {
    public let sentCount: Int
    public let failedCount: Int
    public let errors: [String]

    public init(sentCount: Int, failedCount: Int, errors: [String] = []) {
        self.sentCount = sentCount
        self.failedCount = failedCount
        self.errors = errors
    }
}

public struct CrashTelemetryUserSettingsStore {
    public let settingsURL: URL
    private let fileManager: FileManager

    public init(
        settingsURL: URL = CrashTelemetry.defaultSettingsURL(),
        fileManager: FileManager = .default
    ) {
        self.settingsURL = settingsURL
        self.fileManager = fileManager
    }

    public func read() -> CrashTelemetryUserSettings {
        guard let data = try? Data(contentsOf: settingsURL) else {
            return CrashTelemetryUserSettings()
        }
        return (try? JSONDecoder().decode(CrashTelemetryUserSettings.self, from: data))
            ?? CrashTelemetryUserSettings()
    }

    public func write(_ settings: CrashTelemetryUserSettings) throws {
        try fileManager.createDirectory(
            at: settingsURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(settings).write(to: settingsURL, options: .atomic)
    }
}

public protocol CrashTelemetryUploading {
    func upload(reportData: Data, to endpoint: URL) throws
}

public struct URLSessionCrashTelemetryUploader: CrashTelemetryUploading {
    public let timeout: TimeInterval

    public init(timeout: TimeInterval = 10) {
        self.timeout = timeout
    }

    public func upload(reportData: Data, to endpoint: URL) throws {
        var request = URLRequest(url: endpoint, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Void, Error>?
        let task = URLSession.shared.uploadTask(with: request, from: reportData) { _, response, error in
            if let error {
                result = .failure(error)
            } else if let response = response as? HTTPURLResponse,
                      !(200..<300).contains(response.statusCode) {
                result = .failure(CrashTelemetryError.uploadFailed("HTTP \(response.statusCode)"))
            } else {
                result = .success(())
            }
            semaphore.signal()
        }
        task.resume()
        if semaphore.wait(timeout: .now() + timeout) == .timedOut {
            task.cancel()
            throw CrashTelemetryError.uploadFailed("timed out")
        }
        switch result {
        case .success:
            return
        case let .failure(error):
            throw error
        case .none:
            throw CrashTelemetryError.uploadFailed("no response")
        }
    }
}
