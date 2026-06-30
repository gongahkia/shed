import ArgumentParser
import Foundation
import ollyDiagnostics
import ollyDSL
import ollyIPC
import ollyKit

enum DoctorStatus: String, Codable, Equatable {
    case passed = "ok"
    case warning
    case error
}

struct Doctor: ParsableCommand {
    static let configuration = CommandConfiguration(
        abstract: "Check olly runtime, config, permissions, and desktop compatibility."
    )

    @OptionGroup
    var options: ClientOptions

    @Option(name: .customLong("config"), help: "Path to Config.swift.")
    var configPath: String?

    func run() throws {
        let configURL = configPath.map(URL.init(fileURLWithPath:)) ?? ConfigLoader.defaultSourceURL()
        let report = OllyDoctor.live(socketPath: options.socketPath, configURL: configURL).run()
        if options.json {
            print(try report.renderJSON())
        } else {
            print(report.renderPretty())
        }
        if report.overallStatus == .error {
            throw ExitCode.failure
        }
    }
}

struct DoctorCheck: Codable, Equatable {
    let id: String
    let title: String
    let status: DoctorStatus
    let summary: String
    let detail: String?

    init(
        id: String,
        title: String,
        status: DoctorStatus,
        summary: String,
        detail: String? = nil
    ) {
        self.id = id
        self.title = title
        self.status = status
        self.summary = summary
        self.detail = detail
    }
}

struct DoctorReport: Codable, Equatable {
    let schemaVersion: Int
    let overallStatus: DoctorStatus
    let checks: [DoctorCheck]

    init(schemaVersion: Int = 1, checks: [DoctorCheck]) {
        self.schemaVersion = schemaVersion
        self.checks = checks
        overallStatus = Self.overallStatus(for: checks)
    }

    func renderJSON() throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(self)
        guard let string = String(data: data, encoding: .utf8) else {
            throw OllyCtlError("failed to encode doctor report as UTF-8")
        }
        return string
    }

    func renderPretty() -> String {
        checks.map { check in
            let prefix: String
            switch check.status {
            case .passed:
                prefix = "ok"
            case .warning:
                prefix = "warn"
            case .error:
                prefix = "error"
            }
            let detail = check.detail.map { "\n  \($0)" } ?? ""
            return "\(prefix): \(check.title): \(check.summary)\(detail)"
        }.joined(separator: "\n")
    }

    private static func overallStatus(for checks: [DoctorCheck]) -> DoctorStatus {
        if checks.contains(where: { $0.status == .error }) {
            return .error
        }
        if checks.contains(where: { $0.status == .warning }) {
            return .warning
        }
        return .passed
    }
}

struct DoctorIPCProbe: Equatable {
    let protocolVersion: Int
    let supportedCommandCount: Int
    let displayCount: Int
    let windowCount: Int
}

struct DoctorCompatibilitySummary: Equatable {
    let installedCommonAppCount: Int
    let runningCommonAppCount: Int
    let observedWindowCount: Int
    let inspectedAXWindows: Bool
}

struct DoctorTelemetrySummary: Equatable {
    let enabled: Bool
    let pendingReportCount: Int
    let logDirectory: URL
}

struct DoctorConfigResult: Equatable {
    let config: Config
    let didCompile: Bool
}

struct OllyDoctor {
    let socketPath: IPCSocketPath
    let configURL: URL
    let axStatus: () -> AXPermissionStatus
    let loadConfig: () throws -> DoctorConfigResult
    let hotKeyReport: (Config) -> HotKeyCollisionReport
    let displayProvider: () -> [Display]
    let ipcProbe: () throws -> DoctorIPCProbe
    let compatibilitySummary: (AXPermissionStatus) -> DoctorCompatibilitySummary
    let telemetrySummary: () -> DoctorTelemetrySummary

    static func live(socketPath: IPCSocketPath, configURL: URL) -> OllyDoctor {
        OllyDoctor(
            socketPath: socketPath,
            configURL: configURL,
            axStatus: { AXPermission.status(prompt: false) },
            loadConfig: {
                let loaded = try ConfigLoader(sourceURL: configURL).load()
                return DoctorConfigResult(config: loaded.config, didCompile: loaded.didCompile)
            },
            hotKeyReport: { HotKeyCollisionDetector.live().report(for: $0.keybinds) },
            displayProvider: { DisplayMonitor().displays() },
            ipcProbe: { try Self.probeIPC(socketPath: socketPath) },
            compatibilitySummary: Self.compatibilitySummary,
            telemetrySummary: { Self.telemetrySummary(configURL: configURL) }
        )
    }

