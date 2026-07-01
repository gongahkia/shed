@testable import ItsyEditor
import Foundation
import Testing

@Test func pieceTreeTracksLengthLinesAndBytes() {
	let tree = PieceTree("alpha\nbeta\ngamma")
	#expect(tree.length == "alpha\nbeta\ngamma".utf8.count)
	#expect(tree.lineCount == 3)
	#expect(tree.substring(0 ..< 5) == "alpha")
	#expect(tree.utf8Byte(at: 5) == 10)
	#expect(tree.line(forOffset: 0) == 0)
	#expect(tree.line(forOffset: 6) == 1)
	#expect(tree.offset(forLine: 2) == 11)
	#expect(tree.lineRange(1) == 6 ..< 10)
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
