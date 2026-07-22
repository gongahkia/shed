import Foundation

public enum WorkspaceSymbolKind: String, Codable, Equatable, Sendable {
	case function
	case method
	case type
	case variable
}

public enum WorkspaceSymbolSource: String, Codable, Equatable, Sendable {
	case local
	case languageServer
}

public struct WorkspaceSymbol: Codable, Equatable, Sendable {
	public var name: String
	public var kind: WorkspaceSymbolKind
	public var relativePath: String
	public var line: Int
	public var column: Int
	public var endLine: Int?
	public var endColumn: Int?
	public var signature: String?
	public var containerName: String?
	public var documentation: String?
	public var source: WorkspaceSymbolSource

	public init(
		name: String,
		kind: WorkspaceSymbolKind,
		relativePath: String,
		line: Int,
		column: Int,
		endLine: Int? = nil,
		endColumn: Int? = nil,
		signature: String? = nil,
		containerName: String? = nil,
		documentation: String? = nil,
		source: WorkspaceSymbolSource = .local
	) {
		self.name = name
		self.kind = kind
		self.relativePath = relativePath
		self.line = line
		self.column = column
		self.endLine = endLine
		self.endColumn = endColumn
		self.signature = signature
		self.containerName = containerName
		self.documentation = documentation
		self.source = source
	}

	private enum CodingKeys: String, CodingKey {
		case name, kind, relativePath, line, column, endLine, endColumn, signature, containerName, documentation, source
	}

	public init(from decoder: Decoder) throws {
		let values = try decoder.container(keyedBy: CodingKeys.self)
		name = try values.decode(String.self, forKey: .name)
		kind = try values.decode(WorkspaceSymbolKind.self, forKey: .kind)
		relativePath = try values.decode(String.self, forKey: .relativePath)
		line = try values.decode(Int.self, forKey: .line)
		column = try values.decode(Int.self, forKey: .column)
		endLine = try values.decodeIfPresent(Int.self, forKey: .endLine)
		endColumn = try values.decodeIfPresent(Int.self, forKey: .endColumn)
		signature = try values.decodeIfPresent(String.self, forKey: .signature)
		containerName = try values.decodeIfPresent(String.self, forKey: .containerName)
		documentation = try values.decodeIfPresent(String.self, forKey: .documentation)
		source = try values.decodeIfPresent(WorkspaceSymbolSource.self, forKey: .source) ?? .local
	}

	public func encode(to encoder: Encoder) throws {
		var values = encoder.container(keyedBy: CodingKeys.self)
		try values.encode(name, forKey: .name)
		try values.encode(kind, forKey: .kind)
		try values.encode(relativePath, forKey: .relativePath)
		try values.encode(line, forKey: .line)
		try values.encode(column, forKey: .column)
		try values.encodeIfPresent(endLine, forKey: .endLine)
		try values.encodeIfPresent(endColumn, forKey: .endColumn)
		try values.encodeIfPresent(signature, forKey: .signature)
		try values.encodeIfPresent(containerName, forKey: .containerName)
		try values.encodeIfPresent(documentation, forKey: .documentation)
		try values.encode(source, forKey: .source)
	}
}

public enum WorkspaceSymbolMerge {
	public static func preferringLanguageServer(_ languageServer: [WorkspaceSymbol], over local: [WorkspaceSymbol]) -> [WorkspaceSymbol] {
		let lsp = languageServer.map { symbol in
			var symbol = symbol
			symbol.source = .languageServer
			return symbol
		}
		let identities = Set(lsp.map(identity(for:)))
		let fallback = local.compactMap { symbol -> WorkspaceSymbol? in
			guard !identities.contains(identity(for: symbol)) else {
				return nil
			}
			var symbol = symbol
			symbol.source = .local
			return symbol
		}
		return lsp + fallback
	}

	private static func identity(for symbol: WorkspaceSymbol) -> String {
		[
			symbol.relativePath,
			symbol.name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current),
			symbol.kind.rawValue,
			symbol.containerName ?? "",
		].joined(separator: "\u{1F}")
	}
}

public struct WorkspaceIndexedFile: Codable, Equatable, Sendable {
	public var relativePath: String
	public var symbols: [WorkspaceSymbol]

	public init(relativePath: String, symbols: [WorkspaceSymbol]) {
		self.relativePath = relativePath
		self.symbols = symbols
	}
}

