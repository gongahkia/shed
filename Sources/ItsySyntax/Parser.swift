import CTreeSitter
import Darwin
import Foundation
import ItsyEditor

public enum SyntaxError: Error, Equatable {
	case parserAllocationFailed
	case incompatibleLanguage(Language)
	case parseFailed
	case documentTooLarge(Int)
	case queryLoadFailed(Language)
	case queryCompileFailed(Language, UInt32, Int)
	case queryCursorAllocationFailed
}

public enum Language: Sendable, Hashable, CaseIterable {
	case bash
	case c
	case cpp
	case css
	case dart
	case dockerfile
	case elixir
	case go
	case html
	case javascript
	case json
	case kotlin
	case markdown
	case markdownInline
	case python
	case rust
	case sql
	case swift
	case toml
	case tsx
	case typescript
	case yaml
	case zig

	var rawLanguage: OpaquePointer? {
		GrammarLoader.language(for: self)
	}

	fileprivate var symbolName: String {
		switch self {
		case .bash:
			return "tree_sitter_bash"
		case .c:
			return "tree_sitter_c"
		case .cpp:
			return "tree_sitter_cpp"
		case .css:
			return "tree_sitter_css"
		case .dart:
			return "tree_sitter_dart"
		case .dockerfile:
			return "tree_sitter_dockerfile"
		case .elixir:
			return "tree_sitter_elixir"
		case .go:
			return "tree_sitter_go"
		case .html:
			return "tree_sitter_html"
		case .javascript:
			return "tree_sitter_javascript"
		case .json:
			return "tree_sitter_json"
		case .kotlin:
			return "tree_sitter_kotlin"
		case .markdown:
			return "tree_sitter_markdown"
		case .markdownInline:
			return "tree_sitter_markdown_inline"
		case .python:
			return "tree_sitter_python"
		case .rust:
			return "tree_sitter_rust"
		case .sql:
			return "tree_sitter_sql"
		case .swift:
			return "tree_sitter_swift"
		case .toml:
			return "tree_sitter_toml"
		case .tsx:
			return "tree_sitter_tsx"
		case .typescript:
			return "tree_sitter_typescript"
		case .yaml:
			return "tree_sitter_yaml"
		case .zig:
			return "tree_sitter_zig"
		}
	}

	fileprivate var libraryStem: String {
		switch self {
		case .markdownInline:
			return "markdown"
		case .tsx, .typescript:
			return "typescript"
		default:
			return queryResourceName
		}
	}

	var queryResourceName: String {
		switch self {
		case .bash:
			return "bash"
		case .c:
			return "c"
		case .cpp:
			return "cpp"
		case .css:
			return "css"
		case .dart:
			return "dart"
		case .dockerfile:
			return "dockerfile"
		case .elixir:
			return "elixir"
		case .go:
			return "go"
		case .html:
			return "html"
		case .javascript:
			return "javascript"
		case .json:
			return "json"
		case .kotlin:
			return "kotlin"
		case .markdown:
			return "markdown"
		case .markdownInline:
			return "markdown-inline"
		case .python:
			return "python"
		case .rust:
			return "rust"
		case .sql:
			return "sql"
		case .swift:
			return "swift"
		case .toml:
			return "toml"
		case .tsx, .typescript:
			return "typescript"
		case .yaml:
			return "yaml"
		case .zig:
			return "zig"
		}
	}
}

private enum GrammarLoader {
	private typealias LanguageFactory = @convention(c) () -> OpaquePointer?
	private static let lock = NSLock()
	private static var handles: [String: UnsafeMutableRawPointer] = [:]

