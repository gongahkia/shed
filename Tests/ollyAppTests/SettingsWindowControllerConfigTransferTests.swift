import Foundation
import XCTest
import ollyDSL
@testable import ollyApp

final class SettingsWindowControllerConfigTransferTests: XCTestCase {
    func testExportCreatesMissingConfigFromSelectedProfile() throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("config/Config.swift")
        let destinationURL = directory.appendingPathComponent("share/Config.swift")

        try SettingsWindowController.exportConfig(
            profile: .minimal,
            sourceURL: sourceURL,
            destinationURL: destinationURL
        )

        XCTAssertEqual(try String(contentsOf: sourceURL), ConfigTemplateProfile.minimal.source)
        XCTAssertEqual(try String(contentsOf: destinationURL), ConfigTemplateProfile.minimal.source)
    }

    func testImportOverwritesConfigAndCreatesParentDirectory() throws {
        let directory = try temporaryDirectory()
        let importURL = directory.appendingPathComponent("incoming.swift")
        let sourceURL = directory.appendingPathComponent("nested/Config.swift")
        try "imported".write(to: importURL, atomically: true, encoding: .utf8)

        try SettingsWindowController.importConfig(from: importURL, to: sourceURL)

        XCTAssertEqual(try String(contentsOf: sourceURL), "imported")
    }

    func testImportSameFileIsNoOp() throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("Config.swift")
        try "same".write(to: sourceURL, atomically: true, encoding: .utf8)

        try SettingsWindowController.importConfig(from: sourceURL, to: sourceURL)

        XCTAssertEqual(try String(contentsOf: sourceURL), "same")
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollySettingsTransferTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
