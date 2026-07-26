import Foundation

public actor WatchStore {
	private struct WorkspaceRecord: Codable, Equatable {
		var url: String
		var expressions: [String]
	}

	private struct WatchFile: Codable, Equatable {
		var workspaces: [WorkspaceRecord]
	}

	private let fileURL: URL
	private let fileManager: FileManager
	private var expressionsByWorkspace: [URL: [String]] = [:]

	public init(fileURL: URL = WatchStore.defaultFileURL, fileManager: FileManager = .default) {
		self.fileURL = fileURL
		self.fileManager = fileManager
	}

	public static var defaultFileURL: URL {
		FileManager.default.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("watches.json")
	}

	public func load() throws {
		guard fileManager.fileExists(atPath: fileURL.path) else {
			expressionsByWorkspace = [:]
			return
		}
		let file = try JSONDecoder().decode(WatchFile.self, from: Data(contentsOf: fileURL))
		var loaded: [URL: [String]] = [:]
		for record in file.workspaces {
			guard let url = URL(string: record.url) else {
				continue
			}
			loaded[canonical(url)] = unique(record.expressions)
		}
		expressionsByWorkspace = loaded
	}

	public func save() throws {
		let records = expressionsByWorkspace
			.filter { !$0.value.isEmpty }
			.map { url, expressions in
				WorkspaceRecord(url: url.absoluteString, expressions: unique(expressions))
			}
			.sorted { $0.url < $1.url }
		let file = WatchFile(workspaces: records)
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		let data = try encoder.encode(file)
		try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
		try data.write(to: fileURL, options: .atomic)
	}

	public func expressions(for workspaceRoot: URL) -> [String] {
		expressionsByWorkspace[canonical(workspaceRoot), default: []]
	}

	public func replace(_ expressions: [String], for workspaceRoot: URL) {
		let key = canonical(workspaceRoot)
		let next = unique(expressions)
		if next.isEmpty {
			expressionsByWorkspace.removeValue(forKey: key)
		} else {
			expressionsByWorkspace[key] = next
		}
	}

	@discardableResult
	public func add(_ expression: String, for workspaceRoot: URL) -> Bool {
		let trimmed = expression.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmed.isEmpty else {
			return false
		}
		let key = canonical(workspaceRoot)
		var expressions = expressionsByWorkspace[key, default: []]
		guard !expressions.contains(trimmed) else {
			return false
		}
		expressions.append(trimmed)
		expressionsByWorkspace[key] = expressions
		return true
	}

	public func remove(_ expression: String, for workspaceRoot: URL) {
		let key = canonical(workspaceRoot)
		let next = expressionsByWorkspace[key, default: []].filter { $0 != expression }
		replace(next, for: key)
	}

	private func canonical(_ url: URL) -> URL {
		url.standardizedFileURL
	}
}

private func unique(_ expressions: [String]) -> [String] {
	var seen: Set<String> = []
	var result: [String] = []
	for expression in expressions.map({ $0.trimmingCharacters(in: .whitespacesAndNewlines) }) where !expression.isEmpty {
		if seen.insert(expression).inserted {
			result.append(expression)
		}
	}
	return result
}
