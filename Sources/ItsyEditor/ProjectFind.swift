import Dispatch
import Foundation

public struct ProjectFindOptions: Sendable, Equatable {
	public var query: String
	public var matchCase: Bool
	public var maxFileBytes: Int

	public init(query: String, matchCase: Bool = false, maxFileBytes: Int = 1_000_000) {
		self.query = query
		self.matchCase = matchCase
		self.maxFileBytes = maxFileBytes
	}
}

public struct ProjectFindMatch: Sendable, Equatable {
	public var url: URL
	public var relativePath: String
	public var line: Int
	public var column: Int
	public var lineText: String

	public init(url: URL, relativePath: String, line: Int, column: Int, lineText: String) {
		self.url = url
		self.relativePath = relativePath
		self.line = line
		self.column = column
		self.lineText = lineText
	}
}

public enum ProjectFind {
	public static func search(root: URL, options: ProjectFindOptions, fileManager: FileManager = .default) -> [ProjectFindMatch] {
		guard !options.query.isEmpty else {
			return []
		}
		let matcher = GitIgnoreMatcher(root: root, fileManager: fileManager)
		let files = searchableFiles(root: root, matcher: matcher, maxFileBytes: options.maxFileBytes, fileManager: fileManager)
		let collector = ProjectFindMatchCollector()
		DispatchQueue.concurrentPerform(iterations: files.count) { index in
			let fileMatches = matchesInFile(files[index], root: root, options: options)
			guard !fileMatches.isEmpty else {
				return
			}
			collector.append(contentsOf: fileMatches)
		}
		return collector.snapshot().sorted { lhs, rhs in
			if lhs.relativePath != rhs.relativePath {
				return lhs.relativePath.localizedStandardCompare(rhs.relativePath) == .orderedAscending
			}
			if lhs.line != rhs.line {
				return lhs.line < rhs.line
			}
			return lhs.column < rhs.column
		}
	}

	static func searchableFiles(root: URL, matcher: GitIgnoreMatcher, maxFileBytes: Int, fileManager: FileManager = .default) -> [URL] {
		let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]
		var stack = [root]
		var files: [URL] = []
		while let directory = stack.popLast() {
			let contents = (try? fileManager.contentsOfDirectory(
				at: directory,
				includingPropertiesForKeys: Array(keys),
				options: [.skipsPackageDescendants]
			)) ?? []
			for child in contents {
				let relativePath = Self.relativePath(for: child, root: root)
				if child.lastPathComponent == ".git" {
					continue
				}
				let values = try? child.resourceValues(forKeys: keys)
				if values?.isSymbolicLink == true {
					continue
				}
				if values?.isDirectory == true {
					if !matcher.ignores(relativePath: relativePath, isDirectory: true) {
						stack.append(child)
					}
					continue
				}
				guard values?.isRegularFile == true, (values?.fileSize ?? 0) <= maxFileBytes else {
					continue
				}
				if !matcher.ignores(relativePath: relativePath, isDirectory: false) {
					files.append(child)
				}
			}
		}
		return files.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
	}

	private static func matchesInFile(_ url: URL, root: URL, options: ProjectFindOptions) -> [ProjectFindMatch] {
		guard
			let data = try? Data(contentsOf: url, options: .mappedIfSafe),
			!data.contains(0),
			let text = String(data: data, encoding: .utf8)
		else {
			return []
		}
		let compareOptions: String.CompareOptions = options.matchCase ? [] : [.caseInsensitive]
		let relativePath = relativePath(for: url, root: root)
		var matches: [ProjectFindMatch] = []
		for (lineIndex, lineSlice) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			var line = String(lineSlice)
			if line.last == "\r" {
				line.removeLast()
			}
			var searchStart = line.startIndex
			while searchStart < line.endIndex, let range = line.range(of: options.query, options: compareOptions, range: searchStart ..< line.endIndex) {
				let column = line.distance(from: line.startIndex, to: range.lowerBound) + 1
				matches.append(ProjectFindMatch(
					url: url,
					relativePath: relativePath,
					line: lineIndex + 1,
					column: column,
					lineText: line
				))
				searchStart = range.upperBound
			}
		}
		return matches
	}

	static func relativePath(for url: URL, root: URL) -> String {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		guard path.hasPrefix(prefix) else {
			return url.lastPathComponent
		}
		return String(path.dropFirst(prefix.count))
	}
}

