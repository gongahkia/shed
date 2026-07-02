@testable import ItsyEditor
import CryptoKit
import Foundation
import Testing

@Test func pieceTreeTracksLengthLinesAndBytes() {
	let tree = PieceTree("alpha\nbeta\ngamma")
	#expect(tree.length == "alpha\nbeta\ngamma".utf8.count)
	#expect(tree.lineCount == 3)
	#expect(tree.graphemeCount == tree.length)
	#expect(tree.substring(0 ..< 5) == "alpha")
	#expect(tree.utf8Byte(at: 5) == 10)
	#expect(tree.line(forOffset: 0) == 0)
	#expect(tree.line(forOffset: 6) == 1)
	#expect(tree.offset(forLine: 2) == 11)
	#expect(tree.lineRange(1) == 6 ..< 10)
}

@Test func pieceTreeTracksGraphemeSummariesByUTF8Offset() {
	let text = "aé🇸🇬e\u{301}"
	var tree = PieceTree(text)
	tree.insert("👋🏽", at: tree.length)
	let expected = text + "👋🏽"
	#expect(tree.graphemeCount == expected.count)
	var offset = 0
	for (index, character) in expected.enumerated() {
		offset += String(character).utf8.count
		#expect(tree.graphemeIndex(forOffset: offset) == index + 1)
	}
	#expect(tree.graphemeIndex(forOffset: 1) == 1)
}

@Test func pieceTreeUsesUAX29CRLFGraphemeBoundary() {
	let tree = PieceTree("a\r\nb")
	#expect(tree.graphemeCount == 3)
	#expect(tree.graphemeIndex(forOffset: 1) == 1)
	#expect(tree.graphemeIndex(forOffset: 2) == 1)
	#expect(tree.graphemeIndex(forOffset: 3) == 2)
}

@Test func pieceTreeInsertSplitsOriginalAndAddsBufferPiece() {
	var tree = PieceTree("hello\nworld")
	tree.insert(" tiny", at: 5)
	#expect(tree.substring(0 ..< tree.length) == "hello tiny\nworld")
	#expect(tree.lineCount == 2)
	#expect(tree.offset(forLine: 1) == 11)
	#expect(tree.debugPieces().map(\.buffer) == [.original(0), .add(0), .original(0)])
}

@Test func pieceTreeRemoveAcrossPiecesPreservesLineLookups() {
	var tree = PieceTree("ab\ncd\nef")
	tree.insert("XX\n", at: 5)
	#expect(tree.substring(0 ..< tree.length) == "ab\ncdXX\n\nef")
	tree.remove(2 ..< 8)
	#expect(tree.substring(0 ..< tree.length) == "ab\nef")
	#expect(tree.lineCount == 2)
	#expect(tree.line(forOffset: 3) == 1)
	#expect(tree.offset(forLine: 1) == 3)
}

@Test func pieceTreeRemoveCoalescesOriginalNeighbors() {
	var tree = PieceTree("abcdef")
	tree.insert("XX", at: 3)
	tree.remove(3 ..< 5)
	#expect(tree.substring(0 ..< tree.length) == "abcdef")
	#expect(tree.debugPieces() == [
		PieceTree.Piece(buffer: .original(0), start: 0, length: 6, lineFeeds: 0),
	])
}

@Test func pieceTreeIteratesContiguousByteSpansFromOffset() {
	var tree = PieceTree("abc")
	tree.insert("DEF", at: 1)
	var chunks: [String] = []
	tree.iterateBytes(from: 1) { buffer in
		chunks.append(String(decoding: Array(buffer), as: UTF8.self))
		return true
	}
	#expect(chunks == ["DEF", "bc"])
}

@Test func pieceTreeCopiesUTF8AcrossPieces() {
	var tree = PieceTree("abé🇸🇬tail")
	tree.insert("XYZ", at: 2)
	var buffer = [UInt8](repeating: 0, count: 32)
	let copied = buffer.withUnsafeMutableBufferPointer {
		tree.copyUTF8(at: 1, into: $0)
	}
	let text = String(decoding: buffer.prefix(copied), as: UTF8.self)
	#expect(text == "bXYZé🇸🇬tail")
	#expect(copied == tree.length - 1)
}

