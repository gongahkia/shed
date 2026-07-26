import Foundation
import ollyDiagnostics
import ollyDSL

struct DoctorTelemetrySummary: Equatable {
    let enabled: Bool
    let pendingReportCount: Int
    let logDirectory: URL
}

extension OllyDoctor {
    func telemetryCheck() -> DoctorCheck {
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
        return DoctorCheck(
            id: "telemetry",
            title: "Telemetry",
            status: .passed,
            summary: "\(state), no pending reports"
        )
    }

    static func telemetrySummary(configURL: URL) -> DoctorTelemetrySummary {
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
