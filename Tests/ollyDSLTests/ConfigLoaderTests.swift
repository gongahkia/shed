import XCTest
@testable import ollyDSL

final class ConfigLoaderTests: XCTestCase {
    func testConfigDefaultsToVersionOne() {
        XCTAssertEqual(Config(), Config(version: .v1))
        XCTAssertEqual(Config {}, Config(version: .v1))
    }

    func testConfigBuilderAcceptsTopLevelSections() {
        let config = Config {
            Keybinds()
            Rules()
            Workspaces()
            Engines()
            Hooks()
        }

        XCTAssertEqual(config.keybinds, Keybinds())
        XCTAssertEqual(config.rules, Rules())
        XCTAssertEqual(config.workspaces, Workspaces())
        XCTAssertEqual(config.engines, Engines())
        XCTAssertEqual(config.hooks, Hooks())
    }

    func testConfigDecodingDefaultsMissingSections() throws {
        let data = Data(#"{"version":"v1"}"#.utf8)
        let config = try JSONDecoder().decode(Config.self, from: data)

        XCTAssertEqual(config, Config())
    }

    func testCompileIfNeededCachesBySourceHash() throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("Config.swift")
        let compilerURL = directory.appendingPathComponent("fake-swiftc")
        let countURL = directory.appendingPathComponent("compile-count")
        try """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {}
        }
        """.write(to: sourceURL, atomically: true, encoding: .utf8)
        try fakeCompiler(countURL: countURL).write(to: compilerURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: compilerURL.path)

        let loader = ConfigLoader(
            sourceURL: sourceURL,
            cacheDirectory: directory.appendingPathComponent("cache", isDirectory: true),
            swiftcURL: compilerURL
        )
        let first = try loader.compileIfNeeded()
        let second = try loader.compileIfNeeded()

        XCTAssertTrue(first.didCompile)
        XCTAssertFalse(second.didCompile)
        XCTAssertEqual(first.contentHash, second.contentHash)
        XCTAssertEqual(first.libraryURL, second.libraryURL)
        XCTAssertEqual(try String(contentsOf: countURL, encoding: .utf8), "1\n")
    }

    func testLoadConfigReadsJSONFromDynamicLibrarySymbol() throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("ConfigShim.swift")
        let libraryURL = directory.appendingPathComponent("Config.dylib")
        try """
        import Darwin

        @_cdecl("olly_config_json")
        public func olly_config_json() -> UnsafeMutablePointer<CChar>? {
            strdup("{\\"version\\":\\"v1\\"}")
        }
        """.write(to: sourceURL, atomically: true, encoding: .utf8)
        try runSwiftC(arguments: ["-emit-library", "-o", libraryURL.path, sourceURL.path])

        let config = try ConfigLoader().loadConfig(from: libraryURL)

        XCTAssertEqual(config, Config())
    }

    func testLoadCompilesAndLoadsConfigSourceWithSwiftC() throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("Config.swift")
        let modulesURL = Bundle(for: Self.self).bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)
        try """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {}
        }
        """.write(to: sourceURL, atomically: true, encoding: .utf8)

        let loader = ConfigLoader(
            sourceURL: sourceURL,
            cacheDirectory: directory.appendingPathComponent("cache", isDirectory: true),
            moduleSearchPaths: [modulesURL]
        )
        let first = try loader.load()
        let second = try loader.load()

        XCTAssertEqual(first.config, Config())
        XCTAssertEqual(second.config, Config())
        XCTAssertTrue(first.didCompile)
        XCTAssertFalse(second.didCompile)
        XCTAssertEqual(first.libraryURL, second.libraryURL)
    }

    func testExampleConfigCompilesAndLoads() throws {
        let packageRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let modulesURL = Bundle(for: Self.self).bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)
        let loader = ConfigLoader(
            sourceURL: packageRoot.appendingPathComponent("examples/Config.swift"),
            cacheDirectory: try temporaryDirectory().appendingPathComponent("cache", isDirectory: true),
            moduleSearchPaths: [modulesURL]
        )

        let loaded = try loader.load()

        XCTAssertEqual(loaded.config.workspaces.tags.count, 8)
        XCTAssertEqual(loaded.config.engines.engines.count, 5)
        XCTAssertGreaterThanOrEqual(loaded.config.keybinds.bindings.count, 30)
        XCTAssertGreaterThanOrEqual(loaded.config.rules.rules.count, 10)
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyDSLTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func fakeCompiler(countURL: URL) -> String {
        """
        #!/bin/sh
        out=
        while [ "$#" -gt 0 ]; do
          if [ "$1" = "-o" ]; then
            shift
            out="$1"
          fi
          shift
        done
        mkdir -p "$(dirname "$out")"
        printf 'fake dylib' > "$out"
        if [ -f "\(countURL.path)" ]; then
          count=$(cat "\(countURL.path)")
        else
          count=0
        fi
        expr "$count" + 1 > "\(countURL.path)"
        """
    }

    private func runSwiftC(arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/swiftc")
        process.arguments = arguments
        try process.run()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
    }
}
