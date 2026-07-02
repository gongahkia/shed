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
