import Darwin
import Foundation

public enum PieceTreeSaveError: Error, Equatable, CustomStringConvertible {
	case openFailed(path: String, code: Int32)
	case writeFailed(path: String, code: Int32)
	case closeFailed(path: String, code: Int32)

	public var description: String {
		switch self {
		case let .openFailed(path, code):
			return "open failed for \(path): \(Self.message(code))"
		case let .writeFailed(path, code):
			return "write failed for \(path): \(Self.message(code))"
		case let .closeFailed(path, code):
			return "close failed for \(path): \(Self.message(code))"
		}
	}

	private static func message(_ code: Int32) -> String {
		String(cString: strerror(code))
	}
}

public struct PieceTree: Sendable {
	public enum BufferID: Equatable, Sendable {
		case original(Int)
		case add(Int)
	}

	public struct Piece: Equatable, Sendable {
		public var buffer: BufferID
		public var start: Int
		public var length: Int
		public var lineFeeds: Int
		public var graphemes: Int

		public init(buffer: BufferID, start: Int, length: Int, lineFeeds: Int, graphemes: Int? = nil) {
			self.buffer = buffer
			self.start = start
			self.length = length
			self.lineFeeds = lineFeeds
			self.graphemes = graphemes ?? length
		}
	}

	private var root: PieceTreeNode?
	private var originalBuffers: [PieceTreeOriginalBuffer]
	private var addBuffers: [[UInt8]]

	public init() {
		root = nil
		originalBuffers = []
		addBuffers = []
	}

	public init(_ string: String) {
		self.init(bytes: Array(string.utf8))
	}

	public init(bytes: [UInt8]) {
		originalBuffers = bytes.isEmpty ? [] : [.bytes(bytes)]
		addBuffers = []
		if bytes.isEmpty {
			root = nil
		} else {
			let piece = Piece(
				buffer: .original(0),
				start: 0,
				length: bytes.count,
				lineFeeds: Self.lineFeeds(in: bytes),
				graphemes: Self.graphemes(in: bytes)
			)
			root = Self.buildTree(from: [piece])
		}
	}

	public init(readingMappedFile url: URL) throws {
		try self.init(readingMappedFile: url) { bytes in
			LineFeedIndexer.lineFeedCount(in: bytes)
		}
	}

	public init(readingMappedFile url: URL, indexedPrefixBytes: Int, didIndexPrefix: @escaping () -> Void) throws {
		try self.init(readingMappedFile: url) { bytes in
			LineFeedIndexer.lineFeedCount(
				in: bytes,
				notifyAfterPrefix: indexedPrefixBytes,
				onIndexedPrefix: didIndexPrefix
			)
		}
	}

	private init(readingMappedFile url: URL, lineFeedCounter: (UnsafeBufferPointer<UInt8>) -> Int) throws {
		let mapped = try MMapBuffer(url: url)
		originalBuffers = mapped.count == 0 ? [] : [.mapped(mapped)]
		addBuffers = []
		if mapped.count == 0 {
			root = nil
		} else {
			let piece = Piece(
				buffer: .original(0),
				start: 0,
				length: mapped.count,
				lineFeeds: lineFeedCounter(mapped.bytes),
				graphemes: Self.graphemes(in: mapped.bytes)
			)
			root = Self.buildTree(from: [piece])
		}
	}

	public var length: Int {
		root?.summary.bytes ?? 0
	}

	public var lineCount: Int {
		(root?.summary.lineFeeds ?? 0) + 1
	}

	public var graphemeCount: Int {
		root?.summary.graphemes ?? 0
	}

