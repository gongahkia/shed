import ArgumentParser
import Foundation
import ollyDiagnostics
import ollyDSL

struct TelemetryCommands: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "telemetry",
        abstract: "Manage local crash telemetry.",
        subcommands: [
            TelemetryStatus.self,
            TelemetryEnable.self,
            TelemetryDisable.self,
            TelemetryFlush.self
        ],
        defaultSubcommand: TelemetryStatus.self
    )
}

struct TelemetryOptions: ParsableArguments {
    @Option(name: .customLong("config"), help: "Path to Config.swift.")
    var configPath: String?

    @Option(name: .customLong("settings"), help: "Path to Telemetry.json.")
    var settingsPath: String?

    @Option(name: .customLong("logs"), help: "Path to the crash telemetry log directory.")
    var logsPath: String?

    @Flag(help: "Print JSON output.")
    var json = false

    var configURL: URL {
        configPath.map(URL.init(fileURLWithPath:)) ?? ConfigLoader.defaultSourceURL()
    }

    var settingsURL: URL {
        settingsPath.map(URL.init(fileURLWithPath:)) ?? CrashTelemetry.defaultSettingsURL()
    }

    var logDirectory: URL {
        logsPath.map(URL.init(fileURLWithPath:)) ?? CrashTelemetry.defaultLogDirectory()
    }

    func userSettingsStore() -> CrashTelemetryUserSettingsStore {
        CrashTelemetryUserSettingsStore(settingsURL: settingsURL)
    }

    func effectiveSettings() -> CrashTelemetryRuntimeSettings {
        let userSettings = userSettingsStore().read()
        let loaded = try? ConfigLoader(sourceURL: configURL).load()
        let config = loaded?.config ?? Config()
        return CrashTelemetryRuntimeSettings(
            configEnabled: config.telemetry.enabled,
            configEndpoint: config.telemetry.endpoint,
            configScrubbedBundleIDs: config.telemetry.scrubbedBundleIDs,
            userSettings: userSettings
        )
    }
}

struct TelemetryStatus: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "status",
        abstract: "Print local crash telemetry status."
    )

    @OptionGroup
    var options: TelemetryOptions

    func run() throws {
        let snapshot = CrashTelemetry.status(
            settings: options.effectiveSettings(),
            settingsURL: options.settingsURL,
            logDirectory: options.logDirectory
        )
        if options.json {
            print(try renderJSON(snapshot))
        } else {
            print(renderPretty(snapshot))
        }
    }

    private func renderPretty(_ snapshot: CrashTelemetryStatusSnapshot) -> String {
        [
            "enabled: \(snapshot.enabled)",
            "endpoint: \(snapshot.endpoint?.absoluteString ?? "-")",
            "scrubbedBundleIDs: \(snapshot.scrubbedBundleIDs)",
            "pendingReports: \(snapshot.pendingReportCount)",
            "logs: \(snapshot.logDirectory.path)",
            "settings: \(snapshot.settingsURL.path)"
        ].joined(separator: "\n")
    }

    private func renderJSON(_ snapshot: CrashTelemetryStatusSnapshot) throws -> String {
        let payload = TelemetryStatusPayload(snapshot)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(payload)
        guard let string = String(data: data, encoding: .utf8) else {
            throw CrashTelemetryError.utf8EncodingFailed
        }
        return string
    }
}

struct TelemetryEnable: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "enable",
        abstract: "Opt in to local crash capture."
    )

    @OptionGroup
    var options: TelemetryOptions

    @Option(help: "Self-hosted crash upload endpoint used by telemetry flush.")
    var endpoint: String?

    @Flag(help: "Allow future crash payloads to include app bundle identifiers.")
    var allowBundleIDs = false

    func run() throws {
        if let endpoint {
            _ = try parseEndpoint(endpoint)
        }
        var settings = options.userSettingsStore().read()
        settings.enabled = true
        if let endpoint {
            settings.endpoint = endpoint
        }
        settings.scrubbedBundleIDs = !allowBundleIDs
        try options.userSettingsStore().write(settings)
        print("telemetry enabled")
    }
}

struct TelemetryDisable: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "disable",
        abstract: "Disable crash telemetry."
    )

    @OptionGroup
    var options: TelemetryOptions

    func run() throws {
        var settings = options.userSettingsStore().read()
        settings.enabled = false
        try options.userSettingsStore().write(settings)
        print("telemetry disabled")
    }
}

struct TelemetryFlush: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "flush",
        abstract: "Upload pending crash reports to the configured endpoint."
    )

    @OptionGroup
    var options: TelemetryOptions

    @Option(help: "Override the configured self-hosted endpoint.")
    var endpoint: String?

    func run() throws {
        let settings = options.effectiveSettings()
        guard settings.enabled else {
            throw OllyCtlError("telemetry is disabled; run `ollyctl telemetry enable` first")
        }
        let target = try endpoint.map(parseEndpoint) ?? settings.endpoint
        guard let target else {
            throw CrashTelemetryError.missingEndpoint
        }
        let result = CrashTelemetry.flush(endpoint: target, logDirectory: options.logDirectory)
        if options.json {
            print(try renderJSON(result))
        } else {
            print("sent \(result.sentCount), failed \(result.failedCount)")
            if !result.errors.isEmpty {
                print(result.errors.joined(separator: "\n"))
            }
        }
        if result.failedCount > 0 {
            throw ExitCode.failure
        }
    }

    private func renderJSON(_ result: CrashTelemetryFlushResult) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(result)
        guard let string = String(data: data, encoding: .utf8) else {
            throw CrashTelemetryError.utf8EncodingFailed
        }
        return string
    }
}

private struct TelemetryStatusPayload: Codable {
    let enabled: Bool
    let endpoint: String?
    let scrubbedBundleIDs: Bool
    let pendingReportCount: Int
    let logDirectory: String
    let settingsURL: String

    init(_ snapshot: CrashTelemetryStatusSnapshot) {
        enabled = snapshot.enabled
        endpoint = snapshot.endpoint?.absoluteString
        scrubbedBundleIDs = snapshot.scrubbedBundleIDs
        pendingReportCount = snapshot.pendingReportCount
        logDirectory = snapshot.logDirectory.path
        settingsURL = snapshot.settingsURL.path
    }
}

private func parseEndpoint(_ value: String) throws -> URL {
    guard let url = URL(string: value), url.scheme != nil, url.host != nil else {
        throw CrashTelemetryError.invalidEndpoint(value)
    }
    return url
}
