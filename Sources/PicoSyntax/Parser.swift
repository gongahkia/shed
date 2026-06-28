import CTreeSitter
import CTSGrammars
import PicoEditor

public enum SyntaxError: Error, Equatable {
	case parserAllocationFailed
	case incompatibleLanguage(Language)
	case parseFailed
	case documentTooLarge(Int)
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
		switch self {
		case .c:
			return tree_sitter_c()
		case .cpp:
			return tree_sitter_cpp()
		case .css:
			return tree_sitter_css()
		case .go:
			return tree_sitter_go()
		case .html:
			return tree_sitter_html()
		case .javascript:
			return tree_sitter_javascript()
		case .json:
			return tree_sitter_json()
		case .markdown:
			return tree_sitter_markdown()
		case .markdownInline:
			return tree_sitter_markdown_inline()
		case .python:
			return tree_sitter_python()
		case .rust:
			return tree_sitter_rust()
		case .toml:
			return tree_sitter_toml()
		case .tsx:
			return tree_sitter_tsx()
		case .typescript:
			return tree_sitter_typescript()
		case .yaml:
			return tree_sitter_yaml()
		}
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