	static func language(for language: Language) -> OpaquePointer? {
		lock.lock()
		defer { lock.unlock() }
		if let symbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), language.symbolName) {
			return unsafeBitCast(symbol, to: LanguageFactory.self)()
		}
		let stem = language.libraryStem
		if let handle = handles[stem], let symbol = dlsym(handle, language.symbolName) {
			return unsafeBitCast(symbol, to: LanguageFactory.self)()
		}
		for url in libraryURLs(for: language) {
			guard let handle = dlopen(url.path, RTLD_NOW | RTLD_LOCAL) else {
				continue
			}
			handles[stem] = handle
			guard let symbol = dlsym(handle, language.symbolName) else {
				continue
			}
			return unsafeBitCast(symbol, to: LanguageFactory.self)()
		}
		return nil
	}

	private static func libraryURLs(for language: Language) -> [URL] {
		let name = "libitsy-tree-sitter-\(language.libraryStem).dylib"
		return libraryDirectories().map { $0.appendingPathComponent(name) }
	}

	private static func libraryDirectories() -> [URL] {
		var directories: [URL] = []
		if let path = ProcessInfo.processInfo.environment["ITSY_GRAMMAR_LIBRARY_DIR"], !path.isEmpty {
			directories.append(URL(fileURLWithPath: path))
		}
		if let frameworksURL = Bundle.main.privateFrameworksURL {
			directories.append(frameworksURL.appendingPathComponent("ItsyGrammars"))
		}
		if let resourceURL = Bundle.main.resourceURL {
			directories.append(resourceURL.appendingPathComponent("ItsyGrammars"))
		}
		let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
		directories.append(cwd.appendingPathComponent(".build/release/ItsyGrammars"))
		directories.append(cwd.appendingPathComponent("Itsy.app/Contents/Frameworks/ItsyGrammars"))
		return directories
	}
}

final class Parser {
	private let parser: OpaquePointer

	init(language: Language) throws {
		guard let parser = ts_parser_new() else {
			throw SyntaxError.parserAllocationFailed
		}
		guard let rawLanguage = language.rawLanguage, ts_parser_set_language(parser, rawLanguage) else {
			ts_parser_delete(parser)
			throw SyntaxError.incompatibleLanguage(language)
		}
		self.parser = parser
	}

	deinit {
		ts_parser_delete(parser)
	}

	func parse(_ rope: Rope, oldTree: Tree? = nil) throws -> Tree {
		guard rope.length <= Int(UInt32.max) else {
			throw SyntaxError.documentTooLarge(rope.length)
		}
		var input = RopeInput(rope)
		defer {
			input.deallocate()
		}
		let tree = try withUnsafeMutablePointer(to: &input) { inputPointer in
			let tsInput = TSInput(
				payload: UnsafeMutableRawPointer(inputPointer),
				read: ropeInputRead,
				encoding: TSInputEncodingUTF8,
				decode: nil
			)
			guard let tree = ts_parser_parse(parser, oldTree?.tree, tsInput) else {
				throw SyntaxError.parseFailed
			}
			return tree
		}
		return Tree(tree)
	}

	func parse(_ pieceTree: PieceTree, oldTree: Tree? = nil) throws -> Tree {
		guard pieceTree.length <= Int(UInt32.max) else {
			throw SyntaxError.documentTooLarge(pieceTree.length)
		}
		var input = PieceTreeInput(pieceTree)
		defer {
			input.deallocate()
		}
		let tree = try withUnsafeMutablePointer(to: &input) { inputPointer in
			let tsInput = TSInput(
				payload: UnsafeMutableRawPointer(inputPointer),
				read: pieceTreeInputRead,
				encoding: TSInputEncodingUTF8,
				decode: nil
			)
			guard let tree = ts_parser_parse(parser, oldTree?.tree, tsInput) else {
				throw SyntaxError.parseFailed
			}
			return tree
		}
		return Tree(tree)
	}
}

public final class Tree {
	fileprivate let tree: OpaquePointer

	fileprivate init(_ tree: OpaquePointer) {
		self.tree = tree
	}

	deinit {
		ts_tree_delete(tree)
	}

	public var rootNode: Node {
		Node(ts_tree_root_node(tree))
	}

