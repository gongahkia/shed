// @file workspace file-tree sidebar and file event watching.
import AppKit
import ItsyEditor
import OSLog

private let workspaceLogger = Logger(
	subsystem: Bundle.main.bundleIdentifier ?? "dev.itsy.editor",
	category: "Workspace"
)

enum ItsyWorkspaceController {
	private static weak var documentController: ItsyDocumentController?
	private static let controllers = NSHashTable<EditorWindowController>.weakObjects()
	private static var rootURL: URL?
	private static var gitSnapshot: GitWorkspaceSnapshot?
	private static var workspaceIndex: WorkspaceIndex?
	private static var gitIgnoreMatcher: GitIgnoreMatcher?
	private static var indexGeneration = 0
	private static var indexWatcher: WorkspaceFSEventStream?

	static var currentRootURL: URL? {
		rootURL
	}

	static var currentWorkspaceIndex: WorkspaceIndex? {
		workspaceIndex
	}

	static func searchWorkspaceSymbols(query: String, limit: Int = 50) -> [WorkspaceSymbol] {
		workspaceIndex?.searchSymbols(query: query, limit: limit) ?? []
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
		workspaceLogger.info("Opening workspace: \(url.lastPathComponent, privacy: .public)")
		rootURL = url
		loadGitStatus()
		for controller in controllers.allObjects {
			controller.setWorkspaceRootURL(url)
			controller.setGitSnapshot(gitSnapshot)
		}
		rebuildWorkspaceIndex()
		startIndexWatcher()
	}

	static func rebuildWorkspaceIndex() {
		guard let root = rootURL else {
			workspaceIndex = nil
			gitIgnoreMatcher = nil
			broadcastIndexingStatus(nil)
			return
		}
		indexGeneration &+= 1
		let generation = indexGeneration
		let store = WorkspaceIndexStore()
		workspaceIndex = try? store.load(for: root)
		gitIgnoreMatcher = nil
		broadcastIndexingStatus(L10n.string("Indexing…"))
		DispatchQueue.global(qos: .utility).async {
			let matcher = GitIgnoreMatcher(root: root)
			let index = WorkspaceIndexer.build(root: root) { processed, total in
				DispatchQueue.main.async {
					guard generation == indexGeneration, total > 0 else {
						return
					}
					broadcastIndexingStatus(L10n.string("Indexing \(processed)/\(total)"))
				}
			}
			DispatchQueue.main.async {
				guard generation == indexGeneration else {
					return
				}
				workspaceIndex = index
				gitIgnoreMatcher = matcher
				broadcastIndexingStatus(nil)
				persistWorkspaceIndex(index)
			}
		}
	}

	private static func broadcastIndexingStatus(_ status: String?) {
		for controller in controllers.allObjects {
			controller.setIndexingStatus(status)
		}
	}

	private static func startIndexWatcher() {
		stopIndexWatcher()
		guard let root = rootURL else {
			return
		}
		let watcher = WorkspaceFSEventStream(root: root) { batch in
			workspaceLogger.debug("Workspace index event count: \(batch.events.count, privacy: .public)")
			DispatchQueue.main.async {
				ItsyWorkspaceController.applyIndexFileChanges(batch)
			}
		}
		guard watcher.start() else {
			workspaceLogger.error("Failed to start workspace index watcher")
			return
		}
		indexWatcher = watcher
		workspaceLogger.info("Started workspace index watcher")
	}

	private static func stopIndexWatcher() {
		indexWatcher?.stop()
		indexWatcher = nil
	}

	fileprivate static func applyIndexFileChanges(_ batch: WorkspaceFileEventBatch) {
		if batch.requiresFullRescan {
			rebuildWorkspaceIndex()
			return
		}
		guard var index = workspaceIndex, let matcher = gitIgnoreMatcher else {
			return
		}
		let urls = batch.events.map(\.url)
		WorkspaceIndexer.reindex(&index, changedURLs: urls, matcher: matcher)
		workspaceIndex = index
		persistWorkspaceIndex(index)
	}

	private static func persistWorkspaceIndex(_ index: WorkspaceIndex) {
		DispatchQueue.global(qos: .utility).async {
			try? WorkspaceIndexStore().save(index)
		}
	}

	static func openFile(at url: URL) -> Bool {
		workspaceLogger.info("Opening workspace file: \(url.lastPathComponent, privacy: .public)")
		let didOpen = documentController?.openDocument(at: url) ?? false
		if !didOpen {
			workspaceLogger.error("Failed to open workspace file: \(url.lastPathComponent, privacy: .public)")
		}
		return didOpen
	}

	static func refreshGitStatus() {
		loadGitStatus()
		for controller in controllers.allObjects {
			controller.setGitSnapshot(gitSnapshot)
		}
		ItsyGitHunkGutterCoordinator.applyAll()
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
