import XCTest
@testable import ollyDSL

final class ConfigTemplateTests: XCTestCase {
    func testTemplateProfilesExposeStableNames() throws {
        XCTAssertEqual(ConfigTemplateProfile.allCases.map(\.rawValue), [
            "starter",
            "minimal",
            "niri",
            "bsp",
            "ultrawide"
        ])
        XCTAssertEqual(try ConfigTemplateProfile(name: "starter"), .starter)
        XCTAssertThrowsError(try ConfigTemplateProfile(name: "unknown"))
    }

    func testTemplateProfilesCompileAndLoad() throws {
        let modulesURL = Bundle(for: Self.self).bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)

        for profile in ConfigTemplateProfile.allCases {
            let directory = try temporaryDirectory()
            let sourceURL = directory.appendingPathComponent("Config.swift")
            try profile.source.write(to: sourceURL, atomically: true, encoding: .utf8)
            let loader = ConfigLoader(
                sourceURL: sourceURL,
                cacheDirectory: directory.appendingPathComponent("cache", isDirectory: true),
                moduleSearchPaths: [modulesURL]
            )

            let loaded = try loader.load()

            XCTAssertEqual(loaded.config.version, .v1, profile.rawValue)
            XCTAssertFalse(loaded.config.workspaces.tags.isEmpty, profile.rawValue)
            XCTAssertFalse(loaded.config.engines.engines.isEmpty, profile.rawValue)
        }
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyTemplateTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
