import Foundation

public struct DSLMigrationPrompt: Equatable, Sendable {
    public let detectedVersion: DSLVersion
    public let targetVersion: DSLVersion
    public let message: String
    public let diffSuggestion: String?

    public init(
        detectedVersion: DSLVersion,
        targetVersion: DSLVersion = .current,
        diffSuggestion: String? = nil
    ) {
        self.detectedVersion = detectedVersion
        self.targetVersion = targetVersion
        self.message = "Config uses DSL \(detectedVersion.rawValue); migrate to \(targetVersion.rawValue)."
        self.diffSuggestion = diffSuggestion
    }
}

public struct DSLMigrationSuggestion: Equatable, Sendable {
    public let sourcePath: String
    public let targetVersion: DSLVersion
    public let diff: String

    public var isEmpty: Bool {
        diff.isEmpty
    }

    public init(sourcePath: String, targetVersion: DSLVersion = .current, diff: String) {
        self.sourcePath = sourcePath
        self.targetVersion = targetVersion
        self.diff = diff
    }
}

public enum DSLVersionMigrator {
    public static func prompt(
        for version: DSLVersion,
        source: String? = nil,
        sourcePath: String = "Config.swift"
    ) -> DSLMigrationPrompt? {
        guard version != .current else {
            return nil
        }
        return DSLMigrationPrompt(
            detectedVersion: version,
            diffSuggestion: source.map { suggestion(for: $0, sourcePath: sourcePath).diff }
        )
    }

    public static func suggestion(
        for source: String,
        sourcePath: String = "Config.swift",
        targetVersion: DSLVersion = .current
    ) -> DSLMigrationSuggestion {
        let lines = source.components(separatedBy: .newlines)
        guard let index = lines.firstIndex(where: lineNeedsVersionMigration) else {
            return DSLMigrationSuggestion(sourcePath: sourcePath, targetVersion: targetVersion, diff: "")
        }

        let original = lines[index]
        let replacement = migratedLine(original, targetVersion: targetVersion)
        guard replacement != original else {
            return DSLMigrationSuggestion(sourcePath: sourcePath, targetVersion: targetVersion, diff: "")
        }

        let diff = """
        --- a/\(sourcePath)
        +++ b/\(sourcePath)
        @@
        -\(original)
        +\(replacement)
        """
        return DSLMigrationSuggestion(sourcePath: sourcePath, targetVersion: targetVersion, diff: diff)
    }

    private static func lineNeedsVersionMigration(_ line: String) -> Bool {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        return line.contains("Config(version:")
            || trimmed.hasPrefix("Config {")
            || line.contains("= Config {")
            || line.contains("return Config {")
            || trimmed == "Config()"
            || line.contains("= Config()")
            || line.contains("return Config()")
    }

    private static func migratedLine(_ line: String, targetVersion: DSLVersion) -> String {
        if line.contains("Config(version:") {
            return line.replacingOccurrences(
                of: #"version:\s*\.[A-Za-z0-9_]+"#,
                with: "version: .\(targetVersion.rawValue)",
                options: .regularExpression
            )
        }
        if line.contains("Config {") {
            return line.replacingOccurrences(of: "Config {", with: "Config(version: .\(targetVersion.rawValue)) {")
        }
        if line.contains("Config()") {
            return line.replacingOccurrences(of: "Config()", with: "Config(version: .\(targetVersion.rawValue))")
        }
        return line
    }
}
