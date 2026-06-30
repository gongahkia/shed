import CrashReporter
import Foundation

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
        guard let reporter = PLCrashReporter(configuration: config) else {
            throw CrashTelemetryError.uploadFailed("failed to initialize PLCrashReporter")
        }
        let wroteReportURL = try writePendingReportIfNeeded(
            reporter: reporter,
            fallbackContext: context,
            logDirectory: logDirectory,
            fileManager: fileManager
        )
        reporter.customData = try JSONEncoder().encode(context)
        try reporter.enableAndReturnError()
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
        let data = try reporter.loadPendingCrashReportDataAndReturnError()
        let report = try makeReport(from: data, fallbackContext: fallbackContext)
        let url = try write(report: report, to: logDirectory, fileManager: fileManager)
        try reporter.purgePendingCrashReportAndReturnError()
        return url
    }

    private static func makeReport(
        from data: Data,
        fallbackContext: CrashTelemetryContext
    ) throws -> CrashTelemetryReport {
        let crashReport = try PLCrashReport(data: data)
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
