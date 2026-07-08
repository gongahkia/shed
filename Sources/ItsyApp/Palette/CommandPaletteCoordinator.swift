import AppKit
import Foundation
import ItsyEditor

@MainActor final class CommandPaletteCoordinator: NSObject, NSWindowDelegate, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
	private enum CommandPaletteSymbolScope { case workspace, file }

	private let documentController: ItsyDocumentController
	private let commandRegistryProvider: () -> CommandRegistry
	private let activeDocumentProvider: () -> NSDocument?
	private let workspaceSymbolProvider: @MainActor (String) async throws -> [WorkspaceSymbol]
	private let fileSymbolProvider: @MainActor () async throws -> [WorkspaceSymbol]?
	private var commandPalettePanel: NSPanel?
	private var commandPaletteInputField: NSTextField?
	private var commandPaletteTableView: NSTableView?
	private var commandPaletteCancelHandler: (() -> Void)?
	private var commandPaletteRunText: ((String) -> Void)?
	private var commandPaletteItems: [Command] = []
	private var commandPaletteFilteredItems: [Command] = []
	private var commandPaletteFiles: [String] = []
	private var commandPaletteFilteredFiles: [String] = []
	private var commandPaletteShowsFiles = false
	private var commandPaletteAcceptsRawText = false
	private var commandPaletteSymbolScope: CommandPaletteSymbolScope?
	private var commandPaletteBaseSymbols: [WorkspaceSymbol] = []
	private var commandPaletteLSPSymbols: [WorkspaceSymbol]?
	private var commandPaletteFilteredSymbols: [WorkspaceSymbol] = []
	private var commandPaletteSymbolGeneration = 0
	private var commandPaletteFileSymbolsRequested = false

	init(
		documentController: ItsyDocumentController,
		commandRegistryProvider: @escaping () -> CommandRegistry,
		activeDocumentProvider: @escaping () -> NSDocument?,
		workspaceSymbolProvider: @escaping @MainActor (String) async throws -> [WorkspaceSymbol] = { _ in [] },
		fileSymbolProvider: @escaping @MainActor () async throws -> [WorkspaceSymbol]? = { nil }
	) {
		self.documentController = documentController
		self.commandRegistryProvider = commandRegistryProvider
		self.activeDocumentProvider = activeDocumentProvider
		self.workspaceSymbolProvider = workspaceSymbolProvider
		self.fileSymbolProvider = fileSymbolProvider
	}

	func installBridge() {
		ItsyCommandPaletteBridge.showExCommand = { [weak self] window, completion in
			guard let self else {
				return false
			}
			self.showExCommand(relativeTo: window, completion: completion)
			return true
		}
	}

	@objc func toggleCommandPalette(_ sender: Any?) {
		toggleCommandPalettePanel(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	@objc func showWorkspaceSymbolPalette(_ sender: Any?) {
		showCommandPalette(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow, prefill: "@")
	}

	@objc func showFileSymbolPalette(_ sender: Any?) {
		showCommandPalette(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow, prefill: "#")
	}

	@objc func showFilePalette(_ sender: Any?) {
		let panel = makeCommandPalettePanelIfNeeded()
		commandPaletteCancelHandler = nil
		commandPaletteRunText = nil
		let files = ItsyWorkspaceController.currentWorkspaceIndex?.files.map(\.relativePath) ?? []
		setCommandPaletteFiles(files)
		centerCommandPalette(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		focusCommandPaletteInput()
	}

	@objc func showLinePalette(_ sender: Any?) {
		let panel = makeCommandPalettePanelIfNeeded()
		commandPaletteCancelHandler = nil
		commandPaletteRunText = { [weak self] text in
			guard
				let self,
				let target = CommandPaletteLineTarget.parse(text),
				let document = self.activeDocumentProvider() as? ItsyDocument
			else {
				NSSound.beep()
				return
			}
			self.closeCommandPalette()
			document.jumpTo(line: target.line, column: target.column)
		}
		setCommandPaletteCommandLine(":")
		commandPaletteInputField?.placeholderString = L10n.string("Line")
		centerCommandPalette(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		focusCommandPaletteInput()
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
		setCommandPaletteItems(commandRegistryProvider().commands)
		if let prefill, !prefill.isEmpty {
			commandPaletteInputField?.stringValue = prefill
			filterCommandPaletteItems()
		}
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
		commandPaletteBaseSymbols = []
		commandPaletteLSPSymbols = nil
		commandPaletteFilteredSymbols = []
		commandPaletteFiles = []
		commandPaletteFilteredFiles = []
		commandPaletteShowsFiles = false
		commandPaletteSymbolGeneration += 1
		commandPaletteFileSymbolsRequested = false
		commandPaletteItems = items
		commandPaletteInputField?.stringValue = ""
		commandPaletteInputField?.placeholderString = L10n.string("Command")
		commandPaletteTableView?.enclosingScrollView?.isHidden = false
		filterCommandPaletteItems()
	}

	private func setCommandPaletteCommandLine(_ value: String) {
		commandPaletteAcceptsRawText = true
		commandPaletteSymbolScope = nil
		commandPaletteBaseSymbols = []
		commandPaletteLSPSymbols = nil
		commandPaletteFilteredSymbols = []
		commandPaletteFiles = []
		commandPaletteFilteredFiles = []
		commandPaletteShowsFiles = false
		commandPaletteSymbolGeneration += 1
		commandPaletteFileSymbolsRequested = false
		commandPaletteItems = []
		commandPaletteFilteredItems = []
		commandPaletteInputField?.placeholderString = ""
		commandPaletteInputField?.stringValue = value
		commandPaletteTableView?.enclosingScrollView?.isHidden = true
		commandPaletteTableView?.reloadData()
	}

	private func setCommandPaletteFiles(_ files: [String]) {
		commandPaletteAcceptsRawText = false
		commandPaletteSymbolScope = nil
		commandPaletteBaseSymbols = []
		commandPaletteLSPSymbols = nil
		commandPaletteFilteredSymbols = []
		commandPaletteSymbolGeneration += 1
		commandPaletteFileSymbolsRequested = false
		commandPaletteItems = []
		commandPaletteFilteredItems = []
		commandPaletteFiles = files
		commandPaletteFilteredFiles = []
		commandPaletteShowsFiles = true
		commandPaletteInputField?.stringValue = ""
		commandPaletteInputField?.placeholderString = L10n.string("File")
		commandPaletteTableView?.enclosingScrollView?.isHidden = false
		filterCommandPaletteItems()
	}

	private func focusCommandPaletteInput() {
		applyCommandPaletteTheme()
		commandPalettePanel?.makeFirstResponder(commandPaletteInputField)
		commandPaletteInputField?.currentEditor()?.selectedRange = NSRange(location: commandPaletteInputField?.stringValue.count ?? 0, length: 0)
	}

	private func applyCommandPaletteTheme() {
		guard let panel = commandPalettePanel else {
			return
		}
		let palette = AppTheme.palette
		panel.contentView?.layer?.backgroundColor = palette.panelBackground.cgColor
		panel.contentView?.layer?.borderColor = palette.border.cgColor
		commandPaletteInputField?.textColor = palette.inputForeground
		commandPaletteInputField?.backgroundColor = palette.panelBackground
		commandPaletteInputField?.placeholderAttributedString = NSAttributedString(
			string: commandPaletteInputField?.placeholderString ?? "",
			attributes: [.foregroundColor: palette.inputPlaceholder]
		)
		commandPaletteTableView?.backgroundColor = palette.panelBackground
		commandPaletteTableView?.gridColor = palette.border
		commandPaletteTableView?.reloadData()
		AppThemeApplier.apply(palette, to: panel)
	}

	private func symbolsForCommandPaletteScope(_ scope: CommandPaletteSymbolScope) -> [WorkspaceSymbol] {
		guard let index = ItsyWorkspaceController.currentWorkspaceIndex else {
			return []
		}
		switch scope {
		case .workspace:
			return index.symbols
		case .file:
			guard let url = (activeDocumentProvider() as? ItsyDocument)?.fileURL,
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
		if commandPaletteShowsFiles {
			commandPaletteFilteredFiles = CommandPaletteFileFilter.ranked(paths: commandPaletteFiles, query: raw)
			commandPaletteTableView?.reloadData()
			if !commandPaletteFilteredFiles.isEmpty {
				commandPaletteTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
			}
			return
		}
		if raw.hasPrefix("@") || raw.hasPrefix("#") {
			let scope: CommandPaletteSymbolScope = raw.hasPrefix("@") ? .workspace : .file
			if commandPaletteSymbolScope != scope {
				commandPaletteBaseSymbols = symbolsForCommandPaletteScope(scope)
				commandPaletteLSPSymbols = nil
				commandPaletteSymbolScope = scope
				commandPaletteSymbolGeneration += 1
				commandPaletteFileSymbolsRequested = false
			}
			let query = String(raw.dropFirst())
			if scope == .workspace {
				commandPaletteLSPSymbols = nil
				requestWorkspaceSymbols(query: query)
			} else if commandPaletteFileSymbolsRequested == false {
				commandPaletteFileSymbolsRequested = true
				requestFileSymbols()
			}
			applyCommandPaletteSymbolFilter(scope: scope, query: query)
			commandPaletteFilteredItems = []
			return
		}
		if commandPaletteSymbolScope != nil {
			commandPaletteSymbolScope = nil
			commandPaletteBaseSymbols = []
			commandPaletteLSPSymbols = nil
			commandPaletteFilteredSymbols = []
			commandPaletteSymbolGeneration += 1
			commandPaletteFileSymbolsRequested = false
		}
		let query = raw.lowercased()
		commandPaletteFilteredItems = FuzzyMatcher.ranked(commandPaletteItems, query: query, includeUnmatched: false, by: \.title)
		commandPaletteTableView?.reloadData()
		if !commandPaletteFilteredItems.isEmpty {
			commandPaletteTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func requestWorkspaceSymbols(query: String) {
		commandPaletteSymbolGeneration += 1
		let generation = commandPaletteSymbolGeneration
		let provider = workspaceSymbolProvider
		Task { [weak self] in
			let symbols: [WorkspaceSymbol]
			do {
				symbols = try await provider(query)
			} catch {
				return
			}
			await MainActor.run { [weak self] in
				guard
					let self,
					generation == self.commandPaletteSymbolGeneration,
					self.commandPaletteSymbolScope == .workspace,
					self.commandPaletteInputField?.stringValue == "@\(query)"
				else {
					return
				}
				self.commandPaletteLSPSymbols = Array(symbols.prefix(100))
				self.applyCommandPaletteSymbolFilter(scope: .workspace, query: query)
			}
		}
	}

	private func requestFileSymbols() {
		commandPaletteSymbolGeneration += 1
		let generation = commandPaletteSymbolGeneration
		let provider = fileSymbolProvider
		Task { [weak self] in
			let symbols: [WorkspaceSymbol]?
			do {
				symbols = try await provider()
			} catch {
				return
			}
			await MainActor.run { [weak self] in
				guard
					let self,
					generation == self.commandPaletteSymbolGeneration,
					self.commandPaletteSymbolScope == .file,
					self.commandPaletteInputField?.stringValue.hasPrefix("#") == true
				else {
					return
				}
				guard let symbols else {
					return
				}
				self.commandPaletteLSPSymbols = symbols
				let raw = self.commandPaletteInputField?.stringValue ?? ""
				let currentQuery = String(raw.dropFirst())
				self.applyCommandPaletteSymbolFilter(scope: .file, query: currentQuery)
			}
		}
	}

	private func applyCommandPaletteSymbolFilter(scope: CommandPaletteSymbolScope, query: String) {
		let fuzzyQuery = query.lowercased()
		let keyPath: (WorkspaceSymbol) -> String = scope == .workspace
			? { "\($0.name) \($0.relativePath)" }
			: { $0.name }
		switch scope {
		case .workspace where commandPaletteLSPSymbols != nil:
			let lspSymbols = Array((commandPaletteLSPSymbols ?? []).prefix(100))
			let lspKeys = Set(lspSymbols.map(symbolRangeKey(_:)))
			let fallback = commandPaletteBaseSymbols.filter { !lspKeys.contains(symbolRangeKey($0)) }
			commandPaletteFilteredSymbols = lspSymbols + FuzzyMatcher.ranked(
				fallback,
				query: fuzzyQuery,
				includeUnmatched: fuzzyQuery.isEmpty,
				by: keyPath
			)
		case .file where commandPaletteLSPSymbols != nil:
			commandPaletteFilteredSymbols = FuzzyMatcher.ranked(
				commandPaletteLSPSymbols ?? [],
				query: fuzzyQuery,
				includeUnmatched: fuzzyQuery.isEmpty,
				by: keyPath
			)
		default:
			commandPaletteFilteredSymbols = FuzzyMatcher.ranked(
				commandPaletteBaseSymbols,
				query: fuzzyQuery,
				includeUnmatched: fuzzyQuery.isEmpty,
				by: keyPath
			)
		}
		commandPaletteTableView?.reloadData()
		if !commandPaletteFilteredSymbols.isEmpty {
			commandPaletteTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func symbolRangeKey(_ symbol: WorkspaceSymbol) -> String {
		[
			symbol.relativePath,
			String(symbol.line),
			String(symbol.column),
			String(symbol.endLine ?? symbol.line),
			String(symbol.endColumn ?? symbol.column),
		].joined(separator: "\u{1f}")
	}

	private func moveCommandPaletteSelection(_ delta: Int) {
		guard !commandPaletteAcceptsRawText, let tableView = commandPaletteTableView else {
			return
		}
		let count = commandPaletteSymbolScope != nil ? commandPaletteFilteredSymbols.count : (commandPaletteShowsFiles ? commandPaletteFilteredFiles.count : commandPaletteFilteredItems.count)
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
		if commandPaletteShowsFiles {
			guard
				let tableView = commandPaletteTableView,
				tableView.selectedRow >= 0,
				tableView.selectedRow < commandPaletteFilteredFiles.count,
				let root = ItsyWorkspaceController.currentRootURL
			else {
				return
			}
			let relativePath = commandPaletteFilteredFiles[tableView.selectedRow]
			closeCommandPalette()
			documentController.openDocument(at: root.appendingPathComponent(relativePath))
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
		guard let field = notification.object as? NSTextField, field === commandPaletteInputField else {
			return
		}
		filterCommandPaletteItems()
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		guard control === commandPaletteInputField else {
			return false
		}
		switch commandSelector {
		case #selector(NSResponder.insertNewline(_:)):
			runCommandPaletteSelection()
			return true
		case #selector(NSResponder.insertTab(_:)):
			if commandPaletteAcceptsRawText {
				completeCommandPaletteRawText()
				return true
			}
			return false
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

	private func completeCommandPaletteRawText() {
		guard let input = commandPaletteInputField else {
			return
		}
		let raw = input.stringValue
		let hasColon = raw.hasPrefix(":")
		let prefix = hasColon ? String(raw.dropFirst()) : raw
		guard let match = commandRegistryProvider().allCommands.map(\.id).sorted().first(where: { $0.hasPrefix(prefix) }) else {
			return
		}
		input.stringValue = (hasColon ? ":" : "") + match
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		tableView === commandPaletteTableView
			? (commandPaletteSymbolScope != nil ? commandPaletteFilteredSymbols.count : (commandPaletteShowsFiles ? commandPaletteFilteredFiles.count : commandPaletteFilteredItems.count))
			: 0
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard tableView === commandPaletteTableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("CommandPaletteCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .systemFont(ofSize: 13)
		textField.textColor = AppTheme.palette.foreground
		textField.lineBreakMode = .byTruncatingTail
		if let scope = commandPaletteSymbolScope {
			let symbol = commandPaletteFilteredSymbols[row]
			switch scope {
			case .workspace:
				textField.stringValue = "\(symbol.name) · \(symbol.kind.rawValue) — \(symbol.relativePath):\(symbol.line)"
			case .file:
				textField.stringValue = "\(symbol.name) · \(symbol.kind.rawValue) — line \(symbol.line)"
			}
		} else if commandPaletteShowsFiles {
			textField.stringValue = commandPaletteFilteredFiles[row]
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
}
