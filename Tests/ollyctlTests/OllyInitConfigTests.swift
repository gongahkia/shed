import Foundation
import XCTest
import ollyDSL
@testable import ollyctl

final class OllyInitConfigTests: XCTestCase {
    func testInitializerCreatesParentDirectoryAndProfileSource() throws {
        let directory = try temporaryDirectory()
        let configURL = directory.appendingPathComponent("nested/Config.swift")

        let result = try ConfigInitializer().write(profile: .bsp, to: configURL, force: false)

        XCTAssertEqual(result.profile, .bsp)
        XCTAssertFalse(result.didOverwrite)
        let source = try String(contentsOf: configURL, encoding: .utf8)
        XCTAssertTrue(source.contains("EngineDeclaration.bsp"))
    }

    func testInitializerRefusesOverwriteWithoutForce() throws {
        let directory = try temporaryDirectory()
        let configURL = directory.appendingPathComponent("Config.swift")
        try "existing".write(to: configURL, atomically: true, encoding: .utf8)

        XCTAssertThrowsError(try ConfigInitializer().write(profile: .starter, to: configURL, force: false))
        XCTAssertEqual(try String(contentsOf: configURL, encoding: .utf8), "existing")
    }

    func testInitializerOverwritesWithForce() throws {
        let directory = try temporaryDirectory()
        let configURL = directory.appendingPathComponent("Config.swift")
        try "existing".write(to: configURL, atomically: true, encoding: .utf8)

        let result = try ConfigInitializer().write(profile: .minimal, to: configURL, force: true)

        XCTAssertTrue(result.didOverwrite)
        XCTAssertTrue(try String(contentsOf: configURL, encoding: .utf8).contains("Tag.named(\"main\")"))
    }

    func testProfileListIncludesSummaries() {
        let list = InitConfig.profileList()

        XCTAssertTrue(list.contains("starter:"))
        XCTAssertTrue(list.contains("ultrawide:"))
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyInitConfigTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
