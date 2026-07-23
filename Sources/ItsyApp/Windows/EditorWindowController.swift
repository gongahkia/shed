import AppKit
import CoreServices
import Dispatch
import Foundation
import ItsyConfig
import ItsyEditor
import ItsyLSP
import ItsyRender
import ItsySyntax

private struct LSPStatusEntry {
	var key: LSPSessionKey
	var status: String
	var health: LSPHealthState
	var server: String
	var pid: Int32?
	var startDate: Date?
	var lastError: String
	var output: [LSPSessionOutput]
	var url: URL?
	var client: LSPProcessClient?
}

private enum LSPWorkspaceEditFileError: Error {
	case invalidURI(String)
	case nonUTF8(URL)
}

private struct LSPSemanticSurfaceCapabilities {
	var semanticTokens: LSPSemanticTokensOptions?
	var inlayHint: Bool
	var foldingRange: Bool
	var documentHighlight: Bool
}

struct LSPFormattingCapabilities {
	var document: Bool
	var range: Bool

	func requestError(for operation: LSPFormattingOperation) -> LSPFormattingRequestError? {
		switch operation {
		case .document:
			document ? nil : .documentFormattingDisabled
		case .range:
			range ? nil : .rangeFormattingDisabled
		}
	}
}

enum LSPFormattingOperation {
	case document
	case range
}

enum LSPFormattingRequestError: Error, Equatable {
	case documentFormattingDisabled
	case rangeFormattingDisabled
}

struct LSPSemanticTokenState: Equatable {
	var resultId: String?
	var data: [Int]
}

struct LSPSemanticTokenCache {
	private var states: [String: LSPSemanticTokenState] = [:]

	func state(for uri: String) -> LSPSemanticTokenState? {
		states[uri]
	}

	mutating func invalidate(_ uri: String) {
		states[uri] = nil
	}

	@discardableResult mutating func replace(
		_ state: LSPSemanticTokenState?,
		for uri: String,
		generation: Int,
		currentGeneration: Int
	) -> Bool {
		guard generation == currentGeneration else {
			return false
		}
		states[uri] = state
		return true
	}
}

private struct LSPSemanticHighlightResult {
	var spans: [TextHighlightSpan]
	var tokenState: LSPSemanticTokenState?
}

private enum LSPNavigationOperation {
	case definition
	case declaration
	case typeDefinition
	case implementation
}

private final class LSPFoldGutterDecorator: GutterDecorator {
	var ranges: [LSPFoldingRange] = []
	var collapsedStartLines: Set<Int> = []
	var toggleFold: ((Int) -> Void)?

	func gutterMarkers(in lineRange: Range<Int>, for _: MetalTextView) -> [GutterMarker] {
		ranges.compactMap { range in
			guard range.endLine > range.startLine, lineRange.contains(range.startLine) else {
				return nil
			}
			let collapsed = collapsedStartLines.contains(range.startLine)
			return GutterMarker(
				id: "fold-\(range.startLine)-\(range.endLine)",
				line: range.startLine,
				severity: .hint,
				message: collapsed ? "folded" : "fold",
				color: SIMD4<Float>(0.54, 0.57, 0.62, 1.0),
				shape: collapsed ? .foldClosed : .foldOpen
			)
		}
	}

	func gutterMarkerClicked(_ marker: GutterMarker, in _: MetalTextView) {
		guard marker.id.hasPrefix("fold-") else {
			return
		}
		toggleFold?(marker.line)
	}

	func gutterPopoverViewController(for _: GutterMarker, in _: MetalTextView) -> NSViewController? {
		nil
	}
}

