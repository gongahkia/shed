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

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private let documentController: ItsyDocumentController
	private weak var openRecentMenu: NSMenu?
	private lazy var commandRegistry = makeCommandRegistry()
	private var commandPalettePanel: NSPanel?
	private var commandPaletteInputField: ItsyActionTextField?
	private var commandPaletteTableView: NSTableView?
	private var commandPaletteCancelHandler: (() -> Void)?
	private var commandPaletteRunText: ((String) -> Void)?
	private var commandPaletteItems: [Command] = []
	private var commandPaletteFilteredItems: [Command] = []
	private var commandPaletteAcceptsRawText = false
	private var settingsWindowController: NSWindowController?
	private var settingsThemePopup: NSPopUpButton?
	private var settingsStatusLabel: NSTextField?
	private var projectFindPanel: NSPanel?
	private var projectFindInputField: ItsyActionTextField?
	private var projectFindStatusLabel: NSTextField?
	private var projectFindTableView: NSTableView?
	private var projectFindMatches: [ProjectFindMatch] = []
	private var projectFindGeneration = 0

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
		commandPaletteInputField?.onCancel = { [weak self] in self?.cancelCommandPalette() }
		setCommandPaletteCommandLine(":")
		centerCommandPalette(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		focusCommandPaletteInput()
	}

	private func showCommandPalette(relativeTo hostWindow: NSWindow?) {
		let panel = makeCommandPalettePanelIfNeeded()
		commandPaletteCancelHandler = nil
		commandPaletteRunText = nil
		commandPaletteInputField?.onCancel = { [weak self] in self?.closeCommandPalette() }
		setCommandPaletteItems(commandRegistry.commands)
		centerCommandPalette(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		focusCommandPaletteInput()
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

		let inputField = ItsyActionTextField(frame: .zero)
		inputField.placeholderString = L10n.string("Command")
		inputField.font = .systemFont(ofSize: 18)
		inputField.isBordered = false
		inputField.focusRingType = .none
		inputField.backgroundColor = .clear
		inputField.translatesAutoresizingMaskIntoConstraints = false
		inputField.onConfirm = { [weak self] in self?.runCommandPaletteSelection() }
		inputField.onMoveSelection = { [weak self] delta in self?.moveCommandPaletteSelection(delta) }
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
		commandPaletteItems = items
		commandPaletteInputField?.stringValue = ""
		commandPaletteInputField?.placeholderString = L10n.string("Command")
		commandPaletteTableView?.enclosingScrollView?.isHidden = false
		filterCommandPaletteItems()
	}

	private func setCommandPaletteCommandLine(_ value: String) {
		commandPaletteAcceptsRawText = true
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

	private func filterCommandPaletteItems() {
		guard !commandPaletteAcceptsRawText else {
			return
		}
		let query = commandPaletteInputField?.stringValue.lowercased() ?? ""
		commandPaletteFilteredItems = FuzzyMatcher.ranked(commandPaletteItems, query: query, includeUnmatched: false, by: \.title)
		commandPaletteTableView?.reloadData()
		if !commandPaletteFilteredItems.isEmpty {
			commandPaletteTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func moveCommandPaletteSelection(_ delta: Int) {
		guard !commandPaletteAcceptsRawText, !commandPaletteFilteredItems.isEmpty, let tableView = commandPaletteTableView else {
			return
		}
		let current = tableView.selectedRow >= 0 ? tableView.selectedRow : 0
		let next = min(max(current + delta, 0), commandPaletteFilteredItems.count - 1)
		tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
		tableView.scrollRowToVisible(next)
	}

	private func runCommandPaletteSelection() {
		if commandPaletteAcceptsRawText {
			commandPaletteRunText?(commandPaletteInputField?.stringValue ?? "")
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

		let queryField = ItsyActionTextField(frame: .zero)
		queryField.placeholderString = L10n.string("Find in project")
		queryField.font = .systemFont(ofSize: 15)
		queryField.isBordered = true
		queryField.focusRingType = .default
		queryField.translatesAutoresizingMaskIntoConstraints = false
		queryField.onCancel = { [weak self] in self?.closeProjectFind() }
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
		let commandItem = NSMenuItem()
		mainMenu.addItem(appItem)
		mainMenu.addItem(fileItem)
		mainMenu.addItem(editItem)
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
		newTabItem.target = documentController
		let openItem = fileMenu.addItem(withTitle: L10n.string("Open..."), action: #selector(NSDocumentController.openDocument(_:)), keyEquivalent: "o")
		openItem.target = documentController
		let openFolderItem = fileMenu.addItem(withTitle: L10n.string("Open Folder..."), action: #selector(openFolder(_:)), keyEquivalent: "O")
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

extension AppDelegate: NSMenuDelegate, NSWindowDelegate, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
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

	func numberOfRows(in tableView: NSTableView) -> Int {
		if tableView === projectFindTableView {
			return projectFindMatches.count
		}
		if tableView === commandPaletteTableView {
			return commandPaletteFilteredItems.count
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
			textField.stringValue = commandPaletteFilteredItems[row].title
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
}

if CommandLine.arguments.contains("--bench-exit-on-ready") {
	let ns = DispatchTime.now().uptimeNanoseconds
	FileHandle.standardOutput.write(Data("READY \(ns)\n".utf8))
	exit(0)
}

recordBenchStage("process_start")

let app = NSApplication.shared
private let documentController = ItsyDocumentController()
private let appDelegate = AppDelegate(documentController: documentController)

_ = documentController
app.setActivationPolicy(.regular)
app.delegate = appDelegate
app.run()
