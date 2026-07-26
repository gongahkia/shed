import Foundation

public struct DiffFile: Equatable, Sendable {
	public var oldPath: String?
	public var newPath: String?
	public var indexLine: String?
	public var oldMode: String?
	public var newMode: String?
	public var isBinary: Bool
	public var isNewFile: Bool
	public var isDeletedFile: Bool
	public var hunks: [DiffHunk]

	public init(
		oldPath: String?,
		newPath: String?,
		indexLine: String? = nil,
		oldMode: String? = nil,
		newMode: String? = nil,
		isBinary: Bool = false,
		isNewFile: Bool = false,
		isDeletedFile: Bool = false,
		hunks: [DiffHunk] = []
	) {
		self.oldPath = oldPath
		self.newPath = newPath
		self.indexLine = indexLine
		self.oldMode = oldMode
		self.newMode = newMode
		self.isBinary = isBinary
		self.isNewFile = isNewFile
		self.isDeletedFile = isDeletedFile
		self.hunks = hunks
	}
}

public struct DiffHunk: Equatable, Sendable {
	public var oldStart: Int
	public var oldCount: Int
	public var newStart: Int
	public var newCount: Int
	public var lines: [DiffLine]
	public var noNewlineLineIndexes: Set<Int>

	public init(
		oldStart: Int,
		oldCount: Int,
		newStart: Int,
		newCount: Int,
		lines: [DiffLine] = [],
		noNewlineLineIndexes: Set<Int> = []
	) {
		self.oldStart = oldStart
		self.oldCount = oldCount
		self.newStart = newStart
		self.newCount = newCount
		self.lines = lines
		self.noNewlineLineIndexes = noNewlineLineIndexes
	}
}

public enum DiffLine: Equatable, Sendable {
	case context(String)
	case add(String)
	case remove(String)
}

public enum UnifiedDiffParseError: Error, Equatable, Sendable {
	case malformedDiffHeader(String)
	case malformedHunkHeader(String)
	case hunkOutsideFile(String)
}

public enum UnifiedDiffParser {
	public static func parse(_ text: String) throws -> [DiffFile] {
		var files: [DiffFile] = []
		var currentFile: DiffFile?
		var currentHunk: DiffHunk?

		func finishHunk() {
			if let hunk = currentHunk {
				currentFile?.hunks.append(hunk)
				currentHunk = nil
			}
		}

		func finishFile() {
			finishHunk()
			if let file = currentFile {
				files.append(file)
				currentFile = nil
			}
		}

		for line in text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
			if line.hasPrefix("diff --git ") {
				finishFile()
				currentFile = try file(fromDiffHeader: line)
			} else if line.hasPrefix("@@ ") {
				guard currentFile != nil else {
					throw UnifiedDiffParseError.hunkOutsideFile(line)
				}
				finishHunk()
				currentHunk = try hunk(fromHeader: line)
			} else if currentHunk != nil {
				appendHunkLine(line, to: &currentHunk)
			} else if currentFile != nil {
				applyExtendedHeader(line, to: &currentFile)
			}
		}
		finishFile()
		return files
	}

	private static func file(fromDiffHeader line: String) throws -> DiffFile {
		let rest = String(line.dropFirst("diff --git ".count))
		let paths = splitPathTokens(rest)
		guard paths.count >= 2 else {
			throw UnifiedDiffParseError.malformedDiffHeader(line)
		}
		return DiffFile(oldPath: normalizedDiffPath(paths[0], expectedPrefix: "a/"), newPath: normalizedDiffPath(paths[1], expectedPrefix: "b/"))
	}

	private static func hunk(fromHeader line: String) throws -> DiffHunk {
		let parts = line.split(separator: " ").map(String.init)
		guard
			parts.count >= 4,
			parts[0] == "@@",
			parts[3] == "@@",
			parts[1].hasPrefix("-"),
			parts[2].hasPrefix("+"),
			let oldRange = parseRange(parts[1]),
			let newRange = parseRange(parts[2])
		else {
			throw UnifiedDiffParseError.malformedHunkHeader(line)
		}
		return DiffHunk(oldStart: oldRange.start, oldCount: oldRange.count, newStart: newRange.start, newCount: newRange.count)
	}

	private static func parseRange(_ token: String) -> (start: Int, count: Int)? {
		let values = token.dropFirst().split(separator: ",", maxSplits: 1).map(String.init)
		guard let start = Int(values[0]) else {
			return nil
		}
		let count = values.count > 1 ? Int(values[1]) : 1
		guard let count else {
			return nil
		}
		return (start, count)
	}

	private static func appendHunkLine(_ line: String, to hunk: inout DiffHunk?) {
		if line == "\\ No newline at end of file" {
			if let index = hunk?.lines.indices.last {
				hunk?.noNewlineLineIndexes.insert(index)
			}
			return
		}
		guard !line.isEmpty, let marker = line.first else {
			return
		}
		let content = String(line.dropFirst())
		switch marker {
		case " ":
			hunk?.lines.append(.context(content))
		case "+":
			hunk?.lines.append(.add(content))
		case "-":
			hunk?.lines.append(.remove(content))
		default:
			break
		}
	}

	private static func applyExtendedHeader(_ line: String, to file: inout DiffFile?) {
		if line.hasPrefix("index ") {
			file?.indexLine = line
		} else if line.hasPrefix("new file mode ") {
			file?.isNewFile = true
			file?.newMode = String(line.dropFirst("new file mode ".count))
		} else if line.hasPrefix("deleted file mode ") {
			file?.isDeletedFile = true
			file?.oldMode = String(line.dropFirst("deleted file mode ".count))
		} else if line.hasPrefix("old mode ") {
			file?.oldMode = String(line.dropFirst("old mode ".count))
		} else if line.hasPrefix("new mode ") {
			file?.newMode = String(line.dropFirst("new mode ".count))
		} else if line.hasPrefix("rename from ") {
			file?.oldPath = String(line.dropFirst("rename from ".count))
		} else if line.hasPrefix("rename to ") {
			file?.newPath = String(line.dropFirst("rename to ".count))
		} else if line == "GIT binary patch" || line.hasPrefix("Binary files ") {
			file?.isBinary = true
		} else if line.hasPrefix("--- ") {
			file?.oldPath = pathHeaderValue(line)
		} else if line.hasPrefix("+++ ") {
			file?.newPath = pathHeaderValue(line)
		}
	}

	private static func pathHeaderValue(_ line: String) -> String? {
		let value = String(line.dropFirst(4)).split(separator: "\t", maxSplits: 1).first.map(String.init) ?? ""
		if value == "/dev/null" {
			return nil
		}
		return normalizedDiffPath(value, expectedPrefix: value.hasPrefix("a/") ? "a/" : "b/")
	}

	private static func normalizedDiffPath(_ path: String, expectedPrefix: String) -> String {
		path.hasPrefix(expectedPrefix) ? String(path.dropFirst(expectedPrefix.count)) : path
	}

	private static func splitPathTokens(_ text: String) -> [String] {
		var tokens: [String] = []
		var current = ""
		var inQuotes = false
		var escaping = false
		for character in text {
			if escaping {
				current.append(character)
				escaping = false
			} else if character == "\\" && inQuotes {
				escaping = true
			} else if character == "\"" {
				inQuotes.toggle()
			} else if character == " " && !inQuotes {
				if !current.isEmpty {
					tokens.append(current)
					current = ""
				}
			} else {
				current.append(character)
			}
		}
		if !current.isEmpty {
			tokens.append(current)
		}
		return tokens
	}
}
