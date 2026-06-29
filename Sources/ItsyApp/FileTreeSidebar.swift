import AppKit
import ItsyEditor

enum ItsyWorkspaceController {
	private static weak var documentController: ItsyDocumentController?
	private static let controllers = NSHashTable<EditorWindowController>.weakObjects()
	private static var rootURL: URL?
	private static var gitSnapshot: GitWorkspaceSnapshot?

	static var currentRootURL: URL? {
		rootURL
	}

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	static func register(_ controller: EditorWindowController) {
		controllers.add(controller)
		controller.setWorkspaceRootURL(rootURL)
		controller.setGitSnapshot(gitSnapshot)
	}

	static func openWorkspace(at url: URL) {
		rootURL = url
		loadGitStatus()
		for controller in controllers.allObjects {
			controller.setWorkspaceRootURL(url)
			controller.setGitSnapshot(gitSnapshot)
		}
	}

	static func openFile(at url: URL) -> Bool {
		documentController?.openDocument(at: url) ?? false
	}

	static func refreshGitStatus() {
		loadGitStatus()
		for controller in controllers.allObjects {
			controller.setGitSnapshot(gitSnapshot)
		}
	}

	private static func loadGitStatus() {
		guard let rootURL,
		      let gitRoot = try? GitRepository.discoverRoot(containing: rootURL)
		else {
			gitSnapshot = nil
			return
		}
		gitSnapshot = try? GitRepository(root: gitRoot).snapshot()
	}
}