@MainActor final class EditorWindowController: NSWindowController {
	private static let paneLayoutStateKey = "dev.itsy.editor.paneLayout"
	private static let lspManager = LSPManager(registry: LSPServerRegistryLoader.loadOrBundled())
	private static let snippetLanguageRegistry = LSPServerRegistryLoader.loadOrBundled()
	private static var dismissedLSPMissingCommands: Set<String> = []
	private let fileTreeController = FileTreeSidebarController()
	private let rootSplitView = NSSplitView(frame: NSRect(x: 0, y: 0, width: 1200, height: 672))
	private let editorContainer = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
	private var findBarController: FindBarController?
	private var findSettings = ItsySettings.FindSettings()
	private let tabBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let tabScrollView = NSScrollView()
	private let tabStackView = NSStackView()
	private let lspMissingBanner = LSPMissingBanner()
	private let recoveryBanner = RecoveryBanner()
	private let statusBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 18))
	private let statusBarLabel = NSTextField(labelWithString: "")
	private let lspStatusButton = NSButton(title: "", target: nil, action: nil)
	private var paneCoordinator = EditorPaneCoordinator()
	private var sidebarWidthConstraint: NSLayoutConstraint?
	private var sidebarVisible = true
	private var editorView: MetalTextView {
		paneCoordinator.activePane.editorView
	}

	private var tabIDsByTag: [Int: ObjectIdentifier] = [:]
	private var tabGroupScope = ItsySettings.TabGroupScope.window
	private var latestTabs: [ItsyTab] = []
	private var paneTabDocuments: [ObjectIdentifier: [ItsyDocument]] = [:]
	private var paneSelectedDocuments: [ObjectIdentifier: ObjectIdentifier] = [:]
	private var tabBoundsObserver: NSObjectProtocol?
	private var completionPopup: CompletionPopupController?
	private var completionRequestGeneration = 0
	private var completionRequestTask: Task<Void, Never>?
	private weak var snippetTabStopView: MetalTextView?
	private var snippetTabStopSession: SnippetTabStopSession?
	private var hoverPopover: NSPopover?
	private var hoverTimer: Timer?
	private weak var hoverTargetView: MetalTextView?
	private var hoverTargetOffset = 0
	private var hoverTargetRect = NSRect.zero
	private var hoverRequestGeneration = 0
	private var hoverRequestTask: Task<Void, Never>?
	private var renamePopover: NSPopover?
	private var codeActionPopover: NSPopover?
	private var codeActionRequestGeneration = 0
	private var codeActionRequestTask: Task<Void, Never>?
	private var formattingRequestGeneration = 0
	private var formattingRequestTask: Task<Void, Never>?
	private weak var contextMenuEditorView: MetalTextView?
	private var signatureHelpPopover: NSPopover?
	private var signatureHelpRequestGeneration = 0
	private var signatureHelpRequestTask: Task<Void, Never>?
	private var referencesRequestGeneration = 0
	private var referencesCoordinator: ReferencesCoordinator?
	private var navigationRequestGeneration = 0
	private var lspNavigationHistory = LSPNavigationHistory()
	private var lspSyncCoordinators: [LSPSessionKey: LSPDocumentSyncCoordinator] = [:]
	private var lspDocumentVersionsBySession: [LSPSessionKey: [String: Int]] = [:]
	private var lspSupervisors: [LSPSessionKey: LSPSessionSupervisor] = [:]
	private var lspSupervisorTasks: [LSPSessionKey: Task<Void, Never>] = [:]
	private var lspStatusEntries: [LSPSessionKey: LSPStatusEntry] = [:]
	private var completionTriggerCharactersBySession: [LSPSessionKey: Set<String>] = [:]
	private var signatureHelpTriggerCharactersBySession: [LSPSessionKey: Set<String>] = [:]
	private var completionResolveEnabledBySession: [LSPSessionKey: Bool] = [:]
	private var codeActionResolveEnabledBySession: [LSPSessionKey: Bool] = [:]
	private var callHierarchyEnabledBySession: [LSPSessionKey: Bool] = [:]
	private var typeHierarchyEnabledBySession: [LSPSessionKey: Bool] = [:]
	private var semanticSurfaceCapabilitiesBySession: [LSPSessionKey: LSPSemanticSurfaceCapabilities] = [:]
	private var formattingCapabilitiesBySession: [LSPSessionKey: LSPFormattingCapabilities] = [:]
	private var semanticTokenCache = LSPSemanticTokenCache()
	private var foldingRangesByURI: [String: [LSPFoldingRange]] = [:]
	private var collapsedFoldStartsByURI: [String: Set<Int>] = [:]
	private let lspFoldGutterDecorator = LSPFoldGutterDecorator()
	private var lspSurfaceRefreshTask: Task<Void, Never>?
	private var lspSurfaceGeneration = 0
	private var lspMissingBannerGeneration = 0
	private var lspStatusGeneration = 0
	private var lspConfigurationGeneration = 0
	private var indexingStatusText: String?
	private var lspCrashStatusText: String?
	private var lspRestartKey: LSPSessionKey?
	private var lspRestartURL: URL?
	private var activeLSPKey: LSPSessionKey?
	private var lspStatusPanel: LSPStatusPanel?
	private var undoTreePanel: UndoTreePanelController?
	private(set) var focusTraversalTargetsForTesting: [NSView] = []

	init(document: ItsyDocument) {
		recordBenchStage("window_controller_init_begin")
		let editorStack = NSStackView(frame: NSRect(x: 240, y: 0, width: 960, height: 672))
		editorStack.orientation = .vertical
		editorStack.alignment = .width
		editorStack.distribution = .fill
		editorStack.spacing = 0
		Self.configureTabBarView(tabBarView, scrollView: tabScrollView, stackView: tabStackView)
		Self.configureStatusBarView(statusBarView, label: statusBarLabel, lspButton: lspStatusButton)
		tabBarView.setContentHuggingPriority(.required, for: .vertical)
		lspMissingBanner.setContentHuggingPriority(.required, for: .vertical)
		recoveryBanner.setContentHuggingPriority(.required, for: .vertical)
		statusBarView.setContentHuggingPriority(.required, for: .vertical)
		statusBarView.isHidden = true
		editorContainer.setContentHuggingPriority(.defaultLow, for: .vertical)
		editorContainer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
		paneCoordinator.view.translatesAutoresizingMaskIntoConstraints = false
		editorContainer.addSubview(paneCoordinator.view)
		NSLayoutConstraint.activate([
			paneCoordinator.view.leadingAnchor.constraint(equalTo: editorContainer.leadingAnchor),
			paneCoordinator.view.trailingAnchor.constraint(equalTo: editorContainer.trailingAnchor),
			paneCoordinator.view.topAnchor.constraint(equalTo: editorContainer.topAnchor),
			paneCoordinator.view.bottomAnchor.constraint(equalTo: editorContainer.bottomAnchor),
		])
		editorStack.addArrangedSubview(tabBarView)
		editorStack.addArrangedSubview(lspMissingBanner)
		editorStack.addArrangedSubview(recoveryBanner)
		editorStack.addArrangedSubview(editorContainer)
		editorStack.addArrangedSubview(statusBarView)

		rootSplitView.isVertical = true
		rootSplitView.dividerStyle = .thin
		rootSplitView.autoresizingMask = [.width, .height]
		fileTreeController.view.translatesAutoresizingMaskIntoConstraints = false
		editorStack.translatesAutoresizingMaskIntoConstraints = false
		rootSplitView.addArrangedSubview(fileTreeController.view)
		rootSplitView.addArrangedSubview(editorStack)
		let sidebarWidthConstraint = fileTreeController.view.widthAnchor.constraint(equalToConstant: 240)
		sidebarWidthConstraint.isActive = true
		self.sidebarWidthConstraint = sidebarWidthConstraint
		let window = NSWindow(
			contentRect: rootSplitView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = document.fileURL?.lastPathComponent ?? L10n.string("Untitled")
		window.isRestorable = true
		window.contentView = rootSplitView
		super.init(window: window)
		tabGroupScope = ItsySettingsStore().load(
			workspaceRoot: ItsyWorkspaceController.currentRootURL,
			fallback: EditorPreferences.legacySettings()
		).settings.normalized().editor.tabGroups
		configurePaneTabBar(paneCoordinator.activePane)
		syncTabGroupVisibility()
		configureLSPMissingBanner()
		configureRecoveryBanner()
		configureLSPStatusRestart()
		fileTreeController.attach(to: window)
		fileTreeController.openFile = { ItsyWorkspaceController.openFile(at: $0) }
		fileTreeController.createFileRequested = { ItsyWorkspaceController.createFile(named: $0, in: $1) }
		fileTreeController.createFolderRequested = { ItsyWorkspaceController.createFolder(named: $0, in: $1) }
		fileTreeController.renameItemRequested = { ItsyWorkspaceController.renameItem($0, to: $1) }
		fileTreeController.duplicateItemRequested = { ItsyWorkspaceController.duplicateItem($0) }
		fileTreeController.deleteItemRequested = { ItsyWorkspaceController.deleteItem($0) }
		fileTreeController.trashItemRequested = { ItsyWorkspaceController.moveItemToTrash($0) }
		fileTreeController.moveItemRequested = { ItsyWorkspaceController.moveItem($0, toDirectory: $1) }
		installTabBoundsObserver()
		window.delegate = self
		installPane(paneCoordinator.activePane, document: document)
		applyTheme(AppTheme.palette)
		recordBenchStage("window_controller_install_pane_end")
		refreshLSPMissingBanner(for: document)
		refreshRecoveryBanner(for: document)
		refreshLSPStatus(for: document)
		recordBenchStage("window_controller_lsp_refresh_end")
		ItsyWorkspaceController.register(self)
		ItsyTabCoordinator.register(self)
		rebuildFocusTraversal()
		window.makeFirstResponder(editorView)
		recordBenchStage("window_controller_init_end")
	}

	static func reloadLSPConfiguration() {
		let registry = LSPServerRegistryLoader.loadOrBundled()
		Task {
			await lspManager.replaceRegistry(registry)
			await MainActor.run {
				ItsyWorkspaceController.lspConfigurationDidReload()
			}
		}
	}

	static func shutdownAllLSP() {
		LSPProcessClient.terminateAll()
		Task {
			await lspManager.shutdownAll()
		}
	}

	static func documentDidClose(_ url: URL) {
		ItsyWorkspaceController.lspDocumentDidClose(url)
	}

	static func documentDidReload(_ url: URL, content: String) {
		ItsyWorkspaceController.lspDocumentDidReload(url, content: content)
	}

	required init?(coder _: NSCoder) {
		nil
	}

	deinit {
		MainActor.assumeIsolated {
			completionPopup?.dismiss()
			completionRequestTask?.cancel()
			hoverTimer?.invalidate()
			hoverPopover?.close()
			hoverRequestTask?.cancel()
			renamePopover?.close()
			codeActionPopover?.close()
			codeActionRequestTask?.cancel()
			formattingRequestTask?.cancel()
			signatureHelpPopover?.close()
			signatureHelpRequestTask?.cancel()
			undoTreePanel = nil
			lspSurfaceRefreshTask?.cancel()
			for task in lspSupervisorTasks.values {
				task.cancel()
			}
			if let tabBoundsObserver {
				NotificationCenter.default.removeObserver(tabBoundsObserver)
			}
		}
	}

	func setWorkspaceRootURL(_ url: URL?) {
		fileTreeController.setWorkspaceRootURL(url)
	}

	func setWorkspaceRootURLs(_ urls: [URL]) {
		fileTreeController.setWorkspaceRootURLs(urls)
	}

	func revealInFileTree(_ url: URL) {
		fileTreeController.reveal(url)
	}

	func setGitSnapshot(_ snapshot: GitWorkspaceSnapshot?) {
		fileTreeController.setGitSnapshot(snapshot)
	}

	func notifyLSPWatchedFiles(_ batch: WorkspaceFileEventBatch) {
		guard !batch.events.isEmpty else {
			return
		}
		for entry in lspStatusEntries.values {
			guard let client = entry.client else {
				continue
			}
			let rootPath = entry.key.workspaceRoot.standardizedFileURL.path
			let matchingEvents = batch.events.filter { event in
				let path = event.url.standardizedFileURL.path
				return path == rootPath || path.hasPrefix(rootPath + "/")
			}
			guard !matchingEvents.isEmpty else {
				continue
			}
			let params = LSPDidChangeWatchedFilesParams(changes: matchingEvents.map {
				LSPFileEvent(uri: $0.url.standardizedFileURL.absoluteString, type: Self.lspFileChangeType(for: $0))
			})
			Task {
				do {
					try await client.sendNotification(
						method: LSPMethod.workspaceDidChangeWatchedFiles,
						params: LSPAny(encoding: params)
					)
				} catch {
					NSLog("lsp didChangeWatchedFiles failed: \(error)")
				}
			}
		}
	}

	private static func lspFileChangeType(for event: WorkspaceFileEvent) -> LSPFileChangeType {
		if event.flags & FSEventStreamEventFlags(kFSEventStreamEventFlagItemRemoved) != 0 {
			return .deleted
		}
		if event.flags & FSEventStreamEventFlags(kFSEventStreamEventFlagItemCreated) != 0 {
			return .created
		}
		return .changed
	}

	func toggleSidebar() {
		setSidebarVisible(!sidebarVisible)
	}

	func focusSidebar() {
		if !sidebarVisible {
			setSidebarVisible(true)
		}
		fileTreeController.focus()
	}

	func focusTabs() {
		guard let target = focusTarget(in: tabGroupScope == .pane ? paneCoordinator.activePane.tabBarController.view : tabBarView) else {
			return
		}
		window?.makeFirstResponder(target)
	}

	private func setSidebarVisible(_ visible: Bool) {
		guard visible != sidebarVisible else {
			return
		}
		sidebarVisible = visible
		if visible {
			sidebarWidthConstraint?.constant = 240
			fileTreeController.view.isHidden = false
			if !rootSplitView.arrangedSubviews.contains(fileTreeController.view) {
				rootSplitView.insertArrangedSubview(fileTreeController.view, at: 0)
			}
		} else {
			if rootSplitView.arrangedSubviews.contains(fileTreeController.view) {
				rootSplitView.removeArrangedSubview(fileTreeController.view)
				fileTreeController.view.removeFromSuperview()
			}
			fileTreeController.view.isHidden = true
			sidebarWidthConstraint?.constant = 0
		}
		invalidateEditorShellLayout()
		rebuildFocusTraversal()
	}

	private func invalidateEditorShellLayout() {
		rootSplitView.needsLayout = true
		rootSplitView.layoutSubtreeIfNeeded()
		editorContainer.needsLayout = true
		editorContainer.layoutSubtreeIfNeeded()
		paneCoordinator.view.needsLayout = true
		paneCoordinator.view.layoutSubtreeIfNeeded()
		for pane in paneCoordinator.panes {
			pane.viewController.view.needsLayout = true
			pane.viewController.view.layoutSubtreeIfNeeded()
			pane.editorView.needsDisplay = true
		}
		layoutTabContent()
	}

	func toggleHiddenFiles() {
		fileTreeController.toggleHiddenFiles()
	}

	func setIndexingStatus(_ text: String?) {
		indexingStatusText = text.flatMap { $0.isEmpty ? nil : $0 }
		refreshStatusBar()
	}

	private static func configureTabBarView(_ tabBarView: NSView, scrollView: NSScrollView, stackView: NSStackView) {
		tabBarView.wantsLayer = true
		tabBarView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		scrollView.drawsBackground = false
		scrollView.borderType = .noBorder
		scrollView.hasHorizontalScroller = true
		scrollView.hasVerticalScroller = false
		scrollView.autohidesScrollers = true
		scrollView.scrollerStyle = .overlay
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		scrollView.contentView.postsBoundsChangedNotifications = true
		stackView.orientation = .horizontal
		stackView.alignment = .centerY
		stackView.spacing = 2
		stackView.edgeInsets = NSEdgeInsets(top: 3, left: 6, bottom: 3, right: 6)
		scrollView.documentView = stackView
		tabBarView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: tabBarView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: tabBarView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: tabBarView.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: tabBarView.bottomAnchor),
			tabBarView.heightAnchor.constraint(equalToConstant: 32),
		])
	}

	private func installTabBoundsObserver() {
		tabBoundsObserver = NotificationCenter.default.addObserver(
			forName: NSView.boundsDidChangeNotification,
			object: tabScrollView.contentView,
			queue: nil
		) { [weak self] _ in
			MainActor.assumeIsolated {
				self?.layoutTabContent()
			}
		}
	}

	func setTabs(_ tabs: [ItsyTab]) {
		latestTabs = tabs
		guard tabGroupScope == .window else {
			refreshPaneTabBars()
			return
		}
		for view in tabStackView.arrangedSubviews {
			tabStackView.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		tabIDsByTag = Dictionary(uniqueKeysWithValues: tabs.enumerated().map { index, tab in (index, tab.id) })
		for (index, tab) in tabs.enumerated() {
			tabStackView.addArrangedSubview(makeTabView(tab, tag: index))
		}
		layoutTabContent()
		rebuildFocusTraversal()
	}

	private func layoutTabContent() {
		tabStackView.layoutSubtreeIfNeeded()
		let fit = tabStackView.fittingSize
		let height = max(tabBarView.bounds.height, 32)
		let width = max(tabScrollView.contentView.bounds.width, fit.width)
		tabStackView.frame = NSRect(x: 0, y: 0, width: width, height: height)
		tabScrollView.documentView = tabStackView
	}

	private func makeTabView(_ tab: ItsyTab, tag: Int) -> NSView {
		let container = NSView()
		container.wantsLayer = true
		container.layer?.backgroundColor = tab.isSelected
			? AppTheme.palette.tabActiveBackground.cgColor
			: AppTheme.palette.tabInactiveBackground.cgColor

		let stack = NSStackView()
		stack.orientation = .horizontal
		stack.alignment = .centerY
		stack.spacing = 4
		stack.edgeInsets = NSEdgeInsets(top: 0, left: 8, bottom: 0, right: 6)
		stack.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(stack)

		let title = tab.isDirty ? "• \(tab.title)" : tab.title
		let selectButton = NSButton(title: title, target: self, action: #selector(selectTab(_:)))
		selectButton.tag = tag
		selectButton.isBordered = false
		selectButton.font = .systemFont(ofSize: 12, weight: tab.isSelected ? .semibold : .regular)
		selectButton.lineBreakMode = .byTruncatingMiddle
		selectButton.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
		selectButton.toolTip = tab.title
		selectButton.setAccessibilityLabel(L10n.string("Tab: \(tab.title)"))
		selectButton.contentTintColor = tab.isSelected ? AppTheme.palette.tabActiveForeground : AppTheme.palette.tabInactiveForeground

		let closeButton = NSButton(title: L10n.string("X"), target: self, action: #selector(closeTab(_:)))
		closeButton.tag = tag
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11, weight: .regular)
		closeButton.toolTip = L10n.string("Close")
		closeButton.setAccessibilityLabel(L10n.string("Close tab: \(tab.title)"))
		closeButton.contentTintColor = AppTheme.palette.tabInactiveForeground

		stack.addArrangedSubview(selectButton)
		stack.addArrangedSubview(closeButton)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			stack.topAnchor.constraint(equalTo: container.topAnchor),
			stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
			container.heightAnchor.constraint(equalToConstant: 26),
			container.widthAnchor.constraint(greaterThanOrEqualToConstant: 96),
			container.widthAnchor.constraint(lessThanOrEqualToConstant: 220),
		])
		return container
	}

	@objc private func selectTab(_ sender: NSButton) {
		guard let tabID = tabIDsByTag[sender.tag] else {
			return
		}
		ItsyTabCoordinator.selectDocument(tabID)
	}

	@objc private func closeTab(_ sender: NSButton) {
		guard let tabID = tabIDsByTag[sender.tag] else {
			return
		}
		ItsyTabCoordinator.closeDocument(tabID)
	}

	private static func configureStatusBarView(_ statusBarView: NSView, label: NSTextField, lspButton: NSButton) {
		statusBarView.wantsLayer = true
		statusBarView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		label.font = .systemFont(ofSize: 11)
		label.textColor = .secondaryLabelColor
		label.lineBreakMode = .byTruncatingTail
		label.translatesAutoresizingMaskIntoConstraints = false
		lspButton.isHidden = true
		lspButton.bezelStyle = .rounded
		lspButton.controlSize = .small
		lspButton.font = .systemFont(ofSize: 11)
		lspButton.setAccessibilityLabel(L10n.string("Language server status"))
		lspButton.translatesAutoresizingMaskIntoConstraints = false
		statusBarView.addSubview(label)
		statusBarView.addSubview(lspButton)
		NSLayoutConstraint.activate([
			label.leadingAnchor.constraint(equalTo: statusBarView.leadingAnchor, constant: 10),
			label.trailingAnchor.constraint(lessThanOrEqualTo: lspButton.leadingAnchor, constant: -8),
			label.centerYAnchor.constraint(equalTo: statusBarView.centerYAnchor),
			lspButton.trailingAnchor.constraint(equalTo: statusBarView.trailingAnchor, constant: -10),
			lspButton.centerYAnchor.constraint(equalTo: statusBarView.centerYAnchor),
			statusBarView.heightAnchor.constraint(equalToConstant: 20),
		])
	}

	private func configureLSPMissingBanner() {
		lspMissingBanner.copyRequested = { [weak self] missingBinary in
			self?.copyLSPInstallCommand(for: missingBinary)
		}
		lspMissingBanner.supportRequested = { command in
			let componentID = command.flatMap { ManagedSupportCatalog.bundled.component(command: $0, kind: .languageServer)?.id }
			NSApp.sendAction(#selector(AppCoordinator.showManagedSupport(_:)), to: nil, from: ManagedSupportRequest(componentID: componentID))
		}
		lspMissingBanner.dismissRequested = { [weak self] missingBinary in
			Self.dismissedLSPMissingCommands.insert(missingBinary.command)
			self?.lspMissingBanner.hide()
			self?.focusEditor()
		}
		lspMissingBanner.unavailableDismissRequested = { [weak self] unavailableLanguage in
			Self.dismissedLSPMissingCommands.insert("unsupported:\(unavailableLanguage.languageID)")
			self?.lspMissingBanner.hide()
			self?.focusEditor()
		}
	}

	private func configureRecoveryBanner() {
		recoveryBanner.dismissRequested = { [weak self] in
			self?.focusEditor()
		}
	}

	private func refreshRecoveryBanner(for document: ItsyDocument) {
		guard let fileURL = document.recoveredJournalFileURL else {
			recoveryBanner.hide()
			return
		}
		recoveryBanner.show(fileURL: fileURL)
	}

	private func configureLSPStatusRestart() {
		lspStatusButton.target = self
		lspStatusButton.action = #selector(showLSPStatusPanel(_:))
	}

	private func refreshLSPMissingBanner(for document: ItsyDocument) {
		lspMissingBannerGeneration += 1
		let generation = lspMissingBannerGeneration
		guard let fileURL = document.fileURL else {
			lspMissingBanner.hide()
			return
		}
		Task { [weak self] in
			let missingBinary = await Self.lspManager.missingBinary(for: fileURL)
			let unavailableLanguage = await Self.lspManager.unsupportedLanguage(for: fileURL)
			let key = await Self.lspManager.sessionKey(for: fileURL)
			await MainActor.run { [weak self] in
				guard let self, generation == lspMissingBannerGeneration else {
					return
				}
				if let missingBinary {
					showLSPMissingBanner(missingBinary)
					if let key {
						setLSPStatus(key: key, status: "unavailable", client: nil, lastError: missingBinary.hint, url: fileURL, server: missingBinary.command)
					}
				} else if let unavailableLanguage {
					showLSPUnavailableBanner(unavailableLanguage)
				} else {
					lspMissingBanner.hide()
					if let key, lspStatusEntries[key]?.health == .unavailable {
						setLSPStatus(key: key, status: "idle", client: nil, lastError: nil, url: fileURL)
					}
				}
			}
		}
	}

	private func showLSPMissingBanner(_ missingBinary: LSPServerRegistry.MissingBinary) {
		if Self.dismissedLSPMissingCommands.contains(missingBinary.command) {
			lspMissingBanner.hide()
			return
		}
		lspMissingBanner.show(missingBinary: missingBinary)
	}

	private func showLSPUnavailableBanner(_ unavailableLanguage: LSPServerRegistry.UnsupportedLanguage) {
		let dismissalKey = "unsupported:\(unavailableLanguage.languageID)"
		if Self.dismissedLSPMissingCommands.contains(dismissalKey) {
			lspMissingBanner.hide()
			return
		}
		lspMissingBanner.show(unavailableLanguage: unavailableLanguage)
	}

	private func handleLSPRequestError(_ error: Error) {
		if case let LSPManagerError.missingBinary(missingBinary) = error {
			showLSPMissingBanner(missingBinary)
			if let key = activeLSPKey {
				setLSPStatus(key: key, status: "unavailable", client: nil, lastError: missingBinary.hint, url: lspStatusEntries[key]?.url, server: missingBinary.command)
			}
		} else if case let LSPManagerError.unsupportedLanguage(unavailableLanguage) = error {
			showLSPUnavailableBanner(unavailableLanguage)
		} else if case let LSPManagerError.serverDisabled(key) = error {
			setLSPStatus(
				key: key,
				status: "disabled",
				client: nil,
				lastError: lspStatusEntries[key]?.lastError,
				url: lspStatusEntries[key]?.url
			)
		} else if case LSPManagerError.retryLimitExceeded = error, let key = activeLSPKey {
			setLSPStatus(
				key: key,
				status: "disabled",
				client: nil,
				lastError: lspStatusEntries[key]?.lastError,
				url: lspStatusEntries[key]?.url
			)
		}
	}

	private func showLSPOperationFailure(_ operation: String, error: Error) {
		let message = "LSP \(operation) failed: \(error)"
		lspCrashStatusText = message
		if let key = activeLSPKey, let entry = lspStatusEntries[key] {
			setLSPStatus(
				key: key,
				status: entry.status,
				client: entry.client,
				lastError: message,
				url: entry.url,
				health: .degraded
			)
		}
		refreshStatusBar()
	}

	private func copyLSPInstallCommand(for missingBinary: LSPServerRegistry.MissingBinary) {
		let pasteboard = NSPasteboard.general
		pasteboard.clearContents()
		pasteboard.setString(Self.installCommand(from: missingBinary), forType: .string)
		focusEditor()
	}

	private static func installCommand(from missingBinary: LSPServerRegistry.MissingBinary) -> String {
		let hint = missingBinary.hint
		guard
			let start = hint.firstIndex(of: "`"),
			let end = hint[hint.index(after: start)...].firstIndex(of: "`")
		else {
			return missingBinary.command
		}
		return String(hint[hint.index(after: start) ..< end])
	}

	private func refreshStatusBar() {
		refreshLSPStatusPill()
		if let text = lspCrashStatusText ?? indexingStatusText {
			statusBarLabel.stringValue = text
		} else {
			statusBarLabel.stringValue = ""
		}
		statusBarView.isHidden = statusBarLabel.stringValue.isEmpty && lspStatusButton.isHidden
	}

	private func showLSPCrashStatus(key: LSPSessionKey, url: URL, reason: LSPSessionFailureReason) {
		appendLSPOutput(
			LSPSessionOutput(kind: .process, text: "Server exited with status \(reason.status)"),
			for: key,
			url: url
		)
		setLSPStatus(key: key, status: "crashed", client: nil, lastError: reason.stderrTail, url: url)
		lspCrashStatusText = L10n.string("LSP: \(key.languageID) crashed (exit \(reason.status))")
		AccessibilityAnnouncement.post(.languageServerFailure(language: key.languageID))
		refreshStatusBar()
	}

	private func clearLSPCrashStatus(for key: LSPSessionKey) {
		guard lspRestartKey == key else {
			return
		}
		lspRestartKey = nil
		lspRestartURL = nil
		lspCrashStatusText = nil
		AccessibilityAnnouncement.post(.languageServerRecovery(language: key.languageID))
		refreshStatusBar()
	}

	@objc private func showLSPStatusPanel(_: NSButton) {
		guard let snapshot = currentLSPStatusSnapshot() else {
			return
		}
		let panel = lspStatusPanel ?? LSPStatusPanel()
		panel.restartRequested = { [weak self] key in
			self?.restartLSPFromStatusPanel(key)
		}
		panel.stopRequested = { [weak self] key in
			self?.stopLSPFromStatusPanel(key)
		}
		lspStatusPanel = panel
		panel.show(snapshot: snapshot, relativeTo: window)
	}

	func showLSPStatus() {
		showLSPStatusPanel(lspStatusButton)
	}

	func lspConfigurationDidReload() {
		lspConfigurationGeneration &+= 1
		for key in Set(lspSupervisors.keys).union(lspStatusEntries.keys) {
			publishEmptyLSPDiagnostics(for: key)
		}
		for task in lspSupervisorTasks.values {
			task.cancel()
		}
		lspSyncCoordinators.removeAll()
		lspDocumentVersionsBySession.removeAll()
		lspSupervisors.removeAll()
		lspSupervisorTasks.removeAll()
		lspStatusEntries.removeAll()
		completionTriggerCharactersBySession.removeAll()
		signatureHelpTriggerCharactersBySession.removeAll()
		completionResolveEnabledBySession.removeAll()
		codeActionResolveEnabledBySession.removeAll()
		callHierarchyEnabledBySession.removeAll()
		typeHierarchyEnabledBySession.removeAll()
		semanticSurfaceCapabilitiesBySession.removeAll()
		formattingCapabilitiesBySession.removeAll()
		activeLSPKey = nil
		lspRestartKey = nil
		lspRestartURL = nil
		lspCrashStatusText = nil
		if let document = document as? ItsyDocument {
			refreshLSPMissingBanner(for: document)
			refreshLSPStatus(for: document)
		} else {
			refreshStatusBar()
		}
	}

	func lspDocumentDidClose(_ url: URL) {
		forgetLSPDocumentVersion(url)
		let coordinators = lspSyncCoordinators
		let supervisors = lspSupervisors
		Task {
			for supervisor in supervisors.values {
				await supervisor.clearDiagnostics(forURI: url.standardizedFileURL.absoluteString, removingDocument: true)
			}
			for (key, coordinator) in coordinators {
				if await Self.lspManager.closeSynchronizedDocument(url, for: key, using: coordinator) {
					discardLSPState(for: key)
				}
			}
		}
	}

	func lspDocumentDidReload(_ url: URL, content: String) {
		let coordinators = lspSyncCoordinators
		let supervisors = lspSupervisors
		let clients = lspStatusEntries.reduce(into: [LSPSessionKey: LSPProcessClient]()) { result, entry in
			if let client = entry.value.client {
				result[entry.key] = client
			}
		}
		Task { [weak self] in
			guard let self else {
				return
			}
			for (key, supervisor) in supervisors {
				if let version = await coordinators[key]?.currentVersion(for: url) {
					await supervisor.recordDocumentVersion(version + 1, forURI: url.standardizedFileURL.absoluteString)
				}
				await supervisor.clearDiagnostics(forURI: url.standardizedFileURL.absoluteString)
			}
			for (key, coordinator) in coordinators {
				guard let client = clients[key], await coordinator.currentVersion(for: url) != nil else {
					continue
				}
				do {
					try await self.syncLSPDocument(client: client, key: key, url: url, content: content)
				} catch {
					NSLog("lsp reload sync failed: \(error)")
				}
			}
		}
	}

	private func discardLSPState(for key: LSPSessionKey) {
		lspSyncCoordinators[key] = nil
		lspDocumentVersionsBySession[key] = nil
		lspSupervisorTasks[key]?.cancel()
		lspSupervisorTasks[key] = nil
		lspSupervisors[key] = nil
		lspStatusEntries[key] = nil
		completionTriggerCharactersBySession[key] = nil
		signatureHelpTriggerCharactersBySession[key] = nil
		completionResolveEnabledBySession[key] = nil
		codeActionResolveEnabledBySession[key] = nil
		callHierarchyEnabledBySession[key] = nil
		typeHierarchyEnabledBySession[key] = nil
		semanticSurfaceCapabilitiesBySession[key] = nil
		formattingCapabilitiesBySession[key] = nil
		if activeLSPKey == key {
			activeLSPKey = nil
			refreshStatusBar()
		}
	}

	private func restartLSPFromStatusPanel(_ key: LSPSessionKey) {
		guard let url = lspStatusEntries[key]?.url ?? lspRestartURL else {
			return
		}
		lspRestartKey = key
		lspRestartURL = url
		Task { [weak self] in
			await Self.lspManager.enableSession(key)
			await MainActor.run { [weak self] in
				guard let self else {
					return
				}
				restartLSPSession(for: url)
			}
		}
	}

	private func stopLSPFromStatusPanel(_ key: LSPSessionKey) {
		let entry = lspStatusEntries[key]
		let supervisor = lspSupervisors[key]
		lspSyncCoordinators[key] = nil
		lspDocumentVersionsBySession[key] = nil
		lspSupervisorTasks[key]?.cancel()
		lspSupervisorTasks[key] = nil
		lspSupervisors[key] = nil
		Task {
			await supervisor?.stop()
			await Self.lspManager.stopSession(key)
		}
		setLSPStatus(key: key, status: "idle", client: nil, lastError: entry?.lastError, url: entry?.url)
	}

	private func refreshLSPStatus(for document: ItsyDocument) {
		lspStatusGeneration += 1
		let generation = lspStatusGeneration
		guard let fileURL = document.fileURL else {
			activeLSPKey = nil
			refreshStatusBar()
			return
		}
		Task { [weak self] in
			let key = await Self.lspManager.sessionKey(for: fileURL)
			await MainActor.run { [weak self] in
				guard let self else {
					return
				}
				guard generation == lspStatusGeneration else {
					return
				}
				activeLSPKey = key
				if let key, lspStatusEntries[key] == nil {
					setLSPStatus(key: key, status: "idle", client: nil, lastError: nil, url: fileURL)
				} else {
					refreshStatusBar()
				}
			}
		}
	}

	private func setLSPStatus(
		key: LSPSessionKey,
		status: String,
		client: LSPProcessClient?,
		lastError: String?,
		url: URL?,
		server: String? = nil,
		health: LSPHealthState? = nil
	) {
		let existing = lspStatusEntries[key]
		let clearsClient = status == "idle" || status == "crashed" || status == "disabled" || status == "unavailable"
		let resolvedHealth = health ?? (status == "running" && existing?.health == .degraded ? .degraded : Self.health(for: status))
		let entry = LSPStatusEntry(
			key: key,
			status: status,
			health: resolvedHealth,
			server: server ?? client.map(Self.serverName(for:)) ?? existing?.server ?? key.languageID,
			pid: clearsClient ? nil : client?.processIdentifier ?? existing?.pid,
			startDate: clearsClient ? nil : client?.startDate ?? existing?.startDate,
			lastError: lastError ?? existing?.lastError ?? "",
			output: existing?.output ?? [],
			url: url ?? existing?.url,
			client: client ?? (clearsClient ? nil : existing?.client)
		)
		lspStatusEntries[key] = entry
		Task {
			await IntegrationHealthStore.shared.report(
				service: .lsp,
				identifier: "\(key.languageID):\(key.workspaceRoot.path)",
				lifecycle: Self.integrationLifecycle(for: status),
				state: Self.integrationState(for: resolvedHealth),
				lastError: entry.lastError.isEmpty ? nil : entry.lastError,
				remediation: Self.integrationRemediation(for: status),
				detailLogReference: "lsp://\(key.languageID)/\(key.workspaceRoot.path)"
			)
		}
		activeLSPKey = key
		if status == "crashed" || status == "disabled" {
			lspRestartKey = key
			lspRestartURL = url ?? existing?.url
		}
		refreshStatusBar()
	}

	private static func health(for status: String) -> LSPHealthState {
		switch status {
		case "starting":
			return .starting
		case "running", "ready":
			return .ready
		case "degraded", "disabled":
			return .degraded
		case "crashed":
			return .crashed
		case "unavailable":
			return .unavailable
		default:
			return .idle
		}
	}

	private static func integrationLifecycle(for status: String) -> IntegrationLifecycle {
		switch status {
		case "starting":
			.starting
		case "running", "ready", "degraded":
			.running
		case "crashed", "disabled", "unavailable":
			.stopped
		default:
			.inactive
		}
	}

	private static func integrationState(for health: LSPHealthState) -> IntegrationHealthState {
		switch health {
		case .degraded, .crashed:
			.degraded
		case .unavailable:
			.unavailable
		case .starting:
			.retrying
		case .idle, .ready:
			.healthy
		}
	}

	private static func integrationRemediation(for status: String) -> String? {
		switch status {
		case "crashed", "disabled":
			"Restart the language server."
		case "unavailable":
			"Open Language Server Configuration."
		default:
			nil
		}
	}

	private func appendLSPOutput(_ output: LSPSessionOutput, for key: LSPSessionKey, url: URL?) {
		guard var entry = lspStatusEntries[key] else {
			setLSPStatus(key: key, status: "starting", client: nil, lastError: nil, url: url)
			appendLSPOutput(output, for: key, url: url)
			return
		}
		entry.output.append(output)
		if entry.output.count > 200 {
			entry.output.removeFirst(entry.output.count - 200)
		}
		if output.kind == .protocolOutput {
			entry.status = "degraded"
			entry.health = .degraded
			entry.lastError = output.text
		}
		lspStatusEntries[key] = entry
		Task {
			await IntegrationOutputConsole.shared.append(
				service: .lsp,
				identifier: "\(key.languageID):\(key.workspaceRoot.path)",
				kind: output.kind == .protocolOutput ? .protocolOutput : .standardError,
				text: output.text,
				errorReference: output.kind == .protocolOutput ? "lsp://\(key.languageID)/\(key.workspaceRoot.path)" : nil,
				timestamp: output.timestamp
			)
		}
		activeLSPKey = key
		refreshStatusBar()
	}

	private func refreshLSPStatusPill() {
		guard let key = activeLSPKey, let entry = lspStatusEntries[key] else {
			lspStatusButton.isHidden = true
			return
		}
		lspStatusButton.title = L10n.string("LSP: \(entry.key.languageID) \(entry.health.rawValue)")
		lspStatusButton.toolTip = L10n.string("LSP status")
		lspStatusButton.setAccessibilityValue(L10n.string("\(entry.key.languageID) \(entry.health.rawValue)"))
		lspStatusButton.isHidden = false
	}

	private func currentLSPStatusSnapshot() -> LSPStatusPanelSnapshot? {
		guard let key = activeLSPKey, let entry = lspStatusEntries[key] else {
			return nil
		}
		return LSPStatusPanelSnapshot(
			key: entry.key,
			status: entry.status,
			health: entry.health,
			server: entry.server,
			pid: entry.pid,
			startDate: entry.startDate,
			lastError: entry.lastError,
			output: entry.output
		)
	}

	private static func serverName(for client: LSPProcessClient) -> String {
		([client.executableURL.path] + client.arguments).joined(separator: " ")
	}

	override func windowDidLoad() {
		super.windowDidLoad()
		window?.center()
	}

	override func showWindow(_ sender: Any?) {
		recordBenchStage("window_show_begin")
		super.showWindow(sender)
		window?.makeKeyAndOrderFront(sender)
		window?.orderFrontRegardless()
		focusEditor()
		ItsyTabCoordinator.refresh()
		recordBenchStage("window_show_end")
	}

	func display(document newDocument: ItsyDocument) {
		guard tabGroupScope == .window else {
			displayPaneScoped(document: newDocument)
			return
		}
		displayWindowScoped(document: newDocument)
	}

	private func displayWindowScoped(document newDocument: ItsyDocument) {
		if document as? ItsyDocument === newDocument {
			showWindow(nil)
			focusEditor()
			ItsyTabCoordinator.refresh()
			return
		}
		if let oldDocument = document as? ItsyDocument {
			for pane in paneCoordinator.panes {
				oldDocument.detach(pane.editorView)
			}
			oldDocument.removeWindowController(self)
		}
		if !newDocument.windowControllers.contains(where: { $0 === self }) {
			newDocument.addWindowController(self)
		}
		for pane in paneCoordinator.panes {
			installPane(pane, document: newDocument)
		}
		refreshLSPMissingBanner(for: newDocument)
		refreshLSPStatus(for: newDocument)
		window?.title = newDocument.fileURL?.lastPathComponent ?? newDocument.displayName
		window?.representedURL = newDocument.fileURL
		showWindow(nil)
		focusEditor()
		ItsyTabCoordinator.refresh()
		ItsyWorkspaceController.persistWindowState(from: self)
	}

	private func displayPaneScoped(document newDocument: ItsyDocument) {
		let pane = paneCoordinator.activePane
		selectPaneDocument(newDocument, in: pane, addIfMissing: true)
		showWindow(nil)
		focusEditor()
		ItsyTabCoordinator.refresh()
		ItsyWorkspaceController.persistWindowState(from: self)
	}

	override func encodeRestorableState(with coder: NSCoder) {
		super.encodeRestorableState(with: coder)
		coder.encode(paneCoordinator.layout().encoded, forKey: Self.paneLayoutStateKey)
	}

	override func restoreState(with coder: NSCoder) {
		super.restoreState(with: coder)
		guard
			let string = coder.decodeObject(forKey: Self.paneLayoutStateKey) as? String,
			let layout = EditorPaneLayout.decode(string),
			let document = document as? ItsyDocument
		else {
			return
		}
		restorePaneLayout(layout, fallbackDocument: document, paneStates: nil, focusedPaneIndex: nil)
	}

	var paneLayoutEncoded: String {
		paneCoordinator.layout().encoded
	}

	var workspacePaneStates: [WorkspacePaneState]? {
		guard tabGroupScope == .pane else {
			return nil
		}
		return paneCoordinator.panes.map { pane in
			let documents = paneTabDocuments[paneID(pane)] ?? [document as? ItsyDocument].compactMap { $0 }
			let paths = documents.compactMap { $0.fileURL?.standardizedFileURL.path }
			let selectedPath = selectedDocument(for: pane)?.fileURL?.standardizedFileURL.path
			return WorkspacePaneState(openPaths: paths, selectedPath: selectedPath)
		}
	}

	var workspaceFocusedPaneIndex: Int? {
		tabGroupScope == .pane ? paneCoordinator.focusedPaneIndex : nil
	}

	func restoreWorkspacePaneLayout(
		_ encoded: String,
		paneStates: [WorkspacePaneState]? = nil,
		focusedPaneIndex: Int? = nil
	) {
		guard let document = document as? ItsyDocument else {
			return
		}
		restorePaneLayout(
			EditorPaneLayout.decode(encoded) ?? .leaf,
			fallbackDocument: document,
			paneStates: paneStates,
			focusedPaneIndex: focusedPaneIndex
		)
	}

	private func restorePaneLayout(
		_ layout: EditorPaneLayout,
		fallbackDocument: ItsyDocument,
		paneStates: [WorkspacePaneState]?,
		focusedPaneIndex: Int?
	) {
		detachPaneDocuments()
		paneTabDocuments = [:]
		paneSelectedDocuments = [:]
		let restoredPanes = paneCoordinator.restore(layout: layout)
		let restoresPaneTabs = tabGroupScope == .pane && paneStates?.count == restoredPanes.count
		for (index, pane) in restoredPanes.enumerated() {
			configurePaneTabBar(pane)
			if restoresPaneTabs, let state = paneStates?[index] {
				let documents = state.openPaths.compactMap(document(atPersistedPath:))
				if let selected = document(atPersistedPath: state.selectedPath), documents.contains(where: { $0 === selected }) {
					paneTabDocuments[paneID(pane)] = documents
					paneSelectedDocuments[paneID(pane)] = ObjectIdentifier(selected)
					installPane(pane, document: selected)
					continue
				}
				if let selected = documents.first {
					paneTabDocuments[paneID(pane)] = documents
					paneSelectedDocuments[paneID(pane)] = ObjectIdentifier(selected)
					installPane(pane, document: selected)
					continue
				}
			}
			paneTabDocuments[paneID(pane)] = [fallbackDocument]
			paneSelectedDocuments[paneID(pane)] = ObjectIdentifier(fallbackDocument)
			installPane(pane, document: fallbackDocument)
		}
		if tabGroupScope == .pane {
			_ = paneCoordinator.focusPane(at: focusedPaneIndex ?? 0)
			syncActiveDocumentToFocusedPane()
		}
		syncTabGroupVisibility()
		refreshPaneTabBars()
		refreshLSPMissingBanner(for: document as? ItsyDocument ?? fallbackDocument)
		refreshLSPStatus(for: document as? ItsyDocument ?? fallbackDocument)
		focusEditor()
	}

	private func detachPaneDocuments() {
		for pane in paneCoordinator.panes {
			var documents = paneTabDocuments[paneID(pane)] ?? []
			if let document = document as? ItsyDocument, !documents.contains(where: { $0 === document }) {
				documents.append(document)
			}
			for document in documents {
				document.detach(pane.editorView)
			}
		}
	}

	private func document(atPersistedPath path: String?) -> ItsyDocument? {
		guard let path else {
			return nil
		}
		let target = URL(fileURLWithPath: path).standardizedFileURL.path
		return NSDocumentController.shared.documents.compactMap { $0 as? ItsyDocument }.first {
			$0.fileURL?.standardizedFileURL.path == target
		}
	}

	func focusEditor() {
		window?.makeFirstResponder(editorView)
	}

	private func rebuildFocusTraversal() {
		var targets: [NSView] = []
		if sidebarVisible {
			targets.append(fileTreeController.focusView)
		}
		if !tabBarView.isHidden, let tab = focusTarget(in: tabBarView) {
			targets.append(tab)
		}
		if let findBarController, !findBarController.view.isHidden {
			targets.append(contentsOf: findBarController.focusableViews)
		}
		for pane in paneCoordinator.panes {
			if !pane.tabBarController.view.isHidden, let tab = focusTarget(in: pane.tabBarController.view) {
				targets.append(tab)
			}
			targets.append(pane.editorView)
		}
		if !lspMissingBanner.isHidden, let button = focusTarget(in: lspMissingBanner) {
			targets.append(button)
		}
		if !recoveryBanner.isHidden, let button = focusTarget(in: recoveryBanner) {
			targets.append(button)
		}
		if !lspStatusButton.isHidden {
			targets.append(lspStatusButton)
		}
		focusTraversalTargetsForTesting = targets
		guard let first = targets.first else {
			return
		}
		guard targets.count > 1 else {
			first.nextKeyView = first
			return
		}
		for index in targets.indices {
			targets[index].nextKeyView = targets[(index + 1) % targets.count]
		}
	}

	private func focusTarget(in view: NSView) -> NSView? {
		if view.canBecomeKeyView {
			return view
		}
		for child in view.subviews {
			if let target = focusTarget(in: child) {
				return target
			}
		}
		return nil
	}

	private func focusEditor(_ targetView: MetalTextView) {
		paneCoordinator.focusPane(containing: targetView)
		syncActiveDocumentToFocusedPane()
		refreshPaneTabBars()
		window?.makeFirstResponder(targetView)
	}

	private func installPane(_ pane: EditorPane, document: ItsyDocument) {
		recordBenchStage("editor_pane_install_begin")
		let view = pane.editorView
		document.attach(view)
		document.setLSPGutterDecorator(lspFoldGutterDecorator)
		document.lspSurfaceRefreshRequested = { [weak self, weak document] in
			if let document {
				self?.invalidateLSPSemanticState(for: document)
			}
			self?.scheduleLSPSemanticSurfaceRefresh()
		}
		document.lspDocumentSaved = { [weak self] in
			self?.notifyLSPDidSave()
		}
		lspFoldGutterDecorator.toggleFold = { [weak self] line in
			self?.toggleFold(startLine: line)
		}
		recordBenchStage("editor_pane_attach_end")
		recordBenchStage("editor_pane_preferences_begin")
		let editorSettings = currentEditorSettings()
		let preferences = EditorPreferences(settings: editorSettings)
		recordBenchStage("editor_pane_preferences_end")
		recordBenchStage("editor_pane_appearance_begin")
		view.configureEditorAppearance(
			fontName: preferences.fontName,
			fontSize: preferences.fontSize,
			showsLineNumbers: preferences.showLineNumbers
		)
		view.configureEditorBehavior(
			lineNumberMode: Self.metalLineNumberMode(preferences.lineNumberMode),
			wrapMode: Self.metalWrapMode(preferences.wrap),
			hardWrapColumn: preferences.wrapColumn
		)
		view.allowsMultipleSelections = editorSettings.multipleSelections
		view.applyEditorColorPalette(AppTheme.palette.editor)
		recordBenchStage("editor_pane_appearance_end")
		recordBenchStage("editor_pane_keymap_begin")
		view.keymapEngine = ItsyAppKeymap.makeEngine()
		recordBenchStage("editor_pane_keymap_end")
		recordBenchStage("editor_pane_callbacks_begin")
		view.commandRequested = { [weak self] commandID in
			self?.performKeymapCommand(commandID) ?? false
		}
		view.closeRequested = { [weak self, weak view, weak document] in
			guard let self, let view else {
				document?.close()
				return
			}
			if tabGroupScope == .pane, let pane = paneCoordinator.panes.first(where: { $0.editorView === view }), let selectedDocument = selectedDocument(for: pane) {
				closePaneDocument(ObjectIdentifier(selectedDocument), in: pane)
			} else {
				document?.close()
			}
		}
		view.focusRequested = { [weak self, weak view] in
			guard let self, let view else {
				return
			}
			_ = paneCoordinator.focusPane(containing: view)
			syncActiveDocumentToFocusedPane()
			refreshPaneTabBars()
		}
		view.contextMenuProvider = { [weak self, weak view] request in
			guard let self, let view else {
				return nil
			}
			return self.makeEditorContextMenu(request: request, in: view)
		}
		view.completionRequested = { [weak self, weak view] trigger in
			self?.requestCompletion(triggerCharacter: trigger, in: view) ?? false
		}
		view.snippetTabStopRequested = { [weak self, weak view] direction in
			self?.moveSnippetTabStop(direction: direction, in: view) ?? false
		}
		view.signatureHelpRequested = { [weak self, weak view] trigger in
			self?.requestSignatureHelp(triggerCharacter: trigger, in: view) ?? false
		}
		view.signatureHelpDismissRequested = { [weak self] in
			self?.closeSignatureHelpPopover()
		}
		installCompletionTriggersIfKnown(for: view, document: document)
		installSignatureHelpTriggersIfKnown(for: view, document: document)
		view.hoverCandidateChanged = { [weak self, weak view] candidate in
			self?.scheduleHover(candidate, in: view)
		}
		view.exCommandRequested = { [weak self] command in
			self?.performExCommand(command) ?? false
		}
		view.exCommandCompletionsProvider = {
			ItsyAppCommandBridge.availableCommandIDs()
		}
		view.exCommandLineRequested = { [weak self] completion in
			ItsyCommandPaletteBridge.requestExCommand(relativeTo: self?.window, completion: completion)
		}
		view.vimMarksWorkspaceRoot = ItsyWorkspaceController.currentRootURL
		view.undoTreeChanged = { [weak self] tree in
			self?.undoTreePanel?.update(tree: tree)
		}
		view.fileDropRequested = { [weak self] urls in
			self?.openDroppedFiles(urls) ?? false
		}
		scheduleLSPSemanticSurfaceRefresh()
		recordBenchStage("editor_pane_callbacks_end")
		recordBenchStage("editor_pane_install_end")
	}

	func applyEditorPreferences(_ preferences: EditorPreferences) {
		for pane in paneCoordinator.panes {
			pane.editorView.configureEditorAppearance(
				fontName: preferences.fontName,
				fontSize: preferences.fontSize,
				showsLineNumbers: preferences.showLineNumbers
			)
			pane.editorView.configureEditorBehavior(
				lineNumberMode: Self.metalLineNumberMode(preferences.lineNumberMode),
				wrapMode: Self.metalWrapMode(preferences.wrap),
				hardWrapColumn: preferences.wrapColumn
			)
			pane.editorView.applyEditorColorPalette(AppTheme.palette.editor)
		}
	}

	func reloadKeymap() {
		for pane in paneCoordinator.panes {
			pane.editorView.keymapEngine = ItsyAppKeymap.makeEngine()
		}
	}

	private static func metalLineNumberMode(_ mode: ItsySettings.LineNumberMode) -> MetalLineNumberMode {
		switch mode {
		case .off:
			return .off
		case .absolute:
			return .absolute
		case .relative:
			return .relative
		}
	}

	private static func metalWrapMode(_ mode: ItsySettings.WrapMode) -> MetalWrapMode {
		switch mode {
		case .none:
			return .none
		case .soft:
			return .soft
		case .hard:
			return .hard
		}
	}

	func applySettings(_ settings: ItsySettings) {
		let settings = settings.normalized()
		let editorSettings = settings.editorSettings(languageID: currentLanguageID())
		applyEditorPreferences(EditorPreferences(settings: editorSettings))
		let indentationUnit = editorSettings.useSpaces ? String(repeating: " ", count: editorSettings.tabWidth) : "\t"
		for pane in paneCoordinator.panes {
			pane.editorView.textEditBehaviorConfiguration = TextEditBehaviorConfiguration(
				autoPairs: editorSettings.autoPairs,
				smartIndent: editorSettings.smartIndent,
				indentationUnit: indentationUnit
			)
			pane.editorView.allowsMultipleSelections = editorSettings.multipleSelections
		}
		findSettings = settings.find
		findBarController?.applyDefaultOptions(findSettings)
		applyTheme(AppTheme.palette)
		setTabGroupScope(settings.editor.tabGroups)
	}

	func applyTheme(_ palette: AppThemePalette) {
		if let window {
			AppThemeApplier.apply(palette, to: window)
		}
		tabBarView.layer?.backgroundColor = palette.tabInactiveBackground.cgColor
		editorContainer.wantsLayer = true
		editorContainer.layer?.backgroundColor = palette.editor.nsBackgroundColor.cgColor
		statusBarView.layer?.backgroundColor = palette.statusBackground.cgColor
		statusBarLabel.textColor = palette.statusForeground
		lspStatusButton.contentTintColor = palette.statusForeground
		lspMissingBanner.applyTheme(palette)
		recoveryBanner.applyTheme(palette)
		findBarController?.applyTheme(palette)
		fileTreeController.applyTheme(palette)
		for pane in paneCoordinator.panes {
			pane.tabBarController.applyTheme(palette)
			pane.editorView.applyEditorColorPalette(palette.editor)
		}
		refreshPaneTabBars()
	}

	private func configurePaneTabBar(_ pane: EditorPane) {
		pane.tabBarController.selectTab = { [weak self, weak paneViewController = pane.viewController] tabID in
			guard
				let self,
				let paneViewController,
				let pane = self.pane(for: paneViewController)
			else {
				return
			}
			_ = self.paneCoordinator.focusPane(containing: pane.editorView)
			self.selectPaneDocument(tabID, in: pane)
		}
		pane.tabBarController.closeTab = { [weak self, weak paneViewController = pane.viewController] tabID in
			guard
				let self,
				let paneViewController,
				let pane = self.pane(for: paneViewController)
			else {
				return
			}
			_ = self.paneCoordinator.focusPane(containing: pane.editorView)
			self.closePaneDocument(tabID, in: pane)
		}
	}

	private func setTabGroupScope(_ scope: ItsySettings.TabGroupScope) {
		guard scope != tabGroupScope else {
			syncTabGroupVisibility()
			refreshPaneTabBars()
			return
		}
		let activeDocument = selectedDocument(for: paneCoordinator.activePane) ?? document as? ItsyDocument
		tabGroupScope = scope
		syncTabGroupVisibility()
		if scope == .pane {
			for pane in paneCoordinator.panes {
				configurePaneTabBar(pane)
				ensurePaneTabs(for: pane, preferredDocument: activeDocument)
			}
			refreshPaneTabBars()
		} else if let activeDocument {
			for pane in paneCoordinator.panes {
				if let oldDocument = selectedDocument(for: pane), oldDocument !== activeDocument {
					oldDocument.detach(pane.editorView)
				}
				installPane(pane, document: activeDocument)
			}
			activateWindowDocument(activeDocument)
			ItsyTabCoordinator.refresh()
		}
	}

	private func syncTabGroupVisibility() {
		tabBarView.isHidden = tabGroupScope == .pane
		for pane in paneCoordinator.panes {
			pane.tabBarController.view.isHidden = tabGroupScope == .window
		}
		rebuildFocusTraversal()
	}

	private func refreshPaneTabBars() {
		let paneIDs = Set(paneCoordinator.panes.map(paneID(_:)))
		paneTabDocuments = paneTabDocuments.filter { paneIDs.contains($0.key) }
		paneSelectedDocuments = paneSelectedDocuments.filter { paneIDs.contains($0.key) }
		let openIDs = Set((NSDocumentController.shared.documents.compactMap { $0 as? ItsyDocument }).map(ObjectIdentifier.init))
		for pane in paneCoordinator.panes {
			ensurePaneTabs(for: pane, preferredDocument: document as? ItsyDocument)
			let id = paneID(pane)
			let documents = (paneTabDocuments[id] ?? []).filter { openIDs.contains(ObjectIdentifier($0)) }
			paneTabDocuments[id] = documents
			if let selected = paneSelectedDocuments[id], !documents.contains(where: { ObjectIdentifier($0) == selected }) {
				paneSelectedDocuments[id] = documents.first.map(ObjectIdentifier.init)
			}
			let selectedID = paneSelectedDocuments[id]
			let tabs = documents.map { tab(for: $0, selected: ObjectIdentifier($0) == selectedID) }
			pane.tabBarController.setTabs(tabs)
			pane.tabBarController.view.isHidden = tabGroupScope == .window
		}
		rebuildFocusTraversal()
	}

	private func ensurePaneTabs(for pane: EditorPane, preferredDocument: ItsyDocument?) {
		let id = paneID(pane)
		if paneTabDocuments[id]?.isEmpty != false, let preferredDocument {
			paneTabDocuments[id] = [preferredDocument]
			paneSelectedDocuments[id] = ObjectIdentifier(preferredDocument)
			return
		}
		if paneSelectedDocuments[id] == nil {
			paneSelectedDocuments[id] = paneTabDocuments[id]?.first.map(ObjectIdentifier.init)
		}
	}

	private func selectPaneDocument(_ tabID: ObjectIdentifier, in pane: EditorPane) {
		guard let document = document(for: tabID) else {
			return
		}
		selectPaneDocument(document, in: pane, addIfMissing: false)
	}

	private func selectPaneDocument(_ newDocument: ItsyDocument, in pane: EditorPane, addIfMissing: Bool) {
		let id = paneID(pane)
		var documents = paneTabDocuments[id] ?? []
		if !documents.contains(where: { $0 === newDocument }) {
			guard addIfMissing else {
				return
			}
			documents.append(newDocument)
			paneTabDocuments[id] = documents
		}
		let oldDocument = selectedDocument(for: pane)
		if oldDocument !== newDocument {
			oldDocument?.detach(pane.editorView)
		}
		paneSelectedDocuments[id] = ObjectIdentifier(newDocument)
		activateWindowDocument(newDocument)
		if oldDocument !== newDocument {
			installPane(pane, document: newDocument)
		}
		refreshLSPMissingBanner(for: newDocument)
		refreshLSPStatus(for: newDocument)
		refreshPaneTabBars()
	}

	private func closePaneDocument(_ tabID: ObjectIdentifier, in pane: EditorPane) {
		let id = paneID(pane)
		guard let closingDocument = document(for: tabID) else {
			return
		}
		var documents = paneTabDocuments[id] ?? []
		guard let index = documents.firstIndex(where: { ObjectIdentifier($0) == tabID }) else {
			return
		}
		documents.remove(at: index)
		paneTabDocuments[id] = documents
		let wasSelected = paneSelectedDocuments[id] == tabID
		if wasSelected {
			if !documents.isEmpty {
				let replacementIndex = min(index, documents.count - 1)
				selectPaneDocument(documents[replacementIndex], in: pane, addIfMissing: false)
			} else {
				_ = closeActivePane()
			}
		}
		let stillOpenInPane = paneTabDocuments.values.joined().contains { $0 === closingDocument }
		if !stillOpenInPane {
			closingDocument.close()
		}
		refreshPaneTabBars()
	}

	private func selectedDocument(for pane: EditorPane) -> ItsyDocument? {
		let id = paneID(pane)
		guard let selectedID = paneSelectedDocuments[id] else {
			return nil
		}
		return paneTabDocuments[id]?.first { ObjectIdentifier($0) == selectedID }
	}

	private func syncActiveDocumentToFocusedPane() {
		guard tabGroupScope == .pane else {
			return
		}
		ensurePaneTabs(for: paneCoordinator.activePane, preferredDocument: document as? ItsyDocument)
		guard let selectedDocument = selectedDocument(for: paneCoordinator.activePane) else {
			return
		}
		activateWindowDocument(selectedDocument)
		refreshLSPMissingBanner(for: selectedDocument)
		refreshLSPStatus(for: selectedDocument)
	}

	private func activateWindowDocument(_ newDocument: ItsyDocument) {
		if document as? ItsyDocument !== newDocument {
			(document as? ItsyDocument)?.removeWindowController(self)
			if !newDocument.windowControllers.contains(where: { $0 === self }) {
				newDocument.addWindowController(self)
			}
		}
		window?.title = newDocument.fileURL?.lastPathComponent ?? newDocument.displayName
		window?.representedURL = newDocument.fileURL
	}

	private func paneID(_ pane: EditorPane) -> ObjectIdentifier {
		ObjectIdentifier(pane.viewController)
	}

	private func pane(for viewController: NSViewController) -> EditorPane? {
		paneCoordinator.panes.first { $0.viewController === viewController }
	}

	private func document(for tabID: ObjectIdentifier) -> ItsyDocument? {
		NSDocumentController.shared.documents.compactMap { $0 as? ItsyDocument }.first { ObjectIdentifier($0) == tabID }
	}

	private func tab(for document: ItsyDocument, selected: Bool) -> ItsyTab {
		let id = ObjectIdentifier(document)
		if let tab = latestTabs.first(where: { $0.id == id }) {
			return ItsyTab(id: id, title: tab.title, isDirty: tab.isDirty, isSelected: selected)
		}
		return ItsyTab(id: id, title: Self.title(for: document), isDirty: document.isDocumentEdited, isSelected: selected)
	}

	private static func title(for document: ItsyDocument) -> String {
		if let fileName = document.fileURL?.lastPathComponent {
			return fileName
		}
		return document.displayName
	}

	private func ensureFindBarController() -> FindBarController {
		if let findBarController {
			return findBarController
		}
		findSettings = currentSettings().find
		let controller = FindBarController()
		controller.view.translatesAutoresizingMaskIntoConstraints = false
		editorContainer.addSubview(controller.view)
		NSLayoutConstraint.activate([
			controller.view.leadingAnchor.constraint(equalTo: editorContainer.leadingAnchor),
			controller.view.trailingAnchor.constraint(equalTo: editorContainer.trailingAnchor),
			controller.view.topAnchor.constraint(equalTo: editorContainer.topAnchor),
		])
		if let window {
			controller.attach(to: window)
		}
		controller.currentEditorView = { [weak self] in self?.editorView }
		controller.focusEditor = { [weak self] in self?.focusEditor() }
		controller.visibilityDidChange = { [weak self] in self?.rebuildFocusTraversal() }
		controller.applyDefaultOptions(findSettings)
		findBarController = controller
		rebuildFocusTraversal()
		return controller
	}

	private func splitActivePane(vertical: Bool) {
		let sourcePane = paneCoordinator.activePane
		let sourceDocument = tabGroupScope == .pane
			? selectedDocument(for: sourcePane) ?? document as? ItsyDocument
			: document as? ItsyDocument
		guard let sourceDocument else {
			return
		}
		let pane = paneCoordinator.splitActive(vertical: vertical)
		configurePaneTabBar(pane)
		if tabGroupScope == .pane {
			paneTabDocuments[paneID(pane)] = [sourceDocument]
			paneSelectedDocuments[paneID(pane)] = ObjectIdentifier(sourceDocument)
		}
		installPane(pane, document: sourceDocument)
		syncTabGroupVisibility()
		refreshPaneTabBars()
		focusEditor()
		ItsyWorkspaceController.persistWindowState(from: self)
	}

	private func closeActivePane() -> Bool {
		if tabGroupScope == .pane {
			let closingPane = paneCoordinator.activePane
			let closingDocument = selectedDocument(for: closingPane)
			guard let pane = paneCoordinator.closeActive() else {
				return false
			}
			closingDocument?.detach(pane.editorView)
			paneTabDocuments[paneID(pane)] = nil
			paneSelectedDocuments[paneID(pane)] = nil
			syncTabGroupVisibility()
			syncActiveDocumentToFocusedPane()
			refreshPaneTabBars()
			focusEditor()
			ItsyWorkspaceController.persistWindowState(from: self)
			return true
		}
		guard let document = document as? ItsyDocument, let pane = paneCoordinator.closeActive() else {
			return false
		}
		document.detach(pane.editorView)
		focusEditor()
		ItsyWorkspaceController.persistWindowState(from: self)
		return true
	}

	private func closeOtherPanes() {
		if tabGroupScope == .pane {
			for pane in paneCoordinator.closeOtherPanes() {
				selectedDocument(for: pane)?.detach(pane.editorView)
				paneTabDocuments[paneID(pane)] = nil
				paneSelectedDocuments[paneID(pane)] = nil
			}
			syncTabGroupVisibility()
			syncActiveDocumentToFocusedPane()
			refreshPaneTabBars()
			focusEditor()
			ItsyWorkspaceController.persistWindowState(from: self)
			return
		}
		guard let document = document as? ItsyDocument else {
			return
		}
		for pane in paneCoordinator.closeOtherPanes() {
			document.detach(pane.editorView)
		}
		focusEditor()
		ItsyWorkspaceController.persistWindowState(from: self)
	}

	@discardableResult
	func moveActivePaneTab(toAdjacentPane delta: Int) -> Bool {
		guard tabGroupScope == .pane, paneCoordinator.panes.count > 1 else {
			return false
		}
		let source = paneCoordinator.activePane
		guard let document = selectedDocument(for: source), let sourceIndex = paneCoordinator.panes.firstIndex(where: { $0.editorView === source.editorView }) else {
			return false
		}
		let destinationIndex = (sourceIndex + delta + paneCoordinator.panes.count) % paneCoordinator.panes.count
		guard destinationIndex != sourceIndex else {
			return false
		}
		let destination = paneCoordinator.panes[destinationIndex]
		let sourceID = paneID(source)
		var sourceDocuments = paneTabDocuments[sourceID] ?? []
		guard let removedIndex = sourceDocuments.firstIndex(where: { $0 === document }) else {
			return false
		}
		sourceDocuments.remove(at: removedIndex)
		selectPaneDocument(document, in: destination, addIfMissing: true)

		if sourceDocuments.isEmpty {
			document.detach(source.editorView)
			paneTabDocuments[sourceID] = nil
			paneSelectedDocuments[sourceID] = nil
			_ = paneCoordinator.focusPane(containing: source.editorView)
			_ = paneCoordinator.closeActive()
		} else {
			let replacement = sourceDocuments[min(removedIndex, sourceDocuments.count - 1)]
			paneTabDocuments[sourceID] = sourceDocuments
			paneSelectedDocuments[sourceID] = ObjectIdentifier(replacement)
			document.detach(source.editorView)
			installPane(source, document: replacement)
		}
		_ = paneCoordinator.focusPane(containing: destination.editorView)
		syncActiveDocumentToFocusedPane()
		syncTabGroupVisibility()
		refreshPaneTabBars()
		focusEditor()
		ItsyWorkspaceController.persistWindowState(from: self)
		return true
	}

	private func focusAdjacentPane(delta: Int) {
		_ = paneCoordinator.focusAdjacent(delta: delta)
		syncActiveDocumentToFocusedPane()
		refreshPaneTabBars()
		focusEditor()
	}

	func performEditorMotion(_ motion: Motion) {
		editorView.performMotion(motion)
		focusEditor()
	}

	func toggleFindBar() {
		ensureFindBarController().toggle()
	}

	func toggleUndoTree(_: Any?) {
		let panel = undoTreePanel ?? UndoTreePanelController()
		panel.jumpRequested = { [weak self] nodeID in
			guard let self, editorView.restoreUndoTreeNode(nodeID) else {
				return
			}
			window?.makeFirstResponder(editorView)
		}
		undoTreePanel = panel
		panel.toggle(relativeTo: window, tree: editorView.editor.history.tree)
	}

	@discardableResult
	func selectTab(atDisplayIndex index: Int) -> Bool {
		guard tabGroupScope == .pane else {
			return ItsyTabCoordinator.selectDocument(atDisplayIndex: index)
		}
		let pane = paneCoordinator.activePane
		let documents = paneTabDocuments[paneID(pane)] ?? []
		guard documents.indices.contains(index) else {
			return false
		}
		selectPaneDocument(documents[index], in: pane, addIfMissing: false)
		focusEditor()
		return true
	}

	func selectAdjacentTab(delta: Int) {
		guard tabGroupScope == .pane else {
			ItsyTabCoordinator.selectAdjacentDocument(delta: delta)
			return
		}
		let pane = paneCoordinator.activePane
		let documents = paneTabDocuments[paneID(pane)] ?? []
		guard
			!documents.isEmpty,
			let selected = selectedDocument(for: pane),
			let index = documents.firstIndex(where: { $0 === selected })
		else {
			return
		}
		let next = (index + delta + documents.count) % documents.count
		selectPaneDocument(documents[next], in: pane, addIfMissing: false)
		focusEditor()
	}

	func closeActiveTabOrDocument() {
		guard tabGroupScope == .pane, let selectedDocument = selectedDocument(for: paneCoordinator.activePane) else {
			(document as? NSDocument)?.close()
			return
		}
		closePaneDocument(ObjectIdentifier(selectedDocument), in: paneCoordinator.activePane)
	}

	func findNext() {
		ensureFindBarController().findNext()
	}

	func findPrevious() {
		ensureFindBarController().findPrevious()
	}

	func startIncrementalSearch(direction: Int) {
		ensureFindBarController().startIncrementalSearch(direction: direction)
	}

	func selectAllFindMatches() {
		ensureFindBarController().selectAllMatches()
	}

	private func makeEditorContextMenu(request: TextContextMenuRequest, in view: MetalTextView) -> NSMenu {
		contextMenuEditorView = view
		focusEditor(view)
		let hasFileURL = (document as? ItsyDocument)?.fileURL != nil
		let menu = NSMenu()
		menu.autoenablesItems = false

		@discardableResult
		func addItem(
			_ title: String.LocalizationValue,
			action: Selector?,
			enabled: Bool = true,
			commandID: String? = nil,
			keyEquivalent: String = "",
			modifiers: NSEvent.ModifierFlags = []
		) -> NSMenuItem {
			let item = NSMenuItem(title: L10n.string(title), action: action, keyEquivalent: keyEquivalent)
			item.target = action == nil ? nil : self
			item.isEnabled = enabled
			item.representedObject = commandID
			item.keyEquivalentModifierMask = modifiers
			menu.addItem(item)
			return item
		}

		addItem("Go to Definition", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.definition")
		addItem("Go to Declaration", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.declaration")
		addItem("Go to Type Definition", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.typeDefinition")
		addItem("Go to Implementation", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.implementation")
		addItem("Find All References", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.references")
		menu.addItem(NSMenuItem.separator())
		addItem("Rename Symbol", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.rename")
		addItem("Format Selection", action: #selector(runContextCommand(_:)), enabled: hasFileURL && request.hasSelection, commandID: "lsp.formatSelection")
		addItem("Show Code Actions", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "lsp.codeAction")
		menu.addItem(NSMenuItem.separator())
		addItem("Cut", action: #selector(cutContextSelection(_:)), enabled: request.hasSelection, keyEquivalent: "x", modifiers: .command)
		addItem("Copy", action: #selector(copyContextSelection(_:)), enabled: request.hasSelection, keyEquivalent: "c", modifiers: .command)
		addItem("Copy and Trim", action: #selector(copyTrimmedContextSelection(_:)), enabled: request.hasSelection)
		addItem("Paste", action: #selector(pasteContextClipboard(_:)), enabled: NSPasteboard.general.string(forType: .string) != nil, keyEquivalent: "v", modifiers: .command)
		menu.addItem(NSMenuItem.separator())
		addItem("Reveal in Finder", action: #selector(revealContextFileInFinder(_:)), enabled: hasFileURL)
		addItem("Open in Terminal", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "terminal.openAtFileDirectory")
		addItem("Copy Permalink", action: nil, enabled: false)
		addItem("View File History", action: #selector(runContextCommand(_:)), enabled: hasFileURL, commandID: "git.fileHistory")
		return menu
	}

	@objc private func runContextCommand(_ sender: NSMenuItem) {
		guard let commandID = sender.representedObject as? String else {
			return
		}
		let targetView = contextMenuEditorView ?? editorView
		focusEditor(targetView)
		if commandID == "git.fileHistory" {
			_ = ItsyAppCommandBridge.requestRunCommand(commandID)
			return
		}
		if !performKeymapCommand(commandID) {
			_ = ItsyAppCommandBridge.requestRunCommand(commandID)
		}
	}

	@objc private func cutContextSelection(_: NSMenuItem) {
		let targetView = contextMenuEditorView ?? editorView
		_ = targetView.cutSelectedText()
		focusEditor(targetView)
	}

	@objc private func copyContextSelection(_: NSMenuItem) {
		let targetView = contextMenuEditorView ?? editorView
		_ = targetView.copySelectedText()
		focusEditor(targetView)
	}

	@objc private func copyTrimmedContextSelection(_: NSMenuItem) {
		let targetView = contextMenuEditorView ?? editorView
		_ = targetView.copySelectedText(trimmed: true)
		focusEditor(targetView)
	}

	@objc private func pasteContextClipboard(_: NSMenuItem) {
		let targetView = contextMenuEditorView ?? editorView
		_ = targetView.pasteTextFromPasteboard()
		focusEditor(targetView)
	}

	@objc private func revealContextFileInFinder(_: NSMenuItem) {
		guard let fileURL = (document as? ItsyDocument)?.fileURL else {
			return
		}
		NSWorkspace.shared.activateFileViewerSelecting([fileURL])
	}

	private func performKeymapCommand(_ commandID: String) -> Bool {
		if let tabNumber = selectedTabNumber(commandID) {
			return selectTab(atDisplayIndex: tabNumber - 1)
		}
		switch commandID {
		case "file.new":
			NSDocumentController.shared.newDocument(nil)
		case "file.open":
			NSDocumentController.shared.openDocument(nil)
		case "file.newWindow":
			return NSApp.sendAction(#selector(AppCoordinator.newWindow(_:)), to: nil, from: self)
		case "app.quit":
			NSApp.terminate(nil)
		case "app.settings":
			return NSApp.sendAction(#selector(AppCoordinator.showSettings(_:)), to: nil, from: self)
		case "app.keyboardShortcuts":
			return NSApp.sendAction(#selector(AppCoordinator.showSettings(_:)), to: nil, from: self)
		case "view.commandPalette":
			return NSApp.sendAction(#selector(AppCoordinator.toggleCommandPalette(_:)), to: nil, from: self)
		case "nav.gotoFile", "nav.gotoLine", "nav.gotoSymbolWorkspace", "nav.gotoSymbolFile":
			return ItsyAppCommandBridge.requestRunCommand(commandID)
		case "terminal.toggle":
			return NSApp.sendAction(#selector(AppCoordinator.showTerminal(_:)), to: nil, from: self)
		case "terminal.newTab":
			return NSApp.sendAction(#selector(AppCoordinator.newTerminalTab(_:)), to: nil, from: self)
		case "terminal.splitHorizontal":
			return NSApp.sendAction(#selector(AppCoordinator.splitTerminalHorizontal(_:)), to: nil, from: self)
		case "terminal.splitVertical":
			return NSApp.sendAction(#selector(AppCoordinator.splitTerminalVertical(_:)), to: nil, from: self)
		case "terminal.find":
			return NSApp.sendAction(#selector(AppCoordinator.findInTerminal(_:)), to: nil, from: self)
		case "terminal.findNext":
			return NSApp.sendAction(#selector(AppCoordinator.findTerminalNext(_:)), to: nil, from: self)
		case "terminal.findPrevious":
			return NSApp.sendAction(#selector(AppCoordinator.findTerminalPrevious(_:)), to: nil, from: self)
		case "terminal.openAtFileDirectory":
			return NSApp.sendAction(#selector(AppCoordinator.openTerminalAtFileDirectory(_:)), to: nil, from: self)
		case "terminal.revealCWD":
			return NSApp.sendAction(#selector(AppCoordinator.revealTerminalCWD(_:)), to: nil, from: self)
		case "file.nextBuffer":
			selectAdjacentTab(delta: 1)
		case "file.previousBuffer":
			selectAdjacentTab(delta: -1)
		case "view.sidebar.toggle":
			toggleSidebar()
		case "view.hiddenFiles.toggle":
			toggleHiddenFiles()
		case "history.undoTree.toggle":
			toggleUndoTree(nil)
		case "pane.close":
			if !closeActivePane() {
				(document as? NSDocument)?.close()
			}
		case "pane.closeOthers":
			closeOtherPanes()
		case "pane.splitHorizontal":
			splitActivePane(vertical: false)
		case "pane.splitVertical":
			splitActivePane(vertical: true)
		case "pane.focusRight", "pane.focusDown", "pane.focusNext":
			focusAdjacentPane(delta: 1)
		case "pane.focusLeft", "pane.focusUp", "pane.focusPrevious":
			focusAdjacentPane(delta: -1)
		case "pane.moveTabNext":
			return moveActivePaneTab(toAdjacentPane: 1)
		case "pane.moveTabPrevious":
			return moveActivePaneTab(toAdjacentPane: -1)
		case "edit.find":
			toggleFindBar()
		case "edit.findNext":
			findNext()
		case "edit.findPrevious":
			findPrevious()
		case "vim.searchForward":
			startIncrementalSearch(direction: 1)
		case "vim.searchBackward":
			startIncrementalSearch(direction: -1)
		case "emacs.isearchForward":
			startIncrementalSearch(direction: 1)
		case "emacs.isearchBackward":
			startIncrementalSearch(direction: -1)
		case "edit.selectAllFindMatches":
			selectAllFindMatches()
		case "lsp.completion":
			return requestCompletion(triggerCharacter: nil, in: editorView)
		case "lsp.hover":
			let offset = editorView.editor.selections.primary.head
			return requestHover(at: offset, positioningRect: editorView.positioningRectForUTF8Offset(offset), in: editorView)
		case "lsp.definition":
			return requestNavigation(.definition, in: editorView)
		case "lsp.declaration":
			return requestNavigation(.declaration, in: editorView)
		case "lsp.typeDefinition":
			return requestNavigation(.typeDefinition, in: editorView)
		case "lsp.implementation":
			return requestNavigation(.implementation, in: editorView)
		case "nav.back":
			return navigateBack()
		case "nav.forward":
			return navigateForward()
		case "lsp.references":
			return findAllReferences(nil)
		case "lsp.callHierarchy":
			return findCallHierarchy(nil)
		case "lsp.rename":
			return renameSymbol(nil)
		case "lsp.formatDocument":
			return formatDocument(nil)
		case "lsp.formatSelection", "vim.format.line":
			return formatSelection(nil)
		case "git.stashSave", "git.stashCurrent":
			return NSApp.sendAction(#selector(GitCoordinator.stashCurrentGitChanges(_:)), to: nil, from: self)
		case "git.stashApply":
			return NSApp.sendAction(#selector(GitCoordinator.applyLatestGitStash(_:)), to: nil, from: self)
		case "git.stashPop":
			return NSApp.sendAction(#selector(GitCoordinator.popLatestGitStash(_:)), to: nil, from: self)
		case "lsp.codeAction":
			return showCodeActions(nil)
		case "problems.next", "problems.previous":
			return ItsyAppCommandBridge.requestRunCommand(commandID)
		case "vim.fold.close":
			return closeFoldAtCursor()
		case "vim.fold.open":
			return openFoldAtCursor()
		case "vim.fold.toggle":
			return toggleFoldAtCursor()
		default:
			if commandID.hasPrefix("extension:") {
				return ItsyAppCommandBridge.requestRunCommand(commandID)
			}
			return false
		}
		return true
	}

	private func selectedTabNumber(_ commandID: String) -> Int? {
		guard commandID.hasPrefix("file.selectTab.") else {
			return nil
		}
		return Int(commandID.dropFirst("file.selectTab.".count))
	}

	@discardableResult
	func findAllReferences(_: Any?) -> Bool {
		requestReferences(at: editorView.editor.selections.primary.head, in: editorView)
	}

	@discardableResult
	func findCallHierarchy(_: Any?) -> Bool {
		requestCallHierarchy(at: editorView.editor.selections.primary.head, in: editorView)
	}

	@discardableResult
	func renameSymbol(_: Any?) -> Bool {
		requestRename(in: editorView)
	}

	@discardableResult
	func formatDocument(_: Any?) -> Bool {
		requestFormatDocument(in: editorView)
	}

	@discardableResult
	func formatSelection(_: Any?) -> Bool {
		requestFormatSelection(in: editorView)
	}

	@discardableResult
	func showCodeActions(_: Any?) -> Bool {
		requestCodeActions(in: editorView)
	}

	@discardableResult
	private func closeFoldAtCursor() -> Bool {
		setFoldAtCursor(collapsed: true)
	}

	@discardableResult
	private func openFoldAtCursor() -> Bool {
		setFoldAtCursor(collapsed: false)
	}

	@discardableResult
	private func toggleFoldAtCursor() -> Bool {
		guard
			let document = document as? ItsyDocument,
			let uri = document.fileURL?.standardizedFileURL.absoluteString,
			let range = foldRangeAtCursor(uri: uri)
		else {
			return false
		}
		toggleFold(startLine: range.startLine)
		return true
	}

	private func setFoldAtCursor(collapsed: Bool) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let uri = document.fileURL?.standardizedFileURL.absoluteString,
			let range = foldRangeAtCursor(uri: uri)
		else {
			return false
		}
		var starts = collapsedFoldStartsByURI[uri, default: []]
		if collapsed {
			starts.insert(range.startLine)
		} else {
			starts.remove(range.startLine)
		}
		collapsedFoldStartsByURI[uri] = starts
		applyFoldState(uri: uri, document: document)
		ItsyWorkspaceController.persistWindowState(from: self)
		return true
	}

	private func toggleFold(startLine: Int) {
		guard let document = document as? ItsyDocument, let uri = document.fileURL?.standardizedFileURL.absoluteString else {
			return
		}
		var starts = collapsedFoldStartsByURI[uri, default: []]
		if starts.contains(startLine) {
			starts.remove(startLine)
		} else {
			starts.insert(startLine)
		}
		collapsedFoldStartsByURI[uri] = starts
		applyFoldState(uri: uri, document: document)
		ItsyWorkspaceController.persistWindowState(from: self)
	}

	private func openDroppedFiles(_ urls: [URL]) -> Bool {
		var didOpen = false
		for url in urls {
			var isDirectory: ObjCBool = false
			FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
			if isDirectory.boolValue {
				ItsyWorkspaceController.addWorkspaceRoot(url)
				didOpen = true
			} else {
				didOpen = ItsyWorkspaceController.openFile(at: url) || didOpen
			}
		}
		return didOpen
	}

	private func foldRangeAtCursor(uri: String) -> LSPFoldingRange? {
		let line = editorView.editor.textStorage.line(forOffset: editorView.editor.selections.primary.head)
		return foldingRangesByURI[uri]?
			.filter { $0.startLine <= line && line <= $0.endLine && $0.endLine > $0.startLine }
			.sorted { ($0.endLine - $0.startLine) < ($1.endLine - $1.startLine) }
			.first
	}

	private func applyFoldState(uri: String, document: ItsyDocument) {
		let ranges = foldingRangesByURI[uri] ?? []
		let collapsedStarts = collapsedFoldStartsByURI[uri, default: []]
		let hidden = ranges.compactMap { range -> Range<Int>? in
			guard collapsedStarts.contains(range.startLine), range.endLine > range.startLine else {
				return nil
			}
			return (range.startLine + 1) ..< (range.endLine + 1)
		}
		lspFoldGutterDecorator.ranges = ranges
		lspFoldGutterDecorator.collapsedStartLines = collapsedStarts
		document.setLSPGutterDecorator(lspFoldGutterDecorator)
		document.setLSPFoldedLineRanges(hidden)
	}

	@MainActor
	func workspaceSymbols(matching query: String) async throws -> [WorkspaceSymbol] {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL
		else {
			return []
		}
		let session = try await ensureLSPSession(for: fileURL)
		let content = editorStorageString(editorView.editor)
		try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
		let symbols = try await Self.lspManager.symbols(matching: query, in: fileURL)
		return LSPSymbolAdapter.workspaceSymbols(from: symbols, root: session.key.workspaceRoot)
	}

	@MainActor
	func fileSymbolsFromLSP() async throws -> [WorkspaceSymbol]? {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let key = await Self.lspManager.sessionKey(for: fileURL),
			await Self.lspManager.status(of: key) == .running,
			let client = await Self.lspManager.existingClient(for: key)
		else {
			return nil
		}
		let content = editorStorageString(editorView.editor)
		try await syncLSPDocument(client: client, key: key, url: fileURL, content: content)
		let result = try await client
			.documentSymbol(textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString))
		let relativePath = LSPDiagnosticsAggregator.relativePath(
			forURI: fileURL.standardizedFileURL.absoluteString,
			root: key.workspaceRoot
		) ?? fileURL.lastPathComponent
		switch result {
		case let .documentSymbols(symbols):
			return LSPSymbolAdapter.workspaceSymbols(from: symbols, relativePath: relativePath)
		case let .symbolInformation(info):
			return LSPSymbolAdapter.workspaceSymbols(from: info, root: key.workspaceRoot)
		case .none:
			return []
		}
	}

	@discardableResult
	private func requestCompletion(triggerCharacter: String?, forIncomplete: Bool = false,
	                               in targetView: MetalTextView?) -> Bool
	{
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		let snippetItems = snippetCompletionItems(
			fileURL: fileURL,
			content: content,
			cursorOffset: cursorOffset,
			includeEmptyPrefix: triggerCharacter == nil
		)
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: cursorOffset, in: content)
		let context = completionContext(triggerCharacter: triggerCharacter, forIncomplete: forIncomplete)
		cancelCompletionRequest()
		completionRequestGeneration += 1
		let generation = completionRequestGeneration
		completionRequestTask = Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: cursorOffset) else {
					return
				}
				let params = LSPCompletionParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position,
					context: context
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentCompletion,
					params: LSPAny(encoding: params)
				)
				let result = try LSPCompletionResult(result: response.result)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == completionRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					showCompletionPopup(
						result: completionResult(result, appending: snippetItems),
						in: targetView,
						sessionKey: session.key,
						requestContext: requestContext
					)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == completionRequestGeneration, !Task.isCancelled else {
						return
					}
					let result = completionResult(.none, appending: snippetItems)
					if !result.items.isEmpty, let targetView {
						showCompletionPopup(result: result, in: targetView, sessionKey: nil, requestContext: nil)
					} else {
						completionPopup?.dismiss()
					}
					handleLSPRequestError(error)
					NSLog("completion failed: \(error)")
				}
			}
		}
		return true
	}

	private func cancelCompletionRequest() {
		completionRequestTask?.cancel()
		completionRequestTask = nil
	}

	private func completionContext(triggerCharacter: String?, forIncomplete: Bool) -> LSPCompletionContext {
		if forIncomplete {
			return LSPCompletionContext(triggerKind: .triggerForIncompleteCompletions)
		}
		if let triggerCharacter {
			return LSPCompletionContext(triggerKind: .triggerCharacter, triggerCharacter: triggerCharacter)
		}
		return LSPCompletionContext(triggerKind: .invoked)
	}

	private func completionResult(_ result: LSPCompletionResult,
	                              appending snippetItems: [LSPCompletionItem]) -> LSPCompletionResult
	{
		let items = result.items + snippetItems
		if result.isIncomplete {
			return .list(LSPCompletionList(isIncomplete: true, items: items))
		}
		return items.isEmpty ? .none : .items(items)
	}

	private func snippetCompletionItems(
		fileURL: URL,
		content: String,
		cursorOffset: Int,
		includeEmptyPrefix: Bool
	) -> [LSPCompletionItem] {
		guard let languageID = Self.snippetLanguageRegistry.languageID(for: fileURL) else {
			return []
		}
		let prefix = completionPrefix(in: content, cursorOffset: cursorOffset)
		guard includeEmptyPrefix || !prefix.isEmpty else {
			return []
		}
		let snippets = SnippetRegistry.discover(
			languageID: languageID,
			workspaceRoot: ItsyWorkspaceController.currentRootURL
		)
		return SnippetCompletionMapper.completionItems(from: snippets, matching: prefix)
	}

	@discardableResult
	private func requestRename(in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		guard let fallbackRange = identifierRange(in: content, near: cursorOffset) else {
			return false
		}
		let renameOffset = min(max(cursorOffset, fallbackRange.lowerBound), fallbackRange.upperBound - 1)
		let uri = fileURL.standardizedFileURL.absoluteString
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: renameOffset, in: content)
		let rect = targetView.positioningRectForUTF8Offset(fallbackRange.lowerBound)
		Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(
					for: session.key,
					url: fileURL,
					content: content,
					cursorOffset: cursorOffset
				) else {
					return
				}
				let prepared = try? await session.client.prepareRename(uri: uri, position: position)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					let range = prepared?.range.flatMap { LSPTextEditApply.utf8Range(for: $0, in: content) } ?? fallbackRange
					let initialName = prepared?.placeholder ?? substring(in: content, range: range)
					showRenamePopover(initialName: initialName, positioningRect: rect, in: targetView) { [
						weak self,
						weak targetView
					] newName in
						self?.requestRenameApply(
							newName: newName,
							fileURL: fileURL,
							uri: uri,
							position: position,
							requestContext: requestContext,
							sessionKey: session.key,
							in: targetView
						)
					}
				}
			} catch {
				await MainActor.run { [weak self] in
					self?.handleLSPRequestError(error)
					NSLog("rename prepare failed: \(error)")
				}
			}
		}
		return true
	}

	private func showRenamePopover(
		initialName: String,
		positioningRect: NSRect,
		in targetView: MetalTextView,
		submit: @escaping (String) -> Void
	) {
		renamePopover?.close()
		var popover: NSPopover!
		let controller = RenamePopoverController(
			initialName: initialName,
			submit: { [weak self] newName in
				popover.close()
				self?.renamePopover = nil
				submit(newName)
			},
			cancel: { [weak self] in
				popover.close()
				self?.renamePopover = nil
				self?.focusEditor()
			}
		)
		popover = NSPopover()
		popover.behavior = .transient
		popover.animates = false
		popover.contentViewController = controller
		popover.show(relativeTo: positioningRect, of: targetView, preferredEdge: .maxY)
		renamePopover = popover
	}

	private func requestRenameApply(
		newName: String,
		fileURL: URL,
		uri: String,
		position: LSPPosition,
		requestContext: LSPRequestContext,
		sessionKey: LSPSessionKey,
		in targetView: MetalTextView?
	) {
		guard let targetView else {
			return
		}
		Task { [weak self, weak targetView] in
			do {
				guard
					let self,
					let targetView,
					isLSPRequestCurrent(requestContext, for: sessionKey, in: targetView)
				else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				guard session.key == sessionKey else {
					return
				}
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: requestContext.content)
				let edit = try await session.client.rename(uri: uri, position: position, newName: newName)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						let edit,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					_ = applyWorkspaceEdit(edit, sessionKey: session.key)
					focusEditor()
				}
			} catch {
				await MainActor.run { [weak self] in
					self?.handleLSPRequestError(error)
					NSLog("rename failed: \(error)")
				}
			}
		}
	}

	@discardableResult
	private func requestFormatDocument(in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		let uri = fileURL.standardizedFileURL.absoluteString
		let options = lspFormattingOptions()
		cancelFormattingRequest()
		let generation = formattingRequestGeneration
		formattingRequestTask = Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				if let error = formattingCapabilitiesBySession[session.key]?.requestError(for: .document) {
					throw error
				}
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: cursorOffset) else {
					return
				}
				let edits = try await session.client.formatDocument(uri: uri, options: options)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == formattingRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					_ = applyTextEdits(edits, uri: uri, sessionKey: session.key, operation: "format document")
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == formattingRequestGeneration, !Task.isCancelled else {
						return
					}
					showLSPOperationFailure("format document", error: error)
					handleLSPRequestError(error)
					NSLog("format document failed: \(error)")
				}
			}
		}
		return true
	}

	@discardableResult
	private func requestFormatSelection(in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		let selection = targetView.editor.selections.primary.range
		let range = selection.isEmpty ? currentLineRange(in: targetView.editor) : selection
		let uri = fileURL.standardizedFileURL.absoluteString
		let options = lspFormattingOptions()
		let lspRange = lspRange(forUTF8Range: range, in: content)
		cancelFormattingRequest()
		let generation = formattingRequestGeneration
		formattingRequestTask = Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				if let error = formattingCapabilitiesBySession[session.key]?.requestError(for: .range) {
					throw error
				}
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: cursorOffset) else {
					return
				}
				let edits = try await session.client.formatRange(uri: uri, range: lspRange, options: options)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == formattingRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					_ = applyTextEdits(edits, uri: uri, sessionKey: session.key, operation: "format selection")
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == formattingRequestGeneration, !Task.isCancelled else {
						return
					}
					showLSPOperationFailure("format selection", error: error)
					handleLSPRequestError(error)
					NSLog("format range failed: \(error)")
				}
			}
		}
		return true
	}

	@discardableResult
	private func requestCodeActions(in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		let selection = targetView.editor.selections.primary.range
		let range = selection.isEmpty ? cursorOffset ..< cursorOffset : selection
		let uri = fileURL.standardizedFileURL.absoluteString
		let lspRange = lspRange(forUTF8Range: range, in: content)
		let rect = targetView.positioningRectForUTF8Offset(cursorOffset)
		cancelCodeActionRequest()
		codeActionRequestGeneration += 1
		let generation = codeActionRequestGeneration
		codeActionRequestTask = Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: cursorOffset) else {
					return
				}
				let response = try await session.client.codeActions(
					uri: uri,
					range: lspRange,
					context: LSPCodeActionContext(diagnostics: [])
				)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == codeActionRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					showCodeActionPopover(
						entries: response.entries,
						sessionKey: session.key,
						fileURL: fileURL,
						requestContext: requestContext,
						positioningRect: rect,
						in: targetView
					)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == codeActionRequestGeneration, !Task.isCancelled else {
						return
					}
					closeCodeActionPopover()
					handleLSPRequestError(error)
					NSLog("code actions failed: \(error)")
				}
			}
		}
		return true
	}

	private func showCodeActionPopover(
		entries: [LSPCodeActionEntry],
		sessionKey: LSPSessionKey,
		fileURL: URL,
		requestContext: LSPRequestContext,
		positioningRect: NSRect,
		in targetView: MetalTextView
	) {
		let enabled = entries.filter { entry in
			if case let .action(action) = entry {
				return action.disabled == nil
			}
			return true
		}
		guard !enabled.isEmpty else {
			closeCodeActionPopover()
			return
		}
		closeCodeActionPopover()
		var popover: NSPopover!
		let controller = CodeActionPopoverController(entries: enabled) { [weak self] entry in
			popover.close()
			self?.codeActionPopover = nil
			self?.applyCodeActionEntry(entry, sessionKey: sessionKey, fileURL: fileURL, requestContext: requestContext, targetView: targetView)
		}
		popover = NSPopover()
		popover.behavior = .transient
		popover.animates = false
		popover.contentViewController = controller
		popover.show(relativeTo: positioningRect, of: targetView, preferredEdge: .maxY)
		codeActionPopover = popover
	}

	private func applyCodeActionEntry(
		_ entry: LSPCodeActionEntry,
		sessionKey: LSPSessionKey,
		fileURL: URL,
		requestContext: LSPRequestContext,
		targetView: MetalTextView
	) {
		let resolveProvider = codeActionResolveEnabledBySession[sessionKey] == true
		Task { [weak self] in
			do {
				guard let self else {
					return
				}
				guard isLSPRequestCurrent(requestContext, for: sessionKey, in: targetView) else {
					showLSPOperationFailure("code action", error: LSPWorkspaceEditApplyError.staleDocumentVersion(
						uri: requestContext.uri,
						expected: requestContext.documentVersion,
						received: lspDocumentVersionsBySession[sessionKey]?[requestContext.uri] ?? -1
					))
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				guard session.key == sessionKey, isLSPRequestCurrent(requestContext, for: sessionKey, in: targetView) else {
					return
				}
				switch entry {
				case let .action(action):
					let resolved = try await resolvedCodeAction(action, client: session.client, resolveProvider: resolveProvider)
					let applied = await MainActor.run { [weak self] in
						guard let self, isLSPRequestCurrent(requestContext, for: sessionKey, in: targetView) else {
							return false
						}
						if let edit = resolved.edit {
							return applyWorkspaceEdit(edit, sessionKey: sessionKey, operation: "code action")
						}
						return true
					}
					guard applied else {
						return
					}
					if let command = resolved.command {
						try await session.client.executeCommand(command)
					}
				case let .command(command):
					try await session.client.executeCommand(command)
				}
				await MainActor.run { [weak self] in
					self?.focusEditor()
				}
			} catch {
				await MainActor.run { [weak self] in
					self?.showLSPOperationFailure("code action", error: error)
					self?.handleLSPRequestError(error)
					NSLog("code action apply failed: \(error)")
				}
			}
		}
	}

	private func resolvedCodeAction(_ action: LSPCodeAction, client: LSPProcessClient,
	                                resolveProvider: Bool) async throws -> LSPCodeAction
	{
		if resolveProvider, action.edit == nil {
			return try await client.resolveCodeAction(action)
		}
		return action
	}

	@discardableResult
	private func applyTextEdits(
		_ edits: [LSPTextEdit],
		uri: String,
		sessionKey: LSPSessionKey,
		operation: String
	) -> Bool {
		guard !edits.isEmpty else {
			return true
		}
		let applied = applyWorkspaceEdit(
			LSPWorkspaceEdit(changes: [uri: edits]),
			sessionKey: sessionKey,
			operation: operation
		)
		if applied {
			focusEditor()
		}
		return applied
	}

	private func cancelFormattingRequest() {
		formattingRequestGeneration += 1
		formattingRequestTask?.cancel()
		formattingRequestTask = nil
	}

	private func cancelCodeActionRequest() {
		codeActionRequestGeneration += 1
		codeActionRequestTask?.cancel()
		codeActionRequestTask = nil
	}

	private func lspFormattingOptions() -> LSPFormattingOptions {
		let settings = currentEditorSettings()
		return LSPFormattingOptions(tabSize: settings.tabWidth, insertSpaces: settings.useSpaces)
	}

	private func currentLineRange(in editor: Editor) -> Range<Int> {
		let line = editor.textStorage.line(forOffset: editor.selections.primary.head)
		return editor.textStorage.lineRange(line)
	}

	private func lspRange(forUTF8Range range: Range<Int>, in text: String) -> LSPRange {
		LSPRange(
			start: LSPTextEditApply.utf16Position(forUTF8Offset: range.lowerBound, in: text),
			end: LSPTextEditApply.utf16Position(forUTF8Offset: range.upperBound, in: text)
		)
	}

	private func scheduleLSPSemanticSurfaceRefresh() {
		lspSurfaceGeneration += 1
		let generation = lspSurfaceGeneration
		lspSurfaceRefreshTask?.cancel()
		lspSurfaceRefreshTask = Task { [weak self] in
			do {
				try await Task.sleep(nanoseconds: 150_000_000)
			} catch {
				return
			}
			guard !Task.isCancelled else {
				return
			}
			await MainActor.run { [weak self] in
				guard let self, generation == lspSurfaceGeneration else {
					return
				}
				requestLSPSemanticSurface(generation: generation)
			}
		}
	}

	private func invalidateLSPSemanticState(for document: ItsyDocument) {
		guard let uri = document.fileURL?.standardizedFileURL.absoluteString else {
			return
		}
		semanticTokenCache.invalidate(uri)
		document.setLSPSemanticHighlightSpans([])
		document.setLSPSemanticSurface(inlayHints: [], highlights: [])
	}

	private func requestLSPSemanticSurface(generation: Int) {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL
		else {
			return
		}
		let targetView = editorView
		let content = editorStorageString(targetView.editor)
		let uri = fileURL.standardizedFileURL.absoluteString
		let visibleRange = lspRange(forUTF8Range: visibleUTF8Range(in: targetView), in: content)
		let cursorOffset = targetView.editor.selections.primary.head
		let cursorPosition = LSPTextEditApply.utf16Position(forUTF8Offset: cursorOffset, in: content)
		Task { [weak self] in
			guard let self else {
				return
			}
			do {
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let capabilities = await MainActor.run {
					self.semanticSurfaceCapabilitiesBySession[session.key]
				}
				let semanticResult = try await semanticHighlightResult(
					client: session.client,
					uri: uri,
					content: content,
					visibleRange: visibleRange,
					capability: capabilities?.semanticTokens
				)
				let inlayHints = await capabilities?.inlayHint == true
					? ((try? session.client.inlayHints(uri: uri, range: visibleRange)) ?? [])
					: []
				let foldingRanges = await capabilities?.foldingRange == true
					? ((try? session.client.foldingRanges(uri: uri)) ?? [])
					: []
				let documentHighlights = await capabilities?.documentHighlight == true
					? ((try? session.client.documentHighlights(uri: uri, position: cursorPosition)) ?? [])
					: []
				await MainActor.run { [weak self] in
					guard let self, generation == lspSurfaceGeneration else {
						return
					}
					semanticTokenCache.replace(
						semanticResult.tokenState,
						for: uri,
						generation: generation,
						currentGeneration: lspSurfaceGeneration
					)
					applyLSPSemanticSurface(
						uri: uri,
						content: content,
						document: document,
						semanticSpans: semanticResult.spans,
						inlayHints: inlayHints,
						foldingRanges: foldingRanges,
						documentHighlights: documentHighlights
					)
				}
			} catch {
				await MainActor.run { [weak self] in
					self?.handleLSPRequestError(error)
				}
			}
		}
	}

	private func visibleUTF8Range(in view: MetalTextView) -> Range<Int> {
		let storage = view.editor.textStorage
		let lines = view.visibleLineRange
		guard !lines.isEmpty else {
			return 0 ..< storage.length
		}
		let lowerLine = min(max(lines.lowerBound, 0), max(0, storage.lineCount - 1))
		let upperLine = min(max(lines.upperBound, lowerLine), storage.lineCount)
		let lower = storage.offset(forLine: lowerLine)
		let upper = upperLine < storage.lineCount ? storage.offset(forLine: upperLine) : storage.length
		return lower ..< max(lower, upper)
	}

	private func semanticHighlightResult(
		client: LSPProcessClient,
		uri: String,
		content: String,
		visibleRange: LSPRange,
		capability: LSPSemanticTokensOptions?
	) async throws -> LSPSemanticHighlightResult {
		guard let capability else {
			return LSPSemanticHighlightResult(spans: [], tokenState: nil)
		}
		let tokens: LSPSemanticTokens?
		let tokenState: LSPSemanticTokenState?
		if capability.full?.isEnabled == true {
			let previous = await MainActor.run {
				self.semanticTokenCache.state(for: uri)
			}
			if capability.full?.supportsDelta == true, let previous, let resultId = previous.resultId {
				let result = try await client.semanticTokensDelta(uri: uri, previousResultId: resultId)
				switch result {
				case let .tokens(full):
					tokens = full
					tokenState = LSPSemanticTokenState(resultId: full.resultId, data: full.data)
				case let .delta(delta):
					let data = Self.applySemanticTokenDelta(delta, to: previous.data)
					tokens = LSPSemanticTokens(resultId: delta.resultId ?? previous.resultId, data: data)
					tokenState = LSPSemanticTokenState(resultId: delta.resultId ?? previous.resultId, data: data)
				case .none:
					tokens = nil
					tokenState = nil
				}
			} else {
				let full = try await client.semanticTokensFull(uri: uri)
				tokens = full
				tokenState = full.map { LSPSemanticTokenState(resultId: $0.resultId, data: $0.data) }
			}
		} else if capability.range?.isEnabled == true {
			tokens = try await client.semanticTokensRange(uri: uri, range: visibleRange)
			tokenState = nil
		} else {
			tokens = nil
			tokenState = nil
		}
		guard let tokens else {
			return LSPSemanticHighlightResult(spans: [], tokenState: tokenState)
		}
		return LSPSemanticHighlightResult(
			spans: Self.semanticHighlightSpans(from: tokens, legend: capability.legend, content: content),
			tokenState: tokenState
		)
	}

	private static func applySemanticTokenDelta(_ delta: LSPSemanticTokensDelta, to previous: [Int]) -> [Int] {
		var data = previous
		for edit in delta.edits.sorted(by: { $0.start > $1.start }) {
			let start = min(max(edit.start, 0), data.count)
			let end = min(max(start, start + edit.deleteCount), data.count)
			data.replaceSubrange(start ..< end, with: edit.data ?? [])
		}
		return data
	}

	private static func semanticHighlightSpans(
		from tokens: LSPSemanticTokens,
		legend: LSPSemanticTokensLegend,
		content: String
	) -> [TextHighlightSpan] {
		var spans: [TextHighlightSpan] = []
		var line = 0
		var character = 0
		var index = 0
		while index + 4 < tokens.data.count {
			let deltaLine = tokens.data[index]
			let deltaStart = tokens.data[index + 1]
			let length = tokens.data[index + 2]
			let tokenTypeIndex = tokens.data[index + 3]
			index += 5
			line += deltaLine
			character = deltaLine == 0 ? character + deltaStart : deltaStart
			guard tokenTypeIndex >= 0, tokenTypeIndex < legend.tokenTypes.count else {
				continue
			}
			let type = legend.tokenTypes[tokenTypeIndex]
			guard let color = semanticTokenColor(for: type) else {
				continue
			}
			let range = LSPRange(
				start: LSPPosition(line: line, character: character),
				end: LSPPosition(line: line, character: character + length)
			)
			guard let utf8Range = LSPTextEditApply.utf8Range(for: range, in: content), !utf8Range.isEmpty else {
				continue
			}
			spans.append(TextHighlightSpan(range: utf8Range, color: color))
		}
		return spans
	}

	private static func semanticTokenColor(for type: String) -> SIMD4<Float>? {
		switch type {
		case "keyword", "modifier", "operator":
			SIMD4<Float>(0.12, 0.32, 0.78, 1.0)
		case "string", "regexp":
			SIMD4<Float>(0.08, 0.45, 0.28, 1.0)
		case "number":
			SIMD4<Float>(0.76, 0.38, 0.10, 1.0)
		case "comment":
			SIMD4<Float>(0.45, 0.49, 0.54, 1.0)
		case "class", "enum", "interface", "struct", "type", "typeParameter":
			SIMD4<Float>(0.43, 0.22, 0.72, 1.0)
		case "function", "method", "macro":
			SIMD4<Float>(0.48, 0.26, 0.10, 1.0)
		case "parameter", "variable", "property", "enumMember":
			SIMD4<Float>(0.08, 0.09, 0.11, 1.0)
		default:
			nil
		}
	}

	private func applyLSPSemanticSurface(
		uri: String,
		content: String,
		document: ItsyDocument,
		semanticSpans: [TextHighlightSpan],
		inlayHints: [LSPInlayHint],
		foldingRanges: [LSPFoldingRange],
		documentHighlights: [LSPDocumentHighlight]
	) {
		document.setLSPSemanticHighlightSpans(semanticSpans)
		let annotations = inlayHints.compactMap { hint -> TextInlineAnnotation? in
			let range = LSPRange(start: hint.position, end: hint.position)
			guard let offset = LSPTextEditApply.utf8Range(for: range, in: content)?.lowerBound else {
				return nil
			}
			let label = hint.label.text.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !label.isEmpty else {
				return nil
			}
			return TextInlineAnnotation(offset: offset, label: label)
		}
		let highlightRanges = documentHighlights.compactMap {
			LSPTextEditApply.utf8Range(for: $0.range, in: content)
		}
		foldingRangesByURI[uri] = foldingRanges
		let validStarts = Set(foldingRanges.map(\.startLine))
		collapsedFoldStartsByURI[uri] = collapsedFoldStartsByURI[uri, default: []].intersection(validStarts)
		applyFoldState(uri: uri, document: document)
		document.setLSPSemanticSurface(inlayHints: annotations, highlights: highlightRanges)
	}

	private func ensureLSPSession(for url: URL) async throws -> (client: LSPProcessClient, key: LSPSessionKey) {
		if let unavailableLanguage = await Self.lspManager.unsupportedLanguage(for: url) {
			throw LSPManagerError.unsupportedLanguage(unavailableLanguage)
		}
		guard let key = await Self.lspManager.sessionKey(for: url) else {
			throw LSPManagerError.noConfigForDocument
		}
		let client = try await Self.lspManager.ensureClient(for: url)
		if await Self.lspManager.status(of: key) != .running {
			await MainActor.run { [weak self] in
				self?.setLSPStatus(key: key, status: "starting", client: client, lastError: nil, url: url)
			}
			do {
				try client.start()
			} catch LSPProcessClientError.alreadyStarted {}
			do {
				let initializationOptions = await Self.lspManager.config(for: url)?.initializationOptions
				let params = try LSPInitializeParams.itsy(workspaceRoot: key.workspaceRoot, initializationOptions: initializationOptions)
				let result = try await client.initialize(params)
				let capabilities = try? LSPInitializeResult(result: result).capabilities
				let completionProvider = capabilities?.completionProvider
				let triggers = completionProvider?.triggerCharacters.map(Set.init) ?? []
				let resolveProvider = completionProvider?.resolveProvider ?? false
				let signatureTriggers = capabilities?.signatureHelpProvider?.triggerCharacters.map(Set.init) ?? []
				let codeActionResolveProvider = capabilities?.codeActionProvider?.resolveProvider ?? false
				let callHierarchyProvider = capabilities?.callHierarchyProvider?.isEnabled ?? false
				let typeHierarchyProvider = capabilities?.typeHierarchyProvider?.isEnabled ?? false
				let semanticSurfaceCapabilities = LSPSemanticSurfaceCapabilities(
					semanticTokens: capabilities?.semanticTokensProvider,
					inlayHint: capabilities?.inlayHintProvider?.isEnabled ?? false,
					foldingRange: capabilities?.foldingRangeProvider?.isEnabled ?? false,
					documentHighlight: capabilities?.documentHighlightProvider?.isEnabled ?? false
				)
				let formattingCapabilities = LSPFormattingCapabilities(
					document: capabilities?.documentFormattingProvider?.isEnabled ?? false,
					range: capabilities?.documentRangeFormattingProvider?.isEnabled ?? false
				)
				await Self.lspManager.markRunning(key)
				await MainActor.run { [weak self] in
					guard let self else {
						return
					}
					installLSPSupervisor(for: key, client: client, url: url)
					setLSPStatus(key: key, status: "running", client: client, lastError: nil, url: url)
					setCompletionCapabilities(triggerCharacters: triggers, resolveProvider: resolveProvider, for: key)
					setSignatureHelpTriggerCharacters(signatureTriggers, for: key)
					setCodeActionCapabilities(resolveProvider: codeActionResolveProvider, for: key)
					setHierarchyCapabilities(callHierarchy: callHierarchyProvider, typeHierarchy: typeHierarchyProvider, for: key)
					setSemanticSurfaceCapabilities(semanticSurfaceCapabilities, for: key)
					setFormattingCapabilities(formattingCapabilities, for: key)
					clearLSPCrashStatus(for: key)
				}
			} catch {
				await Self.lspManager.markFailed(key)
				await MainActor.run { [weak self] in
					self?.lspSyncCoordinators[key] = nil
				}
				throw error
			}
		}
		await MainActor.run { [weak self] in
			guard let self else {
				return
			}
			installLSPSupervisor(for: key, client: client, url: url)
			setLSPStatus(key: key, status: "running", client: client, lastError: nil, url: url)
		}
		return (client, key)
	}

	private func syncLSPDocument(client: LSPProcessClient, key: LSPSessionKey, url: URL, content: String) async throws {
		let supervisor = await MainActor.run {
			self.lspSupervisors[key]
		}
		await supervisor?.recordOwnedURI(url.standardizedFileURL.absoluteString)
		let coordinator = await MainActor.run {
			self.lspSyncCoordinator(for: key, client: client)
		}
		if await coordinator.currentVersion(for: url) == nil {
			try await coordinator.didOpen(url: url, languageID: key.languageID, content: content)
			await Self.lspManager.registerSynchronizedDocument(url, for: key)
		} else {
			await coordinator.didChange(url: url, content: content)
			await coordinator.flushPendingChange(for: url)
		}
		if let version = await coordinator.currentVersion(for: url) {
			await supervisor?.recordDocumentVersion(version, forURI: url.standardizedFileURL.absoluteString)
			rememberLSPDocumentVersion(version, for: key, url: url)
		}
	}

	private func lspRequestContext(
		for key: LSPSessionKey,
		url: URL,
		content: String,
		cursorOffset: Int
	) -> LSPRequestContext? {
		let uri = url.standardizedFileURL.absoluteString
		guard let version = lspDocumentVersionsBySession[key]?[uri] else {
			return nil
		}
		return LSPRequestContext(uri: uri, documentVersion: version, content: content, cursorOffset: cursorOffset)
	}

	private func isLSPRequestCurrent(_ context: LSPRequestContext, for key: LSPSessionKey, in targetView: MetalTextView) -> Bool {
		guard
			targetView === paneCoordinator.activePane.editorView,
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let version = lspDocumentVersionsBySession[key]?[context.uri]
		else {
			return false
		}
		return context.matches(
			uri: fileURL.standardizedFileURL.absoluteString,
			documentVersion: version,
			content: editorStorageString(targetView.editor),
			cursorOffset: targetView.editor.selections.primary.head
		)
	}

	private func notifyLSPDidSave() {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL
		else {
			return
		}
		let content = editorStorageString(editorView.editor)
		Task { [weak self] in
			guard
				let self,
				let key = await Self.lspManager.sessionKey(for: fileURL),
				await Self.lspManager.status(of: key) == .running,
				let client = await Self.lspManager.existingClient(for: key)
			else {
				return
			}
			let coordinator = await MainActor.run {
				self.lspSyncCoordinator(for: key, client: client)
			}
			do {
			if await coordinator.currentVersion(for: fileURL) == nil {
					try await coordinator.didOpen(url: fileURL, languageID: key.languageID, content: content)
					await Self.lspManager.registerSynchronizedDocument(fileURL, for: key)
				} else {
					await coordinator.didChange(url: fileURL, content: content)
				}
				try await coordinator.didSave(url: fileURL)
				if let version = await coordinator.currentVersion(for: fileURL) {
					await self.lspSupervisors[key]?.recordDocumentVersion(version, forURI: fileURL.standardizedFileURL.absoluteString)
					await MainActor.run { [weak self] in
						self?.rememberLSPDocumentVersion(version, for: key, url: fileURL)
					}
				}
			} catch {
				NSLog("lsp didSave failed: \(error)")
			}
		}
	}

	private func lspSyncCoordinator(for key: LSPSessionKey, client: LSPProcessClient) -> LSPDocumentSyncCoordinator {
		if let coordinator = lspSyncCoordinators[key] {
			return coordinator
		}
		let coordinator = LSPDocumentSyncCoordinator(sink: LSPClientNotificationSink(client: client), debounceMillis: 0)
		lspSyncCoordinators[key] = coordinator
		return coordinator
	}

	private func rememberLSPDocumentVersion(_ version: Int, for key: LSPSessionKey, url: URL) {
		var versions = lspDocumentVersionsBySession[key] ?? [:]
		versions[url.standardizedFileURL.absoluteString] = version
		lspDocumentVersionsBySession[key] = versions
	}

	private func forgetLSPDocumentVersion(_ url: URL) {
		let uri = url.standardizedFileURL.absoluteString
		for key in Array(lspDocumentVersionsBySession.keys) {
			var versions = lspDocumentVersionsBySession[key] ?? [:]
			versions[uri] = nil
			if versions.isEmpty {
				lspDocumentVersionsBySession[key] = nil
			} else {
				lspDocumentVersionsBySession[key] = versions
			}
		}
	}

	private func publishEmptyLSPDiagnostics(for key: LSPSessionKey) {
		ItsyProblemsBridge.publishDiagnostics(
			WorkspaceProblemSnapshot(root: key.workspaceRoot, problems: []),
			sourceID: "lsp:\(key.languageID):\(key.workspaceRoot.path)"
		)
	}

	private func installLSPSupervisor(for key: LSPSessionKey, client: LSPProcessClient, url: URL) {
		guard lspSupervisors[key] == nil else {
			lspRestartURL = lspRestartKey == key ? url : lspRestartURL
			return
		}
		let supervisor = LSPSessionSupervisor(key: key, client: client)
		let configurationGeneration = lspConfigurationGeneration
		lspSupervisors[key] = supervisor
		lspSupervisorTasks[key] = Task { [weak self, supervisor] in
			await supervisor.start()
			for await event in supervisor.events {
				await MainActor.run { [weak self] in
					self?.handleLSPSupervisorEvent(event, key: key, url: url, configurationGeneration: configurationGeneration)
				}
			}
		}
	}

	private func handleLSPSupervisorEvent(
		_ event: LSPSessionSupervisorEvent,
		key: LSPSessionKey,
		url: URL,
		configurationGeneration: Int
	) {
		guard configurationGeneration == lspConfigurationGeneration else {
			return
		}
		switch event {
		case let .diagnosticsUpdated(snapshot):
			ItsyProblemsBridge.publishDiagnostics(snapshot, sourceID: "lsp:\(key.languageID):\(key.workspaceRoot.path)")
		case let .output(output):
			appendLSPOutput(output, for: key, url: url)
		case let .sessionFailed(reason):
			lspSyncCoordinators[key] = nil
			lspDocumentVersionsBySession[key] = nil
			completionTriggerCharactersBySession[key] = nil
			signatureHelpTriggerCharactersBySession[key] = nil
			completionResolveEnabledBySession[key] = nil
			codeActionResolveEnabledBySession[key] = nil
			callHierarchyEnabledBySession[key] = nil
			typeHierarchyEnabledBySession[key] = nil
			semanticSurfaceCapabilitiesBySession[key] = nil
			formattingCapabilitiesBySession[key] = nil
			lspSupervisors[key] = nil
			lspSupervisorTasks[key] = nil
			Task {
				await Self.lspManager.markFailed(key)
			}
			showLSPCrashStatus(key: key, url: url, reason: reason)
			NSLog("lsp session failed: \(key.languageID) exit \(reason.status) \(reason.stderrTail)")
		case let .workspaceEditRequested(id, params):
			let applied = applyWorkspaceEdit(params.edit, sessionKey: key)
			let response = LSPApplyWorkspaceEditResponse(
				applied: applied,
				failureReason: applied ? nil : "unable to apply workspace edit"
			)
			guard let client = lspStatusEntries[key]?.client else {
				return
			}
			Task {
				do {
					try await client.session.respond(to: id, result: LSPAny(encoding: response))
				} catch {
					NSLog("workspace/applyEdit response failed: \(error)")
				}
			}
		}
	}

	private func restartLSPSession(for url: URL) {
		guard (document as? ItsyDocument)?.fileURL == url else {
			return
		}
		let targetView = editorView
		let content = editorStorageString(targetView.editor)
		Task { [weak self] in
			do {
				guard let self else {
					return
				}
				let session = try await ensureLSPSession(for: url)
				try await syncLSPDocument(client: session.client, key: session.key, url: url, content: content)
			} catch {
				await MainActor.run { [weak self] in
					self?.handleLSPRequestError(error)
					NSLog("lsp restart failed: \(error)")
				}
			}
		}
	}

	private func setCompletionCapabilities(
		triggerCharacters characters: Set<String>,
		resolveProvider: Bool,
		for key: LSPSessionKey
	) {
		completionTriggerCharactersBySession[key] = characters
		completionResolveEnabledBySession[key] = resolveProvider
		for pane in paneCoordinator.panes {
			pane.editorView.completionTriggerCharacters = characters
		}
	}

	private func setSignatureHelpTriggerCharacters(_ characters: Set<String>, for key: LSPSessionKey) {
		signatureHelpTriggerCharactersBySession[key] = characters
		for pane in paneCoordinator.panes {
			pane.editorView.signatureHelpTriggerCharacters = characters
		}
	}

	private func setCodeActionCapabilities(resolveProvider: Bool, for key: LSPSessionKey) {
		codeActionResolveEnabledBySession[key] = resolveProvider
	}

	private func setHierarchyCapabilities(callHierarchy: Bool, typeHierarchy: Bool, for key: LSPSessionKey) {
		callHierarchyEnabledBySession[key] = callHierarchy
		typeHierarchyEnabledBySession[key] = typeHierarchy
	}

	private func setSemanticSurfaceCapabilities(_ capabilities: LSPSemanticSurfaceCapabilities, for key: LSPSessionKey) {
		semanticSurfaceCapabilitiesBySession[key] = capabilities
	}

	private func setFormattingCapabilities(_ capabilities: LSPFormattingCapabilities, for key: LSPSessionKey) {
		formattingCapabilitiesBySession[key] = capabilities
	}

	private func installCompletionTriggersIfKnown(for view: MetalTextView, document: ItsyDocument) {
		view.completionTriggerCharacters = []
		guard let fileURL = document.fileURL else {
			return
		}
		Task { [weak self, weak view] in
			guard let self, let key = await Self.lspManager.sessionKey(for: fileURL) else {
				return
			}
			await MainActor.run { [weak self, weak view] in
				view?.completionTriggerCharacters = self?.completionTriggerCharactersBySession[key] ?? []
			}
		}
	}

	private func installSignatureHelpTriggersIfKnown(for view: MetalTextView, document: ItsyDocument) {
		view.signatureHelpTriggerCharacters = []
		guard let fileURL = document.fileURL else {
			return
		}
		Task { [weak self, weak view] in
			guard let self, let key = await Self.lspManager.sessionKey(for: fileURL) else {
				return
			}
			await MainActor.run { [weak self, weak view] in
				view?.signatureHelpTriggerCharacters = self?.signatureHelpTriggerCharactersBySession[key] ?? []
			}
		}
	}

	private func showCompletionPopup(
		result: LSPCompletionResult,
		in targetView: MetalTextView,
		sessionKey: LSPSessionKey?,
		requestContext: LSPRequestContext?
	) {
		let popup = completionPopup ?? CompletionPopupController()
		completionPopup = popup
		let canResolve = sessionKey.map { completionResolveEnabledBySession[$0] == true } ?? false
		let resolve: ((LSPCompletionItem, @escaping (LSPCompletionItem) -> Void) -> Void)? = if canResolve {
			{ [weak self] item, completion in
				self?.requestCompletionResolve(item, completion: completion)
			}
		} else {
			nil
		}
		popup.show(
			result: result,
			relativeTo: window,
			editorView: targetView,
			requestAgain: { [weak self, weak targetView] in
				_ = self?.requestCompletion(triggerCharacter: nil, forIncomplete: true, in: targetView)
			},
			resolve: resolve,
			isRequestCurrent: { [weak self, weak targetView] in
				guard let self, let targetView else {
					return false
				}
				if let requestContext, let sessionKey {
					return self.isLSPRequestCurrent(requestContext, for: sessionKey, in: targetView)
				}
				return targetView === self.paneCoordinator.activePane.editorView
			},
			requestInvalidated: { [weak self] in
				self?.cancelCompletionRequest()
			},
			accept: { [weak self, weak targetView] item in
				guard let self, let targetView else {
					return
				}
				acceptCompletion(item, in: targetView)
			}
		)
	}

	private func requestCompletionResolve(_ item: LSPCompletionItem, completion: @escaping (LSPCompletionItem) -> Void) {
		guard let document = document as? ItsyDocument, let fileURL = document.fileURL else {
			return
		}
		Task { [weak self] in
			do {
				guard let self else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				let response = try await session.client.sendRequest(
					method: LSPMethod.completionItemResolve,
					params: LSPAny(encoding: item)
				)
				let resolved = try item.mergingResolvedFields(from: LSPCompletionItem(resolveResult: response.result))
				await MainActor.run {
					completion(resolved)
				}
			} catch {
				await MainActor.run { [weak self] in
					self?.handleLSPRequestError(error)
				}
				NSLog("completion resolve failed: \(error)")
			}
		}
	}

	private func acceptCompletion(_ item: LSPCompletionItem, in targetView: MetalTextView) {
		guard let application = LSPCompletionApply.application(
			for: item,
			in: editorStorageString(targetView.editor),
			cursorOffset: targetView.editor.selections.primary.head
		) else {
			return
		}
		targetView.replaceUTF8Range(
			application.replacementRange,
			with: application.replacementText,
			selectUTF8Ranges: application.selectionRanges
		)
		installSnippetTabStops(application.tabStopRanges, in: targetView)
		focusEditor()
	}

	private func installSnippetTabStops(_ tabStopRanges: [Int: [Range<Int>]], in targetView: MetalTextView) {
		let session = SnippetTabStopSession(tabStopRanges: tabStopRanges)
		guard !session.isEmpty else {
			snippetTabStopSession = nil
			snippetTabStopView = nil
			return
		}
		snippetTabStopSession = session
		snippetTabStopView = targetView
	}

	private func moveSnippetTabStop(direction: Int, in targetView: MetalTextView?) -> Bool {
		guard targetView === snippetTabStopView else {
			snippetTabStopSession = nil
			snippetTabStopView = nil
			return false
		}
		guard
			let targetView,
			var session = snippetTabStopSession
		else {
			return false
		}
		let selectionRanges = [targetView.editor.selections.primary.range] + targetView.editor.selections.secondaries
			.map(\.range)
		guard let ranges = session.move(direction: direction, currentSelectionRanges: selectionRanges) else {
			snippetTabStopSession = nil
			snippetTabStopView = nil
			return false
		}
		targetView.selectUTF8Ranges(ranges)
		snippetTabStopSession = session
		return true
	}

	private func scheduleHover(_ candidate: TextHoverCandidate?, in targetView: MetalTextView?) {
		hoverTimer?.invalidate()
		hoverTimer = nil
		cancelHoverRequest()
		guard
			let candidate,
			let targetView,
			let offset = identifierOffset(in: editorStorageString(targetView.editor), near: candidate.offset)
		else {
			hoverTargetView = nil
			closeHoverPopover()
			return
		}
		hoverTargetView = targetView
		hoverTargetOffset = offset
		hoverTargetRect = targetView.positioningRectForUTF8Offset(offset)
		hoverTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { [weak self] _ in
			MainActor.assumeIsolated {
				guard let self else {
					return
				}
				_ = self.requestHover(at: self.hoverTargetOffset, positioningRect: self.hoverTargetRect, in: self.hoverTargetView)
			}
		}
	}

	@discardableResult
	private func requestHover(at offset: Int, positioningRect: NSRect, in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: offset, in: content)
		cancelHoverRequest()
		hoverRequestGeneration += 1
		let generation = hoverRequestGeneration
		hoverRequestTask = Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: offset) else {
					return
				}
				let params = LSPHoverParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentHover,
					params: LSPAny(encoding: params)
				)
				let result = try LSPHoverResult(result: response.result)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == hoverRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					showHoverPopover(result: result, positioningRect: positioningRect, in: targetView)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == hoverRequestGeneration, !Task.isCancelled else {
						return
					}
					closeHoverPopover()
					handleLSPRequestError(error)
					NSLog("hover failed: \(error)")
				}
			}
		}
		return true
	}

	private func cancelHoverRequest() {
		hoverRequestGeneration += 1
		hoverRequestTask?.cancel()
		hoverRequestTask = nil
	}

	private func showHoverPopover(result: LSPHoverResult, positioningRect: NSRect, in targetView: MetalTextView) {
		guard let hover = result.hover else {
			closeHoverPopover()
			return
		}
		closeHoverPopover()
		let popover = NSPopover()
		popover.behavior = .transient
		popover.animates = false
		popover.contentViewController = HoverTooltipViewController(hover: hover)
		popover.show(relativeTo: positioningRect, of: targetView, preferredEdge: .maxY)
		hoverPopover = popover
	}

	@discardableResult
	private func requestNavigation(_ operation: LSPNavigationOperation, in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: cursorOffset, in: content)
		navigationRequestGeneration += 1
		let generation = navigationRequestGeneration
		Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: cursorOffset) else {
					return
				}
				let result: LSPDefinitionResult = switch operation {
				case .definition:
					try await session.client.definition(uri: fileURL.standardizedFileURL.absoluteString, position: position)
				case .declaration:
					try await session.client.declaration(uri: fileURL.standardizedFileURL.absoluteString, position: position)
				case .typeDefinition:
					try await session.client.typeDefinition(uri: fileURL.standardizedFileURL.absoluteString, position: position)
				case .implementation:
					try await session.client.implementation(uri: fileURL.standardizedFileURL.absoluteString, position: position)
				}
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == navigationRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					navigate(to: result.locations)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == navigationRequestGeneration, !Task.isCancelled else {
						return
					}
					handleLSPRequestError(error)
					NSLog("lsp navigation failed: \(error)")
				}
			}
		}
		return true
	}

	private func navigate(to locations: [LSPLocation]) {
		let destinations = locations.compactMap(navigationLocation(for:))
		guard !destinations.isEmpty else {
			return
		}
		if destinations.count == 1 {
			navigate(to: destinations[0], recordingHistory: true)
			return
		}
		let currentFileURL = (document as? ItsyDocument)?.fileURL ?? URL(fileURLWithPath: "/")
		let snapshot = LSPReferencesSnapshot(
			locations: locations,
			rootURL: ItsyWorkspaceController.currentRootURL,
			currentFileURL: currentFileURL,
			currentText: editorStorageString(editorView.editor)
		)
		let panel = referencesCoordinator ?? ReferencesCoordinator()
		referencesCoordinator = panel
		panel.show(snapshot: snapshot, relativeTo: window) { [weak self] entry in
			guard let location = locations.first(where: {
				$0.uri == entry.url.absoluteString
					&& $0.range.start.line + 1 == entry.line
					&& $0.range.start.character + 1 == entry.column
			}) else {
				return
			}
			self?.navigationLocation(for: location).map { destination in
				self?.navigate(to: destination, recordingHistory: true)
			}
		}
	}

	private func navigationLocation(for location: LSPLocation) -> LSPNavigationLocation? {
		guard let url = fileURL(forLSPURI: location.uri) else {
			return nil
		}
		let text: String
		if let document = openDocument(forURI: location.uri) {
			text = editorStorageString(document.editor)
		} else {
			text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
		}
		guard let range = LSPTextEditApply.utf8Range(for: location.range, in: text) else {
			return nil
		}
		return LSPNavigationLocation(uri: location.uri, selection: range)
	}

	private func navigate(to destination: LSPNavigationLocation, recordingHistory: Bool) {
		guard let origin = currentNavigationLocation(), let url = fileURL(forLSPURI: destination.uri) else {
			return
		}
		guard let controller = NSDocumentController.shared as? ItsyDocumentController, controller.openDocument(at: url) else {
			return
		}
		let length = editorView.editor.textStorage.length
		let range = min(max(destination.selection.lowerBound, 0), length) ..< min(max(destination.selection.upperBound, 0), length)
		editorView.selectUTF8Range(range)
		if recordingHistory {
			lspNavigationHistory.recordJump(from: origin, to: destination)
		}
	}

	private func currentNavigationLocation() -> LSPNavigationLocation? {
		guard let url = (document as? ItsyDocument)?.fileURL?.standardizedFileURL else {
			return nil
		}
		return LSPNavigationLocation(uri: url.absoluteString, selection: editorView.editor.selections.primary.range)
	}

	private func navigateBack() -> Bool {
		guard let current = currentNavigationLocation(), let destination = lspNavigationHistory.goBack(from: current) else {
			return false
		}
		navigate(to: destination, recordingHistory: false)
		return true
	}

	private func navigateForward() -> Bool {
		guard let current = currentNavigationLocation(), let destination = lspNavigationHistory.goForward(from: current) else {
			return false
		}
		navigate(to: destination, recordingHistory: false)
		return true
	}

	@discardableResult
	private func requestSignatureHelp(triggerCharacter: String?, in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let cursorOffset = targetView.editor.selections.primary.head
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: cursorOffset, in: content)
		let context = signatureHelpContext(triggerCharacter: triggerCharacter)
		cancelSignatureHelpRequest()
		signatureHelpRequestGeneration += 1
		let generation = signatureHelpRequestGeneration
		signatureHelpRequestTask = Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				guard let requestContext = lspRequestContext(for: session.key, url: fileURL, content: content, cursorOffset: cursorOffset) else {
					return
				}
				let params = LSPSignatureHelpParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position,
					context: context
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentSignatureHelp,
					params: LSPAny(encoding: params)
				)
				let result = try LSPSignatureHelpResult(result: response.result)
				await MainActor.run { [weak self, weak targetView] in
					guard
						let self,
						let targetView,
						generation == signatureHelpRequestGeneration,
						isLSPRequestCurrent(requestContext, for: session.key, in: targetView)
					else {
						return
					}
					showSignatureHelpPopover(result: result, in: targetView)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == signatureHelpRequestGeneration, !Task.isCancelled else {
						return
					}
					closeSignatureHelpPopover()
					handleLSPRequestError(error)
					NSLog("signature help failed: \(error)")
				}
			}
		}
		return true
	}

	private func cancelSignatureHelpRequest() {
		signatureHelpRequestGeneration += 1
		signatureHelpRequestTask?.cancel()
		signatureHelpRequestTask = nil
	}

	private func signatureHelpContext(triggerCharacter: String?) -> LSPSignatureHelpContext {
		if let triggerCharacter {
			return LSPSignatureHelpContext(
				triggerKind: .triggerCharacter,
				triggerCharacter: triggerCharacter,
				isRetrigger: signatureHelpPopover?.isShown == true
			)
		}
		return LSPSignatureHelpContext(triggerKind: .invoked, isRetrigger: signatureHelpPopover?.isShown == true)
	}

	private func showSignatureHelpPopover(result: LSPSignatureHelpResult, in targetView: MetalTextView) {
		guard let help = result.help else {
			closeSignatureHelpPopover()
			return
		}
		closeSignatureHelpPopover()
		let cursorOffset = targetView.editor.selections.primary.head
		let popover = NSPopover()
		popover.behavior = .transient
		popover.animates = false
		popover.contentViewController = SignatureHelpViewController(help: help)
		popover.show(relativeTo: targetView.positioningRectForUTF8Offset(cursorOffset), of: targetView, preferredEdge: .maxY)
		signatureHelpPopover = popover
	}

	@discardableResult
	private func requestCallHierarchy(at offset: Int, in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: offset, in: content)
		let rootURL = ItsyWorkspaceController.currentRootURL ?? fileURL.deletingLastPathComponent()
		referencesRequestGeneration += 1
		let generation = referencesRequestGeneration
		let panel = referencesCoordinator ?? ReferencesCoordinator()
		referencesCoordinator = panel
		panel.showCallHierarchyLoading(relativeTo: window)
		Task { [weak self] in
			do {
				guard let self else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				guard await MainActor.run(body: { self.callHierarchyEnabledBySession[session.key] == true }) else {
					throw LSPManagerError.noConfigForDocument
				}
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let uri = fileURL.standardizedFileURL.absoluteString
				let items = try await session.client.prepareCallHierarchy(uri: uri, position: position)
				let item = items.first
				let incoming: [LSPCallHierarchyIncomingCall]
				let outgoing: [LSPCallHierarchyOutgoingCall]
				if let item {
					incoming = await (try? session.client.incomingCalls(for: item)) ?? []
					outgoing = await (try? session.client.outgoingCalls(for: item)) ?? []
				} else {
					incoming = []
					outgoing = []
				}
				let locations = Self.callHierarchyLocations(incoming: incoming, outgoing: outgoing)
				let snapshot = LSPReferencesSnapshot(
					locations: locations,
					rootURL: rootURL,
					currentFileURL: fileURL,
					currentText: content
				)
				await MainActor.run { [weak self] in
					guard let self, generation == referencesRequestGeneration else {
						return
					}
					panel.showCallHierarchy(snapshot: snapshot, relativeTo: window) { entry in
						guard let controller = NSDocumentController.shared as? ItsyDocumentController else {
							return
						}
						_ = controller.openDocument(at: entry.url, line: entry.line, column: entry.column)
					}
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == referencesRequestGeneration else {
						return
					}
					panel.show(error: error, relativeTo: window)
					handleLSPRequestError(error)
					NSLog("call hierarchy failed: \(error)")
				}
			}
		}
		return true
	}

	private static func callHierarchyLocations(
		incoming: [LSPCallHierarchyIncomingCall],
		outgoing: [LSPCallHierarchyOutgoingCall]
	) -> [LSPLocation] {
		let incomingLocations = incoming.map {
			LSPLocation(uri: $0.from.uri, range: $0.from.selectionRange)
		}
		let outgoingLocations = outgoing.map {
			LSPLocation(uri: $0.to.uri, range: $0.to.selectionRange)
		}
		return incomingLocations + outgoingLocations
	}

	@discardableResult
	private func requestReferences(at offset: Int, in targetView: MetalTextView?) -> Bool {
		guard
			let document = document as? ItsyDocument,
			let fileURL = document.fileURL,
			let targetView
		else {
			return false
		}
		let content = editorStorageString(targetView.editor)
		let position = LSPTextEditApply.utf16Position(forUTF8Offset: offset, in: content)
		let rootURL = ItsyWorkspaceController.currentRootURL ?? fileURL.deletingLastPathComponent()
		referencesRequestGeneration += 1
		let generation = referencesRequestGeneration
		let panel = referencesCoordinator ?? ReferencesCoordinator()
		referencesCoordinator = panel
		panel.showLoading(relativeTo: window)
		Task { [weak self] in
			do {
				guard let self else {
					return
				}
				let session = try await ensureLSPSession(for: fileURL)
				try await syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let params = LSPReferenceParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position,
					context: LSPReferenceContext(includeDeclaration: true)
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentReferences,
					params: LSPAny(encoding: params)
				)
				let result = try LSPReferencesResult(result: response.result)
				let snapshot = LSPReferencesSnapshot(
					locations: result.locations,
					rootURL: rootURL,
					currentFileURL: fileURL,
					currentText: content
				)
				await MainActor.run { [weak self] in
					guard let self, generation == referencesRequestGeneration else {
						return
					}
					showReferences(snapshot)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == referencesRequestGeneration else {
						return
					}
					handleLSPRequestError(error)
					referencesCoordinator?.show(error: error, relativeTo: window)
				}
			}
		}
		return true
	}

	private func showReferences(_ snapshot: LSPReferencesSnapshot) {
		let panel = referencesCoordinator ?? ReferencesCoordinator()
		referencesCoordinator = panel
		panel.show(snapshot: snapshot, relativeTo: window) { entry in
			guard let controller = NSDocumentController.shared as? ItsyDocumentController else {
				return
			}
			_ = controller.openDocument(at: entry.url, line: entry.line, column: entry.column)
		}
	}

	private func closeHoverPopover() {
		cancelHoverRequest()
		hoverPopover?.close()
		hoverPopover = nil
	}

	private func closeSignatureHelpPopover() {
		cancelSignatureHelpRequest()
		signatureHelpPopover?.close()
		signatureHelpPopover = nil
	}

	private func closeCodeActionPopover() {
		cancelCodeActionRequest()
		codeActionPopover?.close()
		codeActionPopover = nil
	}

	@discardableResult
	private func applyWorkspaceEdit(
		_ edit: LSPWorkspaceEdit,
		sessionKey: LSPSessionKey? = nil,
		operation: String? = nil
	) -> Bool {
		do {
			let groups = LSPWorkspaceEditApply.normalize(edit)
			guard !groups.isEmpty else {
				return true
			}
			var sources: [String: String] = [:]
			for uri in groups.keys {
				sources[uri] = try sourceText(forURI: uri)
			}
			let documentVersions = if let sessionKey {
				lspDocumentVersionsBySession[sessionKey] ?? [:]
			} else {
				lspDocumentVersionsBySession.values.reduce(into: [String: Int]()) { result, versions in
					result.merge(versions) { current, _ in current }
				}
			}
			let resolved = try LSPWorkspaceEditApply.apply(edit, sources: sources, documentVersions: documentVersions)
			let preview = try LSPWorkspaceEditPreview(resolved: resolved, sources: sources)
			guard confirmWorkspaceEdit(preview) else {
				return false
			}
			try LSPWorkspaceEditTransaction.commit(
				preview.files,
				apply: { file in
					try applyResolvedWorkspaceFile(.init(uri: file.uri, updatedText: file.updatedText))
				},
				rollback: { file in
					try applyResolvedWorkspaceFile(.init(uri: file.uri, updatedText: file.originalText))
				}
			)
			return true
		} catch {
			if let operation {
				showLSPOperationFailure(operation, error: error)
			}
			handleLSPRequestError(error)
			NSLog("workspace edit apply failed: \(error)")
			return false
		}
	}

	private func confirmWorkspaceEdit(_ preview: LSPWorkspaceEditPreview) -> Bool {
		guard preview.requiresConfirmation else {
			return true
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("Preview workspace edit")
		alert.informativeText = preview.files.map { file in
			URL(string: file.uri)?.lastPathComponent ?? file.uri
		}.joined(separator: "\n")
		alert.addButton(withTitle: L10n.string("Apply"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	private func sourceText(forURI uri: String) throws -> String {
		if let document = openDocument(forURI: uri) {
			return editorStorageString(document.editor)
		}
		guard let url = fileURL(forLSPURI: uri) else {
			throw LSPWorkspaceEditFileError.invalidURI(uri)
		}
		guard let text = try String(data: Data(contentsOf: url), encoding: .utf8) else {
			throw LSPWorkspaceEditFileError.nonUTF8(url)
		}
		return text
	}

	private func applyResolvedWorkspaceFile(_ file: LSPWorkspaceEditApply.ResolvedFile) throws {
		if let document = openDocument(forURI: file.uri) {
			document.applyLSPUpdatedText(file.updatedText)
			return
		}
		guard let url = fileURL(forLSPURI: file.uri) else {
			throw LSPWorkspaceEditFileError.invalidURI(file.uri)
		}
		try Data(file.updatedText.utf8).write(to: url, options: .atomic)
	}

	private func openDocument(forURI uri: String) -> ItsyDocument? {
		let targetURL = fileURL(forLSPURI: uri)
		return NSDocumentController.shared.documents.compactMap { $0 as? ItsyDocument }.first { document in
			guard let fileURL = document.fileURL?.standardizedFileURL else {
				return false
			}
			if fileURL.absoluteString == uri {
				return true
			}
			return targetURL.map { fileURL == $0 } ?? false
		}
	}

	private func fileURL(forLSPURI uri: String) -> URL? {
		guard let url = URL(string: uri), url.isFileURL else {
			return nil
		}
		return url.standardizedFileURL
	}

	private func identifierOffset(in text: String, near offset: Int) -> Int? {
		let clamped = min(max(offset, 0), text.utf8.count)
		let index = stringIndex(in: text, utf8Offset: clamped)
		if index < text.endIndex, isIdentifierCharacter(text[index]) {
			return clamped
		}
		guard index > text.startIndex else {
			return nil
		}
		let previous = text.index(before: index)
		guard isIdentifierCharacter(text[previous]) else {
			return nil
		}
		return utf8Offset(in: text, for: previous)
	}

	private func identifierRange(in text: String, near offset: Int) -> Range<Int>? {
		guard let identifierOffset = identifierOffset(in: text, near: offset) else {
			return nil
		}
		let center = stringIndex(in: text, utf8Offset: identifierOffset)
		var lower = center
		var upper = center
		while lower > text.startIndex {
			let previous = text.index(before: lower)
			guard isIdentifierCharacter(text[previous]) else {
				break
			}
			lower = previous
		}
		while upper < text.endIndex, isIdentifierCharacter(text[upper]) {
			upper = text.index(after: upper)
		}
		let lowerOffset = utf8Offset(in: text, for: lower)
		let upperOffset = utf8Offset(in: text, for: upper)
		guard lowerOffset < upperOffset else {
			return nil
		}
		return lowerOffset ..< upperOffset
	}

	private func substring(in text: String, range: Range<Int>) -> String {
		let lower = stringIndex(in: text, utf8Offset: range.lowerBound)
		let upper = stringIndex(in: text, utf8Offset: range.upperBound)
		return String(text[lower ..< upper])
	}

	private func completionPrefix(in text: String, cursorOffset: Int) -> String {
		let cursor = stringIndex(in: text, utf8Offset: cursorOffset)
		var start = cursor
		while start > text.startIndex {
			let previous = text.index(before: start)
			guard isIdentifierCharacter(text[previous]) else {
				break
			}
			start = previous
		}
		return String(text[start ..< cursor])
	}

	private func currentEditorSettings() -> ItsySettings.EditorSettings {
		currentSettings().editorSettings(languageID: currentLanguageID())
	}

	private func currentSettings() -> ItsySettings {
		ItsySettingsStore().load(
			workspaceRoot: ItsyWorkspaceController.currentRootURL,
			fallback: EditorPreferences.legacySettings()
		).settings
	}

	private func currentLanguageID() -> String? {
		guard let fileURL = (document as? ItsyDocument)?.fileURL else {
			return nil
		}
		return Self.snippetLanguageRegistry.languageID(for: fileURL)
	}

	private func isIdentifierCharacter(_ character: Character) -> Bool {
		character == "_" || character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) }
	}

	private func stringIndex(in text: String, utf8Offset target: Int) -> String.Index {
		let clamped = min(max(target, 0), text.utf8.count)
		var index = text.startIndex
		var offset = 0
		while index < text.endIndex, offset < clamped {
			let next = text.index(after: index)
			let nextOffset = offset + String(text[index]).utf8.count
			guard nextOffset <= clamped else {
				break
			}
			offset = nextOffset
			index = next
		}
		return index
	}

	private func utf8Offset(in text: String, for target: String.Index) -> Int {
		text.utf8.distance(from: text.utf8.startIndex, to: target.samePosition(in: text.utf8) ?? text.utf8.endIndex)
	}

	private func performExCommand(_ command: String) -> Bool {
		switch command {
		case "w":
			(document as? NSDocument)?.save(nil)
		case "q", "bd":
			(document as? NSDocument)?.close()
		case "wq", "x":
			(document as? NSDocument)?.save(nil)
			(document as? NSDocument)?.close()
		case "bn":
			selectAdjacentTab(delta: 1)
		case "bp":
			selectAdjacentTab(delta: -1)
		default:
			if command.hasPrefix("e ") {
				return openExCommandPath(String(command.dropFirst(2)))
			}
			return false
		}
		return true
	}

	private func openExCommandPath(_ path: String) -> Bool {
		let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
		guard !trimmed.isEmpty else {
			return false
		}
		let url: URL
		if trimmed.hasPrefix("/") {
			url = URL(fileURLWithPath: trimmed)
		} else {
			let base = document?.fileURL?.deletingLastPathComponent() ?? ItsyWorkspaceController
				.currentRootURL ?? URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
			url = base.appendingPathComponent(trimmed)
		}
		return ItsyWorkspaceController.openFile(at: url)
	}
}

extension EditorWindowController: NSWindowDelegate {
	func windowDidBecomeKey(_: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func windowDidBecomeMain(_: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func windowWillClose(_: Notification) {
		ItsyWorkspaceController.persistWindowState(from: self)
		ItsyTabCoordinator.refresh()
	}
}
