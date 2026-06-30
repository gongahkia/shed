import CrashReporter
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

public struct CrashTelemetryFlushResult: Equatable, Sendable {
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

public enum CrashTelemetry {
    private static let reporterLock = NSLock()
    private static var retainedReporter: PLCrashReporter?

    @discardableResult
    public static func install(
        settings: CrashTelemetryRuntimeSettings,
        context: CrashTelemetryContext,
        fileManager: FileManager = .default
    ) throws -> CrashTelemetryInstallResult {
        guard settings.enabled else {
            retainReporter(nil)
            return CrashTelemetryInstallResult(enabled: false, wroteReportURL: nil)
        }
        let logDirectory = defaultLogDirectory(fileManager: fileManager)
        let rawDirectory = logDirectory.appendingPathComponent("pending", isDirectory: true)
        try fileManager.createDirectory(at: rawDirectory, withIntermediateDirectories: true)
        let config = PLCrashReporterConfig(basePath: rawDirectory.path)
        let reporter = PLCrashReporter(configuration: config)
        let wroteReportURL = try writePendingReportIfNeeded(
            reporter: reporter,
            fallbackContext: context,
            logDirectory: logDirectory,
            fileManager: fileManager
        )
        reporter.customData = try JSONEncoder().encode(context)
        var error: NSError?
        guard reporter.enableCrashReporterAndReturnError(&error) else {
            throw error ?? CrashTelemetryError.uploadFailed("failed to enable PLCrashReporter")
        }
        retainReporter(reporter)
        return CrashTelemetryInstallResult(enabled: true, wroteReportURL: wroteReportURL)
    }

    public static func status(
        settings: CrashTelemetryRuntimeSettings,
        settingsURL: URL = defaultSettingsURL(),
        logDirectory: URL = defaultLogDirectory(),
        fileManager: FileManager = .default
    ) -> CrashTelemetryStatusSnapshot {
        CrashTelemetryStatusSnapshot(
            enabled: settings.enabled,
            endpoint: settings.endpoint,
            scrubbedBundleIDs: settings.scrubbedBundleIDs,
            pendingReportCount: pendingReportURLs(logDirectory: logDirectory, fileManager: fileManager).count,
            logDirectory: logDirectory,
            settingsURL: settingsURL
        )
    }

    public static func flush(
        endpoint: URL,
        logDirectory: URL = defaultLogDirectory(),
        fileManager: FileManager = .default,
        uploader: CrashTelemetryUploading = URLSessionCrashTelemetryUploader()
    ) -> CrashTelemetryFlushResult {
        var sent = 0
        var errors: [String] = []
        for url in pendingReportURLs(logDirectory: logDirectory, fileManager: fileManager) {
            do {
                let data = try Data(contentsOf: url)
                try uploader.upload(reportData: data, to: endpoint)
                try fileManager.removeItem(at: url)
                sent += 1
            } catch {
                errors.append("\(url.lastPathComponent): \(String(describing: error))")
            }
        }
        return CrashTelemetryFlushResult(sentCount: sent, failedCount: errors.count, errors: errors)
    }

    public static func write(
        report: CrashTelemetryReport,
        to logDirectory: URL = defaultLogDirectory(),
        fileManager: FileManager = .default
    ) throws -> URL {
        try fileManager.createDirectory(at: logDirectory, withIntermediateDirectories: true)
        let url = logDirectory.appendingPathComponent("\(fileTimestamp(report.timestamp)).crash.json")
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(report).write(to: url, options: .atomic)
        return url
    }

    public static func pendingReportURLs(
        logDirectory: URL = defaultLogDirectory(),
        fileManager: FileManager = .default
    ) -> [URL] {
        let urls = (try? fileManager.contentsOfDirectory(
            at: logDirectory,
            includingPropertiesForKeys: nil
        )) ?? []
        return urls
            .filter { $0.lastPathComponent.hasSuffix(".crash.json") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    public static func defaultLogDirectory(fileManager: FileManager = .default) -> URL {
        if let library = fileManager.urls(for: .libraryDirectory, in: .userDomainMask).first {
            return library.appendingPathComponent("Logs", isDirectory: true)
                .appendingPathComponent("Olly", isDirectory: true)
        }
        return fileManager.homeDirectoryForCurrentUser
            .appendingPathComponent("Library", isDirectory: true)
            .appendingPathComponent("Logs", isDirectory: true)
            .appendingPathComponent("Olly", isDirectory: true)
    }

    public static func defaultSettingsURL(fileManager: FileManager = .default) -> URL {
        fileManager.homeDirectoryForCurrentUser
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("olly", isDirectory: true)
            .appendingPathComponent("Telemetry.json")
    }

    private static func writePendingReportIfNeeded(
        reporter: PLCrashReporter,
        fallbackContext: CrashTelemetryContext,
        logDirectory: URL,
        fileManager: FileManager
    ) throws -> URL? {
        guard reporter.hasPendingCrashReport() else {
            return nil
        }
        var error: NSError?
        guard let data = reporter.loadPendingCrashReportDataAndReturnError(&error) else {
            throw error ?? CrashTelemetryError.uploadFailed("failed to load pending crash report")
        }
        let report = try makeReport(from: data, fallbackContext: fallbackContext)
        let url = try write(report: report, to: logDirectory, fileManager: fileManager)
        guard reporter.purgePendingCrashReportAndReturnError(&error) else {
            throw error ?? CrashTelemetryError.uploadFailed("failed to purge pending crash report")
        }
        return url
    }

    private static func makeReport(
        from data: Data,
        fallbackContext: CrashTelemetryContext
    ) throws -> CrashTelemetryReport {
        var error: NSError?
        guard let crashReport = PLCrashReport(data: data, error: &error) else {
            throw error ?? CrashTelemetryError.uploadFailed("failed to decode pending crash report")
        }
        let context = context(from: crashReport.customData) ?? fallbackContext
        return CrashTelemetryReport(
            timestamp: crashReport.systemInfo.timestamp as Date? ?? Date(),
            signalName: crashReport.signalInfo.name,
            signalCode: crashReport.signalInfo.code,
            exceptionName: crashReport.exceptionInfo?.exceptionName,
            frames: topFrames(from: crashReport),
            context: context
        )
    }

    private static func context(from data: Data?) -> CrashTelemetryContext? {
        guard let data else {
            return nil
        }
        return try? JSONDecoder().decode(CrashTelemetryContext.self, from: data)
    }

    private static func topFrames(from report: PLCrashReport) -> [String] {
        let threads = report.threads.compactMap { $0 as? PLCrashReportThreadInfo }
        let thread = threads.first(where: \.crashed) ?? threads.first
        let frames = thread?.stackFrames.compactMap { $0 as? PLCrashReportStackFrameInfo } ?? []
        return frames.prefix(30).map { frame in
            frame.symbolInfo?.symbolName ?? String(format: "0x%llx", frame.instructionPointer)
        }
    }

    private static func retainReporter(_ reporter: PLCrashReporter?) {
        reporterLock.lock()
        retainedReporter = reporter
        reporterLock.unlock()
    }

    private static func fileTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd-HHmmss-SSS"
        return formatter.string(from: date)
    }
}
