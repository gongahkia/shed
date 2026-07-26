// @file workspace file-tree sidebar and file event watching.
import AppKit
import CoreServices
import ItsyEditor
import ItsySyntax
import OSLog

private let workspaceLogger = Logger(
	subsystem: Bundle.main.bundleIdentifier ?? "dev.itsy.editor",
	category: "Workspace"
)

@MainActor enum ItsyWorkspaceController {
	private static weak var documentController: ItsyDocumentController?
	private static let controllers = NSHashTable<EditorWindowController>.weakObjects()
	private static let workspaceStateStore = WorkspaceStateStore()
	private static var rootURLs: [URL] = []
	private static var gitSnapshot: GitWorkspaceSnapshot?
	private static let gitStatusRefreshCoordinator = GitStatusRefreshCoordinator()
	private static var gitStatusRefreshTask: Task<Void, Never>?
	private static var workspaceIndex: WorkspaceIndex?
	private static var gitIgnoreMatcher: GitIgnoreMatcher?
	private static var indexGeneration = 0
	private static var indexWatchers: [WorkspaceFSEventStream] = []
	private nonisolated static let symbolProvider: WorkspaceSymbolProvider = { text, url, relativePath in
		TreeSitterSymbolExtractor.workspaceSymbols(in: text, fileURL: url, relativePath: relativePath)
	}

	static var currentRootURL: URL? {
		rootURLs.first
	}

	static var currentWorkspaceRoots: [URL] {
		rootURLs
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
		controller.setWorkspaceRootURLs(rootURLs)
		controller.setGitSnapshot(gitSnapshot)
	}

	static func lspConfigurationDidReload() {
		for controller in controllers.allObjects {
			controller.lspConfigurationDidReload()
		}
	}

	static func lspDocumentDidClose(_ url: URL) {
		for controller in controllers.allObjects {
			controller.lspDocumentDidClose(url)
		}
	}

	static func lspDocumentDidReload(_ url: URL, content: String) {
		for controller in controllers.allObjects {
			controller.lspDocumentDidReload(url, content: content)
		}
	}

	static func openWorkspace(at url: URL) {
		let root = url.standardizedFileURL
		workspaceLogger.info("Opening workspace: \(root.lastPathComponent, privacy: .public)")
		let descriptorRoots = workspaceStateStore.loadDescriptor(for: root)?.roots.map { URL(fileURLWithPath: $0).standardizedFileURL } ?? [root]
		rootURLs = normalizedRoots([root] + descriptorRoots)
		ItsyProblemsBridge.resetProblems(root: root)
		persistWorkspaceDescriptor()
		loadGitStatus()
		refreshWorkspaceControllers()
		rebuildWorkspaceIndex()
		startIndexWatcher()
	}

	static func addWorkspaceRoot(_ url: URL) {
		let root = url.standardizedFileURL
		guard currentRootURL != nil else {
			openWorkspace(at: root)
			return
		}
		guard !rootURLs.contains(where: { $0.standardizedFileURL.path == root.path }) else {
			return
		}
		rootURLs.append(root)
		persistWorkspaceDescriptor()
		refreshWorkspaceControllers()
		startIndexWatcher()
	}

	static func rebuildWorkspaceIndex() {
		guard let root = currentRootURL else {
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
			let index = WorkspaceIndexer.build(root: root, symbolProvider: symbolProvider) { processed, total in
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
		for root in rootURLs {
			let watcher = WorkspaceFSEventStream(root: root) { batch in
				workspaceLogger.debug("Workspace index event count: \(batch.events.count, privacy: .public)")
				DispatchQueue.main.async {
					ItsyWorkspaceController.applyIndexFileChanges(batch)
				}
			}
			guard watcher.start() else {
				workspaceLogger.error("Failed to start workspace index watcher")
				continue
			}
			indexWatchers.append(watcher)
		}
		if !indexWatchers.isEmpty {
			workspaceLogger.info("Started workspace index watcher")
		}
	}

	private static func stopIndexWatcher() {
		indexWatchers.forEach { $0.stop() }
		indexWatchers.removeAll(keepingCapacity: true)
	}

	fileprivate static func applyIndexFileChanges(_ batch: WorkspaceFileEventBatch) {
		refreshGitStatus()
		for controller in controllers.allObjects {
			controller.notifyLSPWatchedFiles(batch)
		}
		if batch.requiresFullRescan {
			rebuildWorkspaceIndex()
			return
		}
		guard var index = workspaceIndex, let matcher = gitIgnoreMatcher else {
			return
		}
		let urls = batch.events.map(\.url)
		WorkspaceIndexer.reindex(&index, changedURLs: urls, matcher: matcher, symbolProvider: symbolProvider)
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
		gitStatusRefreshTask?.cancel()
		guard let root = currentRootURL else {
			gitSnapshot = nil
			for controller in controllers.allObjects {
				controller.setGitSnapshot(nil)
			}
			return
		}
		gitStatusRefreshTask = Task { [root] in
			guard let result = await gitStatusRefreshCoordinator.refresh(root: root), !Task.isCancelled else {
				return
			}
			switch result {
			case let .snapshot(snapshot):
				gitSnapshot = snapshot
			case .failure:
				gitSnapshot = nil
			}
			for controller in controllers.allObjects {
				controller.setGitSnapshot(gitSnapshot)
			}
			ItsyGitHunkGutterCoordinator.applyAll()
		}
	}

	static func revealInFileTree(_ url: URL) {
		let url = url.standardizedFileURL
		if !rootURLs.contains(where: { pathIsInside(url.standardizedFileURL.path, root: $0.standardizedFileURL.path) }) {
			addWorkspaceRoot(url)
		}
		for controller in controllers.allObjects {
			controller.revealInFileTree(url)
		}
	}

	private static func loadGitStatus() {
		refreshGitStatus()
	}

	@discardableResult
	static func createFile(named name: String, in directory: URL) -> URL? {
		performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).createFile(named: name, in: directory)
		}
	}