	public func substring(_ range: Range<Int>) -> String {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "substring range out of bounds")
		guard !range.isEmpty else {
			return ""
		}
		var bytes: [UInt8] = []
		bytes.reserveCapacity(range.count)
		appendBytes(in: range, into: &bytes)
		return String(decoding: bytes, as: UTF8.self)
	}

	public func utf8Byte(at offset: Int) -> UInt8 {
		precondition(offset >= 0 && offset < length, "byte offset out of bounds")
		guard let root else {
			preconditionFailure("byte offset out of bounds")
		}
		return byte(at: offset, in: root)
	}

	public func line(forOffset offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "line offset out of bounds")
		guard let root else {
			return 0
		}
		return lineFeedCount(before: offset, in: root)
	}

	public func graphemeIndex(forOffset offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "grapheme offset out of bounds")
		guard let root else {
			return 0
		}
		return graphemeCount(before: offset, in: root)
	}

	public func offset(forGraphemeIndex index: Int) -> Int {
		precondition(index >= 0, "grapheme index out of bounds")
		guard index > 0 else {
			return 0
		}
		guard index < graphemeCount, let root else {
			return length
		}
		return offset(forGraphemeIndex: index, in: root, baseOffset: 0) ?? length
	}

	public func isGraphemeBoundary(_ offset: Int) -> Bool {
		precondition((0 ... length).contains(offset), "grapheme offset out of bounds")
		return self.offset(forGraphemeIndex: graphemeIndex(forOffset: offset)) == offset
	}

	public func previousGraphemeBoundary(before offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "grapheme offset out of bounds")
		guard offset > 0 else {
			return 0
		}
		let index = graphemeIndex(forOffset: offset)
		let boundary = self.offset(forGraphemeIndex: index)
		if boundary < offset {
			return boundary
		}
		guard index > 0 else {
			return 0
		}
		return self.offset(forGraphemeIndex: index - 1)
	}

	public func nextGraphemeBoundary(after offset: Int) -> Int {
		precondition((0 ... length).contains(offset), "grapheme offset out of bounds")
		guard offset < length else {
			return length
		}
		return self.offset(forGraphemeIndex: graphemeIndex(forOffset: offset) + 1)
	}

	public func offset(forLine line: Int) -> Int {
		precondition(line >= 0, "line index out of bounds")
		guard line > 0 else {
			return 0
		}
		guard line < lineCount, let root else {
			return length
		}
		return offset(afterLineFeeds: line, in: root, baseOffset: 0) ?? length
	}

	public func lineRange(_ line: Int) -> Range<Int> {
		precondition(line >= 0, "line index out of bounds")
		guard line < lineCount else {
			return length ..< length
		}
		let start = offset(forLine: line)
		let next = line + 1 < lineCount ? offset(forLine: line + 1) : length
		let end = line + 1 < lineCount ? max(start, next - 1) : next
		return start ..< end
	}

	public mutating func insert(_ string: String, at offset: Int) {
		insert(Array(string.utf8), at: offset)
	}

	public mutating func insert(_ bytes: [UInt8], at offset: Int) {
		precondition((0 ... length).contains(offset), "insert offset out of bounds")
		guard !bytes.isEmpty else {
			return
		}
		let inserted = appendAddPiece(bytes)
		root = PieceTreeNode.insert(inserted, at: offset, into: root, split: split)
		root?.color = .black
		root?.recalculate()
	}

	public mutating func remove(_ range: Range<Int>) {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "remove range out of bounds")
		guard !range.isEmpty else {
			return
		}
		var output: [Piece] = []
		output.reserveCapacity(pieceCount)
		var cursor = 0
		for piece in pieces() {
			let end = cursor + piece.length
			if end <= range.lowerBound || cursor >= range.upperBound {
				output.append(piece)
			} else {
				let lower = max(0, range.lowerBound - cursor)
				let upper = min(piece.length, range.upperBound - cursor)
				if lower > 0 {
					output.append(split(piece, 0 ..< lower))
				}
				if upper < piece.length {
					output.append(split(piece, upper ..< piece.length))
				}
			}
			cursor = end
		}
		rebuild(from: coalesced(output))
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with string: String) -> Edit {
		replace(range, with: Array(string.utf8))
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with data: Data) -> Edit {
		replace(range, with: Array(data))
	}

	@discardableResult
	public mutating func replace(_ range: Range<Int>, with bytes: [UInt8]) -> Edit {
		precondition(range.lowerBound >= 0 && range.upperBound <= length, "replace range out of bounds")
		var removed: [UInt8] = []
		removed.reserveCapacity(range.count)
		appendBytes(in: range, into: &removed)
		if !range.isEmpty {
			remove(range)
		}
		if !bytes.isEmpty {
			insert(bytes, at: range.lowerBound)
		}
		return Edit(
			range: range.lowerBound ..< range.lowerBound + bytes.count,
			removed: Data(bytes),
			inserted: Data(removed)
		)
	}

	public func iterateBytes(from offset: Int, _ body: (UnsafeBufferPointer<UInt8>) throws -> Bool) rethrows {
		precondition((0 ... length).contains(offset), "iteration offset out of bounds")
		guard offset < length else {
			return
		}
		var cursor = 0
		for piece in pieces() {
			let end = cursor + piece.length
			guard end > offset else {
				cursor = end
				continue
			}
			let local = max(0, offset - cursor)
			let shouldContinue = try withPieceBytes(piece, local ..< piece.length, body)
			if !shouldContinue {
				return
			}
			cursor = end
		}
	}

	public func copyUTF8(at offset: Int, into buffer: UnsafeMutableBufferPointer<UInt8>) -> Int {
		precondition((0 ... length).contains(offset), "copy offset out of bounds")
		guard offset < length, !buffer.isEmpty, let target = buffer.baseAddress else {
			return 0
		}
		var copied = 0
		iterateBytes(from: offset) { source in
			let remaining = buffer.count - copied
			let count = min(remaining, source.count)
			if count > 0 {
				for index in 0 ..< count {
					target[copied + index] = source[source.startIndex + index]
				}
				copied += count
			}
			return copied < buffer.count
		}
		let sourceEndReached = offset + copied >= length
		return Self.trimmedUTF8BoundaryCount(in: buffer, copied: copied, sourceEndReached: sourceEndReached)
	}

	public func saveTo(url: URL) throws {
		let path = url.path
		let descriptor = open(path, O_WRONLY | O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH)
		guard descriptor >= 0 else {
			throw PieceTreeSaveError.openFailed(path: path, code: errno)
		}
		var savedError: Error?
		do {
			try writePieces(to: descriptor, path: path)
		} catch {
			savedError = error
		}
		let closeResult = close(descriptor)
		if let savedError {
			throw savedError
		}
		guard closeResult == 0 else {
			throw PieceTreeSaveError.closeFailed(path: path, code: errno)
		}
	}

	func debugPieces() -> [Piece] {
		pieces()
	}

	private var pieceCount: Int {
		pieces().count
	}

	private mutating func appendAddPiece(_ bytes: [UInt8]) -> Piece {
		let index = addBuffers.count
		addBuffers.append(bytes)
		return Piece(
			buffer: .add(index),
			start: 0,
			length: bytes.count,
			lineFeeds: Self.lineFeeds(in: bytes),
			graphemes: Self.graphemes(in: bytes)
		)
	}

	private mutating func rebuild(from pieces: [Piece]) {
		root = Self.buildTree(from: pieces.filter { $0.length > 0 })
	}

	private static func buildTree(from pieces: [Piece]) -> PieceTreeNode? {
		var root: PieceTreeNode?
		var offset = 0
		for piece in pieces where piece.length > 0 {
			root = PieceTreeNode.insertBoundary(piece, at: offset, into: root)
			root?.color = .black
			root?.recalculate()
			offset += piece.length
		}
		return root
	}

	private func pieces() -> [Piece] {
		var result: [Piece] = []
		result.reserveCapacity(16)
		root?.appendPieces(into: &result)
		return result
	}

	private func split(_ piece: Piece, _ localRange: Range<Int>) -> Piece {
		precondition(localRange.lowerBound >= 0 && localRange.upperBound <= piece.length, "piece split out of bounds")
		return Piece(
			buffer: piece.buffer,
			start: piece.start + localRange.lowerBound,
			length: localRange.count,
			lineFeeds: lineFeeds(in: piece, localRange: localRange),
			graphemes: graphemes(in: piece, localRange: localRange)
		)
	}

	private func coalesced(_ pieces: [Piece]) -> [Piece] {
		var result: [Piece] = []
		result.reserveCapacity(pieces.count)
		for piece in pieces where piece.length > 0 {
			if var last = result.last, last.buffer == piece.buffer, last.start + last.length == piece.start {
				last.length += piece.length
				last.lineFeeds += piece.lineFeeds
				last.graphemes += piece.graphemes
				result[result.count - 1] = last
			} else {
				result.append(piece)
			}
		}
		return result
	}

	private func appendBytes(in range: Range<Int>, into bytes: inout [UInt8]) {
		var cursor = 0
		for piece in pieces() {
			let end = cursor + piece.length
			if range.upperBound <= cursor {
				return
			}
			if range.lowerBound < end {
				let lower = max(0, range.lowerBound - cursor)
				let upper = min(piece.length, range.upperBound - cursor)
				_ = withPieceBytes(piece, lower ..< upper) { buffer in
					bytes.append(contentsOf: buffer)
					return true
				}
			}
			cursor = end
		}
	}

	private func byte(at offset: Int, in node: PieceTreeNode) -> UInt8 {
		let leftBytes = node.left?.summary.bytes ?? 0
		if offset < leftBytes, let left = node.left {
			return byte(at: offset, in: left)
		}
		let local = offset - leftBytes
		if local < node.piece.length {
			return byte(in: node.piece, at: local)
		}
		guard let right = node.right else {
			preconditionFailure("byte offset out of bounds")
		}
		return byte(at: local - node.piece.length, in: right)
	}

	private func lineFeedCount(before offset: Int, in node: PieceTreeNode) -> Int {
		let leftBytes = node.left?.summary.bytes ?? 0
		if offset <= leftBytes, let left = node.left {
			return lineFeedCount(before: offset, in: left)
		}
		var lines = node.left?.summary.lineFeeds ?? 0
		let local = offset - leftBytes
		if local <= node.piece.length {
			return lines + lineFeeds(in: node.piece, localRange: 0 ..< local)
		}
		lines += node.piece.lineFeeds
		guard let right = node.right else {
			return lines
		}
		return lines + lineFeedCount(before: local - node.piece.length, in: right)
	}

	private func graphemeCount(before offset: Int, in node: PieceTreeNode) -> Int {
		let leftBytes = node.left?.summary.bytes ?? 0
		if offset <= leftBytes, let left = node.left {
			return graphemeCount(before: offset, in: left)
		}
		var graphemes = node.left?.summary.graphemes ?? 0
		let local = offset - leftBytes
		if local <= node.piece.length {
			return graphemes + graphemeIndex(in: node.piece, before: local)
		}
		graphemes += node.piece.graphemes
		guard let right = node.right else {
			return graphemes
		}
		return graphemes + graphemeCount(before: local - node.piece.length, in: right)
	}

	private func offset(forGraphemeIndex target: Int, in node: PieceTreeNode, baseOffset: Int) -> Int? {
		let leftBytes = node.left?.summary.bytes ?? 0
		let leftGraphemes = node.left?.summary.graphemes ?? 0
		if target < leftGraphemes, let left = node.left {
			return offset(forGraphemeIndex: target, in: left, baseOffset: baseOffset)
		}
		let pieceOffset = baseOffset + leftBytes
		if target == leftGraphemes {
			return pieceOffset
		}
		let remaining = target - leftGraphemes
		if remaining < node.piece.graphemes {
			return offset(forGraphemeIndex: remaining, in: node.piece).map { pieceOffset + $0 }
		}
		if remaining == node.piece.graphemes {
			return pieceOffset + node.piece.length
		}
		guard let right = node.right else {
			return nil
		}
		return offset(
			forGraphemeIndex: remaining - node.piece.graphemes,
			in: right,
			baseOffset: pieceOffset + node.piece.length
		)
	}

	private func offset(afterLineFeeds target: Int, in node: PieceTreeNode, baseOffset: Int) -> Int? {
		let leftBytes = node.left?.summary.bytes ?? 0
		let leftFeeds = node.left?.summary.lineFeeds ?? 0
		if target <= leftFeeds, let left = node.left {
			return offset(afterLineFeeds: target, in: left, baseOffset: baseOffset)
		}
		let afterLeftOffset = baseOffset + leftBytes
		let remaining = target - leftFeeds
		if remaining <= node.piece.lineFeeds {
			return afterLineFeed(remaining, in: node.piece).map { afterLeftOffset + $0 }
		}
		guard let right = node.right else {
			return nil
		}
		return offset(
			afterLineFeeds: remaining - node.piece.lineFeeds,
			in: right,
			baseOffset: afterLeftOffset + node.piece.length
		)
	}

	private func afterLineFeed(_ target: Int, in piece: Piece) -> Int? {
		precondition(target > 0, "line-feed target must be positive")
		var seen = 0
		var result: Int?
		_ = withPieceBytes(piece, 0 ..< piece.length) { buffer in
			for index in buffer.indices where buffer[index] == 10 {
				seen += 1
				if seen == target {
					result = buffer.distance(from: buffer.startIndex, to: index) + 1
					return false
				}
			}
			return true
		}
		return result
	}

	private func offset(forGraphemeIndex target: Int, in piece: Piece) -> Int? {
		precondition(target > 0, "grapheme target must be positive")
		var seen = 0
		var result: Int?
		_ = withPieceBytes(piece, 0 ..< piece.length) { buffer in
			var iterator = UAX29GraphemeIterator(bytes: buffer)
			while let range = iterator.next() {
				seen += 1
				if seen == target {
					result = range.upperBound
					return false
				}
			}
			return true
		}
		return result
	}

	private func lineFeeds(in piece: Piece, localRange: Range<Int>) -> Int {
		var count = 0
		_ = withPieceBytes(piece, localRange) { buffer in
			count = buffer.reduce(0) { $1 == 10 ? $0 + 1 : $0 }
			return true
		}
		return count
	}

	private func graphemes(in piece: Piece, localRange: Range<Int>) -> Int {
		var count = 0
		_ = withPieceBytes(piece, localRange) { buffer in
			count = Self.graphemes(in: buffer)
			return true
		}
		return count
	}

	private func graphemeIndex(in piece: Piece, before offset: Int) -> Int {
		var count = 0
		_ = withPieceBytes(piece, 0 ..< piece.length) { buffer in
			count = Self.graphemes(in: buffer, before: offset)
			return true
		}
		return count
	}

	private func byte(in piece: Piece, at localOffset: Int) -> UInt8 {
		var byte: UInt8 = 0
		_ = withPieceBytes(piece, localOffset ..< localOffset + 1) { buffer in
			byte = buffer[buffer.startIndex]
			return false
		}
		return byte
	}

	private func writePieces(to descriptor: Int32, path: String) throws {
		let allPieces = pieces()
		let batchSize = 64
		var index = 0
		while index < allPieces.count {
			let upperBound = min(index + batchSize, allPieces.count)
			try withIOVectors(for: allPieces, range: index ..< upperBound) { vectors in
				try Self.writeAll(vectors, to: descriptor, path: path)
			}
			index = upperBound
		}
	}

	private func withIOVectors<T>(
		for pieces: [Piece],
		range: Range<Int>,
		_ body: ([iovec]) throws -> T
	) throws -> T {
		var vectors: [iovec] = []
		vectors.reserveCapacity(range.count)
		func appendVector(at index: Int) throws -> T {
			if index == range.upperBound {
				return try body(vectors)
			}
			let piece = pieces[index]
			return try withPieceBytes(piece, 0 ..< piece.length) { buffer in
				var didAppend = false
				if let baseAddress = buffer.baseAddress, !buffer.isEmpty {
					vectors.append(iovec(iov_base: UnsafeMutableRawPointer(mutating: baseAddress), iov_len: buffer.count))
					didAppend = true
				}
				defer {
					if didAppend {
						vectors.removeLast()
					}
				}
				return try appendVector(at: index + 1)
			}
		}
		return try appendVector(at: range.lowerBound)
	}

	private static func writeAll(_ vectors: [iovec], to descriptor: Int32, path: String) throws {
		var vectors = vectors
		var index = 0
		while index < vectors.count {
			let written = vectors.withUnsafeBufferPointer { buffer in
				guard let baseAddress = buffer.baseAddress else {
					return 0
				}
				return writev(descriptor, baseAddress.advanced(by: index), Int32(vectors.count - index))
			}
			if written < 0 {
				if errno == EINTR {
					continue
				}
				throw PieceTreeSaveError.writeFailed(path: path, code: errno)
			}
			guard written > 0 else {
				throw PieceTreeSaveError.writeFailed(path: path, code: EIO)
			}
			var remaining = Int(written)
			while index < vectors.count, remaining >= vectors[index].iov_len {
				remaining -= vectors[index].iov_len
				index += 1
			}
			if remaining > 0, index < vectors.count {
				vectors[index].iov_base = vectors[index].iov_base.advanced(by: remaining)
				vectors[index].iov_len -= remaining
			}
		}
	}

	private func withPieceBytes<T>(_ piece: Piece, _ localRange: Range<Int>, _ body: (UnsafeBufferPointer<UInt8>) throws -> T) rethrows -> T {
		precondition(localRange.lowerBound >= 0 && localRange.upperBound <= piece.length, "piece byte range out of bounds")
		let range = piece.start + localRange.lowerBound ..< piece.start + localRange.upperBound
		switch piece.buffer {
		case let .original(index):
			return try originalBuffers[index].withUnsafeBufferPointer { buffer in
				try body(UnsafeBufferPointer(rebasing: buffer[range]))
			}
		case let .add(index):
			return try addBuffers[index].withUnsafeBufferPointer { buffer in
				try body(UnsafeBufferPointer(rebasing: buffer[range]))
			}
		}
	}

	private static func lineFeeds(in bytes: [UInt8]) -> Int {
		bytes.reduce(0) { $1 == 10 ? $0 + 1 : $0 }
	}

	private static func graphemes(in bytes: [UInt8]) -> Int {
		bytes.withUnsafeBufferPointer {
			graphemes(in: $0)
		}
	}

	private static func graphemes(in bytes: UnsafeBufferPointer<UInt8>) -> Int {
		return UAX29GraphemeIterator.graphemeCount(in: bytes)
	}

	private static func graphemes(in bytes: UnsafeBufferPointer<UInt8>, before offset: Int) -> Int {
		precondition((0 ... bytes.count).contains(offset), "grapheme offset out of bounds")
		return UAX29GraphemeIterator.graphemeIndex(in: bytes, before: offset)
	}

	private static func trimmedUTF8BoundaryCount(
		in buffer: UnsafeMutableBufferPointer<UInt8>,
		copied: Int,
		sourceEndReached: Bool
	) -> Int {
		guard !sourceEndReached, copied > 0 else {
			return copied
		}
		let lowerBound = max(0, copied - 4)
		var scalarStart = copied - 1
		while scalarStart > lowerBound, buffer[scalarStart].isUTF8Continuation {
			scalarStart -= 1
		}
		let expectedLength = buffer[scalarStart].utf8SequenceLength
		if expectedLength == 1 || scalarStart + expectedLength <= copied {
			return copied
		}
		return scalarStart > 0 ? scalarStart : copied
	}
}

