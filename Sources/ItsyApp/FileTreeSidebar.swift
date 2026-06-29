import AppKit

enum ItsyWorkspaceController {
	private static weak var documentController: ItsyDocumentController?
	private static let controllers = NSHashTable<EditorWindowController>.weakObjects()
	private static var rootURL: URL?

	static var currentRootURL: URL? {
		rootURL
	}

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	static func register(_ controller: EditorWindowController) {
		controllers.add(controller)
		controller.setWorkspaceRootURL(rootURL)
	}

	static func openWorkspace(at url: URL) {
		rootURL = url
		for controller in controllers.allObjects {
			controller.setWorkspaceRootURL(url)
		}
	}

	static func openFile(at url: URL) -> Bool {
		documentController?.openDocument(at: url) ?? false
	}
}