private final class ProjectFindMatchCollector: @unchecked Sendable {
	private let lock = NSLock()
	private var matches: [ProjectFindMatch] = []

	func append(contentsOf newMatches: [ProjectFindMatch]) {
		lock.lock()
		matches.append(contentsOf: newMatches)
		lock.unlock()
	}

	func snapshot() -> [ProjectFindMatch] {
		lock.lock()
		defer { lock.unlock() }
		return matches
	}
}

public struct GitIgnoreMatcher: Sendable {
	private let patterns: [GitIgnorePattern]

	public init(root: URL, fileManager: FileManager = .default) {
		let url = root.appendingPathComponent(".gitignore")
		let contents = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
		self.init(patterns: contents.split(separator: "\n", omittingEmptySubsequences: false).map(String.init))
	}

	public init(patterns: [String]) {
		self.patterns = patterns.compactMap(GitIgnorePattern.init(line:))
	}

	public func ignores(relativePath: String, isDirectory: Bool) -> Bool {
		var ignored = false
		for pattern in patterns where pattern.matches(relativePath: relativePath, isDirectory: isDirectory) {
			ignored = !pattern.negated
		}
		return ignored
	}
}

private struct GitIgnorePattern: Sendable {
	let negated: Bool
	let directoryOnly: Bool
	let anchored: Bool
	let hasSlash: Bool
	let regex: NSRegularExpression

	init?(line: String) {
		var pattern = line.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !pattern.isEmpty, !pattern.hasPrefix("#") else {
			return nil
		}
		negated = pattern.hasPrefix("!")
		if negated {
			pattern.removeFirst()
		}
		anchored = pattern.hasPrefix("/")
		if anchored {
			pattern.removeFirst()
		}
		directoryOnly = pattern.hasSuffix("/")
		if directoryOnly {
			pattern.removeLast()
		}
		guard !pattern.isEmpty else {
			return nil
		}
		hasSlash = pattern.contains("/")
		guard let regex = GitIgnorePattern.makeRegex(for: pattern) else {
			return nil
		}
		self.regex = regex
	}

	func matches(relativePath: String, isDirectory: Bool) -> Bool {
		let path = relativePath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
		guard !path.isEmpty else {
			return false
		}
		let components = path.split(separator: "/").map(String.init)
		if directoryOnly {
			let directoryPaths = directoryPrefixes(for: components, isDirectory: isDirectory)
			if anchored || hasSlash {
				return directoryPaths.contains(where: matchesValue(_:))
			}
			return directoryPaths.flatMap { $0.split(separator: "/").map(String.init) }.contains(where: matchesValue(_:))
		}
		if anchored || hasSlash {
			return matchesValue(path)
		}
		return components.contains(where: matchesValue(_:))
	}

	private func directoryPrefixes(for components: [String], isDirectory: Bool) -> [String] {
		let directoryCount = isDirectory ? components.count : max(components.count - 1, 0)
		guard directoryCount > 0 else {
			return []
		}
		return (1 ... directoryCount).map { components.prefix($0).joined(separator: "/") }
	}

	private func matchesValue(_ value: String) -> Bool {
		let range = NSRange(value.startIndex ..< value.endIndex, in: value)
		return regex.firstMatch(in: value, range: range) != nil
	}

	private static func makeRegex(for glob: String) -> NSRegularExpression? {
		try? NSRegularExpression(pattern: "^" + regexPattern(for: glob) + "$")
	}

	private static func regexPattern(for glob: String) -> String {
		var result = ""
		var index = glob.startIndex
		while index < glob.endIndex {
			let character = glob[index]
			if character == "*" {
				let next = glob.index(after: index)
				if next < glob.endIndex, glob[next] == "*" {
					result += ".*"
					index = glob.index(after: next)
				} else {
					result += "[^/]*"
					index = next
				}
			} else if character == "?" {
				result += "[^/]"
				index = glob.index(after: index)
			} else {
				result += NSRegularExpression.escapedPattern(for: String(character))
				index = glob.index(after: index)
			}
		}
		return result
	}
}