private extension UInt8 {
	var isUTF8Continuation: Bool {
		(self & 0b1100_0000) == 0b1000_0000
	}

	var utf8SequenceLength: Int {
		if self < 0b1000_0000 {
			return 1
		}
		if self & 0b1110_0000 == 0b1100_0000 {
			return 2
		}
		if self & 0b1111_0000 == 0b1110_0000 {
			return 3
		}
		if self & 0b1111_1000 == 0b1111_0000 {
			return 4
		}
		return 1
	}
}

private enum PieceTreeOriginalBuffer: Sendable {
	case bytes([UInt8])
	case mapped(MMapBuffer)

	func withUnsafeBufferPointer<T>(_ body: (UnsafeBufferPointer<UInt8>) throws -> T) rethrows -> T {
		switch self {
		case let .bytes(bytes):
			return try bytes.withUnsafeBufferPointer(body)
		case let .mapped(buffer):
			return try body(buffer.bytes)
		}
	}
}

private enum PieceTreeColor: Sendable {
	case red
	case black
}

private struct PieceTreeSummary: Sendable {
	var bytes: Int
	var lineFeeds: Int
	var graphemes: Int

	static let zero = PieceTreeSummary(bytes: 0, lineFeeds: 0, graphemes: 0)

	init(bytes: Int, lineFeeds: Int, graphemes: Int) {
		self.bytes = bytes
		self.lineFeeds = lineFeeds
		self.graphemes = graphemes
	}