	public func edit(_ edit: InputEdit) {
		var raw = edit.rawEdit
		ts_tree_edit(tree, &raw)
	}
}

public struct Node {
	fileprivate let node: TSNode

	fileprivate init(_ node: TSNode) {
		self.node = node
	}

	public var type: String {
		String(cString: ts_node_type(node))
	}

	public var byteRange: Range<Int> {
		Int(ts_node_start_byte(node)) ..< Int(ts_node_end_byte(node))
	}

	public var startPoint: Point {
		Point(ts_node_start_point(node))
	}

	public var endPoint: Point {
		Point(ts_node_end_point(node))
	}

	public var childCount: Int {
		Int(ts_node_child_count(node))
	}

	public var hasError: Bool {
		ts_node_has_error(node)
	}

	public func child(at index: Int) -> Node? {
		precondition(index >= 0, "node child index out of bounds")
		let child = ts_node_child(node, UInt32(index))
		return ts_node_is_null(child) ? nil : Node(child)
	}
}

public struct Point: Sendable, Equatable {
	public var row: Int
	public var column: Int

	public init(row: Int, column: Int) {
		self.row = row
		self.column = column
	}

	fileprivate init(_ point: TSPoint) {
		row = Int(point.row)
		column = Int(point.column)
	}

	fileprivate var rawPoint: TSPoint {
		TSPoint(row: UInt32(row), column: UInt32(column))
	}
}

public struct InputEdit: Sendable, Equatable {
	public var startByte: Int
	public var oldEndByte: Int
	public var newEndByte: Int
	public var startPoint: Point
	public var oldEndPoint: Point
	public var newEndPoint: Point

	public init(
		startByte: Int,
		oldEndByte: Int,
		newEndByte: Int,
		startPoint: Point,
		oldEndPoint: Point,
		newEndPoint: Point
	) {
		self.startByte = startByte
		self.oldEndByte = oldEndByte
		self.newEndByte = newEndByte
		self.startPoint = startPoint
		self.oldEndPoint = oldEndPoint
		self.newEndPoint = newEndPoint
	}

	public init(edit: Edit, oldRope: Rope, newRope: Rope) {
		let startByte = edit.range.lowerBound
		let oldEndByte = edit.range.upperBound
		let newEndByte = edit.range.lowerBound + edit.newText.utf8.count
		self.init(
			startByte: startByte,
			oldEndByte: oldEndByte,
			newEndByte: newEndByte,
			startPoint: Point(rope: oldRope, offset: startByte),
			oldEndPoint: Point(rope: oldRope, offset: oldEndByte),
			newEndPoint: Point(rope: newRope, offset: newEndByte)
		)
	}

	fileprivate var rawEdit: TSInputEdit {
		TSInputEdit(
			start_byte: UInt32(startByte),
			old_end_byte: UInt32(oldEndByte),
			new_end_byte: UInt32(newEndByte),
			start_point: startPoint.rawPoint,
			old_end_point: oldEndPoint.rawPoint,
			new_end_point: newEndPoint.rawPoint
		)
	}
}

private extension Point {
	init(rope: Rope, offset: Int) {
		let row = rope.line(forOffset: offset)
		self.init(row: row, column: offset - rope.offset(forLine: row))
	}
}

public struct HighlightSpan: Sendable, Equatable {
	public var range: Range<Int>
	public var capture: String

	public init(range: Range<Int>, capture: String) {
		self.range = range
		self.capture = capture
	}

	public func mapped(through edit: Edit) -> HighlightSpan? {
		let lowerBound = mapOffset(range.lowerBound, through: edit)
		let upperBound = mapOffset(range.upperBound, through: edit)
		guard lowerBound < upperBound else {
			return nil
		}
		return HighlightSpan(range: lowerBound ..< upperBound, capture: capture)
	}

