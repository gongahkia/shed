import AppKit
import Dispatch
import Foundation
import ItsyEditor
import ItsyLSP
import ItsyRender
import ItsySyntax

private struct LSPStatusEntry {
	var key: LSPSessionKey
	var status: String
	var server: String
	var pid: Int32?
	var startDate: Date?
	var lastError: String
	var url: URL?
	var client: LSPProcessClient?
}

final class EditorWindowController: NSWindowController {
	private static let paneLayoutStateKey = "dev.itsy.editor.paneLayout"
	private static let lspManager = LSPManager(registry: LSPServerRegistryLoader.loadOrBundled())
	private static var dismissedLSPMissingCommands: Set<String> = []
	private let fileTreeController = FileTreeSidebarController()
	private let editorContainer = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
	private var findBarController: FindBarController?
	private let tabBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 32))
	private let tabScrollView = NSScrollView()
	private let tabStackView = NSStackView()
	private let lspMissingBanner = LSPMissingBanner()
	private let statusBarView = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 18))
	private let statusBarLabel = NSTextField(labelWithString: "")
	private let lspStatusButton = NSButton(title: "", target: nil, action: nil)
	private var paneCoordinator = EditorPaneCoordinator()
	private var editorView: MetalTextView {
		paneCoordinator.activePane.editorView
	}
	private var tabIDsByTag: [Int: ObjectIdentifier] = [:]
	private var tabBoundsObserver: NSObjectProtocol?
	private var completionPopup: CompletionPopupController?
	private var completionRequestGeneration = 0
	private var hoverPopover: NSPopover?
	private var hoverTimer: Timer?
	private var hoverRequestGeneration = 0
	private var signatureHelpPopover: NSPopover?
	private var signatureHelpRequestGeneration = 0
	private var referencesRequestGeneration = 0
	private var referencesCoordinator: ReferencesCoordinator?
	private var lspSyncCoordinators: [LSPSessionKey: LSPDocumentSyncCoordinator] = [:]
	private var lspSupervisors: [LSPSessionKey: LSPSessionSupervisor] = [:]
	private var lspSupervisorTasks: [LSPSessionKey: Task<Void, Never>] = [:]
	private var lspStatusEntries: [LSPSessionKey: LSPStatusEntry] = [:]
	private var completionTriggerCharactersBySession: [LSPSessionKey: Set<String>] = [:]
	private var signatureHelpTriggerCharactersBySession: [LSPSessionKey: Set<String>] = [:]
	private var completionResolveEnabledBySession: [LSPSessionKey: Bool] = [:]
	private var lspMissingBannerGeneration = 0
	private var lspStatusGeneration = 0
	private var indexingStatusText: String?
	private var lspCrashStatusText: String?
	private var lspRestartKey: LSPSessionKey?
	private var lspRestartURL: URL?
	private var activeLSPKey: LSPSessionKey?
	private var lspStatusPanel: LSPStatusPanel?

	init(document: ItsyDocument) {
		let editorStack = NSStackView(frame: NSRect(x: 240, y: 0, width: 960, height: 672))
		editorStack.orientation = .vertical
		editorStack.alignment = .width
		editorStack.distribution = .fill
		editorStack.spacing = 0
		Self.configureTabBarView(tabBarView, scrollView: tabScrollView, stackView: tabStackView)
		Self.configureStatusBarView(statusBarView, label: statusBarLabel, lspButton: lspStatusButton)
		tabBarView.setContentHuggingPriority(.required, for: .vertical)
		lspMissingBanner.setContentHuggingPriority(.required, for: .vertical)
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
		editorStack.addArrangedSubview(editorContainer)
		editorStack.addArrangedSubview(statusBarView)

		let splitView = NSSplitView(frame: NSRect(x: 0, y: 0, width: 1200, height: 672))
		splitView.isVertical = true
		splitView.dividerStyle = .thin
		splitView.autoresizingMask = [.width, .height]
		fileTreeController.view.translatesAutoresizingMaskIntoConstraints = false
		editorStack.translatesAutoresizingMaskIntoConstraints = false
		splitView.addArrangedSubview(fileTreeController.view)
		splitView.addArrangedSubview(editorStack)
		fileTreeController.view.widthAnchor.constraint(equalToConstant: 240).isActive = true
		let window = NSWindow(
			contentRect: splitView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = document.fileURL?.lastPathComponent ?? L10n.string("Untitled")
		window.isRestorable = true
		window.contentView = splitView
		super.init(window: window)
		configureLSPMissingBanner()
		configureLSPStatusRestart()
		fileTreeController.attach(to: window)
		fileTreeController.openFile = { ItsyWorkspaceController.openFile(at: $0) }
		installTabBoundsObserver()
		window.delegate = self
		installPane(paneCoordinator.activePane, document: document)
		refreshLSPMissingBanner(for: document)
		refreshLSPStatus(for: document)
		ItsyWorkspaceController.register(self)
		ItsyTabCoordinator.register(self)
		window.makeFirstResponder(editorView)
	}

	required init?(coder: NSCoder) {
		nil
	}

	deinit {
		completionPopup?.dismiss()
		hoverTimer?.invalidate()
		hoverPopover?.close()
		signatureHelpPopover?.close()
		for task in lspSupervisorTasks.values {
			task.cancel()
		}
		if let tabBoundsObserver {
			NotificationCenter.default.removeObserver(tabBoundsObserver)
		}
	}

	func setWorkspaceRootURL(_ url: URL?) {
		fileTreeController.setWorkspaceRootURL(url)
	}

	func setGitSnapshot(_ snapshot: GitWorkspaceSnapshot?) {
		fileTreeController.setGitSnapshot(snapshot)
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
			self?.layoutTabContent()
		}
	}

	func setTabs(_ tabs: [ItsyTab]) {
		for view in tabStackView.arrangedSubviews {
			tabStackView.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		tabIDsByTag = Dictionary(uniqueKeysWithValues: tabs.enumerated().map { index, tab in (index, tab.id) })
		for (index, tab) in tabs.enumerated() {
			tabStackView.addArrangedSubview(makeTabView(tab, tag: index))
		}
		layoutTabContent()
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
			? NSColor.selectedControlColor.withAlphaComponent(0.24).cgColor
			: NSColor.clear.cgColor

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

		let closeButton = NSButton(title: L10n.string("X"), target: self, action: #selector(closeTab(_:)))
		closeButton.tag = tag
		closeButton.isBordered = false
		closeButton.font = .systemFont(ofSize: 11, weight: .regular)
		closeButton.toolTip = L10n.string("Close")

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
		lspMissingBanner.dismissRequested = { [weak self] missingBinary in
			Self.dismissedLSPMissingCommands.insert(missingBinary.command)
			self?.lspMissingBanner.hide()
			self?.focusEditor()
		}
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
			await MainActor.run { [weak self] in
				guard let self, generation == self.lspMissingBannerGeneration else {
					return
				}
				if let missingBinary {
					self.showLSPMissingBanner(missingBinary)
				} else {
					self.lspMissingBanner.hide()
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

	private func handleLSPRequestError(_ error: Error) {
		if case let LSPManagerError.missingBinary(missingBinary) = error {
			showLSPMissingBanner(missingBinary)
		} else if case let LSPManagerError.serverDisabled(key) = error {
			setLSPStatus(key: key, status: "disabled", client: nil, lastError: lspStatusEntries[key]?.lastError, url: lspStatusEntries[key]?.url)
		} else if case LSPManagerError.retryLimitExceeded = error, let key = activeLSPKey {
			setLSPStatus(key: key, status: "disabled", client: nil, lastError: lspStatusEntries[key]?.lastError, url: lspStatusEntries[key]?.url)
		}
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
		setLSPStatus(key: key, status: "crashed", client: nil, lastError: reason.stderrTail, url: url)
		lspCrashStatusText = L10n.string("LSP: \(key.languageID) crashed (exit \(reason.status))")
		refreshStatusBar()
	}

	private func clearLSPCrashStatus(for key: LSPSessionKey) {
		guard lspRestartKey == key else {
			return
		}
		lspRestartKey = nil
		lspRestartURL = nil
		lspCrashStatusText = nil
		refreshStatusBar()
	}

	@objc private func showLSPStatusPanel(_ sender: NSButton) {
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
				self.restartLSPSession(for: url)
			}
		}
	}

	private func stopLSPFromStatusPanel(_ key: LSPSessionKey) {
		let entry = lspStatusEntries[key]
		let client = entry?.client
		let supervisor = lspSupervisors[key]
		lspSyncCoordinators[key] = nil
		lspSupervisorTasks[key]?.cancel()
		lspSupervisorTasks[key] = nil
		lspSupervisors[key] = nil
		Task {
			await supervisor?.stop()
			client?.terminate()
			await Self.lspManager.markFailed(key)
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
				guard generation == self.lspStatusGeneration else {
					return
				}
				self.activeLSPKey = key
				if let key, self.lspStatusEntries[key] == nil {
					self.setLSPStatus(key: key, status: "idle", client: nil, lastError: nil, url: fileURL)
				} else {
					self.refreshStatusBar()
				}
			}
		}
	}

	private func setLSPStatus(
		key: LSPSessionKey,
		status: String,
		client: LSPProcessClient?,
		lastError: String?,
		url: URL?
	) {
		let existing = lspStatusEntries[key]
		let clearsClient = status == "idle" || status == "crashed" || status == "disabled"
		lspStatusEntries[key] = LSPStatusEntry(
			key: key,
			status: status,
			server: client.map(Self.serverName(for:)) ?? existing?.server ?? key.languageID,
			pid: client?.processIdentifier ?? existing?.pid,
			startDate: client?.startDate ?? existing?.startDate,
			lastError: lastError ?? existing?.lastError ?? "",
			url: url ?? existing?.url,
			client: client ?? (clearsClient ? nil : existing?.client)
		)
		activeLSPKey = key
		if status == "crashed" || status == "disabled" {
			lspRestartKey = key
			lspRestartURL = url ?? existing?.url
		}
		refreshStatusBar()
	}

	private func refreshLSPStatusPill() {
		guard let key = activeLSPKey, let entry = lspStatusEntries[key] else {
			lspStatusButton.isHidden = true
			return
		}
		lspStatusButton.title = L10n.string("LSP: \(entry.key.languageID) \(entry.status)")
		lspStatusButton.toolTip = L10n.string("LSP status")
		lspStatusButton.isHidden = false
	}

	private func currentLSPStatusSnapshot() -> LSPStatusPanelSnapshot? {
		guard let key = activeLSPKey, let entry = lspStatusEntries[key] else {
			return nil
		}
		return LSPStatusPanelSnapshot(
			key: entry.key,
			status: entry.status,
			server: entry.server,
			pid: entry.pid,
			startDate: entry.startDate,
			lastError: entry.lastError
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
		super.showWindow(sender)
		window?.makeKeyAndOrderFront(sender)
		window?.orderFrontRegardless()
		focusEditor()
		ItsyTabCoordinator.refresh()
	}

	func display(document newDocument: ItsyDocument) {
		if self.document as? ItsyDocument === newDocument {
			showWindow(nil)
			focusEditor()
			ItsyTabCoordinator.refresh()
			return
		}
		if let oldDocument = self.document as? ItsyDocument {
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
		for pane in paneCoordinator.panes {
			document.detach(pane.editorView)
		}
		for pane in paneCoordinator.restore(layout: layout) {
			installPane(pane, document: document)
		}
		refreshLSPMissingBanner(for: document)
		refreshLSPStatus(for: document)
		focusEditor()
	}

	func focusEditor() {
		window?.makeFirstResponder(editorView)
	}

	private func installPane(_ pane: EditorPane, document: ItsyDocument) {
		let view = pane.editorView
		document.attach(view)
		let preferences = EditorPreferences.load()
		view.configureEditorAppearance(
			fontName: preferences.fontName,
			fontSize: preferences.fontSize,
			showsLineNumbers: preferences.showLineNumbers
		)
		view.keymapEngine = ItsyAppKeymap.makeEngine()
		view.commandRequested = { [weak self] commandID in
			self?.performKeymapCommand(commandID) ?? false
		}
		view.completionRequested = { [weak self, weak view] trigger in
			self?.requestCompletion(triggerCharacter: trigger, in: view) ?? false
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
		view.exCommandLineRequested = { [weak self] completion in
			ItsyCommandPaletteBridge.requestExCommand(relativeTo: self?.window, completion: completion)
		}
	}

	func applyEditorPreferences(_ preferences: EditorPreferences) {
		for pane in paneCoordinator.panes {
			pane.editorView.configureEditorAppearance(
				fontName: preferences.fontName,
				fontSize: preferences.fontSize,
				showsLineNumbers: preferences.showLineNumbers
			)
		}
	}

	private func ensureFindBarController() -> FindBarController {
		if let findBarController {
			return findBarController
		}
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
		findBarController = controller
		return controller
	}

	private func splitActivePane(vertical: Bool) {
		guard let document = document as? ItsyDocument else {
			return
		}
		let pane = paneCoordinator.splitActive(vertical: vertical)
		installPane(pane, document: document)
		focusEditor()
	}

	private func closeActivePane() -> Bool {
		guard let document = document as? ItsyDocument, let pane = paneCoordinator.closeActive() else {
			return false
		}
		document.detach(pane.editorView)
		focusEditor()
		return true
	}

	private func closeOtherPanes() {
		guard let document = document as? ItsyDocument else {
			return
		}
		for pane in paneCoordinator.closeOtherPanes() {
			document.detach(pane.editorView)
		}
		focusEditor()
	}

	private func focusAdjacentPane(delta: Int) {
		_ = paneCoordinator.focusAdjacent(delta: delta)
		focusEditor()
	}

	func performEditorMotion(_ motion: Motion) {
		editorView.performMotion(motion)
		focusEditor()
	}

	func toggleFindBar() {
		ensureFindBarController().toggle()
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

	private func performKeymapCommand(_ commandID: String) -> Bool {
		switch commandID {
		case "file.open":
			NSDocumentController.shared.openDocument(nil)
		case "file.nextBuffer":
			ItsyTabCoordinator.selectAdjacentDocument(delta: 1)
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
		case "lsp.references":
			return findAllReferences(nil)
		default:
			return false
		}
		return true
	}

	@discardableResult
	func findAllReferences(_: Any?) -> Bool {
		requestReferences(at: editorView.editor.selections.primary.head, in: editorView)
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
		let result = try await client.documentSymbol(textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString))
		let relativePath = LSPDiagnosticsAggregator.relativePath(forURI: fileURL.standardizedFileURL.absoluteString, root: key.workspaceRoot) ?? fileURL.lastPathComponent
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
	private func requestCompletion(triggerCharacter: String?, forIncomplete: Bool = false, in targetView: MetalTextView?) -> Bool {
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
		let context = completionContext(triggerCharacter: triggerCharacter, forIncomplete: forIncomplete)
		completionRequestGeneration += 1
		let generation = completionRequestGeneration
		Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await self.ensureLSPSession(for: fileURL)
				try await self.syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let params = LSPCompletionParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position,
					context: context
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentCompletion,
					params: try LSPAny(encoding: params)
				)
				let result = try LSPCompletionResult(result: response.result)
				await MainActor.run { [weak self, weak targetView] in
					guard let self, let targetView, generation == self.completionRequestGeneration else {
						return
					}
					self.showCompletionPopup(result: result, in: targetView, sessionKey: session.key)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == self.completionRequestGeneration else {
						return
					}
					self.completionPopup?.dismiss()
					self.handleLSPRequestError(error)
					NSLog("completion failed: \(error)")
				}
			}
		}
		return true
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

	private func ensureLSPSession(for url: URL) async throws -> (client: LSPProcessClient, key: LSPSessionKey) {
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
				let params = try LSPInitializeParams.itsy(workspaceRoot: key.workspaceRoot)
				let result = try await client.initialize(params)
				let capabilities = try? LSPInitializeResult(result: result).capabilities
				let completionProvider = capabilities?.completionProvider
				let triggers = completionProvider?.triggerCharacters.map(Set.init) ?? []
				let resolveProvider = completionProvider?.resolveProvider ?? false
				let signatureTriggers = capabilities?.signatureHelpProvider?.triggerCharacters.map(Set.init) ?? []
				await Self.lspManager.markRunning(key)
				await MainActor.run { [weak self] in
					guard let self else {
						return
					}
					self.installLSPSupervisor(for: key, client: client, url: url)
					self.setLSPStatus(key: key, status: "running", client: client, lastError: nil, url: url)
					self.setCompletionCapabilities(triggerCharacters: triggers, resolveProvider: resolveProvider, for: key)
					self.setSignatureHelpTriggerCharacters(signatureTriggers, for: key)
					self.clearLSPCrashStatus(for: key)
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
			self.installLSPSupervisor(for: key, client: client, url: url)
			self.setLSPStatus(key: key, status: "running", client: client, lastError: nil, url: url)
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
		} else {
			await coordinator.didChange(url: url, content: content)
			await coordinator.flushPendingChange(for: url)
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

	private func installLSPSupervisor(for key: LSPSessionKey, client: LSPProcessClient, url: URL) {
		guard lspSupervisors[key] == nil else {
			lspRestartURL = lspRestartKey == key ? url : lspRestartURL
			return
		}
		let supervisor = LSPSessionSupervisor(key: key, client: client)
		lspSupervisors[key] = supervisor
		lspSupervisorTasks[key] = Task { [weak self, supervisor] in
			await supervisor.start()
			for await event in supervisor.events {
				await MainActor.run { [weak self] in
					self?.handleLSPSupervisorEvent(event, key: key, url: url)
				}
			}
		}
	}

	private func handleLSPSupervisorEvent(_ event: LSPSessionSupervisorEvent, key: LSPSessionKey, url: URL) {
		switch event {
		case .diagnosticsUpdated:
			break
		case let .sessionFailed(reason):
			lspSyncCoordinators[key] = nil
			completionTriggerCharactersBySession[key] = nil
			signatureHelpTriggerCharactersBySession[key] = nil
			completionResolveEnabledBySession[key] = nil
			lspSupervisors[key] = nil
			lspSupervisorTasks[key] = nil
			Task {
				await Self.lspManager.markFailed(key)
			}
			showLSPCrashStatus(key: key, url: url, reason: reason)
			NSLog("lsp session failed: \(key.languageID) exit \(reason.status) \(reason.stderrTail)")
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
				let session = try await self.ensureLSPSession(for: url)
				try await self.syncLSPDocument(client: session.client, key: session.key, url: url, content: content)
			} catch {
				await MainActor.run { [weak self] in
					self?.handleLSPRequestError(error)
					NSLog("lsp restart failed: \(error)")
				}
			}
		}
	}

	private func setCompletionCapabilities(triggerCharacters characters: Set<String>, resolveProvider: Bool, for key: LSPSessionKey) {
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

	private func showCompletionPopup(result: LSPCompletionResult, in targetView: MetalTextView, sessionKey: LSPSessionKey) {
		let popup = completionPopup ?? CompletionPopupController()
		completionPopup = popup
		let resolve: ((LSPCompletionItem, @escaping (LSPCompletionItem) -> Void) -> Void)?
		if completionResolveEnabledBySession[sessionKey] == true {
			resolve = { [weak self] item, completion in
				self?.requestCompletionResolve(item, completion: completion)
			}
		} else {
			resolve = nil
		}
		popup.show(
			result: result,
			relativeTo: window,
			editorView: targetView,
			requestAgain: { [weak self, weak targetView] in
				_ = self?.requestCompletion(triggerCharacter: nil, forIncomplete: true, in: targetView)
			},
			resolve: resolve,
			accept: { [weak self, weak targetView] item in
				guard let self, let targetView else {
					return
				}
				self.acceptCompletion(item, in: targetView)
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
				let session = try await self.ensureLSPSession(for: fileURL)
				let response = try await session.client.sendRequest(
					method: LSPMethod.completionItemResolve,
					params: try LSPAny(encoding: item)
				)
				let resolved = item.mergingResolvedFields(from: try LSPCompletionItem(resolveResult: response.result))
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
		focusEditor()
	}

	private func scheduleHover(_ candidate: TextHoverCandidate?, in targetView: MetalTextView?) {
		hoverTimer?.invalidate()
		hoverTimer = nil
		guard
			let candidate,
			let targetView,
			let offset = identifierOffset(in: editorStorageString(targetView.editor), near: candidate.offset)
		else {
			closeHoverPopover()
			return
		}
		let rect = targetView.positioningRectForUTF8Offset(offset)
		hoverTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { [weak self, weak targetView] _ in
			_ = self?.requestHover(at: offset, positioningRect: rect, in: targetView)
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
		hoverRequestGeneration += 1
		let generation = hoverRequestGeneration
		Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await self.ensureLSPSession(for: fileURL)
				try await self.syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let params = LSPHoverParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentHover,
					params: try LSPAny(encoding: params)
				)
				let result = try LSPHoverResult(result: response.result)
				await MainActor.run { [weak self, weak targetView] in
					guard let self, let targetView, generation == self.hoverRequestGeneration else {
						return
					}
					self.showHoverPopover(result: result, positioningRect: positioningRect, in: targetView)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == self.hoverRequestGeneration else {
						return
					}
					self.closeHoverPopover()
					self.handleLSPRequestError(error)
					NSLog("hover failed: \(error)")
				}
			}
		}
		return true
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
		signatureHelpRequestGeneration += 1
		let generation = signatureHelpRequestGeneration
		Task { [weak self, weak targetView] in
			do {
				guard let self, let targetView else {
					return
				}
				let session = try await self.ensureLSPSession(for: fileURL)
				try await self.syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let params = LSPSignatureHelpParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position,
					context: context
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentSignatureHelp,
					params: try LSPAny(encoding: params)
				)
				let result = try LSPSignatureHelpResult(result: response.result)
				await MainActor.run { [weak self, weak targetView] in
					guard let self, let targetView, generation == self.signatureHelpRequestGeneration else {
						return
					}
					self.showSignatureHelpPopover(result: result, in: targetView)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == self.signatureHelpRequestGeneration else {
						return
					}
					self.closeSignatureHelpPopover()
					self.handleLSPRequestError(error)
					NSLog("signature help failed: \(error)")
				}
			}
		}
		return true
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
				let session = try await self.ensureLSPSession(for: fileURL)
				try await self.syncLSPDocument(client: session.client, key: session.key, url: fileURL, content: content)
				let params = LSPReferenceParams(
					textDocument: LSPTextDocumentIdentifier(uri: fileURL.standardizedFileURL.absoluteString),
					position: position,
					context: LSPReferenceContext(includeDeclaration: true)
				)
				let response = try await session.client.sendRequest(
					method: LSPMethod.textDocumentReferences,
					params: try LSPAny(encoding: params)
				)
				let result = try LSPReferencesResult(result: response.result)
				let snapshot = LSPReferencesSnapshot(
					locations: result.locations,
					rootURL: rootURL,
					currentFileURL: fileURL,
					currentText: content
				)
				await MainActor.run { [weak self] in
					guard let self, generation == self.referencesRequestGeneration else {
						return
					}
					self.showReferences(snapshot)
				}
			} catch {
				await MainActor.run { [weak self] in
					guard let self, generation == self.referencesRequestGeneration else {
						return
					}
					self.handleLSPRequestError(error)
					self.referencesCoordinator?.show(error: error, relativeTo: self.window)
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
		hoverPopover?.close()
		hoverPopover = nil
	}

	private func closeSignatureHelpPopover() {
		signatureHelpRequestGeneration += 1
		signatureHelpPopover?.close()
		signatureHelpPopover = nil
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
		case "q":
			(document as? NSDocument)?.close()
		case "wq", "x":
			(document as? NSDocument)?.save(nil)
			(document as? NSDocument)?.close()
		case "bn":
			ItsyTabCoordinator.selectAdjacentDocument(delta: 1)
		case "bp":
			ItsyTabCoordinator.selectAdjacentDocument(delta: -1)
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
			let base = document?.fileURL?.deletingLastPathComponent() ?? ItsyWorkspaceController.currentRootURL ?? URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
			url = base.appendingPathComponent(trimmed)
		}
		return ItsyWorkspaceController.openFile(at: url)
	}

}

extension EditorWindowController: NSWindowDelegate {
	func windowDidBecomeKey(_ notification: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func windowDidBecomeMain(_ notification: Notification) {
		ItsyTabCoordinator.refresh()
	}

	func windowWillClose(_ notification: Notification) {
		ItsyTabCoordinator.refresh()
	}
}
