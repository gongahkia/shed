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

public enum Language: Sendable, Equatable, CaseIterable {
	case c
	case cpp
	case css
	case go
	case html
	case javascript
	case json
	case markdown
	case markdownInline
	case python
	case rust
	case toml
	case tsx
	case typescript
	case yaml

	var rawLanguage: OpaquePointer? {
		GrammarLoader.language(for: self)
	}

	fileprivate var symbolName: String {
		switch self {
		case .c:
			return "tree_sitter_c"
		case .cpp:
			return "tree_sitter_cpp"
		case .css:
			return "tree_sitter_css"
		case .go:
			return "tree_sitter_go"
		case .html:
			return "tree_sitter_html"
		case .javascript:
			return "tree_sitter_javascript"
		case .json:
			return "tree_sitter_json"
		case .markdown:
			return "tree_sitter_markdown"
		case .markdownInline:
			return "tree_sitter_markdown_inline"
		case .python:
			return "tree_sitter_python"
		case .rust:
			return "tree_sitter_rust"
		case .toml:
			return "tree_sitter_toml"
		case .tsx:
			return "tree_sitter_tsx"
		case .typescript:
			return "tree_sitter_typescript"
		case .yaml:
			return "tree_sitter_yaml"
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
		case .c:
			return "c"
		case .cpp:
			return "cpp"
		case .css:
			return "css"
		case .go:
			return "go"
		case .html:
			return "html"
		case .javascript:
			return "javascript"
		case .json:
			return "json"
		case .markdown:
			return "markdown"
		case .markdownInline:
			return "markdown-inline"
		case .python:
			return "python"
		case .rust:
			return "rust"
		case .toml:
			return "toml"
		case .tsx, .typescript:
			return "typescript"
		case .yaml:
			return "yaml"
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

public final class Parser {
	private let parser: OpaquePointer

	public init(language: Language) throws {
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

	public func parse(_ rope: Rope, oldTree: Tree? = nil) throws -> Tree {
		guard rope.length <= Int(UInt32.max) else {
			throw SyntaxError.documentTooLarge(rope.length)
		}
		let input = RopeInput(rope)
		let tsInput = TSInput(
			payload: Unmanaged.passUnretained(input).toOpaque(),
			read: ropeInputRead,
			encoding: TSInputEncodingUTF8,
			decode: nil
		)
		let tree = try withExtendedLifetime(input) {
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

public final class HighlightQuery {
	private let query: OpaquePointer

	public init(language: Language) throws {
		guard let rawLanguage = language.rawLanguage else {
			throw SyntaxError.incompatibleLanguage(language)
		}
		let source = try Self.loadSource(language: language)
		var errorOffset: UInt32 = 0
		var errorType = TSQueryErrorNone
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

	public func highlights(in tree: Tree, byteRange: Range<Int>? = nil) throws -> [HighlightSpan] {
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

public struct SyntaxPipeline {
	public let language: Language
	private var parser: Parser?
	private var highlightQuery: HighlightQuery?
	public private(set) var didAllocateParser = false

	public init(language: Language) {
		self.language = language
	}

	public static func language(forFileURL url: URL) -> Language? {
		switch url.pathExtension.lowercased() {
		case "c", "h":
			return .c
		case "cc", "cpp", "cxx", "hpp", "hxx":
			return .cpp
		case "css":
			return .css
		case "go":
			return .go
		case "html", "htm":
			return .html
		case "js", "mjs", "cjs":
			return .javascript
		case "json":
			return .json
		case "md", "markdown":
			return .markdown
		case "py":
			return .python
		case "rs":
			return .rust
		case "toml":
			return .toml
		case "tsx":
			return .tsx
		case "ts":
			return .typescript
		case "yaml", "yml":
			return .yaml
		default:
			return nil
		}
	}

	public mutating func parse(_ rope: Rope, oldTree: Tree? = nil) throws -> Tree {
		let parser = try ensureParser()
		return try parser.parse(rope, oldTree: oldTree)
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

private final class RopeInput {
	let rope: Rope
	let capacity = 16 * 1024
	let buffer: UnsafeMutablePointer<CChar>

	init(_ rope: Rope) {
		self.rope = rope
		buffer = UnsafeMutablePointer<CChar>.allocate(capacity: capacity + 1)
	}

	deinit {
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
	let input = Unmanaged<RopeInput>.fromOpaque(payload).takeUnretainedValue()
	return input.read(byteIndex: byteIndex, bytesRead: bytesRead)
}