	private func mapOffset(_ offset: Int, through edit: Edit) -> Int {
		if offset <= edit.range.lowerBound {
			return offset
		}
		if offset >= edit.range.upperBound {
			return offset + edit.newText.utf8.count - (edit.range.upperBound - edit.range.lowerBound)
		}
		return edit.range.lowerBound + edit.newText.utf8.count
	}
}

final class HighlightQuery {
	private static let compileLock = NSLock()
	private let query: OpaquePointer

	init(language: Language) throws {
		guard let rawLanguage = language.rawLanguage else {
			throw SyntaxError.incompatibleLanguage(language)
		}
		let source = try Self.loadSource(language: language)
		var errorOffset: UInt32 = 0
		var errorType = TSQueryErrorNone
		Self.compileLock.lock()
		defer {
			Self.compileLock.unlock()
		}
		let query = source.withCString { pointer in
			ts_query_new(rawLanguage, pointer, UInt32(source.utf8.count), &errorOffset, &errorType)
		}
		guard let query else {
			throw SyntaxError.queryCompileFailed(language, errorOffset, Int(errorType.rawValue))
		}
		self.query = query
	}

	deinit {
		ts_query_delete(query)
	}

	func highlights(in tree: Tree, byteRange: Range<Int>? = nil) throws -> [HighlightSpan] {
		guard let cursor = ts_query_cursor_new() else {
			throw SyntaxError.queryCursorAllocationFailed
		}
		defer {
			ts_query_cursor_delete(cursor)
		}
		if let byteRange {
			_ = ts_query_cursor_set_byte_range(cursor, UInt32(byteRange.lowerBound), UInt32(byteRange.upperBound))
		}
		ts_query_cursor_exec(cursor, query, tree.rootNode.node)
		var spans: [HighlightSpan] = []
		var match = TSQueryMatch()
		var captureIndex: UInt32 = 0
		while ts_query_cursor_next_capture(cursor, &match, &captureIndex) {
			let index = Int(captureIndex)
			guard index < Int(match.capture_count) else {
				continue
			}
			let capture = match.captures[index]
			let name = captureName(for: capture.index)
			let node = Node(capture.node)
			spans.append(HighlightSpan(range: node.byteRange, capture: name))
		}
		return spans.sorted { lhs, rhs in
			lhs.range.lowerBound == rhs.range.lowerBound ? lhs.capture < rhs.capture : lhs.range.lowerBound < rhs.range.lowerBound
		}
	}

	private func captureName(for index: UInt32) -> String {
		var length: UInt32 = 0
		let pointer = ts_query_capture_name_for_id(query, index, &length)
		guard let pointer else {
			return ""
		}
		let bytes = UnsafeRawPointer(pointer).assumingMemoryBound(to: UInt8.self)
		let buffer = UnsafeBufferPointer(start: bytes, count: Int(length))
		return String(decoding: buffer, as: UTF8.self)
	}

	private static func loadSource(language: Language) throws -> String {
		if language == .typescript || language == .tsx {
			return try loadSource(resourceName: "javascript", language: language) + "\n" + loadSource(resourceName: "typescript", language: language)
		}
		return try loadSource(resourceName: language.queryResourceName, language: language)
	}

	private static func loadSource(resourceName: String, language: Language) throws -> String {
		let subdirectory = "Resources/queries/\(resourceName)"
		guard let url = Bundle.module.url(forResource: "highlights", withExtension: "scm", subdirectory: subdirectory) else {
			throw SyntaxError.queryLoadFailed(language)
		}
		return try String(contentsOf: url, encoding: .utf8)
	}
}

final class TagQuery {
	private static let compileLock = NSLock()
	private let executionLock = NSLock()
	private let query: OpaquePointer

