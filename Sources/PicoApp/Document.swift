import AppKit
import Foundation
import PicoEditor
import PicoRender

final class PicoDocumentController: NSDocumentController {
	override init() {
		super.init()
		PicoTabCoordinator.shared.install(documentController: self)
		PicoWorkspaceController.shared.install(documentController: self)
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		PicoTabCoordinator.shared.install(documentController: self)
		PicoWorkspaceController.shared.install(documentController: self)
	}

	override var defaultType: String? {
		"public.data"
	}

	@discardableResult
	func openDocument(at url: URL) -> Bool {
		let typeName = defaultType ?? "public.data"
		if let document = document(for: url) {
			if document.windowControllers.isEmpty {
				document.makeWindowControllers()
			}
			document.showWindows()
			noteRecentDocumentIfNeeded(document)
			return true
		}
		do {
			let document = try makeDocument(withContentsOf: url, ofType: typeName)
			addDocument(document)
			document.makeWindowControllers()
			document.showWindows()
			return true
		} catch {
			NSLog("failed to open \(url.path): \(error)")
			return false
		}
	}

	override func addDocument(_ document: NSDocument) {
		super.addDocument(document)
		noteRecentDocumentIfNeeded(document)
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

	private func noteRecentDocumentIfNeeded(_ document: NSDocument) {
		guard let url = document.fileURL else {
			return
		}
		noteNewRecentDocument(document)
		noteNewRecentDocumentURL(url)
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
		view.closeRequested = { [weak self] in
			self?.close()
		}
	}
}

final class EditorWindowController: NSWindowController {
	private let fileTreeView = FileTreeSidebarView(frame: NSRect(x: 0, y: 0, width: 240, height: 672))
	private let tabBarView = TabBarView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let editorView: MetalTextView

	init(document: PicoDocument) {
		editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		let editorStack = NSStackView(frame: NSRect(x: 240, y: 0, width: 960, height: 672))
		editorStack.orientation = .vertical
		editorStack.alignment = .width
		editorStack.distribution = .fill
		editorStack.spacing = 0
		tabBarView.setContentHuggingPriority(.required, for: .vertical)
		editorView.setContentHuggingPriority(.defaultLow, for: .vertical)
		editorView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
		editorStack.addArrangedSubview(tabBarView)
		editorStack.addArrangedSubview(editorView)

		let splitView = NSSplitView(frame: NSRect(x: 0, y: 0, width: 1200, height: 672))
		splitView.isVertical = true
		splitView.dividerStyle = .thin
		splitView.autoresizingMask = [.width, .height]
		fileTreeView.translatesAutoresizingMaskIntoConstraints = false
		editorStack.translatesAutoresizingMaskIntoConstraints = false
		splitView.addArrangedSubview(fileTreeView)
		splitView.addArrangedSubview(editorStack)
		fileTreeView.widthAnchor.constraint(equalToConstant: 240).isActive = true
		let window = NSWindow(
			contentRect: splitView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = document.fileURL?.lastPathComponent ?? "Untitled"
		window.contentView = splitView
		super.init(window: window)
		window.delegate = self
		document.attach(editorView)
		PicoWorkspaceController.shared.register(fileTreeView)
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
