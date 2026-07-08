import AppKit
import Foundation
import ItsyConfig

struct TerminalWorkspaceState: Codable, Equatable {
	var selectedTabIndex: Int
	var tabs: [TerminalTabState]
}

struct TerminalTabState: Codable, Equatable {
	var currentDirectoryPath: String
	var layout: String
}

struct TerminalPaneLayout: Equatable {
	var vertical: Bool?
	var children: [TerminalPaneLayout]

	static var leaf: TerminalPaneLayout {
		TerminalPaneLayout(vertical: nil, children: [])
	}

	static func split(vertical: Bool, children: [TerminalPaneLayout]) -> TerminalPaneLayout {
		TerminalPaneLayout(vertical: vertical, children: children)
	}

	var encoded: String {
		guard let vertical else {
			return "L"
		}
		let marker = vertical ? "V" : "H"
		return "\(marker)[\(children.map(\.encoded).joined(separator: ","))]"
	}

	static func decode(_ value: String) -> TerminalPaneLayout? {
		var index = value.startIndex
		guard let layout = decode(in: value, index: &index), index == value.endIndex else {
			return nil
		}
		return layout
	}

	private static func decode(in value: String, index: inout String.Index) -> TerminalPaneLayout? {
		guard index < value.endIndex else {
			return nil
		}
		let marker = value[index]
		index = value.index(after: index)
		if marker == "L" {
			return .leaf
		}
		guard marker == "V" || marker == "H", index < value.endIndex, value[index] == "[" else {
			return nil
		}
		index = value.index(after: index)
		var children: [TerminalPaneLayout] = []
		while index < value.endIndex, value[index] != "]" {
			guard let child = decode(in: value, index: &index) else {
				return nil
			}
			children.append(child)
			if index < value.endIndex, value[index] == "," {
				index = value.index(after: index)
			}
		}
		guard index < value.endIndex, value[index] == "]" else {
			return nil
		}
		index = value.index(after: index)
		return .split(vertical: marker == "V", children: children)
	}
}

@MainActor final class TerminalCoordinator: NSObject {
	@MainActor private final class TerminalPane {
		let id = UUID()
		let view: ItsyTerminalView

		init(emulator: ItsyTerminalEmulator) {
			view = ItsyTerminalView(emulator: emulator)
		}
	}

	@MainActor private indirect enum TerminalPaneNode {
		case leaf(TerminalPane)
		case split(vertical: Bool, children: [TerminalPaneNode])

		static func make(layout: TerminalPaneLayout, emulator: ItsyTerminalEmulator) -> TerminalPaneNode {
			guard let vertical = layout.vertical else {
				return .leaf(TerminalPane(emulator: emulator))
			}
			let children = layout.children.isEmpty ? [TerminalPaneLayout.leaf] : layout.children
			return .split(vertical: vertical, children: children.map { make(layout: $0, emulator: emulator) })
		}

		var panes: [TerminalPane] {
			switch self {
			case let .leaf(pane):
				[pane]
			case let .split(_, children):
				children.flatMap(\.panes)
			}
		}

		var layout: TerminalPaneLayout {
			switch self {
			case .leaf:
				.leaf
			case let .split(vertical, children):
				.split(vertical: vertical, children: children.map(\.layout))
			}
		}

		mutating func splitPane(id: UUID, vertical: Bool, emulator: ItsyTerminalEmulator) -> TerminalPane? {
			switch self {
			case let .leaf(pane) where pane.id == id:
				let newPane = TerminalPane(emulator: emulator)
				self = .split(vertical: vertical, children: [.leaf(pane), .leaf(newPane)])
				return newPane
			case let .split(currentVertical, children):
				var updated = children
				for index in updated.indices {
					if let pane = updated[index].splitPane(id: id, vertical: vertical, emulator: emulator) {
						self = .split(vertical: currentVertical, children: updated)
						return pane
					}
				}
				return nil
			default:
				return nil
			}
		}
	}

	@MainActor private final class TerminalTab {
		let id = UUID()
		let emulator = ItsyTerminalEmulator()
		var root: TerminalPaneNode
		var session: ItsyTerminalSession?
		var currentDirectoryURL: URL
		var hasOSC7CWD = false
		var title: String
		var activePaneID: UUID

