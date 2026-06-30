import AppKit
import Dispatch
import Foundation
import ItsyEditor
import ItsyKeymap
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

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: ItsyDocumentController
	private weak var openRecentMenu: NSMenu?
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
	private var gitEntries: [GitStatusEntry] = []
	private var gitRootURL: URL?
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
			contentRect: NSRect(x: 0, y: 0, width: 620, height: 420),
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
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshGitChanges(_:)))
		let stageButton = NSButton(title: L10n.string("Stage"), target: self, action: #selector(stageSelectedGitEntries(_:)))
		let unstageButton = NSButton(title: L10n.string("Unstage"), target: self, action: #selector(unstageSelectedGitEntries(_:)))
		let buttonStack = NSStackView(views: [refreshButton, stageButton, unstageButton])
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
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		header.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		gitStatusLabel = statusLabel
		gitTableView = tableView
	}

	private func centerGitPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(680, max(520, hostFrame.width - 100))
		let height = min(460, max(280, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	@objc private func refreshGitChanges(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setGitEntries([], root: nil, status: L10n.string("Open a folder first"), isError: true)
			return
		}
		guard let gitRoot = try? GitRepository.discoverRoot(containing: root) else {
			setGitEntries([], root: nil, status: L10n.string("Not a Git repository"), isError: true)
			ItsyWorkspaceController.refreshGitStatus()
			return
		}
		do {
			let snapshot = try GitRepository(root: gitRoot).snapshot()
			let status = "\(snapshot.branchLabel) - \(snapshot.status.stagedCount) staged, \(snapshot.status.unstagedCount) unstaged"
			setGitEntries(snapshot.status.entries, root: gitRoot, status: status, isError: false)
			ItsyWorkspaceController.refreshGitStatus()
		} catch {
			setGitEntries([], root: gitRoot, status: String(describing: error), isError: true)
		}
	}

	private func setGitEntries(_ entries: [GitStatusEntry], root: URL?, status: String, isError: Bool) {
		gitEntries = entries
		gitRootURL = root
		gitStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		gitStatusLabel?.stringValue = status
		gitTableView?.reloadData()
		if !entries.isEmpty {
			gitTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
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
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true)
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
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true)
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
		_ = documentController.openDocument(at: gitRootURL.appendingPathComponent(gitEntries[tableView.selectedRow].path))
	}

	private func gitEntryTitle(_ entry: GitStatusEntry) -> String {
		let original = entry.originalPath.map { " <- \($0)" } ?? ""
		return "\(gitEntryStatus(entry))  \(entry.path)\(original)"
	}

	private func gitEntryStatus(_ entry: GitStatusEntry) -> String {
		if entry.kind == .untracked {
			return "??"
		}
		if entry.kind == .unmerged {
			return "UU"
		}
		let index = entry.indexStatus.map(String.init) ?? "."
		let worktree = entry.worktreeStatus.map(String.init) ?? "."
		return index + worktree
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
		let gitItem = NSMenuItem()
		let taskItem = NSMenuItem()
		let problemItem = NSMenuItem()
		let commandItem = NSMenuItem()
		mainMenu.addItem(appItem)
		mainMenu.addItem(fileItem)
		mainMenu.addItem(editItem)
		mainMenu.addItem(navigateItem)
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

		let gitMenu = NSMenu(title: L10n.string("Git"))
		let gitChangesItem = gitMenu.addItem(withTitle: L10n.string("Git Changes"), action: #selector(showGitChanges(_:)), keyEquivalent: "")
		gitChangesItem.target = self
		let gitRefreshItem = gitMenu.addItem(withTitle: L10n.string("Refresh Git Status"), action: #selector(refreshGitChanges(_:)), keyEquivalent: "")
		gitRefreshItem.target = self
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

extension AppDelegate: NSMenuDelegate, NSWindowDelegate, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate, NSOutlineViewDataSource, NSOutlineViewDelegate {
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
		}
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
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

	func numberOfRows(in tableView: NSTableView) -> Int {
		if tableView === projectFindTableView {
			return projectFindMatches.count
		}
		if tableView === gitTableView {
			return gitEntries.count
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
