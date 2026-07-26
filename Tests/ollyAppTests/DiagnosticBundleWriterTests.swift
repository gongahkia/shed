import Foundation
import XCTest
import ollyRuntime
@testable import ollyApp

final class DiagnosticBundleWriterTests: XCTestCase {
    func testBundleIncludesRuntimeErrorsRecoveryJournalAndCrashReports() throws {
        let directory = try temporaryDirectory()
        let logsURL = directory.appendingPathComponent("logs", isDirectory: true)
        let crashURL = directory.appendingPathComponent("crashes", isDirectory: true)
        let recoveryURL = directory.appendingPathComponent("recovery.json")
        try FileManager.default.createDirectory(at: crashURL, withIntermediateDirectories: true)
        try #"{"version":2}"#.write(to: recoveryURL, atomically: true, encoding: .utf8)
        try #"{"signalName":"SIGABRT"}"#.write(
            to: crashURL.appendingPathComponent("sample.crash.json"),
            atomically: true,
            encoding: .utf8
        )
        let writer = DiagnosticBundleWriter(
            logDirectory: logsURL,
            recoveryJournalURL: recoveryURL,
            crashLogDirectory: crashURL,
            now: { Date(timeIntervalSince1970: 0) }
        )

        let zipURL = try writer.write(errors: [
            RuntimeErrorRecord(timestamp: Date(timeIntervalSince1970: 1), message: "reload failed")
        ])
        let extractURL = directory.appendingPathComponent("extract", isDirectory: true)
        try unzip(zipURL, to: extractURL)

        XCTAssertEqual(zipURL.lastPathComponent, "19700101-000000-diagnostic.zip")
        XCTAssertTrue(FileManager.default.fileExists(atPath: extractURL.appendingPathComponent("runtime-errors.json").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: extractURL.appendingPathComponent("recovery.json").path))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: extractURL.appendingPathComponent("crash-reports/sample.crash.json").path
        ))
    }

    private func unzip(_ zipURL: URL, to destinationURL: URL) throws {
        try FileManager.default.createDirectory(at: destinationURL, withIntermediateDirectories: true)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        process.arguments = ["-q", zipURL.path, "-d", destinationURL.path]
        try process.run()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyDiagnosticBundleTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
