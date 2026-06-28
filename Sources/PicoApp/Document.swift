import AppKit
import Foundation
import PicoEditor
import PicoRender

final class PicoDocumentController: NSDocumentController {
	override var defaultType: String? {
		"public.data"
	}

	override func makeUntitledDocument(ofType typeName: String) throws -> NSDocument {
		PicoDocument()
	}

	override func makeDocument(withContentsOf url: URL, ofType typeName: String) throws -> NSDocument {
		try PicoDocument(contentsOf: url, ofType: typeName)
	}
}

final class PicoDocument: NSDocument {
	var editor = Editor()
	private weak var editorView: MetalTextView?

	override init() {
		super.init()
	}

	override class var autosavesInPlace: Bool {
		false
	}

	override func read(from data: Data, ofType typeName: String) throws {
		guard let text = String(data: data, encoding: .utf8) else {
			throw CocoaError(.fileReadCorruptFile)
		}
		editor = Editor(text: text)
		editorView?.editor = editor
	}

	override func data(ofType typeName: String) throws -> Data {
		if let editorView {
			editor = editorView.editor
		}
		return Data(editor.text.utf8)
	}

	override func makeWindowControllers() {
		let controller = EditorWindowController(document: self)
		addWindowController(controller)
	}

	func attach(_ view: MetalTextView) {
		editorView = view
		view.editor = editor
	}
}

final class EditorWindowController: NSWindowController {
	private let editorView: MetalTextView

	init(document: PicoDocument) {
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		let window = NSWindow(
			contentRect: editorView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = document.fileURL?.lastPathComponent ?? "Untitled"
		window.contentView = editorView
		super.init(window: window)
		document.attach(editorView)
		window.makeFirstResponder(editorView)
	}

	required init?(coder: NSCoder) {
		nil
	}

	override func windowDidLoad() {
		super.windowDidLoad()
		window?.center()
	}

	override func showWindow(_ sender: Any?) {
		super.showWindow(sender)
		window?.makeKeyAndOrderFront(sender)
		window?.orderFrontRegardless()
		window?.makeFirstResponder(editorView)
	}
}