public struct WorkspaceIndex: Equatable, Sendable {
	public var root: URL
	public var files: [WorkspaceIndexedFile]

	public init(root: URL, files: [WorkspaceIndexedFile]) {
		self.root = root
		self.files = files
	}

	public var symbols: [WorkspaceSymbol] {
		files.flatMap(\.symbols)
	}

	public func symbolsForFile(relativePath: String) -> [WorkspaceSymbol] {
		files.first(where: { $0.relativePath == relativePath })?.symbols ?? []
	}

	public func relativePath(for url: URL) -> String? {
		let resolved = url.standardizedFileURL.path
		let rootPath = root.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		guard resolved.hasPrefix(prefix) else {
			return nil
		}
		return String(resolved.dropFirst(prefix.count))
	}

	public func searchFiles(query: String, limit: Int = 50) -> [String] {
		guard limit > 0 else {
			return []
		}
		let paths = files.map(\.relativePath)
		return Array(FuzzyMatcher.ranked(paths, query: query, includeUnmatched: false) { $0 }.prefix(limit))
	}

	public func searchSymbols(query: String, limit: Int = 50) -> [WorkspaceSymbol] {
		guard limit > 0 else {
			return []
		}
		let allSymbols = symbols
		let exact = allSymbols.filter { $0.name.compare(query, options: [.caseInsensitive]) == .orderedSame }
		if !exact.isEmpty {
			return Array(exact.prefix(limit))
		}
		let contained = allSymbols.filter { symbol in
			symbol.name.range(of: query, options: [.caseInsensitive]) != nil ||
				symbol.relativePath.range(of: query, options: [.caseInsensitive]) != nil
		}
		if !contained.isEmpty {
			return Array(contained.prefix(limit))
		}
		return Array(FuzzyMatcher.ranked(allSymbols, query: query, includeUnmatched: false) { symbol in
			"\(symbol.name) \(symbol.relativePath)"
		}.prefix(limit))
	}
}

public typealias WorkspaceSymbolProvider = @Sendable (_ text: String, _ url: URL, _ relativePath: String) -> [WorkspaceSymbol]?

public enum WorkspaceIndexer {
	public static func build(
		root: URL,
		maxFileBytes: Int = 1_000_000,
		fileManager: FileManager = .default,
		symbolProvider: WorkspaceSymbolProvider? = nil,
		progress: ((_ processed: Int, _ total: Int) -> Void)? = nil
	) -> WorkspaceIndex {
		let matcher = GitIgnoreMatcher(root: root, fileManager: fileManager)
		let files = ProjectFind.searchableFiles(root: root, matcher: matcher, maxFileBytes: maxFileBytes, fileManager: fileManager)
		let total = files.count
		progress?(0, total)
		var indexedFiles: [WorkspaceIndexedFile] = []
		indexedFiles.reserveCapacity(total)
		let reportEvery = max(1, total / 40)
		for (offset, file) in files.enumerated() {
			if let indexed = indexedFile(at: file, root: root, maxFileBytes: maxFileBytes, fileManager: fileManager, symbolProvider: symbolProvider) {
				indexedFiles.append(indexed)
			}
			let processed = offset + 1
			if processed == total || processed % reportEvery == 0 {
				progress?(processed, total)
			}
		}
		return WorkspaceIndex(root: root, files: indexedFiles)
	}

	public static func indexedFile(
		at url: URL,
		root: URL,
		maxFileBytes: Int = 1_000_000,
		fileManager: FileManager = .default,
		symbolProvider: WorkspaceSymbolProvider? = nil
	) -> WorkspaceIndexedFile? {
		guard
			let data = try? Data(contentsOf: url, options: .mappedIfSafe),
			data.count <= maxFileBytes,
			!data.contains(0),
			let text = String(data: data, encoding: .utf8)
		else {
			return nil
		}
		let relativePath = ProjectFind.relativePath(for: url, root: root)
		let symbols = symbolProvider?(text, url, relativePath) ?? symbols(in: text, relativePath: relativePath)
		return WorkspaceIndexedFile(
			relativePath: relativePath,
			symbols: symbols
		)
	}