	init(_ piece: PieceTree.Piece) {
		bytes = piece.length
		lineFeeds = piece.lineFeeds
		graphemes = piece.graphemes
	}

	static func + (lhs: PieceTreeSummary, rhs: PieceTreeSummary) -> PieceTreeSummary {
		PieceTreeSummary(
			bytes: lhs.bytes + rhs.bytes,
			lineFeeds: lhs.lineFeeds + rhs.lineFeeds,
			graphemes: lhs.graphemes + rhs.graphemes
		)
	}
}

private final class PieceTreeNode: @unchecked Sendable {
	var color: PieceTreeColor
	var piece: PieceTree.Piece
	var left: PieceTreeNode?
	var right: PieceTreeNode?
	var summary: PieceTreeSummary

	init(color: PieceTreeColor, piece: PieceTree.Piece, left: PieceTreeNode? = nil, right: PieceTreeNode? = nil) {
		self.color = color
		self.piece = piece
		self.left = left
		self.right = right
		summary = .zero
		recalculate()
	}

	func recalculate() {
		summary = (left?.summary ?? .zero) + PieceTreeSummary(piece) + (right?.summary ?? .zero)
	}

	func appendPieces(into pieces: inout [PieceTree.Piece]) {
		left?.appendPieces(into: &pieces)
		pieces.append(piece)
		right?.appendPieces(into: &pieces)
	}

