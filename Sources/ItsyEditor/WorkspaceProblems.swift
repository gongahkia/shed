import Foundation

public enum WorkspaceProblemSeverity: String, Codable, Equatable, Sendable {
	case error
	case warning
	case info
	case hint
}

public struct WorkspaceProblemRelatedInformation: Codable, Equatable, Sendable {
	public var path: String
	public var line: Int
	public var column: Int?
	public var message: String

	public init(path: String, line: Int, column: Int? = nil, message: String) {
		self.path = path
		self.line = line
		self.column = column
		self.message = message
	}
}

public struct WorkspaceProblem: Codable, Equatable, Sendable {
	public var path: String
	public var line: Int
	public var column: Int?
	public var severity: WorkspaceProblemSeverity
	public var message: String
	public var source: String?
	public var relatedInformation: [WorkspaceProblemRelatedInformation]

	public init(
		path: String,
		line: Int,
		column: Int? = nil,
		severity: WorkspaceProblemSeverity,
		message: String,
		source: String? = nil,
		relatedInformation: [WorkspaceProblemRelatedInformation] = []
	) {
		self.path = path
		self.line = line
		self.column = column
		self.severity = severity
		self.message = message
		self.source = source
		self.relatedInformation = relatedInformation
	}

	private enum CodingKeys: String, CodingKey {
		case path
		case line
		case column
		case severity
		case message
		case source
		case relatedInformation
	}

	public init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		path = try container.decode(String.self, forKey: .path)
		line = try container.decode(Int.self, forKey: .line)
		column = try container.decodeIfPresent(Int.self, forKey: .column)
		severity = try container.decode(WorkspaceProblemSeverity.self, forKey: .severity)
		message = try container.decode(String.self, forKey: .message)
		source = try container.decodeIfPresent(String.self, forKey: .source)
		relatedInformation = try container.decodeIfPresent(
			[WorkspaceProblemRelatedInformation].self,
			forKey: .relatedInformation
		) ?? []
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

public struct WorkspaceProblemMatcher: Equatable, Sendable {
	public var id: String
	public var label: String
	public var pattern: String
	public var fileGroup: Int
	public var lineGroup: Int
	public var columnGroup: Int?
	public var severityGroup: Int?
	public var messageGroup: Int
	public var defaultSeverity: WorkspaceProblemSeverity
	public var source: String?

	public init(
		id: String,
		label: String,
		pattern: String,
		fileGroup: Int = 1,
		lineGroup: Int = 2,
		columnGroup: Int? = 3,
		severityGroup: Int? = nil,
		messageGroup: Int = 4,
		defaultSeverity: WorkspaceProblemSeverity = .error,
		source: String? = nil
	) {
		self.id = id
		self.label = label
		self.pattern = pattern
		self.fileGroup = fileGroup
		self.lineGroup = lineGroup
		self.columnGroup = columnGroup
		self.severityGroup = severityGroup
		self.messageGroup = messageGroup
		self.defaultSeverity = defaultSeverity
		self.source = source
	}
}

public enum WorkspaceProblemMatcherLoaderError: Error, Equatable, Sendable {
	case fileNotFound
	case decodeFailed(String)
	case invalidPattern(String)
}

public enum WorkspaceProblemMatcherLoader {
	public static func load(from url: URL, fileManager: FileManager = .default) throws -> [WorkspaceProblemMatcher] {
		guard fileManager.fileExists(atPath: url.path) else {
			throw WorkspaceProblemMatcherLoaderError.fileNotFound
		}
		do {
			return try WorkspaceProblemMatcherTOMLParser().parse(String(contentsOf: url, encoding: .utf8))
		} catch let error as WorkspaceProblemMatcherLoaderError {
			throw error
		} catch {
			throw WorkspaceProblemMatcherLoaderError.decodeFailed(String(describing: error))
		}
	}
}

public enum WorkspaceProblemMatcherDiscovery {
	public static func discover(root: URL, fileManager: FileManager = .default) -> [WorkspaceProblemMatcher] {
		workspaceMatchers(root: root, fileManager: fileManager) + extensionMatchers(root: root, fileManager: fileManager)
	}

	private static func workspaceMatchers(root: URL, fileManager: FileManager) -> [WorkspaceProblemMatcher] {
		let url = root
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("matchers.toml")
		return (try? WorkspaceProblemMatcherLoader.load(from: url, fileManager: fileManager)) ?? []
	}

