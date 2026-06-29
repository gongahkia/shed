import Foundation

public enum WorkspaceProblemSeverity: String, Codable, Equatable, Sendable {
	case error
	case warning
	case info
	case hint
}

public struct WorkspaceProblem: Codable, Equatable, Sendable {
	public var path: String
	public var line: Int
	public var column: Int?
	public var severity: WorkspaceProblemSeverity
	public var message: String
	public var source: String?

	public init(path: String, line: Int, column: Int? = nil, severity: WorkspaceProblemSeverity, message: String, source: String? = nil) {
		self.path = path
		self.line = line
		self.column = column
		self.severity = severity
		self.message = message
		self.source = source
	}
}

public struct WorkspaceProblemSnapshot: Equatable, Sendable {
	public var root: URL
	public var problems: [WorkspaceProblem]

	public init(root: URL, problems: [WorkspaceProblem]) {
		self.root = root.standardizedFileURL
		self.problems = problems.sorted {
			if $0.path != $1.path {
				return $0.path.localizedStandardCompare($1.path) == .orderedAscending
			}
			if $0.line != $1.line {
				return $0.line < $1.line
			}
			return ($0.column ?? 0) < ($1.column ?? 0)
		}
	}

	public var errorCount: Int {
		problems.filter { $0.severity == .error }.count
	}

	public var warningCount: Int {
		problems.filter { $0.severity == .warning }.count
	}

	public func url(for problem: WorkspaceProblem) -> URL {
		root.appendingPathComponent(problem.path)
	}
}

public enum WorkspaceProblemParser {
	public static func parse(_ text: String, root: URL) -> WorkspaceProblemSnapshot {
		let problems = text.split(whereSeparator: \.isNewline).compactMap(parseLine(_:))
		return WorkspaceProblemSnapshot(root: root, problems: problems)
	}

	private static func parseLine(_ line: Substring) -> WorkspaceProblem? {
		let parts = line.split(separator: ":", maxSplits: 4, omittingEmptySubsequences: false).map(String.init)
		guard parts.count >= 4,
		      let lineNumber = Int(parts[1].trimmingCharacters(in: .whitespaces))
		else {
			return nil
		}
		if let column = Int(parts[2].trimmingCharacters(in: .whitespaces)), parts.count >= 5 {
			return problem(path: parts[0], line: lineNumber, column: column, severityText: parts[3], message: parts[4])
		}
		return problem(path: parts[0], line: lineNumber, column: nil, severityText: parts[2], message: parts[3])
	}

	private static func problem(path: String, line: Int, column: Int?, severityText: String, message: String) -> WorkspaceProblem? {
		guard let severity = severity(from: severityText) else {
			return nil
		}
		return WorkspaceProblem(
			path: path.trimmingCharacters(in: .whitespaces),
			line: line,
			column: column,
			severity: severity,
			message: message.trimmingCharacters(in: .whitespaces),
			source: "task"
		)
	}

	private static func severity(from text: String) -> WorkspaceProblemSeverity? {
		switch text.trimmingCharacters(in: .whitespaces).lowercased() {
		case "error", "fatal error":
			return .error
		case "warning":
			return .warning
		case "note", "info", "information":
			return .info
		case "hint":
			return .hint
		default:
			return nil
		}
	}
}