	init(language: Language) throws {
		guard let rawLanguage = language.rawLanguage else {
			throw SyntaxError.incompatibleLanguage(language)
		}
		let source = try Self.loadSource(language: language)
		var errorOffset: UInt32 = 0
		var errorType = TSQueryErrorNone
		Self.compileLock.lock()
		defer {
			Self.compileLock.unlock()
		}
		let query = source.withCString { pointer in
			ts_query_new(rawLanguage, pointer, UInt32(source.utf8.count), &errorOffset, &errorType)
		}
		guard let query else {
			throw SyntaxError.queryCompileFailed(language, errorOffset, Int(errorType.rawValue))
		}
		self.query = query
	}

	deinit {
		ts_query_delete(query)
	}

	func symbols(in tree: Tree, source: String, relativePath: String) throws -> [WorkspaceSymbol] {
		executionLock.lock()
		defer {
			executionLock.unlock()
		}
		guard let cursor = ts_query_cursor_new() else {
			throw SyntaxError.queryCursorAllocationFailed
		}
		defer {
			ts_query_cursor_delete(cursor)
		}
		let bytes = Array(source.utf8)
		ts_query_cursor_exec(cursor, query, tree.rootNode.node)
		var symbols: [WorkspaceSymbol] = []
		var seen: Set<String> = []
		var match = TSQueryMatch()
		while ts_query_cursor_next_match(cursor, &match) {
			guard let symbol = symbol(from: match, bytes: bytes, relativePath: relativePath) else {
				continue
			}
			let key = "\(symbol.relativePath)\u{1f}\(symbol.line)\u{1f}\(symbol.column)\u{1f}\(symbol.name)"
			if seen.insert(key).inserted {
				symbols.append(symbol)
			}
		}
		return symbols.sorted { lhs, rhs in
			lhs.line == rhs.line ? lhs.column < rhs.column : lhs.line < rhs.line
		}
	}

	private func symbol(from match: TSQueryMatch, bytes: [UInt8], relativePath: String) -> WorkspaceSymbol? {
		var nameNode: Node?
		var definitionNode: Node?
		var kind: WorkspaceSymbolKind?
		var docs: [String] = []
		for captureOffset in 0 ..< Int(match.capture_count) {
			let capture = match.captures[captureOffset]
			let captureName = captureName(for: capture.index)
			let node = Node(capture.node)
			if captureName == "name" {
				nameNode = node
			} else if let mappedKind = Self.kind(for: captureName) {
				kind = mappedKind
				definitionNode = node
			} else if captureName == "doc" {
				docs.append(Self.text(in: node.byteRange, bytes: bytes))
			}
		}
		guard let nameNode, let kind else {
			return nil
		}
		let name = Self.text(in: nameNode.byteRange, bytes: bytes).trimmingCharacters(in: .whitespacesAndNewlines)
		guard !name.isEmpty else {
			return nil
		}
		let rangeNode = definitionNode ?? nameNode
		return WorkspaceSymbol(
			name: name,
			kind: kind,
			relativePath: relativePath,
			line: nameNode.startPoint.row + 1,
			column: nameNode.startPoint.column + 1,
			endLine: rangeNode.endPoint.row + 1,
			endColumn: rangeNode.endPoint.column + 1,
			documentation: Self.documentation(from: docs)
		)
	}

	private func captureName(for index: UInt32) -> String {
		var length: UInt32 = 0
		let pointer = ts_query_capture_name_for_id(query, index, &length)
		guard let pointer else {
			return ""
		}
		let bytes = UnsafeRawPointer(pointer).assumingMemoryBound(to: UInt8.self)
		let buffer = UnsafeBufferPointer(start: bytes, count: Int(length))
		return String(decoding: buffer, as: UTF8.self)
	}

	private static func kind(for captureName: String) -> WorkspaceSymbolKind? {
		switch captureName {
		case "definition.function":
			return .function
		case "definition.method":
			return .method
		case "definition.class", "definition.interface", "definition.module":
			return .type
		case "definition.constant", "definition.property":
			return .variable
		default:
			return nil
		}
	}

