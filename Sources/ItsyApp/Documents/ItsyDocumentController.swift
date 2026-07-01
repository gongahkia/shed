import AppKit

final class ItsyDocumentController: NSDocumentController {
	override init() {
		super.init()
		ItsyTabCoordinator.install(documentController: self)
		ItsyWorkspaceController.install(documentController: self)
		ItsyProblemGutterCoordinator.install(documentController: self)
		ItsyGitHunkGutterCoordinator.install(documentController: self)
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		ItsyTabCoordinator.install(documentController: self)
		ItsyWorkspaceController.install(documentController: self)
		ItsyProblemGutterCoordinator.install(documentController: self)
		ItsyGitHunkGutterCoordinator.install(documentController: self)
	}

	override var defaultType: String? {
		"public.data"
	}

	@discardableResult
	func openDocument(at url: URL) -> Bool {
		openDocument(at: url, line: nil, column: nil)
	}

	@discardableResult
	func openDocument(at url: URL, line: Int?, column: Int?) -> Bool {
		let typeName = defaultType ?? "public.data"
		let document: ItsyDocument
		if let existing = self.document(for: url) as? ItsyDocument {
			document = existing
			showDocument(document)
			noteRecentDocumentIfNeeded(document)
		} else {
			do {
				guard let made = try makeDocument(withContentsOf: url, ofType: typeName) as? ItsyDocument else {
					return false
				}
				document = made
				addDocument(document)
				showDocument(document)
			} catch {
				NSLog("failed to open \(url.path): \(error)")
				return false
			}
		}
		if let line {
			document.jumpTo(line: line, column: column ?? 1)
		}
		return true
	}

	func showDocument(_ document: ItsyDocument) {
		if let controller = activeEditorWindowController() {
			controller.display(document: document)
		} else {
			document.makeWindowControllers()
			document.showWindows()
		}
	}

	override func addDocument(_ document: NSDocument) {
		super.addDocument(document)
		noteRecentDocumentIfNeeded(document)
		ItsyTabCoordinator.refresh()
	}

	override func removeDocument(_ document: NSDocument) {
		super.removeDocument(document)
		ItsyTabCoordinator.refresh()
	}

	override func makeUntitledDocument(ofType typeName: String) throws -> NSDocument {
		ItsyDocument()
	}

	override func makeDocument(withContentsOf url: URL, ofType typeName: String) throws -> NSDocument {
		try ItsyDocument(contentsOf: url, ofType: typeName)
	}

	private func noteRecentDocumentIfNeeded(_ document: NSDocument) {
		guard let url = document.fileURL else {
			return
		}
		noteNewRecentDocument(document)
		noteNewRecentDocumentURL(url)
	}

	private func activeEditorWindowController() -> EditorWindowController? {
		if let controller = NSApp.keyWindow?.windowController as? EditorWindowController {
			return controller
		}
		return documents.lazy.compactMap { document in
			(document as? ItsyDocument)?.windowControllers.first as? EditorWindowController
		}.first
	}
}