	static func insertBoundary(_ piece: PieceTree.Piece, at offset: Int, into node: PieceTreeNode?) -> PieceTreeNode {
		guard let node else {
			return PieceTreeNode(color: .red, piece: piece)
		}
		let leftBytes = node.left?.summary.bytes ?? 0
		if offset <= leftBytes {
			node.left = insertBoundary(piece, at: offset, into: node.left)
		} else {
			let rightOffset = offset - leftBytes - node.piece.length
			precondition(rightOffset >= 0, "piece insert offset must land on a boundary")
			node.right = insertBoundary(piece, at: rightOffset, into: node.right)
		}
		return balance(node)
	}

	static func insert(
		_ piece: PieceTree.Piece,
		at offset: Int,
		into node: PieceTreeNode?,
		split: (PieceTree.Piece, Range<Int>) -> PieceTree.Piece
	) -> PieceTreeNode {
		guard let node else {
			return PieceTreeNode(color: .red, piece: piece)
		}
		let leftBytes = node.left?.summary.bytes ?? 0
		if offset < leftBytes {
			node.left = insert(piece, at: offset, into: node.left, split: split)
			return balance(node)
		}
		let pieceEnd = leftBytes + node.piece.length
		if offset > pieceEnd {
			node.right = insert(piece, at: offset - pieceEnd, into: node.right, split: split)
			return balance(node)
		}
		let local = offset - leftBytes
		if local == 0 {
			node.left = insert(piece, at: leftBytes, into: node.left, split: split)
		} else if local == node.piece.length {
			node.right = insert(piece, at: 0, into: node.right, split: split)
		} else {
			let original = node.piece
			node.piece = split(original, 0 ..< local)
			let rightPiece = split(original, local ..< original.length)
			node.right = insert(rightPiece, at: 0, into: node.right, split: split)
			node.right = insert(piece, at: 0, into: node.right, split: split)
		}
		return balance(node)
	}