@Test func pieceTreeCopyUTF8StopsAtScalarBoundary() {
	var tree = PieceTree("abé🇸🇬tail")
	tree.insert("XYZ", at: 2)
	var buffer = [UInt8](repeating: 0, count: 9)
	let copied = buffer.withUnsafeMutableBufferPointer {
		tree.copyUTF8(at: 1, into: $0)
	}
	let text = String(decoding: buffer.prefix(copied), as: UTF8.self)
	#expect(text == "bXYZé")
	#expect(copied == 6)
}

@Test func pieceTreeInitializesFromMappedFileAsSingleOriginalPiece() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent("itsy-piecetree-map-\(UUID().uuidString)", isDirectory: true)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	let url = directory.appendingPathComponent("sample.txt")
	try Data("alpha\nbeta\ngamma".utf8).write(to: url)
	let tree = try PieceTree(readingMappedFile: url)
	#expect(tree.length == "alpha\nbeta\ngamma".utf8.count)
	#expect(tree.lineCount == 3)
	#expect(tree.substring(6 ..< 10) == "beta")
	#expect(tree.offset(forLine: 2) == 11)
	#expect(tree.debugPieces() == [
		PieceTree.Piece(buffer: .original(0), start: 0, length: tree.length, lineFeeds: 2),
	])
}

@Test func pieceTreeSaveToWritesHundredKPiecesWithMatchingSHA256() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent("itsy-piecetree-save-\(UUID().uuidString)", isDirectory: true)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	var tree = PieceTree()
	var oracle: [UInt8] = []
	oracle.reserveCapacity(100_000)
	for index in 0 ..< 100_000 {
		let byte = UInt8(33 + index % 90)
		tree.insert([byte], at: tree.length)
		oracle.append(byte)
	}
	let url = directory.appendingPathComponent("saved.txt")
	try tree.saveTo(url: url)
	let saved = try Data(contentsOf: url)
	#expect(saved.count == oracle.count)
	#expect(SHA256.hash(data: saved) == SHA256.hash(data: Data(oracle)))
}

@Test func pieceTreeRandomInsertsMatchByteOracle() {
	var rng = PieceTreeTestRNG(0x51A7E)
	var tree = PieceTree()
	var oracle: [UInt8] = []
	for _ in 0 ..< 100_000 {
		let byte: UInt8 = rng.nextInt(17) == 0 ? 10 : UInt8(97 + rng.nextInt(26))
		let offset = rng.nextInt(oracle.count + 1)
		tree.insert([byte], at: offset)
		oracle.insert(byte, at: offset)
	}
	#expect(tree.length == oracle.count)
	#expect(tree.lineCount == oracle.reduce(1) { $1 == 10 ? $0 + 1 : $0 })
	#expect(tree.graphemeCount == oracle.count)
	#expect(Array(tree.substring(0 ..< tree.length).utf8) == oracle)
}

@Test func pieceTreeRandomInsertDeletePairsMatchByteOracle() {
	var rng = PieceTreeTestRNG(0xBEEFED)
	var tree = PieceTree()
	var oracle: [UInt8] = []
	for _ in 0 ..< 100_000 {
		let byte: UInt8 = rng.nextInt(19) == 0 ? 10 : UInt8(65 + rng.nextInt(26))
		let insertOffset = rng.nextInt(oracle.count + 1)
		tree.insert([byte], at: insertOffset)
		oracle.insert(byte, at: insertOffset)
		if oracle.count > 256 || rng.nextInt(3) == 0 {
			let lower = rng.nextInt(oracle.count)
			let length = min(1 + rng.nextInt(8), oracle.count - lower)
			tree.remove(lower ..< lower + length)
			oracle.removeSubrange(lower ..< lower + length)
		}
	}
	#expect(tree.length == oracle.count)
	#expect(tree.lineCount == oracle.reduce(1) { $1 == 10 ? $0 + 1 : $0 })
	#expect(tree.graphemeCount == oracle.count)
	#expect(Array(tree.substring(0 ..< tree.length).utf8) == oracle)
}

private struct PieceTreeTestRNG {
	private var state: UInt64

	init(_ seed: UInt64) {
		state = seed
	}

	mutating func nextInt(_ upperBound: Int) -> Int {
		state = state &* 6364136223846793005 &+ 1442695040888963407
		return Int(state % UInt64(upperBound))
	}
}