	private static func text(in range: Range<Int>, bytes: [UInt8]) -> String {
		guard range.lowerBound >= 0, range.upperBound <= bytes.count else {
			return ""
		}
		return String(decoding: bytes[range], as: UTF8.self)
	}

	private static func documentation(from docs: [String]) -> String? {
		let lines = docs
			.flatMap { $0.split(separator: "\n", omittingEmptySubsequences: false) }
			.map { line in
				line
					.trimmingCharacters(in: .whitespaces)
					.trimmingCharacters(in: CharacterSet(charactersIn: "/#* "))
			}
			.filter { !$0.isEmpty }
		return lines.isEmpty ? nil : lines.joined(separator: "\n")
	}

	private static func loadSource(language: Language) throws -> String {
		if language == .typescript || language == .tsx {
			return try loadSource(resourceName: "javascript", language: language) + "\n" + loadSource(resourceName: "typescript", language: language)
		}
		return try loadSource(resourceName: language.queryResourceName, language: language)
	}

	private static func loadSource(resourceName: String, language: Language) throws -> String {
		let subdirectory = "Resources/queries/\(resourceName)"
		guard let url = Bundle.module.url(forResource: "tags", withExtension: "scm", subdirectory: subdirectory) else {
			throw SyntaxError.queryLoadFailed(language)
		}
		return try String(contentsOf: url, encoding: .utf8)
	}
}

public enum TreeSitterSymbolExtractor {
	private static let lock = NSLock()
	private static var queries: [Language: TagQuery] = [:]

	public static func workspaceSymbols(in text: String, fileURL: URL, relativePath: String) -> [WorkspaceSymbol]? {
		guard let language = SyntaxPipeline.language(forFileURL: fileURL) else {
			return nil
		}
		do {
			var pipeline = SyntaxPipeline(language: language)
			let tree = try pipeline.parse(Rope(text))
			let query = try query(for: language)
			return try query.symbols(in: tree, source: text, relativePath: relativePath)
		} catch {
			return nil
		}
	}

	private static func query(for language: Language) throws -> TagQuery {
		lock.lock()
		defer {
			lock.unlock()
		}
		if let query = queries[language] {
			return query
		}
		let query = try TagQuery(language: language)
		queries[language] = query
		return query
	}
}

public struct SyntaxPipeline {
	public let language: Language
	private var parser: Parser?
	private var highlightQuery: HighlightQuery?
	public private(set) var didAllocateParser = false

	public init(language: Language) {
		self.language = language
	}

	public static func language(forFileURL url: URL) -> Language? {
		let filename = url.lastPathComponent.lowercased()
		if filename == "dockerfile" || filename.hasPrefix("dockerfile.") || filename.hasSuffix(".dockerfile") {
			return .dockerfile
		}
		switch url.pathExtension.lowercased() {
		case "bash", "sh":
			return .bash
		case "c", "h":
			return .c
		case "cc", "cpp", "cxx", "hpp", "hxx":
			return .cpp
		case "css":
			return .css
		case "dart":
			return .dart
		case "go":
			return .go
		case "ex", "exs":
			return .elixir
		case "html", "htm":
			return .html
		case "js", "mjs", "cjs":
			return .javascript
		case "json":
			return .json
		case "kt", "kts":
			return .kotlin
		case "md", "markdown":
			return .markdown
		case "py":
			return .python
		case "rs":
			return .rust
		case "sql":
			return .sql
		case "swift":
			return .swift
		case "toml":
			return .toml
		case "tsx":
			return .tsx
		case "ts":
			return .typescript
		case "yaml", "yml":
			return .yaml
		case "zig", "zon":
			return .zig
		default:
			return nil
		}
	}

	public mutating func parse(_ rope: Rope, oldTree: Tree? = nil) throws -> Tree {
		let parser = try ensureParser()
		return try parser.parse(rope, oldTree: oldTree)
	}

