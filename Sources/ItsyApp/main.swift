import AppKit
import Dispatch
import Foundation
import ItsyEditor
import ItsyKeymap
import ItsyRender
import ItsySyntax

private func recordBenchStage(_ name: String) {
	guard let path = ProcessInfo.processInfo.environment["ITSY_BENCH_STAGES_PATH"] else {
		return
	}
	let line = "\(name) \(DispatchTime.now().uptimeNanoseconds)\n"
	let url = URL(fileURLWithPath: path)
	if !FileManager.default.fileExists(atPath: path) {
		FileManager.default.createFile(atPath: path, contents: nil)
	}
	guard let handle = try? FileHandle(forWritingTo: url) else {
		return
	}
	defer {
		try? handle.close()
	}
	_ = try? handle.seekToEnd()
	_ = try? handle.write(contentsOf: Data(line.utf8))
}

private func exitForBenchReady() {
	let ns = DispatchTime.now().uptimeNanoseconds
	FileHandle.standardOutput.write(Data("READY \(ns)\n".utf8))
	exit(0)
}

private final class OutlineKindNode: NSObject {
	let kind: WorkspaceSymbolKind
	let symbols: [OutlineSymbolNode]
	init(kind: WorkspaceSymbolKind, symbols: [OutlineSymbolNode]) {
		self.kind = kind
		self.symbols = symbols
	}
}

private final class OutlineSymbolNode: NSObject {
	let symbol: WorkspaceSymbol
	init(_ symbol: WorkspaceSymbol) {
		self.symbol = symbol
	}
}

private enum OutlineCollapseStore {
	private static var fileURL: URL {
		let home = FileManager.default.homeDirectoryForCurrentUser
		return home
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("outline-state.json")
	}

	static func load() -> [String: Set<String>] {
		guard
			let data = try? Data(contentsOf: fileURL),
			let raw = try? JSONDecoder().decode([String: [String]].self, from: data)
		else {
			return [:]
		}
		return raw.mapValues(Set.init).filter { key, _ in
			URL(string: key).map { FileManager.default.fileExists(atPath: $0.path) } ?? false
		}
	}

	static func save(_ state: [String: Set<String>]) {
		let url = fileURL
		try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		let raw = state.mapValues { Array($0).sorted() }
		guard let data = try? JSONEncoder().encode(raw) else {
			return
		}
		try? data.write(to: url, options: .atomic)
	}
}

private struct GitCommitDraft: Codable, Equatable {
	var summary: String
	var body: String
}

private enum GitCommitDraftStore {
	private static var fileURL: URL {
		let home = FileManager.default.homeDirectoryForCurrentUser
		return home
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("commit-drafts.json")
	}

	static func load(for root: URL) -> GitCommitDraft {
		loadAll()[key(for: root)] ?? GitCommitDraft(summary: "", body: "")
	}