	@discardableResult
	static func createFolder(named name: String, in directory: URL) -> URL? {
		performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).createFolder(named: name, in: directory)
		}
	}

	@discardableResult
	static func renameItem(_ url: URL, to name: String) -> URL? {
		performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).rename(url, to: name)
		}
	}

	@discardableResult
	static func duplicateItem(_ url: URL) -> URL? {
		performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).duplicate(url)
		}
	}

	static func deleteItem(_ url: URL) {
		_ = performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).delete(url)
			return url
		}
	}

	static func moveItemToTrash(_ url: URL) {
		_ = performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).moveToTrash(url)
		}
	}

	@discardableResult
	static func moveItem(_ url: URL, toDirectory directory: URL) -> URL? {
		performFileOperation {
			try WorkspaceFileOperations(roots: rootURLs).move(url, toDirectory: directory)
		}
	}

	static func loadWindowState() -> WorkspaceWindowState? {
		currentRootURL.flatMap { workspaceStateStore.loadWindowState(for: $0) }
	}

	static func saveWindowState(_ state: WorkspaceWindowState) {
		guard let root = currentRootURL else {
			return
		}
		try? workspaceStateStore.saveWindowState(state, for: root)
	}

	static func persistWindowState(from controller: EditorWindowController?) {
		guard currentRootURL != nil else {
			return
		}
		let files = (documentController?.documents ?? []).compactMap { document in
			(document as? ItsyDocument)?.workspaceWindowFileState()
		}
		let state = WorkspaceWindowState(
			paneLayout: controller?.paneLayoutEncoded ?? "L",
			selectedPath: (controller?.document as? ItsyDocument)?.fileURL?.standardizedFileURL.path,
			openFiles: files,
			paneStates: controller?.workspacePaneStates,
			focusedPaneIndex: controller?.workspaceFocusedPaneIndex,
			workbenchDividers: controller?.workspaceWorkbenchDividerState,
			workbenchComponents: controller?.workspaceWorkbenchComponentState
		)
		saveWindowState(state)
	}

	private static func performFileOperation(_ operation: () throws -> URL) -> URL? {
		do {
			let url = try operation()
			finishFileOperation(changedURLs: [url])
			return url
		} catch {
			NSAlert(error: error).runModal()
			return nil
		}
	}

	private static func finishFileOperation(changedURLs: [URL]) {
		refreshWorkspaceControllers()
		refreshGitStatus()
		let events = changedURLs.map {
			WorkspaceFileEvent(url: $0, flags: FSEventStreamEventFlags(kFSEventStreamEventFlagItemModified), eventID: 0)
		}
		let batch = WorkspaceFileEventBatch(events: events)
		for controller in controllers.allObjects {
			controller.notifyLSPWatchedFiles(batch)
		}
		rebuildWorkspaceIndex()
	}

	private static func refreshWorkspaceControllers() {
		for controller in controllers.allObjects {
			controller.setWorkspaceRootURLs(rootURLs)
			controller.setGitSnapshot(gitSnapshot)
		}
	}

	private static func persistWorkspaceDescriptor() {
		guard let root = currentRootURL else {
			return
		}
		try? workspaceStateStore.saveDescriptor(
			WorkspaceDescriptor(roots: rootURLs.map { $0.standardizedFileURL.path }),
			for: root
		)
	}

	private static func normalizedRoots(_ roots: [URL]) -> [URL] {
		var seen = Set<String>()
		var normalized: [URL] = []
		for root in roots {
			let url = root.standardizedFileURL
			var isDirectory: ObjCBool = false
			guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory),
			      isDirectory.boolValue,
			      seen.insert(url.path).inserted
			else {
				continue
			}
			normalized.append(url)
		}
		return normalized
	}

	private static func pathIsInside(_ path: String, root: String) -> Bool {
		path == root || path.hasPrefix(root + "/")
	}
}
