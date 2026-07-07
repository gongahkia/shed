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
	case csharp
	case css
	case dart
	case dockerfile
	case elixir
	case go
	case graphql
	case haskell
	case html
	case java
	case javascript
	case julia
	case json
	case kotlin
	case latex
	case lua
	case markdown
	case markdownInline
	case nix
	case ocaml
	case php
	case proto
	case python
	case r
	case ruby
	case rust
	case scss
	case sql
	case svelte
	case swift
	case terraform
	case toml
	case tsx
	case typescript
	case vue
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
		case .csharp:
			return "tree_sitter_c_sharp"
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
		case .graphql:
			return "tree_sitter_graphql"
		case .haskell:
			return "tree_sitter_haskell"
		case .html:
			return "tree_sitter_html"
		case .java:
			return "tree_sitter_java"
		case .javascript:
			return "tree_sitter_javascript"
		case .julia:
			return "tree_sitter_julia"
		case .json:
			return "tree_sitter_json"
		case .kotlin:
			return "tree_sitter_kotlin"
		case .latex:
			return "tree_sitter_latex"
		case .lua:
			return "tree_sitter_lua"
		case .markdown:
			return "tree_sitter_markdown"
		case .markdownInline:
			return "tree_sitter_markdown_inline"
		case .nix:
			return "tree_sitter_nix"
		case .ocaml:
			return "tree_sitter_ocaml"
		case .php:
			return "tree_sitter_php"
		case .proto:
			return "tree_sitter_proto"
		case .python:
			return "tree_sitter_python"
		case .r:
			return "tree_sitter_r"
		case .ruby:
			return "tree_sitter_ruby"
		case .rust:
			return "tree_sitter_rust"
		case .scss:
			return "tree_sitter_scss"
		case .sql:
			return "tree_sitter_sql"
		case .svelte:
			return "tree_sitter_svelte"
		case .swift:
			return "tree_sitter_swift"
		case .terraform:
			return "tree_sitter_terraform"
		case .toml:
			return "tree_sitter_toml"
		case .tsx:
			return "tree_sitter_tsx"
		case .typescript:
			return "tree_sitter_typescript"
		case .vue:
			return "tree_sitter_vue"
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
		case .csharp:
			return "csharp"
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
		case .csharp:
			return "csharp"
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
		case .graphql:
			return "graphql"
		case .haskell:
			return "haskell"
		case .html:
			return "html"
		case .java:
			return "java"
		case .javascript:
			return "javascript"
		case .julia:
			return "julia"
		case .json:
			return "json"
		case .kotlin:
			return "kotlin"
		case .latex:
			return "latex"
		case .lua:
			return "lua"
		case .markdown:
			return "markdown"
		case .markdownInline:
			return "markdown-inline"
		case .nix:
			return "nix"
		case .ocaml:
			return "ocaml"
		case .php:
			return "php"
		case .proto:
			return "proto"
		case .python:
			return "python"
		case .r:
			return "r"
		case .ruby:
			return "ruby"
		case .rust:
			return "rust"
		case .scss:
			return "scss"
		case .sql:
			return "sql"
		case .svelte:
			return "svelte"
		case .swift:
			return "swift"
		case .terraform:
			return "terraform"
		case .toml:
			return "toml"
		case .tsx, .typescript:
			return "typescript"
		case .vue:
			return "vue"
		case .yaml:
			return "yaml"
		case .zig:
			return "zig"
		}
	}

	static func injectionLanguage(named name: String) -> Language? {
		switch name.lowercased().trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "`\"'"))) {
		case "bash", "shell", "sh":
			return .bash
		case "c":
			return .c
		case "cpp", "c++":
			return .cpp
		case "css":
			return .css
		case "dockerfile":
			return .dockerfile
		case "go", "golang":
			return .go
		case "graphql", "gql":
			return .graphql
		case "html":
			return .html
		case "javascript", "js", "jsx":
			return .javascript
		case "json":
			return .json
		case "markdown", "md":
			return .markdown
		case "markdown_inline", "markdown-inline":
			return .markdownInline
		case "python", "py":
			return .python
		case "rust", "rs":
			return .rust
		case "sql":
			return .sql
		case "swift":
			return .swift
		case "tsx":
			return .tsx
		case "typescript", "ts":
			return .typescript
		case "yaml", "yml":
			return .yaml
		default:
			return nil
		}
	}
}