	public mutating func parse(_ pieceTree: PieceTree, oldTree: Tree? = nil) throws -> Tree {
		let parser = try ensureParser()
		return try parser.parse(pieceTree, oldTree: oldTree)
	}

	public mutating func highlights(in tree: Tree, byteRange: Range<Int>? = nil) throws -> [HighlightSpan] {
		let query = try ensureQuery()
		return try query.highlights(in: tree, byteRange: byteRange)
	}

	private mutating func ensureParser() throws -> Parser {
		if let parser {
			return parser
		}
		let parser = try Parser(language: language)
		self.parser = parser
		didAllocateParser = true
		return parser
	}

	private mutating func ensureQuery() throws -> HighlightQuery {
		if let highlightQuery {
			return highlightQuery
		}
		let query = try HighlightQuery(language: language)
		highlightQuery = query
		return query
	}
}

private struct RopeInput {
	let rope: Rope
	let capacity = 16 * 1024
	let buffer: UnsafeMutablePointer<CChar>

	init(_ rope: Rope) {
		self.rope = rope
		buffer = UnsafeMutablePointer<CChar>.allocate(capacity: capacity + 1)
	}

	func deallocate() {
		buffer.deallocate()
	}

	func read(byteIndex: UInt32, bytesRead: UnsafeMutablePointer<UInt32>?) -> UnsafePointer<CChar>? {
		let offset = Int(byteIndex)
		guard offset < rope.length else {
			bytesRead?.pointee = 0
			return nil
		}
		let rawBuffer = UnsafeMutableRawPointer(buffer).assumingMemoryBound(to: UInt8.self)
		let count = rope.copyUTF8Chunk(at: offset, maxBytes: capacity, into: rawBuffer)
		buffer[count] = 0
		bytesRead?.pointee = UInt32(count)
		return UnsafePointer(buffer)
	}
}

private let ropeInputRead: @convention(c) (
	UnsafeMutableRawPointer?,
	UInt32,
	TSPoint,
	UnsafeMutablePointer<UInt32>?
) -> UnsafePointer<CChar>? = { payload, byteIndex, _, bytesRead in
	guard let payload else {
		bytesRead?.pointee = 0
		return nil
	}
	let input = payload.assumingMemoryBound(to: RopeInput.self)
	return input.pointee.read(byteIndex: byteIndex, bytesRead: bytesRead)
}

private struct PieceTreeInput {
	let pieceTree: PieceTree
	let capacity = 4 * 1024
	let buffer: UnsafeMutablePointer<CChar>

	init(_ pieceTree: PieceTree) {
		self.pieceTree = pieceTree
		buffer = UnsafeMutablePointer<CChar>.allocate(capacity: capacity + 1)
	}

	func deallocate() {
		buffer.deallocate()
	}

	func read(byteIndex: UInt32, bytesRead: UnsafeMutablePointer<UInt32>?) -> UnsafePointer<CChar>? {
		let offset = Int(byteIndex)
		guard offset < pieceTree.length else {
			bytesRead?.pointee = 0
			return nil
		}
		let rawBuffer = UnsafeMutableRawPointer(buffer).assumingMemoryBound(to: UInt8.self)
		let target = UnsafeMutableBufferPointer(start: rawBuffer, count: capacity)
		let count = pieceTree.copyUTF8(at: offset, into: target)
		buffer[count] = 0
		bytesRead?.pointee = UInt32(count)
		return UnsafePointer(buffer)
	}
}

private let pieceTreeInputRead: @convention(c) (
	UnsafeMutableRawPointer?,
	UInt32,
	TSPoint,
	UnsafeMutablePointer<UInt32>?
) -> UnsafePointer<CChar>? = { payload, byteIndex, _, bytesRead in
	guard let payload else {
		bytesRead?.pointee = 0
		return nil
	}
	let input = payload.assumingMemoryBound(to: PieceTreeInput.self)
	return input.pointee.read(byteIndex: byteIndex, bytesRead: bytesRead)
}
