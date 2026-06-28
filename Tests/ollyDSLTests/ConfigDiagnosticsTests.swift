import XCTest
@testable import ollyDSL

final class ConfigDiagnosticsTests: XCTestCase {
    func testDiagnosticFormatterParsesLineColumnAndSourceMarker() {
        let source = """
        import ollyDSL
        public func ollyConfig() -> Config {
            Config { MissingThing() }
        }
        """
        let output = "/tmp/Config.swift:3:14: error: cannot find 'MissingThing' in scope"

        let diagnostics = ConfigDiagnosticFormatter.diagnostics(from: output, source: source)

        XCTAssertEqual(diagnostics.count, 1)
        XCTAssertEqual(diagnostics[0].line, 3)
        XCTAssertEqual(diagnostics[0].column, 14)
        XCTAssertEqual(diagnostics[0].sourceLine, "    Config { MissingThing() }")
        XCTAssertEqual(diagnostics[0].markerLine, "             ^")
        XCTAssertTrue(diagnostics[0].suggestions[0].contains("symbol name"))
    }

    func testDiagnosticFormatterFallsBackToRawOutput() {
        let output = "non-standard compiler output"

        XCTAssertEqual(ConfigDiagnosticFormatter.render(compilerOutput: output, source: ""), output)
    }

    func testDiagnosticFormatterRecognizesCatalogIDs() {
        let source = """
        import ollyDSL
        public func ollyConfig() -> Config {
            Config {}
        }
        """
        let output = "/tmp/Config.swift:3:12: error: ambiguous-rule: use RuleMatch fields or a RulePredicate, not both"

        let diagnostic = ConfigDiagnosticFormatter.diagnostics(from: output, source: source).first

        XCTAssertEqual(diagnostic?.diagnosticID, .ambiguousRule)
        XCTAssertEqual(diagnostic?.suggestions, ["use RuleMatch fields or a RulePredicate expression, not both in the same rule"])
    }
}
