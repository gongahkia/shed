import CoreGraphics
import Foundation
import XCTest
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
@testable import ollyctl

final class OllyDoctorTests: XCTestCase {
    func testDoctorSurfacesBlockingPermissionAndDisplayFailures() {
        let doctor = makeDoctor(
            axStatus: .missing,
            loadConfig: { throw ConfigLoaderError.missingSource(URL(fileURLWithPath: "/tmp/missing.swift")) },
            displays: [],
            ipcProbe: { throw IPCSocketError.notRunning("/tmp/olly.sock") },
            compatibility: { _ in
                DoctorCompatibilitySummary(
                    installedCommonAppCount: 0,
                    runningCommonAppCount: 0,
                    observedWindowCount: 0,
                    inspectedAXWindows: false
                )
            }
        )

        let report = doctor.run()

        XCTAssertEqual(report.overallStatus, .error)
        XCTAssertEqual(report.check(id: "ax")?.status, .error)
        XCTAssertEqual(report.check(id: "config")?.status, .warning)
        XCTAssertEqual(report.check(id: "displays")?.status, .error)
        XCTAssertEqual(report.check(id: "ipc")?.status, .warning)
        XCTAssertEqual(report.check(id: "compatibility")?.status, .warning)
    }

    func testDoctorReportsHotKeyCollisionsFromLoadedConfig() throws {
        let collision = HotKeyCollision(
            chord: HotKeyChord(keyCode: 0, modifiers: 0),
            action: .reload,
            externalOwner: .skhd,
            externalDetail: "skhdrc:1"
        )
        let doctor = makeDoctor(
            loadConfig: {
                DoctorConfigResult(
                    config: Config(),
                    didCompile: false
                )
            },
            hotKeyReport: { _ in HotKeyCollisionReport(collisions: [collision]) }
        )

        let report = doctor.run()

        XCTAssertEqual(report.overallStatus, DoctorStatus.warning)
        XCTAssertEqual(report.check(id: "hotkeys")?.status, DoctorStatus.warning)
        XCTAssertTrue(report.check(id: "hotkeys")?.detail?.contains("skhdrc:1") == true)
        XCTAssertTrue(try report.renderJSON().contains("\"overallStatus\" : \"warning\""))
    }

    func testDoctorWarnsWhenCrashReportsArePendingAndTelemetryDisabled() {
        let doctor = makeDoctor(
            telemetry: {
                DoctorTelemetrySummary(
                    enabled: false,
                    pendingReportCount: 2,
                    logDirectory: URL(fileURLWithPath: "/tmp/olly-crashes")
                )
            }
        )

        let report = doctor.run()

        XCTAssertEqual(report.overallStatus, .warning)
        XCTAssertEqual(report.check(id: "telemetry")?.status, .warning)
        XCTAssertTrue(report.check(id: "telemetry")?.summary.contains("2 pending") == true)
    }

    func testDoctorReportsAllGreenWhenRequiredChecksPass() {
        let doctor = makeDoctor()

        let report = doctor.run()

        XCTAssertEqual(report.overallStatus, .passed)
        XCTAssertTrue(report.checks.allSatisfy { $0.status == .passed })
        XCTAssertTrue(report.renderPretty().contains("ok: IPC"))
    }

    private func makeDoctor(
        axStatus: AXPermissionStatus = .trusted,
        loadConfig: @escaping () throws -> DoctorConfigResult = {
            DoctorConfigResult(
                config: Config(),
                didCompile: false
            )
        },
        hotKeyReport: @escaping (Config) -> HotKeyCollisionReport = { _ in HotKeyCollisionReport(collisions: []) },
        displays: [Display] = [
            Display(
                id: 1,
                frame: CGRect(x: 0, y: 0, width: 1440, height: 900),
                visibleFrame: CGRect(x: 0, y: 0, width: 1440, height: 860),
                scaleFactor: 2,
                localizedName: "Built-in",
                isMain: true
            )
        ],
        ipcProbe: @escaping () throws -> DoctorIPCProbe = {
            DoctorIPCProbe(
                protocolVersion: OllyIPC.protocolVersion,
                supportedCommandCount: OllyIPC.supportedCommandNames.count,
                displayCount: 1,
                windowCount: 0
            )
        },
        compatibility: @escaping (AXPermissionStatus) -> DoctorCompatibilitySummary = { _ in
            DoctorCompatibilitySummary(
                installedCommonAppCount: 1,
                runningCommonAppCount: 1,
                observedWindowCount: 1,
                inspectedAXWindows: true
            )
        },
        telemetry: @escaping () -> DoctorTelemetrySummary = {
            DoctorTelemetrySummary(
                enabled: false,
                pendingReportCount: 0,
                logDirectory: URL(fileURLWithPath: "/tmp/olly-crashes")
            )
        }
    ) -> OllyDoctor {
        OllyDoctor(
            socketPath: IPCSocketPath("/tmp/olly.sock"),
            configURL: URL(fileURLWithPath: "/tmp/Config.swift"),
            axStatus: { axStatus },
            loadConfig: loadConfig,
            hotKeyReport: hotKeyReport,
            displayProvider: { displays },
            ipcProbe: ipcProbe,
            compatibilitySummary: compatibility,
            telemetrySummary: telemetry
        )
    }
}

private extension DoctorReport {
    func check(id: String) -> DoctorCheck? {
        checks.first { $0.id == id }
    }
}