	private static func extensionMatchers(root: URL, fileManager: FileManager) -> [WorkspaceProblemMatcher] {
		ExtensionManifestLoader.discover(root: root, fileManager: fileManager).flatMap { manifest in
			ExtensionProblemMatcherMapper.matchers(from: manifest)
		}
	}
}

public enum WorkspaceProblemParser {
	public static func parse(_ text: String, root: URL, matchers: [WorkspaceProblemMatcher] = []) -> WorkspaceProblemSnapshot {
		let problems = text.split(whereSeparator: \.isNewline).compactMap { parseLine($0, matchers: matchers) }
		return WorkspaceProblemSnapshot(root: root, problems: problems)
	}

	private static func parseLine(_ line: Substring, matchers: [WorkspaceProblemMatcher]) -> WorkspaceProblem? {
		let text = String(line)
		for matcher in matchers {
			if let problem = parseMatcherLine(text, matcher: matcher) {
				return problem
			}
		}
		return parseCompilerLine(line)
	}

	private static func parseCompilerLine(_ line: Substring) -> WorkspaceProblem? {
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

	private static func parseMatcherLine(_ line: String, matcher: WorkspaceProblemMatcher) -> WorkspaceProblem? {
		guard let regex = try? NSRegularExpression(pattern: matcher.pattern) else {
			return nil
		}
		let fullRange = NSRange(line.startIndex ..< line.endIndex, in: line)
		guard let match = regex.firstMatch(in: line, range: fullRange) else {
			return nil
		}
		guard let path = capture(matcher.fileGroup, in: line, match: match)?.trimmingCharacters(in: .whitespacesAndNewlines),
		      !path.isEmpty,
		      let lineText = capture(matcher.lineGroup, in: line, match: match)?.trimmingCharacters(in: .whitespacesAndNewlines),
		      let lineNumber = Int(lineText)
		else {
			return nil
		}
		let column = capture(matcher.columnGroup, in: line, match: match)
			.flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
		let severity = capture(matcher.severityGroup, in: line, match: match)
			.flatMap(severity(from:)) ?? matcher.defaultSeverity
		let message = capture(matcher.messageGroup, in: line, match: match)?
			.trimmingCharacters(in: .whitespacesAndNewlines)
		let resolvedMessage = message?.isEmpty == false ? message ?? matcher.label : matcher.label
		return WorkspaceProblem(
			path: path,
			line: lineNumber,
			column: column,
			severity: severity,
			message: resolvedMessage,
			source: matcher.source ?? matcher.id
		)
	}

	private static func capture(_ index: Int?, in line: String, match: NSTextCheckingResult) -> String? {
		guard let index,
		      index > 0,
		      index < match.numberOfRanges
		else {
			return nil
		}
		let range = match.range(at: index)
		guard range.location != NSNotFound, let stringRange = Range(range, in: line) else {
			return nil
		}
		return String(line[stringRange])
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

	fileprivate static func severity(from text: String) -> WorkspaceProblemSeverity? {
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

private struct WorkspaceProblemMatcherTOMLParser {
	private enum Value: Equatable {
		case string(String)
		case int(Int)
	}

	private struct Draft {
		var id: String
		var label: String?
		var pattern: String?
		var fileGroup = 1
		var lineGroup = 2
		var columnGroup: Int? = 3
		var severityGroup: Int?
		var messageGroup = 4
		var defaultSeverity = WorkspaceProblemSeverity.error
		var source: String?
	}

	func parse(_ text: String) throws -> [WorkspaceProblemMatcher] {
		var drafts: [String: Draft] = [:]
		var currentID: String?
		for (offset, rawLine) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let lineNumber = offset + 1
			let line = stripComment(String(rawLine)).trimmingCharacters(in: .whitespaces)
			guard !line.isEmpty else {
				continue
			}
			if line.hasPrefix("["), line.hasSuffix("]") {
				currentID = try sectionID(line: line, lineNumber: lineNumber)
				if let currentID, drafts[currentID] == nil {
					drafts[currentID] = Draft(id: currentID)
				}
				continue
			}
			guard let currentID, let equals = line.firstIndex(of: "=") else {
				throw WorkspaceProblemMatcherLoaderError.decodeFailed("line \(lineNumber): expected [matcher.<id>] and key = value")
			}
			let key = String(line[..<equals]).trimmingCharacters(in: .whitespaces)
			let rawValue = String(line[line.index(after: equals)...]).trimmingCharacters(in: .whitespaces)
			guard let value = parseValue(rawValue) else {
				throw WorkspaceProblemMatcherLoaderError.decodeFailed("line \(lineNumber): invalid value")
			}
			var draft = drafts[currentID] ?? Draft(id: currentID)
			try assign(value, key: key, draft: &draft, lineNumber: lineNumber)
			drafts[currentID] = draft
		}
		return try drafts.keys.sorted().map { id in
			guard let draft = drafts[id], let pattern = draft.pattern else {
				throw WorkspaceProblemMatcherLoaderError.decodeFailed("\(id): pattern is required")
			}
			do {
				_ = try NSRegularExpression(pattern: pattern)
			} catch {
				throw WorkspaceProblemMatcherLoaderError.invalidPattern(pattern)
			}
			return WorkspaceProblemMatcher(
				id: id,
				label: draft.label ?? id,
				pattern: pattern,
				fileGroup: draft.fileGroup,
				lineGroup: draft.lineGroup,
				columnGroup: draft.columnGroup,
				severityGroup: draft.severityGroup,
				messageGroup: draft.messageGroup,
				defaultSeverity: draft.defaultSeverity,
				source: draft.source
			)
		}
	}

	private func sectionID(line: String, lineNumber: Int) throws -> String {
		let parts = line.dropFirst().dropLast().split(separator: ".").map { String($0).trimmingCharacters(in: .whitespaces) }
		let id: String?
		if parts.count == 2, parts[0] == "matcher" {
			id = parts[1]
		} else if parts.count == 1 {
			id = parts[0]
		} else {
			id = nil
		}
		guard let id, !id.isEmpty else {
			throw WorkspaceProblemMatcherLoaderError.decodeFailed("line \(lineNumber): invalid matcher section")
		}
		return id
	}

	private func assign(_ value: Value, key: String, draft: inout Draft, lineNumber: Int) throws {
		switch (key, value) {
		case ("label", let .string(label)):
			draft.label = label
		case ("pattern", let .string(pattern)):
			draft.pattern = pattern
		case ("file_group", let .int(group)), ("file", let .int(group)):
			draft.fileGroup = group
		case ("line_group", let .int(group)), ("line", let .int(group)):
			draft.lineGroup = group
		case ("column_group", let .int(group)), ("column", let .int(group)), ("col", let .int(group)):
			draft.columnGroup = group
		case ("severity_group", let .int(group)), ("severity", let .int(group)):
			draft.severityGroup = group
		case ("message_group", let .int(group)), ("message", let .int(group)):
			draft.messageGroup = group
		case ("severity", let .string(severity)), ("default_severity", let .string(severity)):
			guard let parsed = WorkspaceProblemParser.severity(from: severity) else {
				throw WorkspaceProblemMatcherLoaderError.decodeFailed("line \(lineNumber): invalid severity")
			}
			draft.defaultSeverity = parsed
		case ("source", let .string(source)):
			draft.source = source
		default:
			throw WorkspaceProblemMatcherLoaderError.decodeFailed("line \(lineNumber): unsupported key \(key)")
		}
	}

	private func parseValue(_ raw: String) -> Value? {
		if raw.hasPrefix("\""), raw.hasSuffix("\""), raw.count >= 2 {
			return .string(unescape(String(raw.dropFirst().dropLast())))
		}
		if let value = Int(raw) {
			return .int(value)
		}
		return nil
	}

	private func stripComment(_ line: String) -> String {
		var quoted = false
		var escaped = false
		for index in line.indices {
			let character = line[index]
			if escaped {
				escaped = false
				continue
			}
			if character == "\\" {
				escaped = true
				continue
			}
			if character == "\"" {
				quoted.toggle()
				continue
			}
			if character == "#", !quoted {
				return String(line[..<index])
			}
		}
		return line
	}

	private func unescape(_ value: String) -> String {
		var result = ""
		var escaping = false
		for character in value {
			if escaping {
				switch character {
				case "n":
					result.append("\n")
				case "t":
					result.append("\t")
				default:
					result.append(character)
				}
				escaping = false
				continue
			}
			if character == "\\" {
				escaping = true
			} else {
				result.append(character)
			}
		}
		if escaping {
			result.append("\\")
		}
		return result
	}
}
