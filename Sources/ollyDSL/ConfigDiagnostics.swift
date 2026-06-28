import Foundation

/// Purpose: Names stable DSL compile-time diagnostic categories.
/// Parameters: Use the case matching the reported config error.
/// Example: `ConfigDiagnosticID.ambiguousRule`
/// See also: `ConfigCompileDiagnostic`, `ConfigDiagnosticFormatter`.
public enum ConfigDiagnosticID: String, CaseIterable, Codable, Equatable, Sendable {
    case duplicateChord = "duplicate-chord"
    case duplicateTagName = "duplicate-tag-name"
    case unknownEngineID = "unknown-engine-id"
    case ambiguousRule = "ambiguous-rule"
}

/// Purpose: Represents one Swift config compile diagnostic with source context.
/// Parameters: Provide line, column, message, source line, and suggestions.
/// Example: `ConfigCompileDiagnostic(line: 3, column: 9, message: "cannot find x")`
/// See also: `ConfigDiagnosticFormatter`, `ConfigLoaderError`.
public struct ConfigCompileDiagnostic: Equatable, Sendable {
    public let diagnosticID: ConfigDiagnosticID?
    public let line: Int
    public let column: Int
    public let message: String
    public let sourceLine: String?
    public let suggestions: [String]

    public init(
        line: Int,
        column: Int,
        message: String,
        diagnosticID: ConfigDiagnosticID? = nil,
        sourceLine: String? = nil,
        suggestions: [String] = []
    ) {
        self.diagnosticID = diagnosticID
        self.line = line
        self.column = column
        self.message = message
        self.sourceLine = sourceLine
        self.suggestions = suggestions
    }

    public var markerLine: String? {
        guard column > 0 else {
            return nil
        }
        return String(repeating: " ", count: column - 1) + "^"
    }
}

/// Purpose: Parses compiler output into user-facing config diagnostics.
/// Parameters: Pass raw `swiftc` output and the source text being compiled.
/// Example: `ConfigDiagnosticFormatter.render(compilerOutput: output, source: source)`
/// See also: `ConfigCompileDiagnostic`, `ConfigLoader`.
public enum ConfigDiagnosticFormatter {
    public static func diagnostics(from compilerOutput: String, source: String) -> [ConfigCompileDiagnostic] {
        let lines = source.components(separatedBy: .newlines)
        return compilerOutput.components(separatedBy: .newlines).compactMap { line in
            parseDiagnostic(line, sourceLines: lines)
        }
    }

    public static func render(compilerOutput: String, source: String) -> String {
        let diagnostics = diagnostics(from: compilerOutput, source: source)
        guard !diagnostics.isEmpty else {
            return compilerOutput
        }
        return diagnostics.map(render).joined(separator: "\n\n")
    }

    private static func parseDiagnostic(_ line: String, sourceLines: [String]) -> ConfigCompileDiagnostic? {
        let pattern = #"^.*:(\d+):(\d+): error: (.*)$"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: line, range: NSRange(line.startIndex..<line.endIndex, in: line)),
              let lineRange = Range(match.range(at: 1), in: line),
              let columnRange = Range(match.range(at: 2), in: line),
              let messageRange = Range(match.range(at: 3), in: line),
              let lineNumber = Int(line[lineRange]),
              let column = Int(line[columnRange]) else {
            return nil
        }
        let sourceLine = sourceLines.indices.contains(lineNumber - 1) ? sourceLines[lineNumber - 1] : nil
        let message = String(line[messageRange])
        return ConfigCompileDiagnostic(
            line: lineNumber,
            column: column,
            message: message,
            diagnosticID: diagnosticID(for: message),
            sourceLine: sourceLine,
            suggestions: suggestions(for: message)
        )
    }

    private static func render(_ diagnostic: ConfigCompileDiagnostic) -> String {
        var lines = ["line \(diagnostic.line), column \(diagnostic.column): \(diagnostic.message)"]
        if let sourceLine = diagnostic.sourceLine {
            lines.append(sourceLine)
        }
        if let markerLine = diagnostic.markerLine {
            lines.append(markerLine)
        }
        lines.append(contentsOf: diagnostic.suggestions.map { "fix: \($0)" })
        return lines.joined(separator: "\n")
    }

    private static func suggestions(for message: String) -> [String] {
        if let diagnosticID = diagnosticID(for: message) {
            return suggestions(for: diagnosticID)
        }
        let lowercased = message.lowercased()
        if lowercased.contains("cannot find") {
            return ["check the symbol name and required imports such as ollyDSL, ollyCore, or CoreGraphics"]
        }
        if lowercased.contains("missing argument label") {
            return ["check the argument labels against docs/dsl-reference.md"]
        }
        if lowercased.contains("cannot convert value") {
            return ["check the expected DSL type for this argument"]
        }
        if lowercased.contains("extra argument") {
            return ["remove unsupported arguments or use the matching DSL builder helper"]
        }
        if lowercased.contains("ambiguous use") {
            return ["add an explicit type or use the concrete DSL constructor"]
        }
        return ["open docs/dsl-reference.md and compare the nearest DSL primitive"]
    }

    private static func diagnosticID(for message: String) -> ConfigDiagnosticID? {
        ConfigDiagnosticID.allCases.first { message.contains($0.rawValue) }
    }

    private static func suggestions(for diagnosticID: ConfigDiagnosticID) -> [String] {
        switch diagnosticID {
        case .duplicateChord:
            return ["remove one keybind or choose a distinct KeyChord"]
        case .duplicateTagName:
            return ["rename one Tag.named declaration so every workspace tag is unique"]
        case .unknownEngineID:
            return ["use a built-in typed engine such as .bsp or an explicit LayoutEngineID(rawValue:)"]
        case .ambiguousRule:
            return ["use RuleMatch fields or a RulePredicate expression, not both in the same rule"]
        }
    }
}