enum GrammarLoader {
	private typealias LanguageFactory = @convention(c) () -> OpaquePointer?
	private static let lock = NSLock()
	private static var handles: [String: UnsafeMutableRawPointer] = [:]
	private static var warnedStems: Set<String> = []
	private static let testLibraryDirectoriesKey = "ItsySyntax.GrammarLoader.testLibraryDirectories"
	private static let testUseDefaultSymbolsKey = "ItsySyntax.GrammarLoader.testUseDefaultSymbols"

	static func language(for language: Language) -> OpaquePointer? {
		lock.lock()
		defer { lock.unlock() }
		if useDefaultSymbols(), let symbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), language.symbolName) {
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
			guard let symbol = dlsym(handle, language.symbolName) else {
				dlclose(handle)
				continue
			}
			handles[stem] = handle
			return unsafeBitCast(symbol, to: LanguageFactory.self)()
		}
		warnMissing(language: language)
		return nil
	}

	static func loadedLibraryStemsForTests() -> Set<String> {
		lock.lock()
		defer { lock.unlock() }
		return Set(handles.keys)
	}

	static func configureForTests(libraryDirectories: [URL]? = nil, useDefaultSymbols: Bool? = nil) {
		let dictionary = Thread.current.threadDictionary
		if let libraryDirectories {
			dictionary[testLibraryDirectoriesKey] = libraryDirectories
		} else {
			dictionary.removeObject(forKey: testLibraryDirectoriesKey)
		}
		if let useDefaultSymbols {
			dictionary[testUseDefaultSymbolsKey] = useDefaultSymbols
		} else {
			dictionary.removeObject(forKey: testUseDefaultSymbolsKey)
		}
	}

	static func unloadLibraryStemForTests(_ stem: String) {
		lock.lock()
		defer { lock.unlock() }
		if let handle = handles.removeValue(forKey: stem) {
			dlclose(handle)
		}
		warnedStems.remove(stem)
	}

	private static func libraryURLs(for language: Language) -> [URL] {
		let name = "libitsy-tree-sitter-\(language.libraryStem).dylib"
		return libraryDirectories().map { $0.appendingPathComponent(name) }
	}

	private static func libraryDirectories() -> [URL] {
		if let directories = Thread.current.threadDictionary[testLibraryDirectoriesKey] as? [URL] {
			return directories
		}
		var directories: [URL] = []
		if let value = getenv("ITSY_GRAMMAR_LIBRARY_DIR") {
			let path = String(cString: value)
			if !path.isEmpty {
				directories.append(URL(fileURLWithPath: path))
			}
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

	private static func useDefaultSymbols() -> Bool {
		if let value = Thread.current.threadDictionary[testUseDefaultSymbolsKey] as? Bool {
			return value
		}
		guard let value = getenv("ITSY_GRAMMAR_DISABLE_DEFAULT_SYMBOLS") else {
			return true
		}
		return String(cString: value) != "1"
	}

	private static func warnMissing(language: Language) {
		let stem = language.libraryStem
		guard warnedStems.insert(stem).inserted else {
			return
		}
		NSLog("ItsySyntax warning: failed to load grammar \(stem); syntax highlighting disabled for \(language)")
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

struct QueryCapture: Sendable, Equatable {
	var range: Range<Int>
	var capture: String
	var text: String
}

struct InjectionSite: Sendable, Equatable {
	var range: Range<Int>
	var language: Language
}

private enum SyntaxQueryFile: String {
	case highlights
	case tags
	case injections
	case locals
	case indents
}

private enum SyntaxQuerySource {
	static func load(language: Language, file: SyntaxQueryFile) throws -> String {
		switch (language, file) {
		case (.typescript, .highlights), (.tsx, .highlights),
		     (.typescript, .tags), (.tsx, .tags),
		     (.typescript, .locals), (.tsx, .locals):
			return try load(resourceName: "javascript", file: file, language: language) + "\n" + load(resourceName: "typescript", file: file, language: language)
		default:
			return try load(resourceName: language.queryResourceName, file: file, language: language)
		}
	}

	private static func load(resourceName: String, file: SyntaxQueryFile, language: Language) throws -> String {
		let subdirectory = "Resources/queries/\(resourceName)"
		guard let url = Bundle.module.url(forResource: file.rawValue, withExtension: "scm", subdirectory: subdirectory) else {
			throw SyntaxError.queryLoadFailed(language)
		}
		return try String(contentsOf: url, encoding: .utf8)
	}
}

private struct RawQueryCapture {
	var node: Node
	var capture: String
}

private struct RawQueryMatch {
	var patternIndex: UInt32
	var captures: [RawQueryCapture]
	var properties: [String: String]
}

private final class CompiledSyntaxQuery {
	private static let compileLock = NSLock()
	private let query: OpaquePointer

	init(language: Language, file: SyntaxQueryFile) throws {
		guard let rawLanguage = language.rawLanguage else {
			throw SyntaxError.incompatibleLanguage(language)
		}
		let source = try SyntaxQuerySource.load(language: language, file: file)
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

	func captures(in tree: Tree, byteRange: Range<Int>? = nil) throws -> [RawQueryCapture] {
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
		var captures: [RawQueryCapture] = []
		var match = TSQueryMatch()
		var captureIndex: UInt32 = 0
		while ts_query_cursor_next_capture(cursor, &match, &captureIndex) {
			let index = Int(captureIndex)
			guard index < Int(match.capture_count) else {
				continue
			}
			let capture = match.captures[index]
			captures.append(RawQueryCapture(node: Node(capture.node), capture: captureName(for: capture.index)))
		}
		return captures.sorted { lhs, rhs in
			lhs.node.byteRange.lowerBound == rhs.node.byteRange.lowerBound ? lhs.capture < rhs.capture : lhs.node.byteRange.lowerBound < rhs.node.byteRange.lowerBound
		}
	}

	func matches(in tree: Tree, byteRange: Range<Int>? = nil) throws -> [RawQueryMatch] {
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
		var result: [RawQueryMatch] = []
		var match = TSQueryMatch()
		while ts_query_cursor_next_match(cursor, &match) {
			var captures: [RawQueryCapture] = []
			for captureOffset in 0 ..< Int(match.capture_count) {
				let capture = match.captures[captureOffset]
				captures.append(RawQueryCapture(node: Node(capture.node), capture: captureName(for: capture.index)))
			}
			result.append(RawQueryMatch(
				patternIndex: UInt32(match.pattern_index),
				captures: captures,
				properties: setProperties(for: UInt32(match.pattern_index))
			))
		}
		return result
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

	private func stringValue(for index: UInt32) -> String {
		var length: UInt32 = 0
		let pointer = ts_query_string_value_for_id(query, index, &length)
		guard let pointer else {
			return ""
		}
		let bytes = UnsafeRawPointer(pointer).assumingMemoryBound(to: UInt8.self)
		let buffer = UnsafeBufferPointer(start: bytes, count: Int(length))
		return String(decoding: buffer, as: UTF8.self)
	}

	private func setProperties(for patternIndex: UInt32) -> [String: String] {
		var count: UInt32 = 0
		guard let steps = ts_query_predicates_for_pattern(query, patternIndex, &count), count > 0 else {
			return [:]
		}
		var properties: [String: String] = [:]
		var current: [String] = []
		for index in 0 ..< Int(count) {
			let step = steps[index]
			if step.type == TSQueryPredicateStepTypeDone {
				applySetPredicate(current, to: &properties)
				current.removeAll(keepingCapacity: true)
			} else if step.type == TSQueryPredicateStepTypeString {
				current.append(stringValue(for: step.value_id))
			}
		}
		applySetPredicate(current, to: &properties)
		return properties
	}

	private func applySetPredicate(_ values: [String], to properties: inout [String: String]) {
		guard values.count >= 2, values[0] == "set!" else {
			return
		}
		properties[values[1]] = values.count >= 3 ? values[2] : "true"
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
		try SyntaxQuerySource.load(language: language, file: .highlights)
	}
}

final class LocalQuery {
	private let query: CompiledSyntaxQuery

	init(language: Language) throws {
		query = try CompiledSyntaxQuery(language: language, file: .locals)
	}

	func captures(in tree: Tree, source: String) throws -> [QueryCapture] {
		let bytes = Array(source.utf8)
		return try query.captures(in: tree).map { capture in
			QueryCapture(
				range: capture.node.byteRange,
				capture: capture.capture,
				text: Self.text(in: capture.node.byteRange, bytes: bytes)
			)
		}
	}

	private static func text(in range: Range<Int>, bytes: [UInt8]) -> String {
		guard range.lowerBound >= 0, range.upperBound <= bytes.count else {
			return ""
		}
		return String(decoding: bytes[range], as: UTF8.self)
	}
}

final class InjectionQuery {
	private let query: CompiledSyntaxQuery

	init(language: Language) throws {
		query = try CompiledSyntaxQuery(language: language, file: .injections)
	}

	func injections(in tree: Tree, source: String, byteRange: Range<Int>? = nil) throws -> [InjectionSite] {
		let bytes = Array(source.utf8)
		return try query.matches(in: tree).flatMap { match -> [InjectionSite] in
			let languageName = match.properties["injection.language"] ?? match.captures.first(where: { $0.capture == "injection.language" }).map { Self.text(in: $0.node.byteRange, bytes: bytes) }
			guard let languageName, let language = Language.injectionLanguage(named: languageName) else {
				return []
			}
			return match.captures.compactMap { capture in
				guard capture.capture == "injection.content" else {
					return nil
				}
				let range = capture.node.byteRange
				if let byteRange, !range.overlaps(byteRange) {
					return nil
				}
				return InjectionSite(range: range, language: language)
			}
		}.sorted { lhs, rhs in
			lhs.range.lowerBound == rhs.range.lowerBound ? lhs.language.queryResourceName < rhs.language.queryResourceName : lhs.range.lowerBound < rhs.range.lowerBound
		}
	}

	private static func text(in range: Range<Int>, bytes: [UInt8]) -> String {
		guard range.lowerBound >= 0, range.upperBound <= bytes.count else {
			return ""
		}
		return String(decoding: bytes[range], as: UTF8.self)
	}
}

final class IndentQuery {
	private let query: CompiledSyntaxQuery

	init(language: Language) throws {
		query = try CompiledSyntaxQuery(language: language, file: .indents)
	}

	func indentationAfterNewline(in tree: Tree, source: String, offset: Int, tabWidth: Int) throws -> String {
		let bytes = Array(source.utf8)
		let clampedOffset = min(max(offset, 0), bytes.count)
		let lineStart = Self.lineStart(before: clampedOffset, bytes: bytes)
		let baseIndent = Self.leadingWhitespace(from: lineStart, bytes: bytes)
		let previous = Self.previousNonBlankByte(before: clampedOffset, stoppingAt: lineStart, bytes: bytes)
		let shouldIndent = try previous.map { byte in
			try query.captures(in: tree).contains { capture in
				capture.capture == "indent.begin" && capture.node.byteRange.contains(byte)
			}
		} ?? false
		let width = min(max(tabWidth, 1), 16)
		return "\n" + baseIndent + (shouldIndent ? String(repeating: " ", count: width) : "")
	}

	private static func lineStart(before offset: Int, bytes: [UInt8]) -> Int {
		var index = offset
		while index > 0 {
			if bytes[index - 1] == 10 {
				return index
			}
			index -= 1
		}
		return 0
	}

	private static func leadingWhitespace(from offset: Int, bytes: [UInt8]) -> String {
		var index = offset
		while index < bytes.count, bytes[index] == 32 || bytes[index] == 9 {
			index += 1
		}
		return String(decoding: bytes[offset ..< index], as: UTF8.self)
	}

	private static func previousNonBlankByte(before offset: Int, stoppingAt lineStart: Int, bytes: [UInt8]) -> Int? {
		var index = offset
		while index > lineStart {
			let byte = bytes[index - 1]
			if byte != 32 && byte != 9 {
				return index - 1
			}
			index -= 1
		}
		return nil
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
		try SyntaxQuerySource.load(language: language, file: .tags)
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
	private var injectionQuery: InjectionQuery?
	private var indentQuery: IndentQuery?
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
		case "cs", "csx":
			return .csharp
		case "css":
			return .css
		case "dart":
			return .dart
		case "go":
			return .go
		case "graphql", "gql":
			return .graphql
		case "ex", "exs":
			return .elixir
		case "hs", "lhs":
			return .haskell
		case "html", "htm":
			return .html
		case "java":
			return .java
		case "js", "mjs", "cjs":
			return .javascript
		case "jl":
			return .julia
		case "json":
			return .json
		case "kt", "kts":
			return .kotlin
		case "tex", "sty", "cls":
			return .latex
		case "lua":
			return .lua
		case "md", "markdown":
			return .markdown
		case "nix":
			return .nix
		case "ml", "mli":
			return .ocaml
		case "php":
			return .php
		case "proto":
			return .proto
		case "py":
			return .python
		case "r":
			return .r
		case "rb", "rake":
			return .ruby
		case "rs":
			return .rust
		case "scss":
			return .scss
		case "sql":
			return .sql
		case "svelte":
			return .svelte
		case "swift":
			return .swift
		case "tf", "tfvars", "hcl":
			return .terraform
		case "toml":
			return .toml
		case "tsx":
			return .tsx
		case "ts":
			return .typescript
		case "vue":
			return .vue
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

	public mutating func highlights(in tree: Tree, source: String, byteRange: Range<Int>? = nil, includeInjections: Bool) throws -> [HighlightSpan] {
		var spans = try highlights(in: tree, byteRange: byteRange)
		guard includeInjections else {
			return spans
		}
		spans += try injectedHighlights(in: tree, source: source, byteRange: byteRange, depth: 0)
		return spans.sorted { lhs, rhs in
			lhs.range.lowerBound == rhs.range.lowerBound ? lhs.capture < rhs.capture : lhs.range.lowerBound < rhs.range.lowerBound
		}
	}

	public mutating func indentationAfterNewline(in tree: Tree, source: String, offset: Int, tabWidth: Int) throws -> String {
		let query = try ensureIndentQuery()
		return try query.indentationAfterNewline(in: tree, source: source, offset: offset, tabWidth: tabWidth)
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

	private mutating func ensureInjectionQuery() throws -> InjectionQuery {
		if let injectionQuery {
			return injectionQuery
		}
		let query = try InjectionQuery(language: language)
		injectionQuery = query
		return query
	}

	private mutating func ensureIndentQuery() throws -> IndentQuery {
		if let indentQuery {
			return indentQuery
		}
		let query = try IndentQuery(language: language)
		indentQuery = query
		return query
	}

	private mutating func injectedHighlights(in tree: Tree, source: String, byteRange: Range<Int>?, depth: Int) throws -> [HighlightSpan] {
		guard depth < 2 else {
			return []
		}
		let query: InjectionQuery
		do {
			query = try ensureInjectionQuery()
		} catch SyntaxError.queryLoadFailed(_) {
			return []
		}
		let bytes = Array(source.utf8)
		return try query.injections(in: tree, source: source, byteRange: byteRange).flatMap { site -> [HighlightSpan] in
			guard site.range.lowerBound >= 0, site.range.upperBound <= bytes.count, site.range.lowerBound < site.range.upperBound else {
				return []
			}
			let embeddedSource = String(decoding: bytes[site.range], as: UTF8.self)
			var pipeline = SyntaxPipeline(language: site.language)
			guard let embeddedTree = try? pipeline.parse(Rope(embeddedSource)) else {
				return []
			}
			let spans = (try? pipeline.highlights(in: embeddedTree)) ?? []
			return spans.map { span in
				HighlightSpan(
					range: (site.range.lowerBound + span.range.lowerBound) ..< (site.range.lowerBound + span.range.upperBound),
					capture: span.capture
				)
			}
		}
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