	private static func balance(_ node: PieceTreeNode) -> PieceTreeNode {
		var node = node
		if isRed(node.right), !isRed(node.left) {
			node = rotateLeft(node)
		}
		if isRed(node.left), isRed(node.left?.left) {
			node = rotateRight(node)
		}
		if isRed(node.left), isRed(node.right) {
			flipColors(node)
		}
		node.recalculate()
		return node
	}

	private static func rotateLeft(_ node: PieceTreeNode) -> PieceTreeNode {
		guard let right = node.right else {
			return node
		}
		node.right = right.left
		right.left = node
		right.color = node.color
		node.color = .red
		node.recalculate()
		right.recalculate()
		return right
	}

	private static func rotateRight(_ node: PieceTreeNode) -> PieceTreeNode {
		guard let left = node.left else {
			return node
		}
		node.left = left.right
		left.right = node
		left.color = node.color
		node.color = .red
		node.recalculate()
		left.recalculate()
		return left
	}

	private static func flipColors(_ node: PieceTreeNode) {
		node.color = node.color == .red ? .black : .red
		node.left?.color = node.left?.color == .red ? .black : .red
		node.right?.color = node.right?.color == .red ? .black : .red
	}

	private static func isRed(_ node: PieceTreeNode?) -> Bool {
		node?.color == .red
	}
}
