import XCTest
@testable import ollyDSL

final class ConfigMigrationTests: XCTestCase {
    func testUnsupportedVersionDecodesAndPromptsForMigration() throws {
        let config = try JSONDecoder().decode(Config.self, from: Data(#"{"version":"v0"}"#.utf8))
        let prompt = try XCTUnwrap(DSLVersionMigrator.prompt(for: config.version))

        XCTAssertEqual(config.version, .unsupported("v0"))
        XCTAssertEqual(prompt.detectedVersion, .unsupported("v0"))
        XCTAssertEqual(prompt.targetVersion, .v1)
        XCTAssertTrue(prompt.message.contains("v0"))
    }

    func testMigrationSuggestionAddsCurrentVersionToConfigBuilder() {
        let source = """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {
                Keybinds()
            }
        }
        """

        let suggestion = DSLVersionMigrator.suggestion(for: source, sourcePath: "Config.swift")

        XCTAssertTrue(suggestion.diff.contains("-    Config {"))
        XCTAssertTrue(suggestion.diff.contains("+    Config(version: .v1) {"))
    }

    func testMigrationSuggestionUpdatesExistingVersionArgument() {
        let source = "let config = Config(version: .v0) { Keybinds() }"

        let suggestion = DSLVersionMigrator.suggestion(for: source, sourcePath: "Config.swift")

        XCTAssertTrue(suggestion.diff.contains("-let config = Config(version: .v0) { Keybinds() }"))
        XCTAssertTrue(suggestion.diff.contains("+let config = Config(version: .v1) { Keybinds() }"))
    }
}