		init(currentDirectoryURL: URL, layout: TerminalPaneLayout = .leaf) {
			self.currentDirectoryURL = currentDirectoryURL
			title = currentDirectoryURL.lastPathComponent.isEmpty
				? currentDirectoryURL.path
				: currentDirectoryURL.lastPathComponent
			root = TerminalPaneNode.make(layout: layout, emulator: emulator)
			activePaneID = root.panes.first?.id ?? UUID()
		}

		var panes: [TerminalPane] {
			root.panes
		}

		var activePane: TerminalPane? {
			panes.first { $0.id == activePaneID } ?? panes.first
		}
	}

	private var terminalPanel: NSPanel?
	private var terminalStatusLabel: NSTextField?
	private var terminalContainer: NSView?
	private var terminalTabStack: NSStackView?
	private var searchStack: NSStackView?
	private var searchField: NSSearchField?
	private var searchRegexButton: NSButton?
	private var searchStatusLabel: NSTextField?
	private var tabs: [TerminalTab] = []
	private var selectedTabIndex = 0
	private let settingsProvider: () -> ItsySettings.TerminalSettings
	private let activeDocumentProvider: () -> NSDocument?

	init(
		settingsProvider: @escaping () -> ItsySettings.TerminalSettings,
		activeDocumentProvider: @escaping () -> NSDocument?
	) {
		self.settingsProvider = settingsProvider
		self.activeDocumentProvider = activeDocumentProvider
	}

	@objc func showTerminal(_: Any?) {
		toggleTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	@objc func newTerminalTab(_: Any?) {
		showTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		let cwd = activeTab?.currentDirectoryURL ?? terminalWorkingDirectory()
		appendTab(currentDirectoryURL: cwd, select: true)
		persistTerminalState()
	}

	@objc func splitTerminalHorizontal(_: Any?) {
		splitTerminal(vertical: false)
	}

	@objc func splitTerminalVertical(_: Any?) {
		splitTerminal(vertical: true)
	}

	@objc func showTerminalFind(_: Any?) {
		showTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		searchStack?.isHidden = false
		terminalPanel?.makeFirstResponder(searchField)
		applySearch()
	}

	@objc func findTerminalNext(_: Any?) {
		_ = activeView?.findNextSearchMatch()
		updateSearchStatus()
	}

	@objc func findTerminalPrevious(_: Any?) {
		_ = activeView?.findPreviousSearchMatch()
		updateSearchStatus()
	}

	func openTerminalAtActiveDocumentDirectory(_: Any?) {
		guard let directory = activeDocumentProvider().flatMap(\.fileURL)?.deletingLastPathComponent() else {
			return
		}
		showTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		appendTab(currentDirectoryURL: directory, select: true)
		persistTerminalState()
	}

	func revealTerminalCWDInFileTree(_: Any?) {
		guard let cwd = activeTab?.currentDirectoryURL else {
			return
		}
		ItsyWorkspaceController.revealInFileTree(cwd)
	}

	func terminate() {
		for tab in tabs {
			tab.session?.terminate()
		}
	}

	func applyTerminalSettings(_ settings: ItsySettings.TerminalSettings) {
		for tab in tabs {
			for pane in tab.panes {
				pane.view.applyTerminalSettings(settings)
			}
		}
	}

	private var activeTab: TerminalTab? {
		guard tabs.indices.contains(selectedTabIndex) else {
			return nil
		}
		return tabs[selectedTabIndex]
	}

	private var activeView: ItsyTerminalView? {
		activeTab?.activePane?.view
	}

	private func toggleTerminal(relativeTo hostWindow: NSWindow?) {
		if terminalPanel?.isVisible == true {
			terminalPanel?.close()
			return
		}
		showTerminal(relativeTo: hostWindow)
	}

	private func showTerminal(relativeTo hostWindow: NSWindow?) {
		let panel = makeTerminalPanelIfNeeded()
		restoreTerminalStateIfNeeded()
		if tabs.isEmpty {
			appendTab(currentDirectoryURL: terminalWorkingDirectory(), select: true)
		}
		centerTerminalPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		startTerminalIfNeeded()
		terminalPanel?.makeFirstResponder(activeView)
	}

	private func makeTerminalPanelIfNeeded() -> NSPanel {
		if let panel = terminalPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 760, height: 420),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Terminal")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureTerminalView(contentView)
		terminalPanel = panel
		return panel
	}

