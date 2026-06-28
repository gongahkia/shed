import XCTest
@testable import ollyDSL

final class ConfigCompileDiagnosticIntegrationTests: XCTestCase {
    func testAmbiguousRuleMatchFailsAtCompileTime() throws {
        let source = """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {
                Rules {
                    Rule(
                        match: RuleMatch(bundleID: "com.example.App", predicate: role("AXWindow")),
                        apply: RuleApply(engine: .floating)
                    )
                }
            }
        }
        """

        try assertSwiftCFailure(source: source, contains: "ambiguous-rule")
    }

    func testStringRuleEngineFailsAtCompileTime() throws {
        let source = """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {
                Rules {
                    Rule(
                        match: RuleMatch(bundleID: "com.example.App"),
                        apply: RuleApply(engine: "floating")
                    )
                }
            }
        }
        """

        try assertSwiftCFailure(source: source, contains: "unknown-engine-id")
    }

    func testStringSetEngineActionFailsAtCompileTime() throws {
        let source = """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {
                Keybinds {
                    Keybind(KeyChord([.command], .space), do: .setEngine("floating"))
                }
            }
        }
        """

        try assertSwiftCFailure(source: source, contains: "unknown-engine-id")
    }

    private func assertSwiftCFailure(source: String, contains expectedFragment: String) throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("Config.swift")
        let modulesURL = Bundle(for: Self.self).bundleURL
            .deletingLastPathComponent()
            .appendingPathComponent("Modules", isDirectory: true)
        try source.write(to: sourceURL, atomically: true, encoding: .utf8)

        let loader = ConfigLoader(
            sourceURL: sourceURL,
            cacheDirectory: directory.appendingPathComponent("cache", isDirectory: true),
            moduleSearchPaths: [modulesURL]
        )

        XCTAssertThrowsError(try loader.load()) { error in
            guard case let ConfigLoaderError.compileFailed(_, _, output) = error else {
                return XCTFail("expected compileFailed, got \(error)")
            }
            XCTAssertTrue(output.contains(expectedFragment), output)
        }
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyDSLTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