	static func save(_ draft: GitCommitDraft, for root: URL) {
		var all = loadAll()
		if draft.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			all.removeValue(forKey: key(for: root))
		} else {
			all[key(for: root)] = draft
		}
		let url = fileURL
		try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		guard let data = try? JSONEncoder().encode(all) else {
			return
		}
		try? data.write(to: url, options: .atomic)
	}

	private static func loadAll() -> [String: GitCommitDraft] {
		guard
			let data = try? Data(contentsOf: fileURL),
			let drafts = try? JSONDecoder().decode([String: GitCommitDraft].self, from: data)
		else {
			return [:]
		}
		return drafts
	}

	private static func key(for root: URL) -> String {
		root.standardizedFileURL.path
	}
}

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: ItsyDocumentController
	private weak var openRecentMenu: NSMenu?
	private weak var gitGutterIndexMenuItem: NSMenuItem?
	private weak var gitGutterHeadMenuItem: NSMenuItem?
	private lazy var commandRegistry = makeCommandRegistry()
	private var commandPalettePanel: NSPanel?
	private var commandPaletteInputField: NSTextField?
	private var commandPaletteTableView: NSTableView?
	private var commandPaletteCancelHandler: (() -> Void)?
	private var commandPaletteRunText: ((String) -> Void)?
	private var commandPaletteItems: [Command] = []
	private var commandPaletteFilteredItems: [Command] = []
	private var commandPaletteAcceptsRawText = false
	private enum CommandPaletteSymbolScope { case workspace, file }
	private var commandPaletteSymbolScope: CommandPaletteSymbolScope?
	private var commandPaletteSymbols: [WorkspaceSymbol] = []
	private var commandPaletteFilteredSymbols: [WorkspaceSymbol] = []
	private var settingsWindowController: NSWindowController?
	private var settingsThemePopup: NSPopUpButton?
	private var settingsStatusLabel: NSTextField?
	private var projectFindPanel: NSPanel?
	private var projectFindInputField: NSTextField?
	private var projectFindStatusLabel: NSTextField?
	private var projectFindTableView: NSTableView?
	private var projectFindMatches: [ProjectFindMatch] = []
	private var projectFindGeneration = 0
	private var gitPanel: NSPanel?
	private var gitStatusLabel: NSTextField?
	private var gitTableView: NSTableView?
	private var gitBranchButton: NSButton?
	private var gitBranchPopover: NSPopover?
	private var gitBranchTableView: NSTableView?
	private var gitBranches: [GitBranch] = []
	private var gitSummaryField: NSTextField?
	private var gitBodyTextView: NSTextView?
	private var gitCommitButton: NSButton?
	private var gitSignoffButton: NSButton?
	private var gitAmendButton: NSButton?
	private var gitComposerStatusLabel: NSTextField?
	private enum GitDiffMode { case unified, sideBySide }
	private var gitDiffMode: GitDiffMode = .unified
	private var gitDiffModeControl: NSSegmentedControl?
	private var gitDiffStatusLabel: NSTextField?
	private var gitUnifiedDiffView: MetalTextView?
	private var gitSideOldDiffView: MetalTextView?
	private var gitSideNewDiffView: MetalTextView?
	private var gitSideBySideSplitView: NSSplitView?
	private var gitHunkTableView: NSTableView?
	private struct GitDiffHunkItem {
		var fileIndex: Int
		var hunkIndex: Int
		var title: String
		var isStaged: Bool
	}
	private struct GitDiffLineItem {
		var fileIndex: Int
		var hunkIndex: Int
		var lineIndex: Int
		var range: Range<Int>
	}
	private enum GitLineSelectionError: Error {
		case unifiedModeRequired
		case noChangedLinesSelected
	}
	private var gitDraftRootURL: URL?
	private var gitDraftBeforeHistory: GitCommitDraft?
	private var gitRecentCommitMessages: [GitCommitDraft] = []
	private var gitRecentCommitIndex: Int?
	private var gitEntries: [GitStatusEntry] = []
	private var gitRootURL: URL?
	private var gitDiffFiles: [DiffFile] = []
	private var gitDiffPath: String?
	private var gitHunkItems: [GitDiffHunkItem] = []
	private var gitUnifiedLineItems: [GitDiffLineItem] = []
	private var gitRemoteProcess: Process?
	private var gitRemoteLog = ""
	private var gitConflictPanel: NSPanel?
	private var gitConflictRootURL: URL?
	private var gitConflictPath: String?
	private var gitConflictMergedTextView: NSTextView?
	private var gitConflictRegionStack: NSStackView?
	private var taskPanel: NSPanel?
	private var taskStatusLabel: NSTextField?
	private var taskTableView: NSTableView?
	private var taskOutputTextView: NSTextView?
	private var workspaceTasks: [WorkspaceTask] = []
	private var taskRunGeneration = 0
	private var problemsPanel: NSPanel?
	private var problemsStatusLabel: NSTextField?
	private var problemsTableView: NSTableView?
	private var workspaceProblems: [WorkspaceProblem] = []
	private var problemsRootURL: URL?
	private var outlinePanel: NSPanel?
	private var outlineStatusLabel: NSTextField?
	private var outlineOutlineView: NSOutlineView?
	private var outlineKindNodes: [OutlineKindNode] = []
	private var outlineWindowObserver: NSObjectProtocol?
	private var outlineCollapseStateByURL: [String: Set<String>] = OutlineCollapseStore.load()
	private var outlineActiveURLKey: String?
	private var outlineSuppressPersist = false

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
		recordBenchStage("delegate_init")
		do {
			let profile = try KeymapProfile.selected(from: CommandLine.arguments)
			let bindings = try KeymapConfiguration.load(profile: profile)
			ItsyAppKeymap.configure(profile: profile, bindings: bindings)
		} catch {
			NSLog("failed to load keymap profile: \(error)")
			ItsyAppKeymap.configure(profile: .plain, bindings: [])
		}
		super.init()
		ItsyCommandPaletteBridge.showExCommand = { [weak self] window, completion in
			guard let self else {
				return false
			}
			self.showExCommand(relativeTo: window, completion: completion)
			return true
		}
	}

	func applicationDidFinishLaunching(_ notification: Notification) {
		recordBenchStage("app_did_finish_launching")
		installServicesProvider()
		installMainMenu()
		recordBenchStage("main_menu_installed")
		openInitialDocument()
		recordBenchStage("initial_document_opened")
		if CommandLine.arguments.contains("--bench-exit-after-initial-document") {
			exitForBenchReady()
		}
		NSApp.activate(ignoringOtherApps: true)
		recordBenchStage("app_activated")
	}

	func application(_ sender: NSApplication, openFile filename: String) -> Bool {
		openPath(URL(fileURLWithPath: filename))
	}

	func application(_ application: NSApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([NSUserActivityRestoring]) -> Void) -> Bool {
		guard userActivity.activityType == ItsyDocument.handoffActivityType,
		      let value = userActivity.userInfo?[ItsyDocument.handoffURLKey] as? String,
		      let url = URL(string: value)
		else {
			return false
		}
		guard documentController.openDocument(at: url),
		      let document = documentController.document(for: url) as? ItsyDocument
		else {
			return false
		}
		if let offset = userActivity.userInfo?[ItsyDocument.handoffCursorOffsetKey] as? Int {
			document.restoreHandoffCursorOffset(offset)
		}
		return true
	}

	@objc private func closeCurrentDocument(_ sender: Any?) {
		if let document = NSApp.keyWindow?.windowController?.document as? NSDocument {
			document.close()
			return
		}
		if let document = documentController.currentDocument {
			document.close()
			return
		}
		if let document = documentController.documents.last {
			document.close()
			return
		}
		NSApp.keyWindow?.performClose(sender)
	}

	@objc private func useGitGutterIndex(_: Any?) {
		ItsyGitHunkGutterCoordinator.setMode(.index)
		refreshGitGutterMenuItems()
	}

	@objc private func useGitGutterHead(_: Any?) {
		ItsyGitHunkGutterCoordinator.setMode(.head)
		refreshGitGutterMenuItems()
	}

	private func refreshGitGutterMenuItems() {
		let mode = ItsyGitHunkGutterCoordinator.currentMode
		gitGutterIndexMenuItem?.state = mode == .index ? .on : .off
		gitGutterHeadMenuItem?.state = mode == .head ? .on : .off
	}

	@objc private func toggleCommandPalette(_ sender: Any?) {
		toggleCommandPalettePanel(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleCommandPalettePanel(relativeTo hostWindow: NSWindow?) {
		if commandPalettePanel?.isVisible == true {
			closeCommandPalette()
			return
		}
		showCommandPalette(relativeTo: hostWindow)
	}

	private func closeCommandPalette() {
		commandPaletteCancelHandler = nil
		commandPaletteRunText = nil
		commandPalettePanel?.close()
	}

	private func showExCommand(relativeTo hostWindow: NSWindow?, completion: @escaping (String?) -> Void) {
		let panel = makeCommandPalettePanelIfNeeded()
		commandPaletteCancelHandler = { completion(nil) }
		commandPaletteRunText = { [weak self] text in
			self?.commandPaletteCancelHandler = nil
			self?.commandPalettePanel?.close()
			completion(text)
		}
		setCommandPaletteCommandLine(":")
		centerCommandPalette(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		focusCommandPaletteInput()
	}

	private func showCommandPalette(relativeTo hostWindow: NSWindow?, prefill: String? = nil) {
		let panel = makeCommandPalettePanelIfNeeded()
		commandPaletteCancelHandler = nil
		commandPaletteRunText = nil
		setCommandPaletteItems(commandRegistry.commands)
		if let prefill, !prefill.isEmpty {
			commandPaletteInputField?.stringValue = prefill
			filterCommandPaletteItems()
		}
		centerCommandPalette(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		focusCommandPaletteInput()
	}

	@objc private func showWorkspaceSymbolPalette(_ sender: Any?) {
		showCommandPalette(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow, prefill: "@")
	}

	@objc private func showFileSymbolPalette(_ sender: Any?) {
		showCommandPalette(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow, prefill: "#")
	}

	private func makeCommandPalettePanelIfNeeded() -> NSPanel {
		if let panel = commandPalettePanel {
			return panel
		}
		let size = NSSize(width: 560, height: 280)
		let panel = NSPanel(
			contentRect: NSRect(origin: .zero, size: size),
			styleMask: [.titled, .fullSizeContentView],
			backing: .buffered,
			defer: false
		)
		let contentView = NSView(frame: NSRect(origin: .zero, size: size))
		configureCommandPaletteView(contentView)
		panel.contentView = contentView
		panel.title = L10n.string("Command Palette")
		panel.titleVisibility = .hidden
		panel.titlebarAppearsTransparent = true
		panel.isReleasedWhenClosed = false
		panel.hasShadow = true
		panel.level = .floating
		panel.delegate = self
		commandPalettePanel = panel
		return panel
	}

	private func configureCommandPaletteView(_ contentView: NSView) {
		contentView.wantsLayer = true
		contentView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		contentView.layer?.cornerRadius = 8
		contentView.layer?.borderWidth = 1
		contentView.layer?.borderColor = NSColor.separatorColor.cgColor

		let inputField = NSTextField(frame: .zero)
		inputField.placeholderString = L10n.string("Command")
		inputField.font = .systemFont(ofSize: 18)
		inputField.isBordered = false
		inputField.focusRingType = .none
		inputField.backgroundColor = .clear
		inputField.translatesAutoresizingMaskIntoConstraints = false
		inputField.delegate = self
		contentView.addSubview(inputField)

		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("command"))
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowHeight = 30
		tableView.intercellSpacing = NSSize(width: 0, height: 0)
		tableView.usesAlternatingRowBackgroundColors = false
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(runCommandPaletteTableSelection(_:))

		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)

		NSLayoutConstraint.activate([
			inputField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 18),
			inputField.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -18),
			inputField.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			inputField.heightAnchor.constraint(equalToConstant: 32),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 8),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -8),
			scrollView.topAnchor.constraint(equalTo: inputField.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -8),
		])
		commandPaletteInputField = inputField
		commandPaletteTableView = tableView
	}

	private func centerCommandPalette(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(560, max(360, hostFrame.width - 80))
		let height: CGFloat = 280
		let frame = NSRect(
			x: hostFrame.midX - width / 2,
			y: hostFrame.midY - height / 2,
			width: width,
			height: height
		)
		panel.setFrame(frame, display: true)
	}

	private func cancelCommandPalette() {
		let handler = commandPaletteCancelHandler
		commandPaletteCancelHandler = nil
		commandPalettePanel?.close()
		handler?()
	}

	private func setCommandPaletteItems(_ items: [Command]) {
		commandPaletteAcceptsRawText = false
		commandPaletteSymbolScope = nil
		commandPaletteSymbols = []
		commandPaletteFilteredSymbols = []
		commandPaletteItems = items
		commandPaletteInputField?.stringValue = ""
		commandPaletteInputField?.placeholderString = L10n.string("Command")
		commandPaletteTableView?.enclosingScrollView?.isHidden = false
		filterCommandPaletteItems()
	}

	private func setCommandPaletteCommandLine(_ value: String) {
		commandPaletteAcceptsRawText = true
		commandPaletteSymbolScope = nil
		commandPaletteSymbols = []
		commandPaletteFilteredSymbols = []
		commandPaletteItems = []
		commandPaletteFilteredItems = []
		commandPaletteInputField?.placeholderString = ""
		commandPaletteInputField?.stringValue = value
		commandPaletteTableView?.enclosingScrollView?.isHidden = true
		commandPaletteTableView?.reloadData()
	}

	private func focusCommandPaletteInput() {
		commandPalettePanel?.makeFirstResponder(commandPaletteInputField)
		commandPaletteInputField?.currentEditor()?.selectedRange = NSRange(location: commandPaletteInputField?.stringValue.count ?? 0, length: 0)
	}

	private func symbolsForCommandPaletteScope(_ scope: CommandPaletteSymbolScope) -> [WorkspaceSymbol] {
		guard let index = ItsyWorkspaceController.currentWorkspaceIndex else {
			return []
		}
		switch scope {
		case .workspace:
			return index.symbols
		case .file:
			guard let url = (activeDocument() as? ItsyDocument)?.fileURL,
			      let relative = index.relativePath(for: url)
			else {
				return []
			}
			return index.symbolsForFile(relativePath: relative)
		}
	}

	private func filterCommandPaletteItems() {
		guard !commandPaletteAcceptsRawText else {
			return
		}
		let raw = commandPaletteInputField?.stringValue ?? ""
		if raw.hasPrefix("@") || raw.hasPrefix("#") {
			let scope: CommandPaletteSymbolScope = raw.hasPrefix("@") ? .workspace : .file
			if commandPaletteSymbolScope != scope {
				commandPaletteSymbols = symbolsForCommandPaletteScope(scope)
				commandPaletteSymbolScope = scope
			}
			let query = String(raw.dropFirst()).lowercased()
			let keyPath: (WorkspaceSymbol) -> String = scope == .workspace
				? { "\($0.name) \($0.relativePath)" }
				: { $0.name }
			commandPaletteFilteredSymbols = FuzzyMatcher.ranked(commandPaletteSymbols, query: query, includeUnmatched: query.isEmpty, by: keyPath)
			commandPaletteFilteredItems = []
			commandPaletteTableView?.reloadData()
			if !commandPaletteFilteredSymbols.isEmpty {
				commandPaletteTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
			}
			return
		}
		if commandPaletteSymbolScope != nil {
			commandPaletteSymbolScope = nil
			commandPaletteSymbols = []
			commandPaletteFilteredSymbols = []
		}
		let query = raw.lowercased()
		commandPaletteFilteredItems = FuzzyMatcher.ranked(commandPaletteItems, query: query, includeUnmatched: false, by: \.title)
		commandPaletteTableView?.reloadData()
		if !commandPaletteFilteredItems.isEmpty {
			commandPaletteTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func moveCommandPaletteSelection(_ delta: Int) {
		guard !commandPaletteAcceptsRawText, let tableView = commandPaletteTableView else {
			return
		}
		let count = commandPaletteSymbolScope != nil ? commandPaletteFilteredSymbols.count : commandPaletteFilteredItems.count
		guard count > 0 else {
			return
		}
		let current = tableView.selectedRow >= 0 ? tableView.selectedRow : 0
		let next = min(max(current + delta, 0), count - 1)
		tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
		tableView.scrollRowToVisible(next)
	}

	private func runCommandPaletteSelection() {
		if commandPaletteAcceptsRawText {
			commandPaletteRunText?(commandPaletteInputField?.stringValue ?? "")
			return
		}
		if commandPaletteSymbolScope != nil {
			guard
				let tableView = commandPaletteTableView,
				tableView.selectedRow >= 0,
				tableView.selectedRow < commandPaletteFilteredSymbols.count,
				let root = ItsyWorkspaceController.currentRootURL
			else {
				return
			}
			let symbol = commandPaletteFilteredSymbols[tableView.selectedRow]
			closeCommandPalette()
			let url = root.appendingPathComponent(symbol.relativePath)
			documentController.openDocument(at: url, line: symbol.line, column: symbol.column)
			return
		}
		guard let tableView = commandPaletteTableView, tableView.selectedRow >= 0, tableView.selectedRow < commandPaletteFilteredItems.count else {
			return
		}
		let item = commandPaletteFilteredItems[tableView.selectedRow]
		closeCommandPalette()
		item.run()
	}

	@objc private func runCommandPaletteTableSelection(_ sender: Any?) {
		runCommandPaletteSelection()
	}

	private func makeCommandRegistry() -> CommandRegistry {
		var registry = CommandRegistry()
		do {
			try registry.register([
				Command(id: "file.new", title: L10n.string("New File"), defaultKey: "Cmd-N") { [weak self] in
					self?.documentController.newDocument(nil)
				},
				Command(id: "file.open", title: L10n.string("Open File"), defaultKey: "Cmd-O") { [weak self] in
					self?.documentController.openDocument(nil)
				},
				Command(id: "file.openFolder", title: L10n.string("Open Folder"), defaultKey: "Cmd-Shift-O") { [weak self] in
					self?.openFolder(nil)
				},
				Command(id: "file.save", title: L10n.string("Save File"), defaultKey: "Cmd-S") { [weak self] in
					self?.activeDocument()?.save(nil)
				},
				Command(id: "file.close", title: L10n.string("Close File"), defaultKey: "Cmd-W") { [weak self] in
					self?.closeCurrentDocument(nil)
				},
				Command(id: "view.commandPalette", title: L10n.string("Command Palette"), defaultKey: "Cmd-Shift-P") { [weak self] in
					self?.toggleCommandPalette(nil)
				},
				Command(id: "view.focusEditor", title: L10n.string("Focus Editor"), defaultKey: nil) { [weak self] in
					self?.activeEditorWindowController()?.focusEditor()
				},
				Command(id: "nav.gotoSymbolWorkspace", title: L10n.string("Go to Symbol in Workspace"), defaultKey: "Cmd-T") { [weak self] in
					self?.showWorkspaceSymbolPalette(nil)
				},
				Command(id: "nav.gotoSymbolFile", title: L10n.string("Go to Symbol in File"), defaultKey: "Cmd-Shift-O") { [weak self] in
					self?.showFileSymbolPalette(nil)
				},
				Command(id: "view.outline", title: L10n.string("Outline"), defaultKey: "Cmd-Opt-7") { [weak self] in
					self?.showOutline(nil)
				},
				Command(id: "lsp.references", title: L10n.string("Find All References"), defaultKey: "Shift-F12") { [weak self] in
					_ = self?.activeEditorWindowController()?.findAllReferences(nil)
				},
				Command(id: "app.settings", title: L10n.string("Settings"), defaultKey: "Cmd-,") { [weak self] in
					self?.showSettings(nil)
				},
					Command(id: "edit.find", title: L10n.string("Find"), defaultKey: "Cmd-F") { [weak self] in
						self?.toggleFindBar(nil)
					},
					Command(id: "edit.findNext", title: L10n.string("Find Next"), defaultKey: "Cmd-G") { [weak self] in
						self?.findNext(nil)
					},
					Command(id: "edit.findPrevious", title: L10n.string("Find Previous"), defaultKey: "Cmd-Shift-G") { [weak self] in
						self?.findPrevious(nil)
					},
					Command(id: "edit.selectAllFindMatches", title: L10n.string("Select All Find Matches"), defaultKey: "Cmd-Ctrl-G") { [weak self] in
						self?.selectAllFindMatches(nil)
					},
					Command(id: "edit.findInProject", title: L10n.string("Find in Project"), defaultKey: "Cmd-Shift-F") { [weak self] in
						self?.showProjectFind(nil)
					},
					Command(id: "git.changes", title: L10n.string("Git Changes"), defaultKey: nil) { [weak self] in
						self?.showGitChanges(nil)
					},
					Command(id: "git.refresh", title: L10n.string("Refresh Git Status"), defaultKey: nil) { [weak self] in
						self?.refreshGitChanges(nil)
					},
					Command(id: "task.run", title: L10n.string("Run Task"), defaultKey: nil) { [weak self] in
						self?.showTasks(nil)
					},
					Command(id: "task.refresh", title: L10n.string("Refresh Tasks"), defaultKey: nil) { [weak self] in
						self?.refreshTasks(nil)
					},
					Command(id: "view.problems", title: L10n.string("Problems"), defaultKey: "Cmd-Shift-M") { [weak self] in
						self?.showProblems(nil)
					},
					Command(id: "editor.moveLeft", title: L10n.string("Move Left"), defaultKey: "Left") { [weak self] in
						self?.performEditorMotion(.charBackward)
					},
					Command(id: "editor.moveRight", title: L10n.string("Move Right"), defaultKey: "Right") { [weak self] in
						self?.performEditorMotion(.charForward)
					},
				Command(id: "editor.moveLineStart", title: L10n.string("Move Line Start"), defaultKey: "Cmd-Left") { [weak self] in
					self?.performEditorMotion(.lineStart)
				},
				Command(id: "editor.moveLineEnd", title: L10n.string("Move Line End"), defaultKey: "Cmd-Right") { [weak self] in
					self?.performEditorMotion(.lineEnd)
				},
			])
			return registry
		} catch {
			preconditionFailure("failed to register commands: \(error)")
		}
	}

	private func activeDocument() -> NSDocument? {
		NSApp.keyWindow?.windowController?.document as? NSDocument ?? documentController.currentDocument
	}

	private func activeEditorWindowController() -> EditorWindowController? {
		NSApp.keyWindow?.windowController as? EditorWindowController
			?? documentController.currentDocument?.windowControllers.first as? EditorWindowController
	}

	private func performEditorMotion(_ motion: Motion) {
		activeEditorWindowController()?.performEditorMotion(motion)
	}

	@objc private func toggleFindBar(_ sender: Any?) {
		activeEditorWindowController()?.toggleFindBar()
	}

	@objc private func findNext(_ sender: Any?) {
		activeEditorWindowController()?.findNext()
	}

	@objc private func findPrevious(_ sender: Any?) {
		activeEditorWindowController()?.findPrevious()
	}

	@objc private func selectAllFindMatches(_ sender: Any?) {
		activeEditorWindowController()?.selectAllFindMatches()
	}

	@objc private func showProjectFind(_ sender: Any?) {
		toggleProjectFind(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleProjectFind(relativeTo hostWindow: NSWindow?) {
		if projectFindPanel?.isVisible == true {
			closeProjectFind()
			return
		}
		showProjectFind(relativeTo: hostWindow)
	}

	private func closeProjectFind() {
		projectFindPanel?.close()
	}

	private func showProjectFind(relativeTo hostWindow: NSWindow?) {
		let panel = makeProjectFindPanelIfNeeded()
		centerProjectFind(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		projectFindPanel?.makeFirstResponder(projectFindInputField)
		updateProjectFindStatusForCurrentWorkspace()
	}

	private func makeProjectFindPanelIfNeeded() -> NSPanel {
		if let panel = projectFindPanel {
			return panel
		}
		let size = NSSize(width: 760, height: 380)
		let panel = NSPanel(
			contentRect: NSRect(origin: .zero, size: size),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		let contentView = NSView(frame: NSRect(origin: .zero, size: size))
		configureProjectFindView(contentView)
		panel.contentView = contentView
		panel.title = L10n.string("Find in Project")
		panel.isReleasedWhenClosed = false
		panel.minSize = NSSize(width: 520, height: 260)
		projectFindPanel = panel
		return panel
	}

	private func configureProjectFindView(_ contentView: NSView) {
		contentView.wantsLayer = true
		contentView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

		let queryField = NSTextField(frame: .zero)
		queryField.placeholderString = L10n.string("Find in project")
		queryField.font = .systemFont(ofSize: 15)
		queryField.isBordered = true
		queryField.focusRingType = .default
		queryField.translatesAutoresizingMaskIntoConstraints = false
		queryField.delegate = self
		contentView.addSubview(queryField)

		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)

		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("result"))
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowHeight = 24
		tableView.intercellSpacing = NSSize(width: 0, height: 0)
		tableView.usesAlternatingRowBackgroundColors = true
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedProjectFindMatch(_:))

		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = true
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)

		NSLayoutConstraint.activate([
			queryField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			queryField.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			queryField.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			queryField.heightAnchor.constraint(equalToConstant: 28),
			statusLabel.leadingAnchor.constraint(equalTo: queryField.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: queryField.trailingAnchor),
			statusLabel.topAnchor.constraint(equalTo: queryField.bottomAnchor, constant: 6),
			statusLabel.heightAnchor.constraint(equalToConstant: 16),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 8),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
		])
		projectFindInputField = queryField
		projectFindStatusLabel = statusLabel
		projectFindTableView = tableView
	}

	private func centerProjectFind(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(760, max(520, hostFrame.width - 80))
		let height = min(420, max(260, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func updateProjectFindStatusForCurrentWorkspace() {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setProjectFindResults([])
			setProjectFindStatus(L10n.string("Open a folder first"))
			return
		}
		setProjectFindResults([])
		setProjectFindStatus(root.path)
	}

	private func setProjectFindStatus(_ status: String) {
		projectFindStatusLabel?.stringValue = status
	}

	private func setProjectFindResults(_ matches: [ProjectFindMatch]) {
		projectFindMatches = matches
		projectFindTableView?.reloadData()
		if !matches.isEmpty {
			projectFindTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func searchProjectFind(query: String) {
		projectFindGeneration += 1
		let generation = projectFindGeneration
		guard !query.isEmpty else {
			updateProjectFindStatusForCurrentWorkspace()
			return
		}
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setProjectFindResults([])
			setProjectFindStatus(L10n.string("Open a folder first"))
			return
		}
		setProjectFindStatus(L10n.string("Searching..."))
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			let matches = ProjectFind.search(root: root, options: ProjectFindOptions(query: query))
			DispatchQueue.main.async { [weak self] in
				guard let self, self.projectFindGeneration == generation else {
					return
				}
				self.setProjectFindResults(matches)
				self.setProjectFindStatus(L10n.string("\(matches.count) matches"))
			}
		}
	}

	@objc private func openSelectedProjectFindMatch(_ sender: Any?) {
		guard let tableView = projectFindTableView, tableView.selectedRow >= 0, tableView.selectedRow < projectFindMatches.count else {
			return
		}
		_ = documentController.openDocument(at: projectFindMatches[tableView.selectedRow].url)
	}

	@objc private func showGitChanges(_ sender: Any?) {
		toggleGitChanges(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleGitChanges(relativeTo hostWindow: NSWindow?) {
		if gitPanel?.isVisible == true {
			closeGitChanges()
			return
		}
		showGitChanges(relativeTo: hostWindow)
	}

	private func closeGitChanges() {
		gitPanel?.close()
	}

	private func showGitChanges(relativeTo hostWindow: NSWindow?) {
		let panel = makeGitPanelIfNeeded()
		centerGitPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshGitChanges(nil)
	}

	private func makeGitPanelIfNeeded() -> NSPanel {
		if let panel = gitPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 980, height: 560),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Git Changes")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureGitView(contentView)
		gitPanel = panel
		return panel
	}

	private func configureGitView(_ contentView: NSView) {
		let composer = makeGitComposerView()
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let branchButton = NSButton(title: L10n.string("Branch"), target: self, action: #selector(showGitBranches(_:)))
		let fetchButton = NSButton(title: L10n.string("Fetch"), target: self, action: #selector(fetchGitRemote(_:)))
		let pullButton = NSButton(title: L10n.string("Pull"), target: self, action: #selector(pullGitRemote(_:)))
		let pushButton = NSButton(title: L10n.string("Push"), target: self, action: #selector(pushGitRemote(_:)))
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshGitChanges(_:)))
		let stageButton = NSButton(title: L10n.string("Stage"), target: self, action: #selector(stageSelectedGitEntries(_:)))
		let unstageButton = NSButton(title: L10n.string("Unstage"), target: self, action: #selector(unstageSelectedGitEntries(_:)))
		let buttonStack = NSStackView(views: [branchButton, fetchButton, pullButton, pushButton, refreshButton, stageButton, unstageButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("git"))
		column.title = L10n.string("Changes")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.usesAlternatingRowBackgroundColors = false
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedGitEntry(_:))
		let listScrollView = NSScrollView()
		listScrollView.documentView = tableView
		listScrollView.hasVerticalScroller = true
		listScrollView.drawsBackground = false
		let diffPane = makeGitDiffPane()
		let splitView = NSSplitView()
		splitView.isVertical = true
		splitView.dividerStyle = .thin
		splitView.addArrangedSubview(listScrollView)
		splitView.addArrangedSubview(diffPane)
		composer.translatesAutoresizingMaskIntoConstraints = false
		header.translatesAutoresizingMaskIntoConstraints = false
		splitView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(composer)
		contentView.addSubview(header)
		contentView.addSubview(splitView)
		NSLayoutConstraint.activate([
			composer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			composer.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			composer.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: composer.bottomAnchor, constant: 10),
			splitView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			splitView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			splitView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			splitView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
			listScrollView.widthAnchor.constraint(greaterThanOrEqualToConstant: 220),
			listScrollView.widthAnchor.constraint(lessThanOrEqualToConstant: 360),
			diffPane.widthAnchor.constraint(greaterThanOrEqualToConstant: 420),
		])
		gitStatusLabel = statusLabel
		gitTableView = tableView
		gitBranchButton = branchButton
	}

	private func makeGitDiffPane() -> NSView {
		let container = NSView()
		let titleLabel = NSTextField(labelWithString: L10n.string("Diff"))
		titleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		let modeControl = NSSegmentedControl(labels: [L10n.string("Unified"), L10n.string("Side")], trackingMode: .selectOne, target: self, action: #selector(changeGitDiffMode(_:)))
		modeControl.selectedSegment = 0
		modeControl.segmentStyle = .rounded
		let header = NSStackView(views: [titleLabel, statusLabel, modeControl])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.spacing = 8
		header.distribution = .fill
		let unifiedView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 640, height: 360))
		let oldView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 320, height: 360))
		let newView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 320, height: 360))
		let sideSplitView = NSSplitView()
		sideSplitView.isVertical = true
		sideSplitView.dividerStyle = .thin
		sideSplitView.addArrangedSubview(oldView)
		sideSplitView.addArrangedSubview(newView)
		let diffContentView = NSView()
		let hunkTableView = NSTableView()
		let hunkColumn = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("hunk"))
		hunkColumn.title = L10n.string("Hunks")
		hunkColumn.resizingMask = .autoresizingMask
		hunkTableView.addTableColumn(hunkColumn)
		hunkTableView.headerView = nil
		hunkTableView.rowHeight = 70
		hunkTableView.dataSource = self
		hunkTableView.delegate = self
		let hunkScrollView = NSScrollView()
		hunkScrollView.documentView = hunkTableView
		hunkScrollView.hasVerticalScroller = true
		hunkScrollView.drawsBackground = false
		let bodySplitView = NSSplitView()
		bodySplitView.isVertical = true
		bodySplitView.dividerStyle = .thin
		bodySplitView.addArrangedSubview(hunkScrollView)
		bodySplitView.addArrangedSubview(diffContentView)
		header.translatesAutoresizingMaskIntoConstraints = false
		unifiedView.translatesAutoresizingMaskIntoConstraints = false
		sideSplitView.translatesAutoresizingMaskIntoConstraints = false
		bodySplitView.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(header)
		container.addSubview(bodySplitView)
		diffContentView.addSubview(unifiedView)
		diffContentView.addSubview(sideSplitView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 10),
			header.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -10),
			header.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
			modeControl.widthAnchor.constraint(equalToConstant: 136),
			bodySplitView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			bodySplitView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			bodySplitView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
			bodySplitView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
			hunkScrollView.widthAnchor.constraint(equalToConstant: 136),
			unifiedView.leadingAnchor.constraint(equalTo: diffContentView.leadingAnchor),
			unifiedView.trailingAnchor.constraint(equalTo: diffContentView.trailingAnchor),
			unifiedView.topAnchor.constraint(equalTo: diffContentView.topAnchor),
			unifiedView.bottomAnchor.constraint(equalTo: diffContentView.bottomAnchor),
			sideSplitView.leadingAnchor.constraint(equalTo: diffContentView.leadingAnchor),
			sideSplitView.trailingAnchor.constraint(equalTo: diffContentView.trailingAnchor),
			sideSplitView.topAnchor.constraint(equalTo: diffContentView.topAnchor),
			sideSplitView.bottomAnchor.constraint(equalTo: diffContentView.bottomAnchor),
			oldView.widthAnchor.constraint(equalTo: newView.widthAnchor),
		])
		sideSplitView.isHidden = true
		gitDiffModeControl = modeControl
		gitDiffStatusLabel = statusLabel
		gitUnifiedDiffView = unifiedView
		gitSideOldDiffView = oldView
		gitSideNewDiffView = newView
		gitSideBySideSplitView = sideSplitView
		gitHunkTableView = hunkTableView
		return container
	}

	private func makeGitComposerView() -> NSView {
		let container = NSView()
		let summaryField = NSTextField()
		summaryField.placeholderString = L10n.string("Summary 50")
		summaryField.font = .systemFont(ofSize: 12)
		summaryField.delegate = self
		let summaryHint = NSTextField(labelWithString: L10n.string("50"))
		summaryHint.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
		summaryHint.textColor = .secondaryLabelColor
		let summaryRow = NSStackView(views: [summaryField, summaryHint])
		summaryRow.orientation = .horizontal
		summaryRow.alignment = .centerY
		summaryRow.spacing = 8
		let bodyTextView = NSTextView()
		bodyTextView.font = .systemFont(ofSize: 12)
		bodyTextView.isRichText = false
		bodyTextView.allowsUndo = true
		bodyTextView.delegate = self
		bodyTextView.textContainerInset = NSSize(width: 4, height: 4)
		bodyTextView.textContainer?.widthTracksTextView = true
		bodyTextView.isHorizontallyResizable = false
		bodyTextView.isVerticallyResizable = true
		let bodyScrollView = NSScrollView()
		bodyScrollView.documentView = bodyTextView
		bodyScrollView.hasVerticalScroller = true
		bodyScrollView.borderType = .bezelBorder
		let bodyHint = NSTextField(labelWithString: L10n.string("Body 72"))
		bodyHint.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
		bodyHint.textColor = .secondaryLabelColor
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		let signoffButton = NSButton(checkboxWithTitle: L10n.string("--signoff"), target: self, action: #selector(updateGitComposerStateAction(_:)))
		let amendButton = NSButton(checkboxWithTitle: L10n.string("--amend"), target: self, action: #selector(updateGitComposerStateAction(_:)))
		let commitButton = NSButton(title: L10n.string("Commit"), target: self, action: #selector(commitGitChanges(_:)))
		let footer = NSStackView(views: [statusLabel, signoffButton, amendButton, commitButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		footer.distribution = .fill
		[summaryRow, bodyHint, bodyScrollView, footer].forEach {
			$0.translatesAutoresizingMaskIntoConstraints = false
			container.addSubview($0)
		}
		NSLayoutConstraint.activate([
			summaryHint.widthAnchor.constraint(equalToConstant: 28),
			summaryRow.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			summaryRow.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			summaryRow.topAnchor.constraint(equalTo: container.topAnchor),
			bodyHint.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			bodyHint.topAnchor.constraint(equalTo: summaryRow.bottomAnchor, constant: 6),
			bodyScrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			bodyScrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			bodyScrollView.topAnchor.constraint(equalTo: bodyHint.bottomAnchor, constant: 4),
			bodyScrollView.heightAnchor.constraint(equalToConstant: 74),
			footer.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			footer.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			footer.topAnchor.constraint(equalTo: bodyScrollView.bottomAnchor, constant: 8),
			footer.bottomAnchor.constraint(equalTo: container.bottomAnchor),
		])
		gitSummaryField = summaryField
		gitBodyTextView = bodyTextView
		gitCommitButton = commitButton
		gitSignoffButton = signoffButton
		gitAmendButton = amendButton
		gitComposerStatusLabel = statusLabel
		updateGitComposerState()
		return container
	}

	private func centerGitPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(1100, max(860, hostFrame.width - 100))
		let height = min(660, max(420, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	@objc private func showGitBranches(_ sender: NSButton) {
		guard gitRootURL != nil else {
			return
		}
		let popover = makeGitBranchPopover()
		refreshGitBranches()
		popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .maxY)
	}

	private func makeGitBranchPopover() -> NSPopover {
		let popover = NSPopover()
		popover.behavior = .transient
		let controller = NSViewController()
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 360))
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("branch"))
		column.title = L10n.string("Branches")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowHeight = 54
		tableView.dataSource = self
		tableView.delegate = self
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: contentView.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		controller.view = contentView
		popover.contentViewController = controller
		gitBranchPopover = popover
		gitBranchTableView = tableView
		return popover
	}

	private func refreshGitBranches() {
		guard let gitRootURL else {
			gitBranches = []
			gitBranchTableView?.reloadData()
			return
		}
		do {
			gitBranches = try GitRepository(root: gitRootURL).branches()
			gitBranchTableView?.reloadData()
			if let current = gitBranches.first(where: \.isCurrent) {
				gitBranchButton?.title = current.name
			}
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func switchGitBranch(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitBranches.count else {
			return
		}
		let branch = gitBranches[sender.tag]
		do {
			let repository = GitRepository(root: gitRootURL)
			guard let shouldStash = try shouldStashBeforeBranchChange(targetBranch: branch.name, repository: repository) else {
				return
			}
			try repository.switchBranch(branch.name, stashingDirtyChanges: shouldStash)
			gitBranchPopover?.close()
			refreshGitChanges(nil)
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func createGitBranchFromRow(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitBranches.count else {
			return
		}
		let source = gitBranches[sender.tag]
		guard let name = promptGitBranchName(defaultName: "") else {
			return
		}
		do {
			let repository = GitRepository(root: gitRootURL)
			guard let shouldStash = try shouldStashBeforeBranchChange(targetBranch: name, repository: repository) else {
				return
			}
			try repository.createBranch(named: name, from: source.name, stashingDirtyChanges: shouldStash)
			gitBranchPopover?.close()
			refreshGitChanges(nil)
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func shouldStashBeforeBranchChange(targetBranch: String, repository: GitRepository) throws -> Bool? {
		guard try repository.status().hasChanges else {
			return false
		}
		return confirmStashAndSwitch(targetBranch) ? true : nil
	}

	@objc private func deleteGitBranchFromRow(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitBranches.count else {
			return
		}
		let branch = gitBranches[sender.tag]
		do {
			try GitRepository(root: gitRootURL).deleteBranch(branch.name)
			refreshGitBranches()
			refreshGitChanges(nil)
		} catch {
			guard confirmForceDeleteBranch(branch.name, error: error) else {
				gitStatusLabel?.textColor = .systemRed
				gitStatusLabel?.stringValue = String(describing: error)
				return
			}
			do {
				try GitRepository(root: gitRootURL).deleteBranch(branch.name, force: true)
				refreshGitBranches()
				refreshGitChanges(nil)
			} catch {
				gitStatusLabel?.textColor = .systemRed
				gitStatusLabel?.stringValue = String(describing: error)
			}
		}
	}

	private func promptGitBranchName(defaultName: String) -> String? {
		let field = NSTextField(string: defaultName)
		field.placeholderString = L10n.string("branch-name")
		let alert = NSAlert()
		alert.messageText = L10n.string("Create Branch")
		alert.accessoryView = field
		alert.addButton(withTitle: L10n.string("Create"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			return nil
		}
		let name = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		return name.isEmpty ? nil : name
	}

	private func confirmForceDeleteBranch(_ name: String, error: Error) -> Bool {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = L10n.string("Force Delete Branch?")
		alert.informativeText = "\(name)\n\(String(describing: error))"
		alert.addButton(withTitle: L10n.string("Force Delete"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	private func confirmStashAndSwitch(_ branch: String) -> Bool {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = L10n.string("Working Tree Dirty")
		alert.informativeText = L10n.string("Stash current changes and switch to \(branch)?")
		alert.addButton(withTitle: L10n.string("Stash and Switch"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	@objc private func fetchGitRemote(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Fetch"), arguments: repository.fetchArguments())
	}

	@objc private func pullGitRemote(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Pull"), arguments: repository.pullArguments())
	}

	@objc private func pullGitRemoteRebase(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Pull Rebase"), arguments: repository.pullArguments(mode: .rebase))
	}

	@objc private func pushGitRemote(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		do {
			let repository = GitRepository(root: gitRootURL)
			runGitRemoteOperation(title: L10n.string("Push"), arguments: try repository.pushArguments())
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func runGitRemoteOperation(title: String, arguments: [String]) {
		guard let gitRootURL, gitRemoteProcess == nil else {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = L10n.string("Git remote command already running")
			return
		}
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
		process.arguments = arguments
		process.currentDirectoryURL = gitRootURL
		let stdout = Pipe()
		let stderr = Pipe()
		process.standardOutput = stdout
		process.standardError = stderr
		gitRemoteProcess = process
		gitRemoteLog = "$ git \(arguments.joined(separator: " "))\n"
		gitStatusLabel?.textColor = .secondaryLabelColor
		gitStatusLabel?.stringValue = "\(title)..."
		let appendOutput: (Data) -> Void = { [weak self] data in
			guard let text = String(data: data, encoding: .utf8), !text.isEmpty else {
				return
			}
			DispatchQueue.main.async {
				guard let self else {
					return
				}
				self.gitRemoteLog += text
				let line = text.split(whereSeparator: \.isNewline).last.map(String.init) ?? text.trimmingCharacters(in: .whitespacesAndNewlines)
				if !line.isEmpty {
					self.gitStatusLabel?.textColor = .secondaryLabelColor
					self.gitStatusLabel?.stringValue = line
				}
			}
		}
		stdout.fileHandleForReading.readabilityHandler = { handle in appendOutput(handle.availableData) }
		stderr.fileHandleForReading.readabilityHandler = { handle in appendOutput(handle.availableData) }
		process.terminationHandler = { [weak self] process in
			DispatchQueue.main.async {
				stdout.fileHandleForReading.readabilityHandler = nil
				stderr.fileHandleForReading.readabilityHandler = nil
				guard let self else {
					return
				}
				self.gitRemoteProcess = nil
				if process.terminationStatus == 0 {
					self.gitStatusLabel?.textColor = .secondaryLabelColor
					self.gitStatusLabel?.stringValue = "\(title) complete"
					self.refreshGitChanges(nil)
				} else {
					self.gitStatusLabel?.textColor = .systemRed
					self.gitStatusLabel?.stringValue = "\(title) failed"
					self.showGitRemoteFailure(title: title)
				}
			}
		}
		do {
			try process.run()
		} catch {
			gitRemoteProcess = nil
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func showGitRemoteFailure(title: String) {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = "\(title) failed"
		let scrollView = NSScrollView(frame: NSRect(x: 0, y: 0, width: 640, height: 260))
		let textView = NSTextView(frame: scrollView.bounds)
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = gitRemoteLog
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		alert.accessoryView = scrollView
		alert.addButton(withTitle: L10n.string("OK"))
		alert.runModal()
	}

	@objc private func refreshGitChanges(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setGitEntries([], root: nil, status: L10n.string("Open a folder first"), isError: true, branchLabel: nil)
			return
		}
		guard let gitRoot = try? GitRepository.discoverRoot(containing: root) else {
			setGitEntries([], root: nil, status: L10n.string("Not a Git repository"), isError: true, branchLabel: nil)
			ItsyWorkspaceController.refreshGitStatus()
			return
		}
		do {
			let snapshot = try GitRepository(root: gitRoot).snapshot()
			let status = "\(snapshot.branchLabel) - \(snapshot.status.stagedCount) staged, \(snapshot.status.unstagedCount) unstaged"
			setGitEntries(snapshot.status.entries, root: gitRoot, status: status, isError: false, branchLabel: snapshot.branchLabel)
			ItsyWorkspaceController.refreshGitStatus()
		} catch {
			setGitEntries([], root: gitRoot, status: String(describing: error), isError: true, branchLabel: nil)
		}
	}

	private func setGitEntries(_ entries: [GitStatusEntry], root: URL?, status: String, isError: Bool, branchLabel: String?) {
		syncGitDraftRoot(root)
		gitEntries = entries
		gitRootURL = root
		gitStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		gitStatusLabel?.stringValue = status
		gitBranchButton?.title = branchLabel ?? L10n.string("Branch")
		gitBranchButton?.isEnabled = root != nil
		gitTableView?.reloadData()
		if !entries.isEmpty {
			gitTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		updateSelectedGitDiff()
		updateGitComposerState()
	}

	@objc private func changeGitDiffMode(_ sender: Any?) {
		gitDiffMode = gitDiffModeControl?.selectedSegment == 1 ? .sideBySide : .unified
		renderGitDiff()
	}

	private func updateSelectedGitDiff() {
		guard let tableView = gitTableView,
		      let gitRootURL,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < gitEntries.count
		else {
			gitDiffPath = nil
			setGitDiffMessage(L10n.string("No file selected"))
			return
		}
		let entry = gitEntries[tableView.selectedRow]
		do {
			let files: [DiffFile]
			let label: String
			let isStagedDiff: Bool
			if entry.kind == .untracked {
				let contents = try String(contentsOf: gitRootURL.appendingPathComponent(entry.path), encoding: .utf8)
				files = [DiffTextRenderer.newFile(path: entry.path, contents: contents)]
				label = L10n.string("untracked")
				isStagedDiff = false
			} else {
				let staged = entry.isStaged && !entry.isUnstaged
				files = try GitRepository(root: gitRootURL).diffFiles(path: entry.path, staged: staged)
				label = staged ? L10n.string("staged") : L10n.string("unstaged")
				isStagedDiff = staged
			}
			gitDiffFiles = files
			gitDiffPath = entry.path
			setGitHunkItems(files: files, isStaged: isStagedDiff)
			gitDiffStatusLabel?.textColor = .secondaryLabelColor
			gitDiffStatusLabel?.stringValue = files.flatMap(\.hunks).isEmpty ? L10n.string("No text diff") : "\(entry.path) (\(label))"
			renderGitDiff()
		} catch {
			gitDiffFiles = []
			gitDiffPath = entry.path
			setGitDiffMessage(String(describing: error), isError: true)
		}
	}

	private func setGitDiffMessage(_ message: String, isError: Bool = false) {
		gitDiffFiles = []
		gitHunkItems = []
		gitUnifiedLineItems = []
		gitHunkTableView?.reloadData()
		gitDiffStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		gitDiffStatusLabel?.stringValue = message
		let document = RenderedDiffDocument(text: "\(message)\n", lines: [
			RenderedDiffLine(kind: .header, fullRange: 0 ..< message.utf8.count),
		])
		applyGitDiff(document, to: gitUnifiedDiffView, path: gitDiffPath)
		applyGitDiff(document, to: gitSideOldDiffView, path: gitDiffPath)
		applyGitDiff(document, to: gitSideNewDiffView, path: gitDiffPath)
	}

	private func setGitHunkItems(files: [DiffFile], isStaged: Bool) {
		gitHunkItems = files.enumerated().flatMap { fileIndex, file in
			file.hunks.enumerated().map { hunkIndex, hunk in
				let title = "\(file.newPath ?? file.oldPath ?? "file"):\(hunk.oldStart)->\(hunk.newStart)"
				return GitDiffHunkItem(fileIndex: fileIndex, hunkIndex: hunkIndex, title: title, isStaged: isStaged)
			}
		}
		gitHunkTableView?.reloadData()
	}

	@objc private func applyGitHunk(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitHunkItems.count else {
			return
		}
		let item = gitHunkItems[sender.tag]
		guard item.fileIndex < gitDiffFiles.count, item.hunkIndex < gitDiffFiles[item.fileIndex].hunks.count else {
			return
		}
		let file = gitDiffFiles[item.fileIndex]
		let hunk = file.hunks[item.hunkIndex]
		do {
			let repository = GitRepository(root: gitRootURL)
			if item.isStaged {
				try repository.unstage(hunk: hunk, in: file)
			} else {
				try repository.stage(hunk: hunk, in: file)
			}
			refreshGitChanges(nil)
		} catch {
			gitDiffStatusLabel?.textColor = .systemRed
			gitDiffStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func applyGitSelectedLines(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitHunkItems.count else {
			return
		}
		let item = gitHunkItems[sender.tag]
		guard item.fileIndex < gitDiffFiles.count, item.hunkIndex < gitDiffFiles[item.fileIndex].hunks.count else {
			return
		}
		let file = gitDiffFiles[item.fileIndex]
		let hunk = file.hunks[item.hunkIndex]
		do {
			let lineIndexes = try selectedGitLineIndexes(for: item)
			let repository = GitRepository(root: gitRootURL)
			if item.isStaged {
				try repository.unstage(lineIndexes: lineIndexes, in: hunk, file: file)
			} else {
				try repository.stage(lineIndexes: lineIndexes, in: hunk, file: file)
			}
			refreshGitChanges(nil)
		} catch {
			gitDiffStatusLabel?.textColor = .systemRed
			gitDiffStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func renderGitDiff() {
		let hasSideBySide = gitDiffMode == .sideBySide
		gitUnifiedDiffView?.isHidden = hasSideBySide
		gitSideBySideSplitView?.isHidden = !hasSideBySide
		guard !gitDiffFiles.isEmpty else {
			gitUnifiedLineItems = []
			return
		}
		switch gitDiffMode {
		case .unified:
			let document = DiffTextRenderer.unified(files: gitDiffFiles)
			gitUnifiedLineItems = unifiedGitDiffLineItems(files: gitDiffFiles, document: document)
			applyGitDiff(document, to: gitUnifiedDiffView, path: gitDiffPath)
		case .sideBySide:
			gitUnifiedLineItems = []
			let rendered = DiffTextRenderer.sideBySide(files: gitDiffFiles)
			applyGitDiff(rendered.old, to: gitSideOldDiffView, path: gitDiffFiles.first?.oldPath ?? gitDiffPath)
			applyGitDiff(rendered.new, to: gitSideNewDiffView, path: gitDiffFiles.first?.newPath ?? gitDiffPath)
		}
	}

	private func unifiedGitDiffLineItems(files: [DiffFile], document: RenderedDiffDocument) -> [GitDiffLineItem] {
		var items: [GitDiffLineItem] = []
		var renderedLine = 0
		for (fileIndex, file) in files.enumerated() {
			renderedLine += 1
			if file.isNewFile, file.newMode != nil {
				renderedLine += 1
			}
			if file.isDeletedFile, file.oldMode != nil {
				renderedLine += 1
			}
			if file.indexLine != nil {
				renderedLine += 1
			}
			renderedLine += 2
			for (hunkIndex, hunk) in file.hunks.enumerated() {
				renderedLine += 1
				for lineIndex in hunk.lines.indices {
					if renderedLine < document.lines.count {
						items.append(GitDiffLineItem(
							fileIndex: fileIndex,
							hunkIndex: hunkIndex,
							lineIndex: lineIndex,
							range: document.lines[renderedLine].fullRange
						))
					}
					renderedLine += 1
				}
			}
		}
		return items
	}

	private func selectedGitLineIndexes(for item: GitDiffHunkItem) throws -> IndexSet {
		guard gitDiffMode == .unified else {
			throw GitLineSelectionError.unifiedModeRequired
		}
		guard let selection = gitUnifiedDiffView?.editor.selections.primary else {
			throw GitLineSelectionError.noChangedLinesSelected
		}
		let selectedItems = gitUnifiedLineItems.filter { lineItem in
			guard lineItem.fileIndex == item.fileIndex, lineItem.hunkIndex == item.hunkIndex else {
				return false
			}
			if selection.isCaret {
				return lineItem.range.contains(selection.head) || lineItem.range.upperBound == selection.head
			}
			return lineItem.range.overlaps(selection.range)
		}
		let indexes = IndexSet(selectedItems.map(\.lineIndex))
		guard !indexes.isEmpty else {
			throw GitLineSelectionError.noChangedLinesSelected
		}
		return indexes
	}

	private func applyGitDiff(_ document: RenderedDiffDocument, to view: MetalTextView?, path: String?) {
		view?.editor = Editor(text: document.text)
		view?.highlightSpans = gitDiffHighlightSpans(for: document, path: path)
	}

	private func gitDiffHighlightSpans(for document: RenderedDiffDocument, path: String?) -> [TextHighlightSpan] {
		var spans = document.lines.compactMap { line -> TextHighlightSpan? in
			guard !line.fullRange.isEmpty, let color = gitDiffColor(for: line.kind) else {
				return nil
			}
			return TextHighlightSpan(range: line.fullRange, color: color)
		}
		spans += syntaxHighlightSpans(for: document, path: path)
		return spans
	}

	private func gitDiffColor(for kind: RenderedDiffLineKind) -> SIMD4<Float>? {
		switch kind {
		case .header:
			return SIMD4<Float>(0.56, 0.62, 0.70, 1)
		case .addition:
			return SIMD4<Float>(0.28, 0.78, 0.46, 1)
		case .removal:
			return SIMD4<Float>(0.93, 0.37, 0.37, 1)
		case .context, .blank:
			return nil
		}
	}

	private func syntaxHighlightSpans(for document: RenderedDiffDocument, path: String?) -> [TextHighlightSpan] {
		guard let path,
		      let gitRootURL,
		      let language = SyntaxPipeline.language(forFileURL: gitRootURL.appendingPathComponent(path)),
		      let theme = try? SyntaxTheme.loadUserOrDefault()
		else {
			return []
		}
		var source = ""
		var mappings: [(source: Range<Int>, rendered: Range<Int>)] = []
		for line in document.lines {
			guard let content = line.content, let contentRange = line.contentRange else {
				continue
			}
			let start = source.utf8.count
			source += content
			let end = source.utf8.count
			mappings.append((start ..< end, contentRange))
			source += "\n"
		}
		guard !source.isEmpty else {
			return []
		}
		do {
			var pipeline = SyntaxPipeline(language: language)
			let tree = try pipeline.parse(Rope(source))
			return try pipeline.highlights(in: tree).flatMap { span -> [TextHighlightSpan] in
				mappings.compactMap { mapping -> TextHighlightSpan? in
					let lower = max(span.range.lowerBound, mapping.source.lowerBound)
					let upper = min(span.range.upperBound, mapping.source.upperBound)
					guard lower < upper, let color = theme.color(for: span.capture) else {
						return nil
					}
					let renderedLower = mapping.rendered.lowerBound + lower - mapping.source.lowerBound
					let renderedUpper = mapping.rendered.lowerBound + upper - mapping.source.lowerBound
					return TextHighlightSpan(range: renderedLower ..< renderedUpper, color: SIMD4<Float>(color.red, color.green, color.blue, color.alpha))
				}
			}
		} catch {
			return []
		}
	}

	@objc private func updateGitComposerStateAction(_ sender: Any?) {
		updateGitComposerState()
	}

	private func syncGitDraftRoot(_ root: URL?) {
		if gitDraftRootURL?.standardizedFileURL.path == root?.standardizedFileURL.path {
			return
		}
		persistGitCommitDraft()
		gitDraftRootURL = root
		gitRecentCommitMessages = []
		gitRecentCommitIndex = nil
		gitDraftBeforeHistory = nil
		setGitComposerDraft(root.map { GitCommitDraftStore.load(for: $0) } ?? GitCommitDraft(summary: "", body: ""), persist: false)
	}

	private func currentGitCommitDraft() -> GitCommitDraft {
		GitCommitDraft(summary: gitSummaryField?.stringValue ?? "", body: gitBodyTextView?.string ?? "")
	}

	private func setGitComposerDraft(_ draft: GitCommitDraft, persist: Bool) {
		gitSummaryField?.stringValue = draft.summary
		gitBodyTextView?.string = draft.body
		if persist {
			persistGitCommitDraft()
		}
		updateGitComposerState()
	}

	private func persistGitCommitDraft() {
		guard let root = gitDraftRootURL else {
			return
		}
		GitCommitDraftStore.save(currentGitCommitDraft(), for: root)
	}

	private func clearGitCommitHistorySelection() {
		gitRecentCommitIndex = nil
		gitDraftBeforeHistory = nil
	}

	private func updateGitComposerState() {
		let stagedCount = gitEntries.filter(\.isStaged).count
		let summary = gitSummaryField?.stringValue.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		gitComposerStatusLabel?.stringValue = L10n.string("\(stagedCount) staged files")
		gitCommitButton?.isEnabled = gitRootURL != nil && stagedCount > 0 && !summary.isEmpty
	}

	@objc private func commitGitChanges(_ sender: Any?) {
		guard let gitRootURL else {
			updateGitComposerState()
			return
		}
		let summary = gitSummaryField?.stringValue ?? ""
		let body = gitBodyTextView?.string ?? ""
		guard gitEntries.contains(where: \.isStaged), !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			updateGitComposerState()
			return
		}
		do {
			try GitRepository(root: gitRootURL).commit(
				summary: summary,
				body: body,
				signoff: gitSignoffButton?.state == .on,
				amend: gitAmendButton?.state == .on
			)
			setGitComposerDraft(GitCommitDraft(summary: "", body: ""), persist: true)
			refreshGitChanges(nil)
		} catch {
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true, branchLabel: gitBranchButton?.title)
		}
	}

	private func showPreviousGitCommitMessage() -> Bool {
		guard let gitRootURL, gitSummaryField?.stringValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true || gitRecentCommitIndex != nil else {
			return false
		}
		if gitRecentCommitIndex == nil {
			gitDraftBeforeHistory = currentGitCommitDraft()
			do {
				gitRecentCommitMessages = try GitRepository(root: gitRootURL).recentCommitMessages().map(commitDraft(from:))
			} catch {
				gitComposerStatusLabel?.stringValue = String(describing: error)
				return true
			}
		}
		guard !gitRecentCommitMessages.isEmpty else {
			gitComposerStatusLabel?.stringValue = L10n.string("No recent commits")
			return true
		}
		let nextIndex = ((gitRecentCommitIndex ?? -1) + 1) % gitRecentCommitMessages.count
		gitRecentCommitIndex = nextIndex
		setGitComposerDraft(gitRecentCommitMessages[nextIndex], persist: false)
		return true
	}

	private func restoreGitCommitDraftFromHistory() -> Bool {
		guard gitRecentCommitIndex != nil else {
			return false
		}
		let draft = gitDraftBeforeHistory ?? GitCommitDraft(summary: "", body: "")
		clearGitCommitHistorySelection()
		setGitComposerDraft(draft, persist: true)
		return true
	}

	private func commitDraft(from message: String) -> GitCommitDraft {
		var lines = message.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
		let summary = lines.isEmpty ? "" : lines.removeFirst()
		let body = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
		return GitCommitDraft(summary: summary, body: body)
	}

	@objc private func stageSelectedGitEntries(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let paths = selectedGitPaths()
		guard !paths.isEmpty else {
			return
		}
		do {
			try GitRepository(root: gitRootURL).stage(paths: paths)
			refreshGitChanges(nil)
		} catch {
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true, branchLabel: gitBranchButton?.title)
		}
	}

	@objc private func unstageSelectedGitEntries(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let paths = selectedGitPaths()
		guard !paths.isEmpty else {
			return
		}
		do {
			try GitRepository(root: gitRootURL).unstage(paths: paths)
			refreshGitChanges(nil)
		} catch {
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true, branchLabel: gitBranchButton?.title)
		}
	}

	private func selectedGitPaths() -> [String] {
		guard let tableView = gitTableView else {
			return []
		}
		return tableView.selectedRowIndexes.compactMap { row in
			guard row >= 0, row < gitEntries.count else {
				return nil
			}
			return gitEntries[row].path
		}
	}

	@objc private func openSelectedGitEntry(_ sender: Any?) {
		guard let tableView = gitTableView,
		      let gitRootURL,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < gitEntries.count
		else {
			return
		}
		let entry = gitEntries[tableView.selectedRow]
		if entry.isConflict {
			showGitConflict(entry: entry, root: gitRootURL)
			return
		}
		_ = documentController.openDocument(at: gitRootURL.appendingPathComponent(entry.path))
	}

	private func showGitConflict(entry: GitStatusEntry, root: URL) {
		let repository = GitRepository(root: root)
		let base = (try? repository.conflictBlob(path: entry.path, stage: 1)) ?? ""
		let ours = (try? repository.conflictBlob(path: entry.path, stage: 2)) ?? ""
		let theirs = (try? repository.conflictBlob(path: entry.path, stage: 3)) ?? ""
		let mergedURL = root.appendingPathComponent(entry.path)
		let merged = (try? String(contentsOf: mergedURL, encoding: .utf8)) ?? ""
		gitConflictPanel?.close()
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 980, height: 760),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Resolve Conflict")
		panel.isFloatingPanel = false
		let contentView = NSView()
		let titleLabel = NSTextField(labelWithString: entry.path)
		titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
		let sourceSplit = NSSplitView()
		sourceSplit.isVertical = true
		sourceSplit.dividerStyle = .thin
		sourceSplit.addArrangedSubview(makeGitConflictPane(title: L10n.string("Ours (:2)"), text: ours, isEditable: false).view)
		sourceSplit.addArrangedSubview(makeGitConflictPane(title: L10n.string("Base (:1)"), text: base, isEditable: false).view)
		sourceSplit.addArrangedSubview(makeGitConflictPane(title: L10n.string("Theirs (:3)"), text: theirs, isEditable: false).view)
		let mergedPane = makeGitConflictPane(title: L10n.string("Merged result"), text: merged, isEditable: true)
		let regionStack = NSStackView()
		regionStack.orientation = .vertical
		regionStack.alignment = .leading
		regionStack.spacing = 6
		let regionScrollView = NSScrollView()
		regionScrollView.documentView = regionStack
		regionScrollView.hasVerticalScroller = true
		regionScrollView.drawsBackground = false
		let saveButton = NSButton(title: L10n.string("Save and Add"), target: self, action: #selector(saveGitConflict(_:)))
		let closeButton = NSButton(title: L10n.string("Close"), target: self, action: #selector(closeGitConflict(_:)))
		let footer = NSStackView(views: [saveButton, closeButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		let stack = NSStackView(views: [titleLabel, sourceSplit, mergedPane.view, regionScrollView, footer])
		stack.orientation = .vertical
		stack.alignment = .leading
		stack.spacing = 10
		stack.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(stack)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
			sourceSplit.widthAnchor.constraint(equalTo: stack.widthAnchor),
			sourceSplit.heightAnchor.constraint(equalToConstant: 180),
			mergedPane.view.widthAnchor.constraint(equalTo: stack.widthAnchor),
			mergedPane.view.heightAnchor.constraint(equalToConstant: 300),
			regionScrollView.widthAnchor.constraint(equalTo: stack.widthAnchor),
			regionScrollView.heightAnchor.constraint(equalToConstant: 140),
		])
		panel.contentView = contentView
		gitConflictPanel = panel
		gitConflictRootURL = root
		gitConflictPath = entry.path
		gitConflictMergedTextView = mergedPane.textView
		gitConflictRegionStack = regionStack
		refreshGitConflictRegions()
		panel.center()
		panel.makeKeyAndOrderFront(nil)
	}

	private func makeGitConflictPane(title: String, text: String, isEditable: Bool) -> (view: NSView, textView: NSTextView) {
		let container = NSView()
		let label = NSTextField(labelWithString: title)
		label.font = .systemFont(ofSize: 11, weight: .semibold)
		let scrollView = NSScrollView()
		let textView = NSTextView()
		textView.isEditable = isEditable
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = text
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.hasHorizontalScroller = true
		label.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(label)
		container.addSubview(scrollView)
		NSLayoutConstraint.activate([
			label.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			label.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			label.topAnchor.constraint(equalTo: container.topAnchor),
			scrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 4),
			scrollView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
		])
		return (container, textView)
	}

	private func refreshGitConflictRegions() {
		guard let stack = gitConflictRegionStack, let textView = gitConflictMergedTextView else {
			return
		}
		for view in stack.arrangedSubviews {
			stack.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		let regions = GitConflictParser.parse(textView.string)
		if regions.isEmpty {
			let label = NSTextField(labelWithString: L10n.string("No conflict markers remain"))
			label.textColor = .secondaryLabelColor
			stack.addArrangedSubview(label)
			return
		}
		for (index, region) in regions.enumerated() {
			let label = NSTextField(labelWithString: L10n.string("Region \(index + 1), lines \(region.startLine + 1)-\(region.endLine)"))
			label.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
			let ours = NSButton(title: L10n.string("Accept Ours"), target: self, action: #selector(acceptGitConflictOurs(_:)))
			let theirs = NSButton(title: L10n.string("Accept Theirs"), target: self, action: #selector(acceptGitConflictTheirs(_:)))
			let both = NSButton(title: L10n.string("Accept Both"), target: self, action: #selector(acceptGitConflictBoth(_:)))
			let edit = NSButton(title: L10n.string("Edit Manually"), target: self, action: #selector(editGitConflictManually(_:)))
			for button in [ours, theirs, both, edit] {
				button.bezelStyle = .rounded
				button.font = .systemFont(ofSize: 11)
				button.tag = index
			}
			let row = NSStackView(views: [label, ours, theirs, both, edit])
			row.orientation = .horizontal
			row.alignment = .centerY
			row.spacing = 8
			stack.addArrangedSubview(row)
		}
	}

	private func applyGitConflictResolution(_ resolution: GitConflictResolution, sender: NSButton) {
		guard let textView = gitConflictMergedTextView else {
			return
		}
		textView.string = GitConflictParser.resolvedText(textView.string, regionIndex: sender.tag, resolution: resolution)
		refreshGitConflictRegions()
	}

	@objc private func acceptGitConflictOurs(_ sender: NSButton) {
		applyGitConflictResolution(.ours, sender: sender)
	}

	@objc private func acceptGitConflictTheirs(_ sender: NSButton) {
		applyGitConflictResolution(.theirs, sender: sender)
	}

	@objc private func acceptGitConflictBoth(_ sender: NSButton) {
		applyGitConflictResolution(.both, sender: sender)
	}

	@objc private func editGitConflictManually(_ sender: NSButton) {
		guard let textView = gitConflictMergedTextView else {
			return
		}
		let regions = GitConflictParser.parse(textView.string)
		guard sender.tag >= 0, sender.tag < regions.count else {
			return
		}
		textView.setSelectedRange(nsRangeForLines(regions[sender.tag].startLine ..< regions[sender.tag].endLine, in: textView.string))
		gitConflictPanel?.makeFirstResponder(textView)
	}

	@objc private func saveGitConflict(_ sender: Any?) {
		guard let root = gitConflictRootURL, let path = gitConflictPath, let textView = gitConflictMergedTextView else {
			return
		}
		do {
			try textView.string.write(to: root.appendingPathComponent(path), atomically: true, encoding: .utf8)
			try GitRepository(root: root).stage(paths: [path])
			gitConflictPanel?.close()
			refreshGitChanges(nil)
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func closeGitConflict(_ sender: Any?) {
		gitConflictPanel?.close()
	}

	private func nsRangeForLines(_ lineRange: Range<Int>, in text: String) -> NSRange {
		let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
		var offset = 0
		var start = 0
		var end = 0
		for index in 0 ... lines.count {
			if index == lineRange.lowerBound {
				start = offset
			}
			if index == lineRange.upperBound {
				end = offset
				break
			}
			guard index < lines.count else {
				break
			}
			offset += lines[index].utf16.count
			if index < lines.count - 1 {
				offset += 1
			}
		}
		return NSRange(location: start, length: max(0, end - start))
	}

	private func gitEntryTitle(_ entry: GitStatusEntry) -> String {
		let original = entry.originalPath.map { " <- \($0)" } ?? ""
		return "\(gitEntryStatus(entry))  \(entry.path)\(original)"
	}

	private func gitEntryStatus(_ entry: GitStatusEntry) -> String {
		if entry.kind == .untracked {
			return "??"
		}
		let index = entry.indexStatus.map(String.init) ?? "."
		let worktree = entry.worktreeStatus.map(String.init) ?? "."
		return index + worktree
	}

	private func branchTitle(_ branch: GitBranch) -> String {
		let marker = branch.isCurrent ? "* " : ""
		let kind = branch.kind == .remote ? "remote" : "local"
		return "\(marker)\(branch.name)  \(kind)"
	}

	private func branchDetail(_ branch: GitBranch) -> String {
		let upstream = branch.upstream.map { "upstream \($0)" } ?? "no upstream"
		return "\(upstream) - \(branch.committerDateRelative)"
	}

	@objc private func showTasks(_ sender: Any?) {
		toggleTasks(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleTasks(relativeTo hostWindow: NSWindow?) {
		if taskPanel?.isVisible == true {
			closeTasks()
			return
		}
		showTasks(relativeTo: hostWindow)
	}

	private func closeTasks() {
		taskPanel?.close()
	}

	private func showTasks(relativeTo hostWindow: NSWindow?) {
		let panel = makeTaskPanelIfNeeded()
		centerTaskPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshTasks(nil)
	}

	private func makeTaskPanelIfNeeded() -> NSPanel {
		if let panel = taskPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 720, height: 520),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Tasks")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureTaskView(contentView)
		taskPanel = panel
		return panel
	}

	private func configureTaskView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshTasks(_:)))
		let runButton = NSButton(title: L10n.string("Run"), target: self, action: #selector(runSelectedTask(_:)))
		let buttonStack = NSStackView(views: [refreshButton, runButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("task"))
		column.title = L10n.string("Tasks")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(runSelectedTask(_:))
		let taskScrollView = NSScrollView()
		taskScrollView.documentView = tableView
		taskScrollView.hasVerticalScroller = true
		taskScrollView.drawsBackground = false
		let outputTextView = NSTextView()
		outputTextView.isEditable = false
		outputTextView.isSelectable = true
		outputTextView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		outputTextView.string = ""
		let outputScrollView = NSScrollView()
		outputScrollView.documentView = outputTextView
		outputScrollView.hasVerticalScroller = true
		outputScrollView.drawsBackground = false
		header.translatesAutoresizingMaskIntoConstraints = false
		taskScrollView.translatesAutoresizingMaskIntoConstraints = false
		outputScrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(taskScrollView)
		contentView.addSubview(outputScrollView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			taskScrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			taskScrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			taskScrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			taskScrollView.heightAnchor.constraint(equalToConstant: 180),
			outputScrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			outputScrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			outputScrollView.topAnchor.constraint(equalTo: taskScrollView.bottomAnchor, constant: 1),
			outputScrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		taskStatusLabel = statusLabel
		taskTableView = tableView
		taskOutputTextView = outputTextView
	}

	private func centerTaskPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(780, max(560, hostFrame.width - 100))
		let height = min(560, max(360, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	@objc private func refreshTasks(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setTasks([], status: L10n.string("Open a folder first"), output: "", isError: true)
			return
		}
		let tasks = WorkspaceTaskDiscovery.discover(root: root)
		setTasks(tasks, status: L10n.string("\(tasks.count) tasks"), output: taskOutputTextView?.string ?? "", isError: false)
	}

	private func setTasks(_ tasks: [WorkspaceTask], status: String, output: String, isError: Bool) {
		workspaceTasks = tasks
		taskStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		taskStatusLabel?.stringValue = status
		taskOutputTextView?.string = output
		taskTableView?.reloadData()
		if !tasks.isEmpty {
			taskTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	@objc private func runSelectedTask(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL,
		      let task = selectedTask()
		else {
			return
		}
		taskRunGeneration += 1
		let generation = taskRunGeneration
		taskStatusLabel?.textColor = .secondaryLabelColor
		taskStatusLabel?.stringValue = L10n.string("Running \(task.label)")
		taskOutputTextView?.string = "$ \(task.commandLine)\n"
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			let result = Result { try WorkspaceTaskRunner().run(task, root: root) }
			DispatchQueue.main.async {
				guard let self, self.taskRunGeneration == generation else {
					return
				}
				self.applyTaskResult(result)
			}
		}
	}

	private func selectedTask() -> WorkspaceTask? {
		guard let tableView = taskTableView,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < workspaceTasks.count
		else {
			return nil
		}
		return workspaceTasks[tableView.selectedRow]
	}

	private func applyTaskResult(_ result: Result<WorkspaceTaskResult, Error>) {
		switch result {
		case let .success(taskResult):
			taskStatusLabel?.textColor = taskResult.succeeded ? .secondaryLabelColor : .systemRed
			taskStatusLabel?.stringValue = L10n.string("\(taskResult.task.label) exited \(taskResult.exitStatus)")
			taskOutputTextView?.string = [
				"$ \(taskResult.task.commandLine)",
				taskResult.stdout,
				taskResult.stderr,
			].filter { !$0.isEmpty }.joined(separator: "\n")
			if let root = ItsyWorkspaceController.currentRootURL {
				setProblems(WorkspaceProblemParser.parse(taskResult.stdout + "\n" + taskResult.stderr, root: root))
			}
		case let .failure(error):
			taskStatusLabel?.textColor = .systemRed
			taskStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func taskTitle(_ task: WorkspaceTask) -> String {
		"\(task.label)  [\(task.source.rawValue)]"
	}

	@objc private func showProblems(_ sender: Any?) {
		toggleProblems(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleProblems(relativeTo hostWindow: NSWindow?) {
		if problemsPanel?.isVisible == true {
			closeProblems()
			return
		}
		showProblems(relativeTo: hostWindow)
	}

	private func closeProblems() {
		problemsPanel?.close()
	}

	private func showProblems(relativeTo hostWindow: NSWindow?) {
		let panel = makeProblemsPanelIfNeeded()
		centerProblemsPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshProblemsStatus()
	}

	private func makeProblemsPanelIfNeeded() -> NSPanel {
		if let panel = problemsPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 720, height: 420),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Problems")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureProblemsView(contentView)
		problemsPanel = panel
		return panel
	}

	private func configureProblemsView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("problem"))
		column.title = L10n.string("Problems")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedProblem(_:))
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			statusLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			statusLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			statusLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		problemsStatusLabel = statusLabel
		problemsTableView = tableView
	}

	private func centerProblemsPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(760, max(560, hostFrame.width - 100))
		let height = min(460, max(300, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	@objc private func showOutline(_ sender: Any?) {
		toggleOutline(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleOutline(relativeTo hostWindow: NSWindow?) {
		if outlinePanel?.isVisible == true {
			closeOutline()
			return
		}
		showOutline(relativeTo: hostWindow)
	}

	private func closeOutline() {
		outlinePanel?.close()
	}

	private func showOutline(relativeTo hostWindow: NSWindow?) {
		let panel = makeOutlinePanelIfNeeded()
		centerOutlinePanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		installOutlineWindowObserverIfNeeded()
		refreshOutline()
	}

	private func makeOutlinePanelIfNeeded() -> NSPanel {
		if let panel = outlinePanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 320, height: 460),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Outline")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureOutlineView(contentView)
		outlinePanel = panel
		return panel
	}

	private func configureOutlineView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshOutlineAction(_:)))
		let header = NSStackView(views: [statusLabel, refreshButton])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 8
		let outlineView = NSOutlineView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("outline"))
		column.title = L10n.string("Symbols")
		column.resizingMask = .autoresizingMask
		outlineView.addTableColumn(column)
		outlineView.outlineTableColumn = column
		outlineView.headerView = nil
		outlineView.rowSizeStyle = .small
		outlineView.indentationPerLevel = 14
		outlineView.dataSource = self
		outlineView.delegate = self
		outlineView.target = self
		outlineView.doubleAction = #selector(openSelectedOutlineSymbol(_:))
		let scrollView = NSScrollView()
		scrollView.documentView = outlineView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		header.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		outlineStatusLabel = statusLabel
		outlineOutlineView = outlineView
	}

	private func centerOutlinePanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = CGFloat(360)
		let height = min(560, max(360, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.maxX - width - 24, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func installOutlineWindowObserverIfNeeded() {
		guard outlineWindowObserver == nil else {
			return
		}
		outlineWindowObserver = NotificationCenter.default.addObserver(forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main) { [weak self] notification in
			guard
				let self,
				self.outlinePanel?.isVisible == true,
				let window = notification.object as? NSWindow,
				window !== self.outlinePanel
			else {
				return
			}
			self.refreshOutline()
		}
	}

	@objc private func refreshOutlineAction(_ sender: Any?) {
		refreshOutline()
	}

	private func refreshOutline() {
		guard
			let url = (activeDocument() as? ItsyDocument)?.fileURL,
			let index = ItsyWorkspaceController.currentWorkspaceIndex,
			let relative = index.relativePath(for: url)
		else {
			outlineKindNodes = []
			outlineActiveURLKey = nil
			outlineStatusLabel?.stringValue = L10n.string("No active file")
			outlineOutlineView?.reloadData()
			return
		}
		let symbols = index.symbolsForFile(relativePath: relative)
		outlineActiveURLKey = url.absoluteString
		if symbols.isEmpty {
			outlineKindNodes = []
			outlineStatusLabel?.stringValue = "\(relative) — \(L10n.string("No symbols in this file"))"
			outlineOutlineView?.reloadData()
			return
		}
		var grouped: [WorkspaceSymbolKind: [OutlineSymbolNode]] = [:]
		for symbol in symbols {
			grouped[symbol.kind, default: []].append(OutlineSymbolNode(symbol))
		}
		let order: [WorkspaceSymbolKind] = [.type, .function, .method, .variable]
		outlineKindNodes = order.compactMap { kind in
			guard let nodes = grouped[kind], !nodes.isEmpty else {
				return nil
			}
			return OutlineKindNode(kind: kind, symbols: nodes)
		}
		outlineStatusLabel?.stringValue = "\(relative) — \(symbols.count) \(L10n.string("symbols"))"
		outlineOutlineView?.reloadData()
		applyOutlineCollapseState()
	}

	private func applyOutlineCollapseState() {
		let collapsedKinds = outlineActiveURLKey.flatMap { outlineCollapseStateByURL[$0] } ?? []
		outlineSuppressPersist = true
		defer {
			outlineSuppressPersist = false
		}
		for kindNode in outlineKindNodes {
			if collapsedKinds.contains(kindNode.kind.rawValue) {
				outlineOutlineView?.collapseItem(kindNode)
			} else {
				outlineOutlineView?.expandItem(kindNode)
			}
		}
	}

	fileprivate func recordOutlineCollapseChange() {
		guard !outlineSuppressPersist, let key = outlineActiveURLKey else {
			return
		}
		var collapsed: Set<String> = []
		for kindNode in outlineKindNodes {
			if outlineOutlineView?.isItemExpanded(kindNode) == false {
				collapsed.insert(kindNode.kind.rawValue)
			}
		}
		if collapsed.isEmpty {
			outlineCollapseStateByURL.removeValue(forKey: key)
		} else {
			outlineCollapseStateByURL[key] = collapsed
		}
		OutlineCollapseStore.save(outlineCollapseStateByURL)
	}

	@objc private func openSelectedOutlineSymbol(_ sender: Any?) {
		guard
			let outlineView = outlineOutlineView,
			outlineView.clickedRow >= 0,
			let node = outlineView.item(atRow: outlineView.clickedRow) as? OutlineSymbolNode,
			let root = ItsyWorkspaceController.currentRootURL
		else {
			return
		}
		let url = root.appendingPathComponent(node.symbol.relativePath)
		documentController.openDocument(at: url, line: node.symbol.line, column: node.symbol.column)
	}

	private func setProblems(_ snapshot: WorkspaceProblemSnapshot) {
		workspaceProblems = snapshot.problems
		problemsRootURL = snapshot.root
		problemsTableView?.reloadData()
		refreshProblemsStatus()
		if !workspaceProblems.isEmpty {
			problemsTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		ItsyProblemGutterCoordinator.setProblems(
			root: problemsRootURL,
			problems: workspaceProblems,
			selectProblem: { [weak self] index in
				self?.focusProblem(index: index)
			},
			openRelated: { [weak self] related in
				self?.openRelatedProblemLocation(related)
			}
		)
	}

	private func refreshProblemsStatus() {
		let errors = workspaceProblems.filter { $0.severity == .error }.count
		let warnings = workspaceProblems.filter { $0.severity == .warning }.count
		problemsStatusLabel?.stringValue = L10n.string("\(errors) errors, \(warnings) warnings, \(workspaceProblems.count) total")
	}

	@objc private func openSelectedProblem(_ sender: Any?) {
		guard let tableView = problemsTableView,
		      let problemsRootURL,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < workspaceProblems.count
		else {
			return
		}
		let problem = workspaceProblems[tableView.selectedRow]
		let url = problemsRootURL.appendingPathComponent(problem.path)
		_ = documentController.openDocument(at: url, line: problem.line, column: problem.column ?? 1)
		if let document = documentController.document(for: url) as? ItsyDocument {
			ItsyProblemGutterCoordinator.apply(to: document)
		}
	}

	private func focusProblem(index: Int) {
		guard index >= 0, index < workspaceProblems.count else {
			return
		}
		showProblems(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		problemsTableView?.selectRowIndexes(IndexSet(integer: index), byExtendingSelection: false)
		problemsTableView?.scrollRowToVisible(index)
		problemsPanel?.makeKeyAndOrderFront(nil)
	}

	private func openRelatedProblemLocation(_ related: WorkspaceProblemRelatedInformation) {
		guard let problemsRootURL else {
			return
		}
		_ = documentController.openDocument(at: problemsRootURL.appendingPathComponent(related.path), line: related.line, column: related.column ?? 1)
		if let document = documentController.document(for: problemsRootURL.appendingPathComponent(related.path)) as? ItsyDocument {
			ItsyProblemGutterCoordinator.apply(to: document)
		}
	}

	private func problemTitle(_ problem: WorkspaceProblem) -> String {
		let column = problem.column.map { ":\($0)" } ?? ""
		return "\(problem.severity.rawValue)  \(problem.path):\(problem.line)\(column)  \(problem.message)"
	}

	@objc private func showSettings(_ sender: Any?) {
		let controller = makeSettingsWindowControllerIfNeeded()
		refreshSettingsThemes()
		controller.showWindow(sender)
		controller.window?.center()
		controller.window?.makeKeyAndOrderFront(sender)
		NSApp.activate(ignoringOtherApps: true)
	}

	private func makeSettingsWindowControllerIfNeeded() -> NSWindowController {
		if let controller = settingsWindowController {
			return controller
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 420, height: 148))
		let window = NSWindow(
			contentRect: contentView.frame,
			styleMask: [.titled, .closable],
			backing: .buffered,
			defer: false
		)
		window.title = L10n.string("Settings")
		window.contentView = contentView
		let controller = NSWindowController(window: window)
		configureSettings(contentView)
		settingsWindowController = controller
		return controller
	}

	private func configureSettings(_ contentView: NSView) {
		let label = NSTextField(labelWithString: L10n.string("Theme"))
		label.font = .systemFont(ofSize: 13, weight: .semibold)
		label.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(label)

		let themePopup = NSPopUpButton(frame: .zero, pullsDown: false)
		themePopup.target = self
		themePopup.action = #selector(settingsThemeDidChange(_:))
		themePopup.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(themePopup)

		let reloadButton = NSButton(title: L10n.string("Reload Themes"), target: self, action: #selector(reloadSettingsThemes(_:)))
		reloadButton.bezelStyle = .rounded
		reloadButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(reloadButton)

		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)

		NSLayoutConstraint.activate([
			label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
			label.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 24),
			label.widthAnchor.constraint(equalToConstant: 80),
			themePopup.leadingAnchor.constraint(equalTo: label.trailingAnchor, constant: 12),
			themePopup.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
			themePopup.centerYAnchor.constraint(equalTo: label.centerYAnchor),
			reloadButton.trailingAnchor.constraint(equalTo: themePopup.trailingAnchor),
			reloadButton.topAnchor.constraint(equalTo: themePopup.bottomAnchor, constant: 18),
			statusLabel.leadingAnchor.constraint(equalTo: label.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: reloadButton.leadingAnchor, constant: -12),
			statusLabel.centerYAnchor.constraint(equalTo: reloadButton.centerYAnchor),
		])
		settingsThemePopup = themePopup
		settingsStatusLabel = statusLabel
	}

	private func refreshSettingsThemes() {
		guard let themePopup = settingsThemePopup else {
			return
		}
		let choices = SyntaxTheme.availableChoices()
		let selectedID = UserDefaults.standard.string(forKey: SyntaxTheme.selectedThemeDefaultsKey) ?? SyntaxTheme.defaultChoiceID
		themePopup.removeAllItems()
		for choice in choices {
			themePopup.addItem(withTitle: choice.displayName)
			themePopup.lastItem?.representedObject = choice.id
		}
		if let item = themePopup.itemArray.first(where: { $0.representedObject as? String == selectedID }) {
			themePopup.select(item)
		} else if let item = themePopup.itemArray.first {
			themePopup.select(item)
		}
		setDefaultSettingsStatus()
	}

	private func setDefaultSettingsStatus() {
		settingsStatusLabel?.textColor = .secondaryLabelColor
		settingsStatusLabel?.stringValue = L10n.string("Custom themes: ~/.config/itsy/themes/*.toml")
	}

	@objc private func reloadSettingsThemes(_ sender: Any?) {
		refreshSettingsThemes()
	}

	@objc private func settingsThemeDidChange(_ sender: Any?) {
		guard let id = settingsThemePopup?.selectedItem?.representedObject as? String else {
			return
		}
		do {
			_ = try SyntaxTheme.loadChoice(id: id)
			UserDefaults.standard.set(id, forKey: SyntaxTheme.selectedThemeDefaultsKey)
			setDefaultSettingsStatus()
			reloadSyntaxThemes()
		} catch {
			settingsStatusLabel?.textColor = .systemRed
			settingsStatusLabel?.stringValue = L10n.string("Failed to load selected theme")
		}
	}

	private func reloadSyntaxThemes() {
		for document in documentController.documents {
			(document as? ItsyDocument)?.reloadSyntaxTheme()
		}
	}

	private func openInitialDocument() {
		let files = CommandLine.arguments.dropFirst().filter { !$0.hasPrefix("--") }
		if let path = files.first {
			_ = openPath(URL(fileURLWithPath: path))
			return
		}
		do {
			_ = try documentController.openUntitledDocumentAndDisplay(true)
		} catch {
			NSLog("failed to open untitled document: \(error)")
		}
	}

	@objc private func openFolder(_ sender: Any?) {
		let panel = NSOpenPanel()
		panel.canChooseDirectories = true
		panel.canChooseFiles = false
		panel.allowsMultipleSelection = false
		guard panel.runModal() == .OK, let url = panel.url else {
			return
		}
		_ = openWorkspace(at: url)
	}

	private func openPath(_ url: URL) -> Bool {
		var isDirectory: ObjCBool = false
		FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
		if isDirectory.boolValue {
			return openWorkspace(at: url)
		}
		return documentController.openDocument(at: url)
	}

	@objc private func openRecentDocument(_ sender: NSMenuItem) {
		guard let url = sender.representedObject as? URL else {
			return
		}
		_ = documentController.openDocument(at: url)
	}

	private func openWorkspace(at url: URL) -> Bool {
		ItsyWorkspaceController.openWorkspace(at: url)
		if documentController.documents.isEmpty {
			do {
				_ = try documentController.openUntitledDocumentAndDisplay(true)
			} catch {
				NSLog("failed to open untitled document for workspace \(url.path): \(error)")
				return false
			}
		}
		return true
	}

	private func installServicesProvider() {
		NSApp.servicesProvider = self
		NSRegisterServicesProvider(self, "Itsy")
		NSUpdateDynamicServices()
	}

	private func installMainMenu() {
		let mainMenu = NSMenu()
		let appItem = NSMenuItem()
		let fileItem = NSMenuItem()
		let editItem = NSMenuItem()
		let navigateItem = NSMenuItem()
		let viewItem = NSMenuItem()
		let gitItem = NSMenuItem()
		let taskItem = NSMenuItem()
		let problemItem = NSMenuItem()
		let commandItem = NSMenuItem()
		mainMenu.addItem(appItem)
		mainMenu.addItem(fileItem)
		mainMenu.addItem(editItem)
		mainMenu.addItem(navigateItem)
		mainMenu.addItem(viewItem)
		mainMenu.addItem(gitItem)
		mainMenu.addItem(taskItem)
		mainMenu.addItem(problemItem)
		mainMenu.addItem(commandItem)

		let appMenu = NSMenu()
		let settingsItem = appMenu.addItem(withTitle: L10n.string("Settings..."), action: #selector(showSettings(_:)), keyEquivalent: ",")
		settingsItem.target = self
		appMenu.addItem(.separator())
		appMenu.addItem(withTitle: L10n.string("Quit Itsy"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
		appItem.submenu = appMenu

		let fileMenu = NSMenu(title: L10n.string("File"))
		let newItem = fileMenu.addItem(withTitle: L10n.string("New"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "n")
		newItem.target = documentController
		let newTabItem = fileMenu.addItem(withTitle: L10n.string("New Tab"), action: #selector(NSDocumentController.newDocument(_:)), keyEquivalent: "t")
		newTabItem.keyEquivalentModifierMask = [.command, .option]
		newTabItem.target = documentController
		let openItem = fileMenu.addItem(withTitle: L10n.string("Open..."), action: #selector(NSDocumentController.openDocument(_:)), keyEquivalent: "o")
		openItem.target = documentController
		let openFolderItem = fileMenu.addItem(withTitle: L10n.string("Open Folder..."), action: #selector(openFolder(_:)), keyEquivalent: "o")
		openFolderItem.keyEquivalentModifierMask = [.command, .option]
		openFolderItem.target = self
		let openRecentItem = fileMenu.addItem(withTitle: L10n.string("Open Recent"), action: nil, keyEquivalent: "")
		let openRecentMenu = NSMenu(title: L10n.string("Open Recent"))
		openRecentMenu.delegate = self
		self.openRecentMenu = openRecentMenu
		fileMenu.setSubmenu(openRecentMenu, for: openRecentItem)
		let closeItem = fileMenu.addItem(withTitle: L10n.string("Close"), action: #selector(closeCurrentDocument(_:)), keyEquivalent: "w")
		closeItem.target = self
		fileMenu.addItem(.separator())
		fileMenu.addItem(withTitle: L10n.string("Save"), action: #selector(NSDocument.save(_:)), keyEquivalent: "s")
		fileMenu.addItem(withTitle: L10n.string("Save As..."), action: #selector(NSDocument.saveAs(_:)), keyEquivalent: "S")
		fileItem.submenu = fileMenu

		let editMenu = NSMenu(title: L10n.string("Edit"))
		let findItem = editMenu.addItem(withTitle: L10n.string("Find"), action: #selector(toggleFindBar(_:)), keyEquivalent: "f")
		findItem.target = self
		let findNextItem = editMenu.addItem(withTitle: L10n.string("Find Next"), action: #selector(findNext(_:)), keyEquivalent: "g")
		findNextItem.target = self
			let findPreviousItem = editMenu.addItem(withTitle: L10n.string("Find Previous"), action: #selector(findPrevious(_:)), keyEquivalent: "G")
			findPreviousItem.keyEquivalentModifierMask = [.command, .shift]
			findPreviousItem.target = self
			let selectAllFindMatchesItem = editMenu.addItem(withTitle: L10n.string("Select All Find Matches"), action: #selector(selectAllFindMatches(_:)), keyEquivalent: "g")
			selectAllFindMatchesItem.keyEquivalentModifierMask = [.command, .control]
			selectAllFindMatchesItem.target = self
			let findInProjectItem = editMenu.addItem(withTitle: L10n.string("Find in Project"), action: #selector(showProjectFind(_:)), keyEquivalent: "F")
			findInProjectItem.keyEquivalentModifierMask = [.command, .shift]
			findInProjectItem.target = self
			editItem.submenu = editMenu

		let navigateMenu = NSMenu(title: L10n.string("Navigate"))
		let gotoWorkspaceSymbolItem = navigateMenu.addItem(withTitle: L10n.string("Go to Symbol in Workspace"), action: #selector(showWorkspaceSymbolPalette(_:)), keyEquivalent: "t")
		gotoWorkspaceSymbolItem.target = self
		let gotoFileSymbolItem = navigateMenu.addItem(withTitle: L10n.string("Go to Symbol in File"), action: #selector(showFileSymbolPalette(_:)), keyEquivalent: "O")
		gotoFileSymbolItem.target = self
		let outlineItem = navigateMenu.addItem(withTitle: L10n.string("Outline"), action: #selector(showOutline(_:)), keyEquivalent: "7")
		outlineItem.keyEquivalentModifierMask = [.command, .option]
		outlineItem.target = self
		navigateItem.submenu = navigateMenu

		let viewMenu = NSMenu(title: L10n.string("View"))
		let gitGutterIndexItem = viewMenu.addItem(
			withTitle: L10n.string("Git Gutter: Compare to Index"),
			action: #selector(useGitGutterIndex(_:)),
			keyEquivalent: ""
		)
		gitGutterIndexItem.target = self
		let gitGutterHeadItem = viewMenu.addItem(
			withTitle: L10n.string("Git Gutter: Compare to HEAD"),
			action: #selector(useGitGutterHead(_:)),
			keyEquivalent: ""
		)
		gitGutterHeadItem.target = self
		gitGutterIndexMenuItem = gitGutterIndexItem
		gitGutterHeadMenuItem = gitGutterHeadItem
		refreshGitGutterMenuItems()
		viewItem.submenu = viewMenu

		let gitMenu = NSMenu(title: L10n.string("Git"))
		let gitChangesItem = gitMenu.addItem(withTitle: L10n.string("Git Changes"), action: #selector(showGitChanges(_:)), keyEquivalent: "")
		gitChangesItem.target = self
		let gitRefreshItem = gitMenu.addItem(withTitle: L10n.string("Refresh Git Status"), action: #selector(refreshGitChanges(_:)), keyEquivalent: "")
		gitRefreshItem.target = self
		gitMenu.addItem(.separator())
		let gitFetchItem = gitMenu.addItem(withTitle: L10n.string("Fetch"), action: #selector(fetchGitRemote(_:)), keyEquivalent: "")
		gitFetchItem.target = self
		let gitPullItem = gitMenu.addItem(withTitle: L10n.string("Pull"), action: #selector(pullGitRemote(_:)), keyEquivalent: "")
		gitPullItem.target = self
		let gitPullRebaseItem = gitMenu.addItem(withTitle: L10n.string("Pull Rebase"), action: #selector(pullGitRemoteRebase(_:)), keyEquivalent: "")
		gitPullRebaseItem.target = self
		let gitPushItem = gitMenu.addItem(withTitle: L10n.string("Push"), action: #selector(pushGitRemote(_:)), keyEquivalent: "")
		gitPushItem.target = self
		gitItem.submenu = gitMenu

		let taskMenu = NSMenu(title: L10n.string("Tasks"))
		let taskRunItem = taskMenu.addItem(withTitle: L10n.string("Run Task"), action: #selector(showTasks(_:)), keyEquivalent: "")
		taskRunItem.target = self
		let taskRefreshItem = taskMenu.addItem(withTitle: L10n.string("Refresh Tasks"), action: #selector(refreshTasks(_:)), keyEquivalent: "")
		taskRefreshItem.target = self
		taskItem.submenu = taskMenu

		let problemMenu = NSMenu(title: L10n.string("Problems"))
		let problemShowItem = problemMenu.addItem(withTitle: L10n.string("Problems"), action: #selector(showProblems(_:)), keyEquivalent: "M")
		problemShowItem.keyEquivalentModifierMask = [.command, .shift]
		problemShowItem.target = self
		problemItem.submenu = problemMenu

		let commandMenu = NSMenu(title: L10n.string("Command"))
		let paletteItem = commandMenu.addItem(withTitle: L10n.string("Command Palette"), action: #selector(toggleCommandPalette(_:)), keyEquivalent: "P")
		paletteItem.keyEquivalentModifierMask = [.command, .shift]
		paletteItem.target = self
		commandItem.submenu = commandMenu
		NSApp.mainMenu = mainMenu
	}

	@objc func openSelection(_ pasteboard: NSPasteboard, userData: String, error serviceError: AutoreleasingUnsafeMutablePointer<NSString?>) {
		guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
			serviceError.pointee = L10n.string("No text selection was provided") as NSString
			return
		}
		do {
			let url = FileManager.default.temporaryDirectory.appendingPathComponent("Itsy-Service-\(UUID().uuidString).txt")
			try text.write(to: url, atomically: true, encoding: .utf8)
			if !documentController.openDocument(at: url) {
				serviceError.pointee = L10n.string("Itsy could not open the service text") as NSString
			}
		} catch {
			self.error(serviceError, "Itsy could not create the service file")
		}
	}

	@objc func openFile(_ pasteboard: NSPasteboard, userData: String, error serviceError: AutoreleasingUnsafeMutablePointer<NSString?>) {
		let urls = (pasteboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL]) ?? []
		guard !urls.isEmpty else {
			serviceError.pointee = L10n.string("No file was provided") as NSString
			return
		}
		for url in urls where !documentController.openDocument(at: url) {
			serviceError.pointee = L10n.string("Itsy could not open \(url.lastPathComponent)") as NSString
			return
		}
	}

	private func error(_ pointer: AutoreleasingUnsafeMutablePointer<NSString?>, _ message: String.LocalizationValue) {
		pointer.pointee = L10n.string(message) as NSString
	}
}

extension AppDelegate: NSMenuDelegate, NSWindowDelegate, NSTextFieldDelegate, NSTextViewDelegate, NSTableViewDataSource, NSTableViewDelegate, NSOutlineViewDataSource, NSOutlineViewDelegate {
	func menuNeedsUpdate(_ menu: NSMenu) {
		guard menu === openRecentMenu else {
			return
		}
		menu.removeAllItems()
		let urls = documentController.recentDocumentURLs
		for url in urls {
			let item = menu.addItem(withTitle: url.lastPathComponent, action: #selector(openRecentDocument(_:)), keyEquivalent: "")
			item.target = self
			item.representedObject = url
		}
		if !urls.isEmpty {
			menu.addItem(.separator())
		}
		let clearItem = menu.addItem(withTitle: L10n.string("Clear Menu"), action: #selector(NSDocumentController.clearRecentDocuments(_:)), keyEquivalent: "")
		clearItem.target = documentController
		clearItem.isEnabled = !urls.isEmpty
	}

	func windowDidResignKey(_ notification: Notification) {
		guard let panel = notification.object as? NSPanel, panel === commandPalettePanel else {
			return
		}
		DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self, weak panel] in
			guard let self, let panel, panel.isVisible, !panel.isKeyWindow else {
				return
			}
			self.cancelCommandPalette()
		}
	}

	func controlTextDidChange(_ notification: Notification) {
		guard let field = notification.object as? NSTextField else {
			return
		}
		if field === projectFindInputField {
			searchProjectFind(query: field.stringValue)
		} else if field === commandPaletteInputField {
			filterCommandPaletteItems()
		} else if field === gitSummaryField {
			clearGitCommitHistorySelection()
			persistGitCommitDraft()
			updateGitComposerState()
		}
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		if control === gitSummaryField {
			switch commandSelector {
			case #selector(NSResponder.insertNewline(_:)) where currentEventHasCommandModifier():
				commitGitChanges(nil)
				return true
			case #selector(NSResponder.moveUp(_:)):
				return showPreviousGitCommitMessage()
			case #selector(NSResponder.moveDown(_:)):
				return restoreGitCommitDraftFromHistory()
			default:
				break
			}
		}
		if control === commandPaletteInputField {
			switch commandSelector {
			case #selector(NSResponder.insertNewline(_:)):
				runCommandPaletteSelection()
				return true
			case #selector(NSResponder.cancelOperation(_:)):
				if commandPaletteAcceptsRawText {
					cancelCommandPalette()
				} else {
					closeCommandPalette()
				}
				return true
			case #selector(NSResponder.moveUp(_:)):
				moveCommandPaletteSelection(-1)
				return true
			case #selector(NSResponder.moveDown(_:)):
				moveCommandPaletteSelection(1)
				return true
			default:
				return false
			}
		}
		if control === projectFindInputField, commandSelector == #selector(NSResponder.cancelOperation(_:)) {
			closeProjectFind()
			return true
		}
		return false
	}

	func textDidChange(_ notification: Notification) {
		guard let textView = notification.object as? NSTextView, textView === gitBodyTextView else {
			return
		}
		clearGitCommitHistorySelection()
		persistGitCommitDraft()
		updateGitComposerState()
	}

	func textView(_ textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		if textView === gitBodyTextView, commandSelector == #selector(NSResponder.insertNewline(_:)), currentEventHasCommandModifier() {
			commitGitChanges(nil)
			return true
		}
		return false
	}

	private func currentEventHasCommandModifier() -> Bool {
		NSApp.currentEvent?.modifierFlags.intersection(.deviceIndependentFlagsMask).contains(.command) == true
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		if tableView === projectFindTableView {
			return projectFindMatches.count
		}
		if tableView === gitTableView {
			return gitEntries.count
		}
		if tableView === gitBranchTableView {
			return gitBranches.count
		}
		if tableView === gitHunkTableView {
			return gitHunkItems.count
		}
		if tableView === taskTableView {
			return workspaceTasks.count
		}
		if tableView === problemsTableView {
			return workspaceProblems.count
		}
		if tableView === commandPaletteTableView {
			return commandPaletteSymbolScope != nil ? commandPaletteFilteredSymbols.count : commandPaletteFilteredItems.count
		}
		return 0
	}

	func tableViewSelectionDidChange(_ notification: Notification) {
		guard let tableView = notification.object as? NSTableView, tableView === gitTableView else {
			return
		}
		updateSelectedGitDiff()
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		if tableView === commandPaletteTableView {
			let identifier = NSUserInterfaceItemIdentifier("CommandPaletteCell")
			let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
			cell.identifier = identifier
			let textField = cell.textField ?? NSTextField(labelWithString: "")
			textField.font = .systemFont(ofSize: 13)
			textField.lineBreakMode = .byTruncatingTail
			if let scope = commandPaletteSymbolScope {
				let symbol = commandPaletteFilteredSymbols[row]
				switch scope {
				case .workspace:
					textField.stringValue = "\(symbol.name) · \(symbol.kind.rawValue) — \(symbol.relativePath):\(symbol.line)"
				case .file:
					textField.stringValue = "\(symbol.name) · \(symbol.kind.rawValue) — line \(symbol.line)"
				}
			} else {
				textField.stringValue = commandPaletteFilteredItems[row].title
			}
			if textField.superview == nil {
				textField.translatesAutoresizingMaskIntoConstraints = false
				cell.addSubview(textField)
				NSLayoutConstraint.activate([
					textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 12),
					textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -12),
					textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				])
				cell.textField = textField
			}
			return cell
		}
		if tableView === gitTableView {
			let identifier = NSUserInterfaceItemIdentifier("GitCell")
			let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
			cell.identifier = identifier
			let textField = cell.textField ?? NSTextField(labelWithString: "")
			textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
			textField.lineBreakMode = .byTruncatingTail
			textField.stringValue = gitEntryTitle(gitEntries[row])
			if textField.superview == nil {
				textField.translatesAutoresizingMaskIntoConstraints = false
				cell.addSubview(textField)
				NSLayoutConstraint.activate([
					textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
					textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
					textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				])
				cell.textField = textField
			}
			return cell
		}
		if tableView === gitHunkTableView {
			let item = gitHunkItems[row]
			let cell = NSTableCellView()
			let hunkButton = NSButton(title: item.isStaged ? L10n.string("Unstage Hunk") : L10n.string("Stage Hunk"), target: self, action: #selector(applyGitHunk(_:)))
			hunkButton.bezelStyle = .rounded
			hunkButton.font = .systemFont(ofSize: 10)
			hunkButton.tag = row
			let lineButton = NSButton(title: item.isStaged ? L10n.string("Unstage Lines") : L10n.string("Stage Lines"), target: self, action: #selector(applyGitSelectedLines(_:)))
			lineButton.bezelStyle = .rounded
			lineButton.font = .systemFont(ofSize: 10)
			lineButton.tag = row
			let label = NSTextField(labelWithString: item.title)
			label.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
			label.textColor = .secondaryLabelColor
			label.lineBreakMode = .byTruncatingMiddle
			let stack = NSStackView(views: [hunkButton, lineButton, label])
			stack.orientation = .vertical
			stack.alignment = .leading
			stack.spacing = 2
			stack.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(stack)
			NSLayoutConstraint.activate([
				stack.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 6),
				stack.trailingAnchor.constraint(lessThanOrEqualTo: cell.trailingAnchor, constant: -6),
				stack.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				hunkButton.widthAnchor.constraint(equalToConstant: 118),
				lineButton.widthAnchor.constraint(equalToConstant: 118),
			])
			return cell
		}
		if tableView === gitBranchTableView {
			let branch = gitBranches[row]
			let cell = NSTableCellView()
			let title = NSTextField(labelWithString: branchTitle(branch))
			title.font = .systemFont(ofSize: 12, weight: branch.isCurrent ? .semibold : .regular)
			title.lineBreakMode = .byTruncatingMiddle
			let detail = NSTextField(labelWithString: branchDetail(branch))
			detail.font = .systemFont(ofSize: 10)
			detail.textColor = .secondaryLabelColor
			detail.lineBreakMode = .byTruncatingMiddle
			let textStack = NSStackView(views: [title, detail])
			textStack.orientation = .vertical
			textStack.alignment = .leading
			textStack.spacing = 2
			let switchButton = NSButton(title: L10n.string("Switch"), target: self, action: #selector(switchGitBranch(_:)))
			let createButton = NSButton(title: L10n.string("Create"), target: self, action: #selector(createGitBranchFromRow(_:)))
			let deleteButton = NSButton(title: L10n.string("Delete"), target: self, action: #selector(deleteGitBranchFromRow(_:)))
			[switchButton, createButton, deleteButton].forEach {
				$0.bezelStyle = .rounded
				$0.font = .systemFont(ofSize: 10)
				$0.tag = row
			}
			switchButton.isEnabled = branch.kind == .local && !branch.isCurrent
			deleteButton.isEnabled = branch.kind == .local && !branch.isCurrent
			let buttonStack = NSStackView(views: [switchButton, createButton, deleteButton])
			buttonStack.orientation = .horizontal
			buttonStack.spacing = 6
			let rowStack = NSStackView(views: [textStack, buttonStack])
			rowStack.orientation = .horizontal
			rowStack.alignment = .centerY
			rowStack.spacing = 10
			rowStack.distribution = .fill
			rowStack.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(rowStack)
			NSLayoutConstraint.activate([
				rowStack.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
				rowStack.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
				rowStack.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				buttonStack.widthAnchor.constraint(equalToConstant: 190),
			])
			return cell
		}
		if tableView === taskTableView {
			let identifier = NSUserInterfaceItemIdentifier("TaskCell")
			let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
			cell.identifier = identifier
			let textField = cell.textField ?? NSTextField(labelWithString: "")
			textField.font = .systemFont(ofSize: 12)
			textField.lineBreakMode = .byTruncatingTail
			textField.stringValue = taskTitle(workspaceTasks[row])
			if textField.superview == nil {
				textField.translatesAutoresizingMaskIntoConstraints = false
				cell.addSubview(textField)
				NSLayoutConstraint.activate([
					textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
					textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
					textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				])
				cell.textField = textField
			}
			return cell
		}
		if tableView === problemsTableView {
			let identifier = NSUserInterfaceItemIdentifier("ProblemCell")
			let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
			cell.identifier = identifier
			let textField = cell.textField ?? NSTextField(labelWithString: "")
			textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
			textField.lineBreakMode = .byTruncatingTail
			textField.stringValue = problemTitle(workspaceProblems[row])
			if textField.superview == nil {
				textField.translatesAutoresizingMaskIntoConstraints = false
				cell.addSubview(textField)
				NSLayoutConstraint.activate([
					textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
					textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
					textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				])
				cell.textField = textField
			}
			return cell
		}
		guard tableView === projectFindTableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("ProjectFindCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		let match = projectFindMatches[row]
		textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textField.lineBreakMode = .byTruncatingTail
		textField.stringValue = "\(match.relativePath):\(match.line):\(match.column)  \(match.lineText)"
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}

	func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
		guard outlineView === outlineOutlineView else {
			return 0
		}
		if let kindNode = item as? OutlineKindNode {
			return kindNode.symbols.count
		}
		if item == nil {
			return outlineKindNodes.count
		}
		return 0
	}

	func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
		if let kindNode = item as? OutlineKindNode {
			return kindNode.symbols[index]
		}
		return outlineKindNodes[index]
	}

	func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
		item is OutlineKindNode
	}

	func outlineViewItemDidExpand(_ notification: Notification) {
		guard (notification.object as? NSOutlineView) === outlineOutlineView else {
			return
		}
		recordOutlineCollapseChange()
	}

	func outlineViewItemDidCollapse(_ notification: Notification) {
		guard (notification.object as? NSOutlineView) === outlineOutlineView else {
			return
		}
		recordOutlineCollapseChange()
	}

	func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
		guard outlineView === outlineOutlineView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("OutlineCell")
		let cell = outlineView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.lineBreakMode = .byTruncatingTail
		if let kindNode = item as? OutlineKindNode {
			textField.font = .boldSystemFont(ofSize: 12)
			textField.stringValue = "\(kindNode.kind.rawValue.uppercased()) · \(kindNode.symbols.count)"
		} else if let symbolNode = item as? OutlineSymbolNode {
			textField.font = .systemFont(ofSize: 12)
			textField.stringValue = "\(symbolNode.symbol.name)  \(symbolNode.symbol.line)"
		} else {
			textField.stringValue = ""
		}
		if textField.superview == nil {
			textField.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(textField)
			NSLayoutConstraint.activate([
				textField.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 2),
				textField.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -2),
				textField.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = textField
		}
		return cell
	}
}

if CommandLine.arguments.contains("--bench-exit-on-ready") {
	exitForBenchReady()
}

recordBenchStage("process_start")

let app = NSApplication.shared
private let documentController = ItsyDocumentController()
private let appDelegate = AppDelegate(documentController: documentController)

_ = documentController
app.setActivationPolicy(.regular)
app.delegate = appDelegate
app.run()