    func run() -> DoctorReport {
        let currentAXStatus = axStatus()
        var checks = [
            axCheck(currentAXStatus),
            configCheck(),
            displayCheck(),
            ipcCheck(),
            compatibilityCheck(currentAXStatus)
        ]
        checks.append(telemetryCheck())
        checks.append(hotKeyCheck())
        return DoctorReport(checks: checks)
    }

    private func axCheck(_ status: AXPermissionStatus) -> DoctorCheck {
        switch status {
        case .trusted:
            return DoctorCheck(id: "ax", title: "Accessibility", status: .passed, summary: "trusted")
        case .missing:
            return DoctorCheck(
                id: "ax",
                title: "Accessibility",
                status: .error,
                summary: "missing Accessibility permission",
                detail: AXPermission.accessibilitySettingsDeepLink
            )
        }
    }

    private func configCheck() -> DoctorCheck {
        do {
            let loaded = try loadConfig()
            let config = loaded.config
            let summary = "\(config.workspaces.tags.count) tags, \(config.engines.engines.count) engines"
            let state = loaded.didCompile ? "compiled" : "cache hit"
            return DoctorCheck(
                id: "config",
                title: "Config",
                status: .passed,
                summary: "\(summary), \(state)",
                detail: configURL.path
            )
        } catch ConfigLoaderError.missingSource {
            return DoctorCheck(
                id: "config",
                title: "Config",
                status: .warning,
                summary: "Config.swift not found; Olly will use defaults",
                detail: configURL.path
            )
        } catch {
            return DoctorCheck(
                id: "config",
                title: "Config",
                status: .error,
                summary: "failed to load Config.swift",
                detail: String(describing: error)
            )
        }
    }

    private func hotKeyCheck() -> DoctorCheck {
        do {
            let config = try loadConfig().config
            let report = hotKeyReport(config)
            if !report.collisions.isEmpty {
                return DoctorCheck(
                    id: "hotkeys",
                    title: "Hotkeys",
                    status: .warning,
                    summary: "\(report.collisions.count) external collision(s)",
                    detail: report.collisions.map(\.description).joined(separator: "\n")
                )
            }
            if !report.sourceErrors.isEmpty {
                return DoctorCheck(
                    id: "hotkeys",
                    title: "Hotkeys",
                    status: .warning,
                    summary: "\(report.sourceErrors.count) external source scan error(s)",
                    detail: report.sourceErrors.map { "\($0.owner.rawValue): \($0.detail)" }.joined(separator: "\n")
                )
            }
            return DoctorCheck(id: "hotkeys", title: "Hotkeys", status: .passed, summary: "no external collisions")
        } catch ConfigLoaderError.missingSource {
            return DoctorCheck(
                id: "hotkeys",
                title: "Hotkeys",
                status: .warning,
                summary: "skipped because Config.swift is missing"
            )
        } catch {
            return DoctorCheck(
                id: "hotkeys",
                title: "Hotkeys",
                status: .error,
                summary: "skipped because config failed to load",
                detail: String(describing: error)
            )
        }
    }

    private func displayCheck() -> DoctorCheck {
        let displays = displayProvider()
        guard !displays.isEmpty else {
            return DoctorCheck(id: "displays", title: "Displays", status: .error, summary: "no displays discovered")
        }
        let mainCount = displays.filter(\.isMain).count
        let status: DoctorStatus = mainCount == 1 ? .passed : .warning
        return DoctorCheck(
            id: "displays",
            title: "Displays",
            status: status,
            summary: "\(displays.count) display(s), \(mainCount) main",
            detail: displays.map { "\($0.id): \($0.localizedName)" }.joined(separator: "\n")
        )
    }

