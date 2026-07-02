import XCTest
@testable import ollyDiagnostics

final class CrashTelemetryTests: XCTestCase {
    func testRuntimeSettingsPreferUserOptIn() throws {
        let settings = CrashTelemetryRuntimeSettings(
            configEnabled: false,
            configEndpoint: nil,
            configScrubbedBundleIDs: true,
            userSettings: CrashTelemetryUserSettings(
                enabled: true,
                endpoint: "https://crashes.example.test/olly",
                scrubbedBundleIDs: false
            )
        )

        XCTAssertTrue(settings.enabled)
        XCTAssertEqual(settings.endpoint?.absoluteString, "https://crashes.example.test/olly")
        XCTAssertFalse(settings.scrubbedBundleIDs)
    }

    func testRuntimeSettingsRespectGlobalTelemetryDisableEnvironment() {
        let settings = CrashTelemetryRuntimeSettings(
            configEnabled: true,
            configEndpoint: "https://crashes.example.test",
            configScrubbedBundleIDs: true,
            environment: ["OLLY_DISABLE_TELEMETRY": "1"]
        )

        XCTAssertFalse(settings.enabled)
        XCTAssertEqual(settings.endpoint?.absoluteString, "https://crashes.example.test")
    }

    func testWriteAndListPendingCrashReports() throws {
        let directory = try temporaryDirectory()
        let report = makeReport(frameCount: 31)

        let url = try CrashTelemetry.write(report: report, to: directory)
        let urls = CrashTelemetry.pendingReportURLs(logDirectory: directory)
        let data = try Data(contentsOf: url)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(CrashTelemetryReport.self, from: data)

        XCTAssertEqual(url.lastPathComponent, "19700101-000000-000.crash.json")
        XCTAssertEqual(urls.map(\.lastPathComponent), [url.lastPathComponent])
        XCTAssertEqual(decoded.frames.count, 30)
        XCTAssertTrue(decoded.scrubbedBundleIDs)
    }

    func testFlushUploadsAndDeletesPendingReports() throws {
        let directory = try temporaryDirectory()
        let reportURL = try CrashTelemetry.write(report: makeReport(), to: directory)
        let recorder = RecordingUploader()
        let endpoint = try XCTUnwrap(URL(string: "https://crashes.example.test/olly"))

        let result = CrashTelemetry.flush(
            endpoint: endpoint,
            logDirectory: directory,
            uploader: recorder
        )

        XCTAssertEqual(result.sentCount, 1)
        XCTAssertEqual(result.failedCount, 0)
        XCTAssertEqual(recorder.uploads.map(\.endpoint), [endpoint])
        XCTAssertFalse(FileManager.default.fileExists(atPath: reportURL.path))
    }

    func testUserSettingsStoreRoundTrips() throws {
        let directory = try temporaryDirectory()
        let url = directory.appendingPathComponent("Telemetry.json")
        let store = CrashTelemetryUserSettingsStore(settingsURL: url)

        try store.write(CrashTelemetryUserSettings(enabled: true, endpoint: "https://example.test", scrubbedBundleIDs: true))

        XCTAssertEqual(
            store.read(),
            CrashTelemetryUserSettings(enabled: true, endpoint: "https://example.test", scrubbedBundleIDs: true)
        )
    }

    private func makeReport(frameCount: Int = 2) -> CrashTelemetryReport {
        CrashTelemetryReport(
            timestamp: Date(timeIntervalSince1970: 0),
            signalName: "SIGABRT",
            signalCode: "0",
            exceptionName: nil,
            frames: (0..<frameCount).map { "_$s4olly\($0)" },
            context: CrashTelemetryContext(
                appVersion: "dev",
                dslVersion: "v1",
                configHash: "abc123",
                displayCount: 2,
                tagCount: 4,
                scrubbedBundleIDs: true
            )
        )
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyCrashTelemetryTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

private final class RecordingUploader: CrashTelemetryUploading {
    struct Upload: Equatable {
        let data: Data
        let endpoint: URL
    }

    private(set) var uploads: [Upload] = []

    func upload(reportData: Data, to endpoint: URL) throws {
        uploads.append(Upload(data: reportData, endpoint: endpoint))
    }
}