	public static func reindex(
		_ index: inout WorkspaceIndex,
		changedURLs: [URL],
		matcher: GitIgnoreMatcher,
		maxFileBytes: Int = 1_000_000,
		fileManager: FileManager = .default,
		symbolProvider: WorkspaceSymbolProvider? = nil
	) {
		for url in changedURLs {
			guard let relative = index.relativePath(for: url) else {
				continue
			}
			let isDirectory: Bool = {
				var value: ObjCBool = false
				let exists = fileManager.fileExists(atPath: url.path, isDirectory: &value)
				return exists && value.boolValue
			}()
			if isDirectory {
				continue
			}
			if matcher.ignores(relativePath: relative, isDirectory: false) {
				index.files.removeAll { $0.relativePath == relative }
				continue
			}
			if let updated = indexedFile(at: url, root: index.root, maxFileBytes: maxFileBytes, fileManager: fileManager, symbolProvider: symbolProvider) {
				if let position = index.files.firstIndex(where: { $0.relativePath == relative }) {
					index.files[position] = updated
				} else {
					index.files.append(updated)
				}
			} else {
				index.files.removeAll { $0.relativePath == relative }
			}
		}
	}

	static func symbols(in text: String, relativePath: String) -> [WorkspaceSymbol] {
		var symbols: [WorkspaceSymbol] = []
		for (lineIndex, lineSlice) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			var line = String(lineSlice)
			if line.last == "\r" {
				line.removeLast()
			}
			guard let symbol = firstSymbol(in: line, relativePath: relativePath, line: lineIndex + 1) else {
				continue
			}
			symbols.append(symbol)
		}
		return symbols
	}

	private static func firstSymbol(in lineText: String, relativePath: String, line: Int) -> WorkspaceSymbol? {
		let range = NSRange(lineText.startIndex ..< lineText.endIndex, in: lineText)
		for pattern in WorkspaceSymbolPattern.patterns {
			guard
				let match = pattern.expression.firstMatch(in: lineText, range: range),
				match.numberOfRanges > pattern.nameCaptureIndex,
				let nameRange = Range(match.range(at: pattern.nameCaptureIndex), in: lineText)
			else {
				continue
			}
			return WorkspaceSymbol(
				name: String(lineText[nameRange]),
				kind: pattern.kind,
				relativePath: relativePath,
				line: line,
				column: lineText.distance(from: lineText.startIndex, to: nameRange.lowerBound) + 1
			)
		}
		return nil
	}
}

private struct WorkspaceSymbolPattern {
	let kind: WorkspaceSymbolKind
	let expression: NSRegularExpression
	let nameCaptureIndex: Int

	init(kind: WorkspaceSymbolKind, pattern: String, nameCaptureIndex: Int = 1) {
		self.kind = kind
		expression = try! NSRegularExpression(pattern: pattern)
		self.nameCaptureIndex = nameCaptureIndex
	}

	static let patterns: [WorkspaceSymbolPattern] = [
		WorkspaceSymbolPattern(kind: .method, pattern: #"^\s*func\s+\([^)]+\)\s*([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .function, pattern: #"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|internal|fileprivate|open|final|static|mutating|nonmutating|override|required|convenience|async)\s+)*func\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .type, pattern: #"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|internal|fileprivate|open|final|indirect)\s+)*(?:class|struct|enum|protocol|actor)\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .variable, pattern: #"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|internal|fileprivate|open|static|lazy|weak|unowned)\s+)*(?:let|var)\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .function, pattern: #"^\s*(?:export\s+default\s+|export\s+|declare\s+|async\s+)*function\s+([A-Za-z_$][A-Za-z0-9_$]*)"#),
		WorkspaceSymbolPattern(kind: .type, pattern: #"^\s*(?:export\s+default\s+|export\s+|declare\s+)*(?:class|interface|type|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)"#),
		WorkspaceSymbolPattern(kind: .variable, pattern: #"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)"#),
		WorkspaceSymbolPattern(kind: .function, pattern: #"^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .type, pattern: #"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .function, pattern: #"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .type, pattern: #"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:struct|enum|trait|type)\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .type, pattern: #"^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)"#),
		WorkspaceSymbolPattern(kind: .function, pattern: #"^\s*(?:(?:static|inline|extern|constexpr)\s+)*[A-Za-z_][A-Za-z0-9_:<>\s\*&]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*(?:\{|$)"#),
		WorkspaceSymbolPattern(kind: .type, pattern: #"^\s*(?:typedef\s+)?(?:struct|class|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"#),
	]
}
