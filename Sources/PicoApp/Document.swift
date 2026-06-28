import AppKit
import Foundation
import PicoEditor
import PicoRender

final class PicoDocumentController: NSDocumentController {
	override init() {
		super.init()
		PicoTabCoordinator.shared.install(documentController: self)
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		PicoTabCoordinator.shared.install(documentController: self)
	}

	override var defaultType: String? {
		"public.data"
	}

	override func addDocument(_ document: NSDocument) {
		super.addDocument(document)
		PicoTabCoordinator.shared.refresh()
	}

	override func removeDocument(_ document: NSDocument) {
		super.removeDocument(document)
		PicoTabCoordinator.shared.refresh()
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

	override func updateChangeCount(_ change: NSDocument.ChangeType) {
		super.updateChangeCount(change)
		PicoTabCoordinator.shared.refresh()
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
		view.editorDidChange = { [weak self] editor in
			self?.editor = editor
			self?.updateChangeCount(.changeDone)
		}
		view.saveRequested = { [weak self] in
			self?.save(nil)
		}
	}
}

final class EditorWindowController: NSWindowController {
	private let tabBarView = TabBarView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let editorView: MetalTextView

	init(document: PicoDocument) {
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		let contentView = NSStackView(frame: NSRect(x: 0, y: 0, width: 960, height: 672))
		contentView.orientation = .vertical
		contentView.alignment = .width
		contentView.distribution = .fill
		contentView.spacing = 0
		tabBarView.setContentHuggingPriority(.required, for: .vertical)
		editorView.setContentHuggingPriority(.defaultLow, for: .vertical)
		editorView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
		contentView.addArrangedSubview(tabBarView)
		contentView.addArrangedSubview(editorView)
		let window = NSWindow(
			contentRect: contentView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = document.fileURL?.lastPathComponent ?? "Untitled"
		window.contentView = contentView
		super.init(window: window)
		window.delegate = self
		document.attach(editorView)
		PicoTabCoordinator.shared.register(tabBarView)
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
		focusEditor()
		PicoTabCoordinator.shared.refresh()
	}

	func focusEditor() {
		window?.makeFirstResponder(editorView)
	}
}

extension EditorWindowController: NSWindowDelegate {
	func windowDidBecomeKey(_ notification: Notification) {
		PicoTabCoordinator.shared.refresh()
	}

	func windowDidBecomeMain(_ notification: Notification) {
		PicoTabCoordinator.shared.refresh()
	}

	func windowWillClose(_ notification: Notification) {
		PicoTabCoordinator.shared.refresh()
	}
}
