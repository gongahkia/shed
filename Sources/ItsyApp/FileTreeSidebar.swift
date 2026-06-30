import AppKit
import CoreServices
import ItsyEditor

enum ItsyWorkspaceController {
	private static weak var documentController: ItsyDocumentController?
	private static let controllers = NSHashTable<EditorWindowController>.weakObjects()
	private static var rootURL: URL?
	private static var gitSnapshot: GitWorkspaceSnapshot?
	private static var workspaceIndex: WorkspaceIndex?
	private static var gitIgnoreMatcher: GitIgnoreMatcher?
	private static var indexGeneration = 0
	private static var indexWatcher: FSEventStreamRef?

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
			return
		}
		indexGeneration &+= 1
		let generation = indexGeneration
		DispatchQueue.global(qos: .utility).async {
			let matcher = GitIgnoreMatcher(root: root)
			let index = WorkspaceIndexer.build(root: root)
			DispatchQueue.main.async {
				guard generation == indexGeneration else {
					return
				}
				workspaceIndex = index
				gitIgnoreMatcher = matcher
			}
		}
	}

	private static func startIndexWatcher() {
		stopIndexWatcher()
		guard let root = rootURL else {
			return
		}
		let paths = [root.path] as CFArray
		var context = FSEventStreamContext(version: 0, info: nil, retain: nil, release: nil, copyDescription: nil)
		let callback: FSEventStreamCallback = { _, _, _, paths, _, _ in
			let cfPaths = unsafeBitCast(paths, to: CFArray.self)
			var urls: [URL] = []
			for index in 0 ..< CFArrayGetCount(cfPaths) {
				let pointer = CFArrayGetValueAtIndex(cfPaths, index)
				let cfString = unsafeBitCast(pointer, to: CFString.self)
				urls.append(URL(fileURLWithPath: cfString as String))
			}
			DispatchQueue.main.async {
				ItsyWorkspaceController.applyIndexFileChanges(urls)
			}
		}
		guard let stream = FSEventStreamCreate(
			kCFAllocatorDefault,
			callback,
			&context,
			paths,
			FSEventStreamEventId(kFSEventStreamEventIdSinceNow),
			0.2,
			UInt32(kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer)
		) else {
			return
		}
		FSEventStreamSetDispatchQueue(stream, DispatchQueue.main)
		FSEventStreamStart(stream)
		indexWatcher = stream
	}

	private static func stopIndexWatcher() {
		guard let stream = indexWatcher else {
			return
		}
		FSEventStreamStop(stream)
		FSEventStreamInvalidate(stream)
		FSEventStreamRelease(stream)
		indexWatcher = nil
	}

	fileprivate static func applyIndexFileChanges(_ urls: [URL]) {
		guard var index = workspaceIndex, let matcher = gitIgnoreMatcher else {
			return
		}
		WorkspaceIndexer.reindex(&index, changedURLs: urls, matcher: matcher)
		workspaceIndex = index
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