	private func configureTerminalView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		let tabStack = NSStackView()
		tabStack.orientation = .horizontal
		tabStack.spacing = 4
		let newTabButton = NSButton(title: L10n.string("+"), target: self, action: #selector(newTerminalTab(_:)))
		let splitHButton = NSButton(
			title: L10n.string("Split H"),
			target: self,
			action: #selector(splitTerminalHorizontal(_:))
		)
		let splitVButton = NSButton(title: L10n.string("Split V"), target: self, action: #selector(splitTerminalVertical(_:)))
		let findButton = NSButton(title: L10n.string("Find"), target: self, action: #selector(showTerminalFind(_:)))
		let clearButton = NSButton(title: L10n.string("Clear"), target: self, action: #selector(clearTerminal(_:)))
		let restartButton = NSButton(title: L10n.string("Restart"), target: self, action: #selector(restartTerminal(_:)))
		let buttonStack = NSStackView(views: [
			newTabButton,
			splitHButton,
			splitVButton,
			findButton,
			clearButton,
			restartButton,
		])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, tabStack, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let searchField = NSSearchField()
		searchField.sendsSearchStringImmediately = true
		searchField.target = self
		searchField.action = #selector(searchFieldChanged(_:))
		let regexButton = NSButton(
			checkboxWithTitle: L10n.string("Regex"),
			target: self,
			action: #selector(searchFieldChanged(_:))
		)
		let previousButton = NSButton(title: L10n.string("Prev"), target: self, action: #selector(findTerminalPrevious(_:)))
		let nextButton = NSButton(title: L10n.string("Next"), target: self, action: #selector(findTerminalNext(_:)))
		let doneButton = NSButton(title: L10n.string("Done"), target: self, action: #selector(closeTerminalFind(_:)))
		let searchStatus = NSTextField(labelWithString: "")
		searchStatus.textColor = .secondaryLabelColor
		let searchStack = NSStackView(views: [searchField, regexButton, previousButton, nextButton, searchStatus, doneButton])
		searchStack.orientation = .horizontal
		searchStack.spacing = 8
		searchStack.isHidden = true
		let terminalContainer = NSView()
		header.translatesAutoresizingMaskIntoConstraints = false
		searchStack.translatesAutoresizingMaskIntoConstraints = false
		terminalContainer.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(header)
		contentView.addSubview(searchStack)
		contentView.addSubview(terminalContainer)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
			searchStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			searchStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			searchStack.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
			terminalContainer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			terminalContainer.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			terminalContainer.topAnchor.constraint(equalTo: searchStack.bottomAnchor, constant: 8),
			terminalContainer.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		terminalStatusLabel = statusLabel
		terminalTabStack = tabStack
		self.searchField = searchField
		searchRegexButton = regexButton
		searchStatusLabel = searchStatus
		self.searchStack = searchStack
		self.terminalContainer = terminalContainer
	}

	private func centerTerminalPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(820, max(560, hostFrame.width - 120))
		let height = min(500, max(320, hostFrame.height - 160))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func appendTab(currentDirectoryURL: URL, layout: TerminalPaneLayout = .leaf, select: Bool,
	                       start: Bool = true)
	{
		let tab = TerminalTab(currentDirectoryURL: currentDirectoryURL, layout: layout)
		configurePanes(for: tab)
		tabs.append(tab)
		if select {
			selectedTabIndex = tabs.count - 1
		}
		rebuildTabBar()
		rebuildTerminalLayout()
		if start {
			startTerminalIfNeeded()
		}
	}

	private func configurePanes(for tab: TerminalTab) {
		for pane in tab.panes {
			pane.view.applyTerminalSettings(settingsProvider())
			pane.view.onInput = { [weak tab] data in
				tab?.session?.send(data)
			}
			pane.view.onResize = { [weak tab] columns, rows in
				tab?.session?.resize(columns: columns, rows: rows)
			}
			pane.view.onFocus = { [weak tab] in
				tab?.activePaneID = pane.id
			}
			pane.view.onCommand = { [weak self] command in
				switch command {
				case .find:
					self?.showTerminalFind(nil)
				case .findNext:
					self?.findTerminalNext(nil)
				case .findPrevious:
					self?.findTerminalPrevious(nil)
				}
				return true
			}
		}
	}

	private func rebuildTabBar() {
		guard let terminalTabStack else {
			return
		}
		for view in terminalTabStack.arrangedSubviews {
			terminalTabStack.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		for (index, tab) in tabs.enumerated() {
			let button = NSButton(title: tab.title, target: self, action: #selector(selectTerminalTab(_:)))
			button.tag = index
			button.bezelStyle = index == selectedTabIndex ? .rounded : .recessed
			button.font = .systemFont(ofSize: 11, weight: index == selectedTabIndex ? .semibold : .regular)
			button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
			terminalTabStack.addArrangedSubview(button)
		}
	}

	@objc private func selectTerminalTab(_ sender: NSButton) {
		guard tabs.indices.contains(sender.tag) else {
			return
		}
		selectedTabIndex = sender.tag
		rebuildTabBar()
		rebuildTerminalLayout()
		startTerminalIfNeeded()
		terminalPanel?.makeFirstResponder(activeView)
		persistTerminalState()
	}

	private func rebuildTerminalLayout() {
		guard let terminalContainer else {
			return
		}
		terminalContainer.subviews.forEach { $0.removeFromSuperview() }
		guard let tab = activeTab else {
			return
		}
		for pane in tab.panes {
			pane.view.removeFromSuperview()
		}
		let view = view(for: tab.root)
		view.translatesAutoresizingMaskIntoConstraints = false
		terminalContainer.addSubview(view)
		NSLayoutConstraint.activate([
			view.leadingAnchor.constraint(equalTo: terminalContainer.leadingAnchor),
			view.trailingAnchor.constraint(equalTo: terminalContainer.trailingAnchor),
			view.topAnchor.constraint(equalTo: terminalContainer.topAnchor),
			view.bottomAnchor.constraint(equalTo: terminalContainer.bottomAnchor),
		])
		updateTerminalStatus()
		applySearch()
	}

	private func view(for node: TerminalPaneNode) -> NSView {
		switch node {
		case let .leaf(pane):
			return pane.view
		case let .split(vertical, children):
			let splitView = NSSplitView()
			splitView.isVertical = vertical
			splitView.dividerStyle = .thin
			for child in children {
				splitView.addArrangedSubview(view(for: child))
			}
			return splitView
		}
	}

	private func splitTerminal(vertical: Bool) {
		showTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		guard let tab = activeTab else {
			return
		}
		let activeID = tab.activePaneID
		guard let newPane = tab.root.splitPane(id: activeID, vertical: vertical, emulator: tab.emulator) else {
			return
		}
		configurePanes(for: tab)
		tab.activePaneID = newPane.id
		rebuildTerminalLayout()
		terminalPanel?.makeFirstResponder(newPane.view)
		persistTerminalState()
	}

	private func startTerminalIfNeeded() {
		guard let tab = activeTab else {
			return
		}
		guard tab.session?.isRunning != true else {
			updateTerminalStatus()
			return
		}
		let size = activeView?.terminalSize ?? (columns: 80, rows: 24)
		let session = ItsyTerminalSession(currentDirectoryURL: tab.currentDirectoryURL)
		session.onOutput = { [weak self, weak tab] data in
			DispatchQueue.main.async {
				guard let self, let tab else {
					return
				}
				self.ingest(data, into: tab)
			}
		}
		session.onExit = { [weak self, weak tab] status in
			DispatchQueue.main.async {
				guard let self, let tab else {
					return
				}
				if let activeTab = self.activeTab, activeTab === tab {
					self.terminalStatusLabel?.textColor = .systemRed
					self.terminalStatusLabel?.stringValue = L10n.string("Shell exited \(status)")
				}
			}
		}
		do {
			try session.start(columns: size.columns, rows: size.rows)
			tab.session = session
			updateTerminalStatus()
		} catch {
			terminalStatusLabel?.textColor = .systemRed
			terminalStatusLabel?.stringValue = String(describing: error)
			tab.activePane?.view.ingest(Data("failed to start shell: \(error)\r\n".utf8))
		}
	}

	private func ingest(_ data: Data, into tab: TerminalTab) {
		tab.emulator.feed(data)
		for pane in tab.panes {
			pane.view.refreshAfterEmulatorUpdate()
		}
		if let cwd = tab.activePane?.view.currentDirectoryURL {
			tab.currentDirectoryURL = cwd
			tab.hasOSC7CWD = true
		}
		if let title = tab.emulator.snapshot(scrollbackOffset: 0).windowTitle, !title.isEmpty {
			tab.title = title
			rebuildTabBar()
		}
		if let activeTab, activeTab === tab {
			updateTerminalStatus()
			applySearch()
		}
		persistTerminalState()
	}

	@objc private func clearTerminal(_: Any?) {
		guard let tab = activeTab else {
			return
		}
		tab.emulator.clearScrollback()
		for pane in tab.panes {
			pane.view.clearScrollback()
		}
	}

	@objc private func restartTerminal(_: Any?) {
		guard let tab = activeTab else {
			return
		}
		tab.session?.terminate()
		tab.session = nil
		tab.emulator.reset()
		for pane in tab.panes {
			pane.view.reset()
		}
		startTerminalIfNeeded()
		terminalPanel?.makeFirstResponder(activeView)
	}

	@objc private func searchFieldChanged(_: Any?) {
		applySearch()
	}

	@objc private func closeTerminalFind(_: Any?) {
		searchStack?.isHidden = true
		searchField?.stringValue = ""
		applySearch()
		terminalPanel?.makeFirstResponder(activeView)
	}

	private func applySearch() {
		let query = searchField?.stringValue ?? ""
		let regex = searchRegexButton?.state == .on
		var count = 0
		for pane in activeTab?.panes ?? [] {
			count = max(count, pane.view.setSearch(query: query, regex: regex))
		}
		searchStatusLabel?.stringValue = query.isEmpty ? "" : L10n.string("\(count) matches")
	}

	private func updateSearchStatus() {
		applySearch()
	}

	private func updateTerminalStatus() {
		terminalStatusLabel?.textColor = .secondaryLabelColor
		let cwd = activeTab?.currentDirectoryURL.path ?? terminalWorkingDirectory().path
		terminalStatusLabel?.stringValue = L10n.string("\(terminalShellName()) · \(cwd)")
	}

	private func terminalWorkingDirectory() -> URL {
		if let root = ItsyWorkspaceController.currentRootURL {
			return root
		}
		if let url = activeDocumentProvider().flatMap(\.fileURL) {
			return url.deletingLastPathComponent()
		}
		return URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
	}

	private func terminalShellName() -> String {
		let shellPath = ProcessInfo.processInfo.environment["SHELL"].flatMap { $0.isEmpty ? nil : $0 } ?? "/bin/zsh"
		return URL(fileURLWithPath: shellPath).lastPathComponent
	}

	private func restoreTerminalStateIfNeeded() {
		guard tabs.isEmpty, let url = terminalStateURL(), let data = try? Data(contentsOf: url),
		      let state = try? JSONDecoder().decode(TerminalWorkspaceState.self, from: data)
		else {
			return
		}
		for tab in state.tabs {
			let cwd = URL(fileURLWithPath: tab.currentDirectoryPath, isDirectory: true)
			let layout = TerminalPaneLayout.decode(tab.layout) ?? .leaf
			appendTab(currentDirectoryURL: cwd, layout: layout, select: false, start: false)
		}
		selectedTabIndex = min(max(0, state.selectedTabIndex), max(0, tabs.count - 1))
		rebuildTabBar()
		rebuildTerminalLayout()
	}

	private func persistTerminalState() {
		guard let url = terminalStateURL(), !tabs.isEmpty else {
			return
		}
		let state = TerminalWorkspaceState(
			selectedTabIndex: selectedTabIndex,
			tabs: tabs.map {
				TerminalTabState(
					currentDirectoryPath: $0.currentDirectoryURL.path,
					layout: $0.root.layout.encoded
				)
			}
		)
		do {
			try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
			let encoder = JSONEncoder()
			encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
			try encoder.encode(state).write(to: url, options: .atomic)
		} catch {
			return
		}
	}

	private func terminalStateURL() -> URL? {
		ItsyWorkspaceController.currentRootURL?
			.appendingPathComponent(".itsy", isDirectory: true)
			.appendingPathComponent("terminal.json")
	}
}