    private func ipcCheck() -> DoctorCheck {
        do {
            let probe = try ipcProbe()
            guard probe.protocolVersion == OllyIPC.protocolVersion else {
                return DoctorCheck(
                    id: "ipc",
                    title: "IPC",
                    status: .error,
                    summary: "protocol mismatch: \(probe.protocolVersion)",
                    detail: "expected \(OllyIPC.protocolVersion) at \(socketPath.rawValue)"
                )
            }
            return DoctorCheck(
                id: "ipc",
                title: "IPC",
                status: .passed,
                summary: "v\(probe.protocolVersion), \(probe.supportedCommandCount) commands",
                detail: "\(probe.displayCount) display states, \(probe.windowCount) windows"
            )
        } catch {
            return DoctorCheck(
                id: "ipc",
                title: "IPC",
                status: .warning,
                summary: "runtime not reachable",
                detail: "\(socketPath.rawValue): \(String(describing: error))"
            )
        }
    }

    private func compatibilityCheck(_ status: AXPermissionStatus) -> DoctorCheck {
        let summary = compatibilitySummary(status)
        let text = "\(summary.installedCommonAppCount) installed, \(summary.runningCommonAppCount) running"
        guard summary.inspectedAXWindows else {
            return DoctorCheck(
                id: "compatibility",
                title: "Compatibility",
                status: .warning,
                summary: "\(text); AX app-window matrix skipped"
            )
        }
        return DoctorCheck(
            id: "compatibility",
            title: "Compatibility",
            status: .passed,
            summary: "\(text), \(summary.observedWindowCount) AX windows observed"
        )
    }

    private func telemetryCheck() -> DoctorCheck {
        let summary = telemetrySummary()
        if summary.pendingReportCount > 0 && !summary.enabled {
            return DoctorCheck(
                id: "telemetry",
                title: "Telemetry",
                status: .warning,
                summary: "\(summary.pendingReportCount) pending crash report(s), telemetry disabled",
                detail: summary.logDirectory.path
            )
        }
        if summary.pendingReportCount > 0 {
            return DoctorCheck(
                id: "telemetry",
                title: "Telemetry",
                status: .passed,
                summary: "\(summary.pendingReportCount) pending crash report(s)",
                detail: "run `ollyctl telemetry flush`"
            )
        }
        let state = summary.enabled ? "enabled" : "disabled"
        return DoctorCheck(id: "telemetry", title: "Telemetry", status: .passed, summary: "\(state), no pending reports")
    }

    private static func probeIPC(socketPath: IPCSocketPath) throws -> DoctorIPCProbe {
        let version = try response(.version(IPCVersionCommand()), socketPath: socketPath).versionPayload()
        let state = try response(.state(IPCStateCommand()), socketPath: socketPath).statePayload()
        return DoctorIPCProbe(
            protocolVersion: version.protocolVersion,
            supportedCommandCount: version.supportedCommands.count,
            displayCount: state.displays.count,
            windowCount: state.windows.count
        )
    }

    private static func response(_ command: IPCCommand, socketPath: IPCSocketPath) throws -> IPCResponseEnvelope {
        let request = try JSONEncoder().encode(IPCRequestEnvelope(command: command))
        let response = try UnixDomainSocketClient(socketPath: socketPath, timeout: 1).sendLine(request)
        let envelope = try JSONDecoder().decode(IPCResponseEnvelope.self, from: response)
        if let error = envelope.error {
            throw OllyCtlError("\(error.code): \(error.message)")
        }
        return envelope
    }

    private static func telemetrySummary(configURL: URL) -> DoctorTelemetrySummary {
        let userSettings = CrashTelemetryUserSettingsStore().read()
        let loaded = try? ConfigLoader(sourceURL: configURL).load()
        let config = loaded?.config ?? Config()
        let settings = CrashTelemetryRuntimeSettings(
            configEnabled: config.telemetry.enabled,
            configEndpoint: config.telemetry.endpoint,
            configScrubbedBundleIDs: config.telemetry.scrubbedBundleIDs,
            userSettings: userSettings
        )
        let snapshot = CrashTelemetry.status(settings: settings)
        return DoctorTelemetrySummary(
            enabled: snapshot.enabled,
            pendingReportCount: snapshot.pendingReportCount,
            logDirectory: snapshot.logDirectory
        )
    }

}

private extension IPCResponseEnvelope {
    func versionPayload() throws -> IPCVersionInfo {
        guard case let .version(info)? = result else {
            throw OllyCtlError("IPC response did not contain version info")
        }
        return info
    }

    func statePayload() throws -> IPCStateSnapshot {
        guard case let .state(snapshot)? = result else {
            throw OllyCtlError("IPC response did not contain state")
        }
        return snapshot
    }
}
