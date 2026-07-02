@testable import ItsyApp
import AppKit
import Foundation
import ItsyEditor
import Testing

@MainActor
@Test func pieceTreeDocumentWriteBypassesDataSerialization() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	var editor = Editor(text: "alpha\ngamma", storage: .pieceTree)
	editor.setSelection(SelectionSet(primary: Selection(anchor: 6, head: 6)))
	editor.insert("beta\n")

	let document = ItsyDocument()
	document.editor = editor

	let url = directory.appendingPathComponent("saved.txt")
	try document.write(
		to: url,
		ofType: "public.plain-text",
		for: .saveOperation,
		originalContentsURL: nil
	)

	#expect(try Data(contentsOf: url) == Data("alpha\nbeta\ngamma".utf8))
	#expect(throws: CocoaError.self) {
		try document.data(ofType: "public.plain-text")
	}
}

@MainActor
@Test func documentReadsLargeFilesThroughMappedPieceTree() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent(UUID().uuidString)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	let line = Array("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n".utf8)
	let bytes = Array(repeating: line, count: 13_000).flatMap { $0 }
	let sourceURL = directory.appendingPathComponent("large.log")
	try Data(bytes).write(to: sourceURL)

	let document = ItsyDocument()
	try document.read(from: sourceURL, ofType: "public.plain-text")

	#expect(document.editor.textStorage.kind == .pieceTree)
	#expect(document.editor.textStorage.length == bytes.count)
	#expect(document.editor.textStorage.lineCount == 13_001)

	let savedURL = directory.appendingPathComponent("saved.log")
	try document.write(
		to: savedURL,
		ofType: "public.plain-text",
		for: .saveOperation,
		originalContentsURL: sourceURL
	)
	#expect(try Data(contentsOf: savedURL) == Data(bytes))
}

@Test func documentSyntaxRefreshParsesPieceTreeStorage() throws {
	let controller = DocumentSyntaxController()
	var spanCount = 0
	controller.setHighlightSpans = { spans in
		spanCount = spans.count
	}
	controller.configure(fileURL: URL(fileURLWithPath: "/tmp/sample.ts"))
	controller.refresh(editor: Editor(text: "const value = \"ok\";\n", storage: .pieceTree))
	#expect(spanCount > 0)
}
