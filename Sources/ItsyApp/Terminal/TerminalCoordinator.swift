import AppKit
import Foundation
import ItsyConfig
import ItsyEditor

struct TerminalWorkspaceState: Codable, Equatable {
	static let currentSchemaVersion = 2

	var schemaVersion: Int
	var selectedTabIndex: Int
	var tabs: [TerminalTabState]

	init(selectedTabIndex: Int, tabs: [TerminalTabState]) {
		schemaVersion = Self.currentSchemaVersion
		self.selectedTabIndex = selectedTabIndex
		self.tabs = tabs
	}

	private enum CodingKeys: String, CodingKey {
		case schemaVersion
		case selectedTabIndex
		case tabs
	}

	init(from decoder: Decoder) throws {
		let container = try decoder.container(keyedBy: CodingKeys.self)
		guard container.contains(.schemaVersion) else {
			self = try Self.migrating(from: TerminalWorkspaceStateV1(from: decoder))
			return
		}
		schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
		selectedTabIndex = try container.decode(Int.self, forKey: .selectedTabIndex)
		tabs = try container.decode([TerminalTabState].self, forKey: .tabs)
		guard schemaVersion == Self.currentSchemaVersion, isValid else {
			throw DecodingError.dataCorruptedError(
				forKey: .schemaVersion,
				in: container,
				debugDescription: "Unsupported or invalid terminal workspace state."
			)
		}
	}

	private var isValid: Bool {
		guard tabs.allSatisfy(\.isValid) else { return false }
		return tabs.isEmpty ? selectedTabIndex == 0 : tabs.indices.contains(selectedTabIndex)
	}

	private static func migrating(from legacy: TerminalWorkspaceStateV1) throws -> TerminalWorkspaceState {
		let tabs = try legacy.tabs.map(TerminalTabState.migrating)
		let selectedTabIndex = tabs.isEmpty ? 0 : min(max(0, legacy.selectedTabIndex), tabs.count - 1)
		return TerminalWorkspaceState(selectedTabIndex: selectedTabIndex, tabs: tabs)
	}

	static func requiresMigration(for data: Data) -> Bool {
		guard let object = try? JSONSerialization.jsonObject(with: data), let dictionary = object as? [String: Any] else {
			return false
		}
		return dictionary["schemaVersion"] == nil
	}
}

struct TerminalCoordinatorState: Equatable {
	var tabCount: Int
	var selectedTabIndex: Int
	var paneCount: Int
	var activePaneIndex: Int?
	var processIdentifiers: [Int32?]
}

struct TerminalTabState: Codable, Equatable {
	var title: String
	var activePaneIndex: Int
	var rootPane: TerminalPaneState

	init(title: String, activePaneIndex: Int, rootPane: TerminalPaneState) {
		self.title = title
		self.activePaneIndex = activePaneIndex
		self.rootPane = rootPane
	}

	fileprivate var isValid: Bool {
		!title.isEmpty && rootPane.isValid && (0..<rootPane.paneCount).contains(activePaneIndex)
	}

	fileprivate static func migrating(from legacy: TerminalTabStateV1) throws -> TerminalTabState {
		guard TerminalPaneState.isValidDirectoryPath(legacy.currentDirectoryPath),
		      let layout = TerminalPaneLayout.decode(legacy.layout)
		else {
			throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Invalid terminal v1 tab state."))
		}
		var paneIndex = 0
		let rootPane = migratePane(
			layout,
			fallbackDirectoryPath: legacy.currentDirectoryPath,
			paneDirectoryPaths: legacy.paneCurrentDirectoryPaths ?? [],
			paneIndex: &paneIndex
		)
		let title = URL(fileURLWithPath: legacy.currentDirectoryPath).lastPathComponent
		return TerminalTabState(
			title: title.isEmpty ? legacy.currentDirectoryPath : title,
			activePaneIndex: 0,
			rootPane: rootPane
		)
	}

	private static func migratePane(
		_ layout: TerminalPaneLayout,
		fallbackDirectoryPath: String,
		paneDirectoryPaths: [String],
		paneIndex: inout Int
	) -> TerminalPaneState {
		guard let vertical = layout.vertical else {
			let path = paneDirectoryPaths.indices.contains(paneIndex) ? paneDirectoryPaths[paneIndex] : fallbackDirectoryPath
			paneIndex += 1
			return .leaf(currentDirectoryPath: TerminalPaneState.isValidDirectoryPath(path) ? path : fallbackDirectoryPath)
		}
		let children = layout.children.map {
			migratePane(
				$0,
				fallbackDirectoryPath: fallbackDirectoryPath,
				paneDirectoryPaths: paneDirectoryPaths,
				paneIndex: &paneIndex
			)
		}
		switch children.count {
		case 0:
			return .leaf(currentDirectoryPath: fallbackDirectoryPath)
		case 1:
			return children[0]
		default:
			return .split(orientation: vertical ? .vertical : .horizontal, children: children)
		}
	}
}

struct TerminalPaneState: Codable, Equatable {
	enum Kind: String, Codable, Equatable {
		case leaf
		case split
	}

	enum Orientation: String, Codable, Equatable {
		case horizontal
		case vertical
	}

	var kind: Kind
	var currentDirectoryPath: String?
	var orientation: Orientation?
	var children: [TerminalPaneState]?

	static func leaf(currentDirectoryPath: String) -> TerminalPaneState {
		TerminalPaneState(kind: .leaf, currentDirectoryPath: currentDirectoryPath, orientation: nil, children: nil)
	}

	static func split(orientation: Orientation, children: [TerminalPaneState]) -> TerminalPaneState {
		TerminalPaneState(kind: .split, currentDirectoryPath: nil, orientation: orientation, children: children)
	}

	fileprivate static func isValidDirectoryPath(_ path: String) -> Bool {
		path.hasPrefix("/")
	}

	fileprivate var paneCount: Int {
		switch kind {
		case .leaf:
			1
		case .split:
			children?.reduce(0) { $0 + $1.paneCount } ?? 0
		}
	}

	fileprivate var isValid: Bool {
		switch kind {
		case .leaf:
			return currentDirectoryPath.map(Self.isValidDirectoryPath) == true && orientation == nil && (children == nil || children?.isEmpty == true)
		case .split:
			guard currentDirectoryPath == nil, orientation != nil, let children, children.count >= 2 else { return false }
			return children.allSatisfy(\.isValid)
		}
	}
}

private struct TerminalWorkspaceStateV1: Decodable {
	var selectedTabIndex: Int
	var tabs: [TerminalTabStateV1]
}

private struct TerminalTabStateV1: Decodable {
	var currentDirectoryPath: String
	var layout: String
	var paneCurrentDirectoryPaths: [String]?
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
		let emulator = ItsyTerminalEmulator()
		let view: ItsyTerminalView
		var currentDirectoryURL: URL
		var hasOSC7CWD = false

		init(currentDirectoryURL: URL) {
			self.currentDirectoryURL = currentDirectoryURL
			view = ItsyTerminalView(emulator: emulator)
		}
	}

	@MainActor private indirect enum TerminalPaneNode {
		case leaf(TerminalPane)
		case split(vertical: Bool, children: [TerminalPaneNode])

		static func make(layout: TerminalPaneLayout, currentDirectoryURL: URL) -> TerminalPaneNode {
			guard let vertical = layout.vertical else {
				return .leaf(TerminalPane(currentDirectoryURL: currentDirectoryURL))
			}
			let children = layout.children.isEmpty ? [TerminalPaneLayout.leaf] : layout.children
			return .split(
				vertical: vertical,
				children: children.map { make(layout: $0, currentDirectoryURL: currentDirectoryURL) }
			)
		}

		static func make(workspaceState: TerminalPaneState) -> TerminalPaneNode {
			switch workspaceState.kind {
			case .leaf:
				return .leaf(TerminalPane(currentDirectoryURL: URL(fileURLWithPath: workspaceState.currentDirectoryPath!, isDirectory: true)))
			case .split:
				return .split(
					vertical: workspaceState.orientation == .vertical,
					children: workspaceState.children!.map(make(workspaceState:))
				)
			}
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

		var workspaceState: TerminalPaneState {
			switch self {
			case let .leaf(pane):
				return .leaf(currentDirectoryPath: pane.currentDirectoryURL.path)
			case let .split(vertical, children):
				return .split(
					orientation: vertical ? .vertical : .horizontal,
					children: children.map(\.workspaceState)
				)
			}
		}

		func pane(id: UUID) -> TerminalPane? {
			switch self {
			case let .leaf(pane):
				pane.id == id ? pane : nil
			case let .split(_, children):
				children.lazy.compactMap { $0.pane(id: id) }.first
			}
		}

		func removingPane(id: UUID) -> TerminalPaneNode? {
			switch self {
			case let .leaf(pane):
				return pane.id == id ? nil : self
			case let .split(vertical, children):
				let remaining = children.compactMap { $0.removingPane(id: id) }
				if remaining.count == children.count {
					return self
				}
				if remaining.count == 1 {
					return remaining[0]
				}
				return .split(vertical: vertical, children: remaining)
			}
		}

		mutating func splitPane(id: UUID, vertical: Bool, currentDirectoryURL: URL) -> TerminalPane? {
			switch self {
			case let .leaf(pane) where pane.id == id:
				let newPane = TerminalPane(currentDirectoryURL: currentDirectoryURL)
				self = .split(vertical: vertical, children: [.leaf(pane), .leaf(newPane)])
				return newPane
			case let .split(currentVertical, children):
				var updated = children
				for index in updated.indices {
					if let pane = updated[index].splitPane(id: id, vertical: vertical, currentDirectoryURL: currentDirectoryURL) {
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
		var root: TerminalPaneNode
		var title: String
		var activePaneID: UUID

		init(currentDirectoryURL: URL, layout: TerminalPaneLayout = .leaf, paneCurrentDirectoryURLs: [URL] = []) {
			title = currentDirectoryURL.lastPathComponent.isEmpty
				? currentDirectoryURL.path
				: currentDirectoryURL.lastPathComponent
			root = TerminalPaneNode.make(layout: layout, currentDirectoryURL: currentDirectoryURL)
			for (index, pane) in root.panes.enumerated() where paneCurrentDirectoryURLs.indices.contains(index) {
				pane.currentDirectoryURL = paneCurrentDirectoryURLs[index]
			}
			activePaneID = root.panes.first?.id ?? UUID()
		}

		init(workspaceState: TerminalTabState) {
			title = workspaceState.title
			root = TerminalPaneNode.make(workspaceState: workspaceState.rootPane)
			let panes = root.panes
			activePaneID = panes[workspaceState.activePaneIndex].id
		}

		var panes: [TerminalPane] {
			root.panes
		}

		var activePane: TerminalPane? {
			panes.first { $0.id == activePaneID } ?? panes.first
		}

		var currentDirectoryURL: URL {
			activePane?.currentDirectoryURL ?? URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
		}

		var workspaceState: TerminalTabState {
			TerminalTabState(
				title: title,
				activePaneIndex: panes.firstIndex { $0.id == activePaneID } ?? 0,
				rootPane: root.workspaceState
			)
		}
	}

	private var terminalPanel: NSPanel?
	private var terminalContentView: NSView?
	private var terminalEmbeddingConstraints: [NSLayoutConstraint] = []
	private var embeddedTerminalVisible = false
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
	private let editorFontProvider: () -> String
	private let activeDocumentProvider: () -> NSDocument?
	private let sessionLifecycle: TerminalSessionLifecycle
	private let openLocation: (TerminalOpenLocation) -> Void
	private let embeddedHostProvider: () -> NSView?
	private let setEmbeddedTerminalVisible: (NSView, Bool) -> Void
	private weak var presentationHost: NSView?

	init(
		settingsProvider: @escaping () -> ItsySettings.TerminalSettings,
		activeDocumentProvider: @escaping () -> NSDocument?,
		sessionFactory: @escaping TerminalSessionLifecycle.SessionFactory = { ItsyTerminalSession(currentDirectoryURL: $0) },
		openLocation: @escaping (TerminalOpenLocation) -> Void = { NSWorkspace.shared.open($0.url) },
		embeddedHostProvider: @escaping () -> NSView? = { nil },
		setEmbeddedTerminalVisible: @escaping (NSView, Bool) -> Void = { _, _ in },
		editorFontProvider: @escaping () -> String = { ItsySettings.EditorSettings.defaultFont }
	) {
		self.settingsProvider = settingsProvider
		self.editorFontProvider = editorFontProvider
		self.activeDocumentProvider = activeDocumentProvider
		sessionLifecycle = TerminalSessionLifecycle(sessionFactory: sessionFactory)
		self.openLocation = openLocation
		self.embeddedHostProvider = embeddedHostProvider
		self.setEmbeddedTerminalVisible = setEmbeddedTerminalVisible
	}

	var state: TerminalCoordinatorState {
		guard let tab = activeTab else {
			return TerminalCoordinatorState(
				tabCount: tabs.count,
				selectedTabIndex: selectedTabIndex,
				paneCount: 0,
				activePaneIndex: nil,
				processIdentifiers: []
			)
		}
		let panes = tab.panes
		return TerminalCoordinatorState(
			tabCount: tabs.count,
			selectedTabIndex: selectedTabIndex,
			paneCount: panes.count,
			activePaneIndex: panes.firstIndex { $0.id == tab.activePaneID },
			processIdentifiers: panes.map { sessionLifecycle.processIdentifier(for: $0.id) }
		)
	}

	@objc func showTerminal(_: Any?) {
		toggleTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	@objc func newTerminalTab(_: Any?) {
		showTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		let cwd = activeTab?.activePane?.currentDirectoryURL ?? terminalWorkingDirectory()
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
		terminalHostWindow()?.makeFirstResponder(searchField)
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
		sessionLifecycle.terminateAll()
	}

	func applyTerminalSettings(_ settings: ItsySettings.TerminalSettings) {
		let editorFontName = editorFontProvider()
		for tab in tabs {
			for pane in tab.panes {
				pane.view.applyTerminalSettings(settings, inheriting: editorFontName)
			}
		}
		switch settings.presentation {
		case .bottom:
			if terminalPanel?.isVisible == true {
				showEmbeddedTerminal(relativeTo: terminalHostWindow())
			}
		case .window:
			if embeddedTerminalVisible {
				showDetachedTerminal(relativeTo: terminalHostWindow())
			}
		}
	}

	func ensureTerminalVisible() {
		showTerminal(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	func applyTerminalTheme(_ theme: TerminalThemePalette) {
		for tab in tabs {
			for pane in tab.panes {
				pane.view.applyTerminalTheme(theme)
			}
		}
		if let panel = terminalPanel {
			AppThemeApplier.apply(AppTheme.palette, to: panel)
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
		capturePresentationHostIfNeeded()
		switch settingsProvider().presentation {
		case .bottom:
			toggleEmbeddedTerminal(relativeTo: hostWindow)
		case .window:
			if terminalPanel?.isVisible == true {
				terminalPanel?.close()
				return
			}
			showDetachedTerminal(relativeTo: hostWindow)
		}
	}

	private func showTerminal(relativeTo hostWindow: NSWindow?) {
		capturePresentationHostIfNeeded()
		switch settingsProvider().presentation {
		case .bottom:
			showEmbeddedTerminal(relativeTo: hostWindow)
		case .window:
			showDetachedTerminal(relativeTo: hostWindow)
		}
	}

	private func showDetachedTerminal(relativeTo hostWindow: NSWindow?) {
		embeddedTerminalVisible = false
		if let host = presentationHost ?? embeddedHostProvider() {
			presentationHost = host
			setEmbeddedTerminalVisible(host, false)
		}
		let panel = makeTerminalPanelIfNeeded()
		restoreTerminalStateIfNeeded()
		if tabs.isEmpty {
			appendTab(currentDirectoryURL: terminalWorkingDirectory(), select: true)
		}
		centerTerminalPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		startTerminalIfNeeded()
		panel.makeFirstResponder(activeView)
	}

	private func toggleEmbeddedTerminal(relativeTo hostWindow: NSWindow?) {
		guard let host = presentationHost ?? embeddedHostProvider() else {
			showDetachedTerminal(relativeTo: hostWindow)
			return
		}
		presentationHost = host
		if embeddedTerminalVisible, terminalContentView?.superview === host {
			embeddedTerminalVisible = false
			setEmbeddedTerminalVisible(host, false)
			return
		}
		showEmbeddedTerminal(relativeTo: hostWindow)
	}

	private func showEmbeddedTerminal(relativeTo hostWindow: NSWindow?) {
		guard let host = presentationHost ?? embeddedHostProvider() else {
			showDetachedTerminal(relativeTo: hostWindow)
			return
		}
		presentationHost = host
		let contentView = makeTerminalContentViewIfNeeded()
		terminalPanel?.orderOut(nil)
		setEmbeddedTerminalVisible(host, true)
		embedTerminalContent(contentView, in: host)
		embeddedTerminalVisible = true
		restoreTerminalStateIfNeeded()
		if tabs.isEmpty {
			appendTab(currentDirectoryURL: terminalWorkingDirectory(), select: true)
		}
		startTerminalIfNeeded()
		terminalHostWindow()?.makeFirstResponder(activeView)
	}

	private func capturePresentationHostIfNeeded() {
		guard !embeddedTerminalVisible, terminalPanel?.isVisible != true else { return }
		presentationHost = embeddedHostProvider()
	}

	private func makeTerminalPanelIfNeeded() -> NSPanel {
		let panel: NSPanel
		if let terminalPanel {
			panel = terminalPanel
		} else {
			panel = NSPanel(
				contentRect: NSRect(x: 0, y: 0, width: 760, height: 420),
				styleMask: [.titled, .closable, .resizable, .utilityWindow],
				backing: .buffered,
				defer: false
			)
			panel.title = L10n.string("Terminal")
			panel.isReleasedWhenClosed = false
			terminalPanel = panel
		}
		let contentView = makeTerminalContentViewIfNeeded()
		detachEmbeddedTerminalContent()
		contentView.removeFromSuperview()
		panel.contentView = contentView
		return panel
	}

	private func makeTerminalContentViewIfNeeded() -> NSView {
		if let terminalContentView {
			return terminalContentView
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 760, height: 420))
		configureTerminalView(contentView)
		terminalContentView = contentView
		return contentView
	}

	private func embedTerminalContent(_ contentView: NSView, in host: NSView) {
		detachEmbeddedTerminalContent()
		contentView.removeFromSuperview()
		contentView.translatesAutoresizingMaskIntoConstraints = false
		host.addSubview(contentView)
		terminalEmbeddingConstraints = [
			contentView.leadingAnchor.constraint(equalTo: host.leadingAnchor),
			contentView.trailingAnchor.constraint(equalTo: host.trailingAnchor),
			contentView.topAnchor.constraint(equalTo: host.topAnchor),
			contentView.bottomAnchor.constraint(equalTo: host.bottomAnchor),
		]
		NSLayoutConstraint.activate(terminalEmbeddingConstraints)
	}

	private func detachEmbeddedTerminalContent() {
		NSLayoutConstraint.deactivate(terminalEmbeddingConstraints)
		terminalEmbeddingConstraints = []
	}

	private func terminalHostWindow() -> NSWindow? {
		terminalContentView?.window ?? terminalPanel
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
		newTabButton.identifier = NSUserInterfaceItemIdentifier("terminal.new-tab")
		let splitHButton = NSButton(
			title: L10n.string("Split H"),
			target: self,
			action: #selector(splitTerminalHorizontal(_:))
		)
		let splitVButton = NSButton(title: L10n.string("Split V"), target: self, action: #selector(splitTerminalVertical(_:)))
		splitHButton.identifier = NSUserInterfaceItemIdentifier("terminal.split-horizontal")
		splitVButton.identifier = NSUserInterfaceItemIdentifier("terminal.split-vertical")
		let closePaneButton = NSButton(
			title: L10n.string("Close Pane"),
			target: self,
			action: #selector(closeTerminalPane(_:))
		)
		closePaneButton.identifier = NSUserInterfaceItemIdentifier("terminal.close-pane")
		let findButton = NSButton(title: L10n.string("Find"), target: self, action: #selector(showTerminalFind(_:)))
		findButton.identifier = NSUserInterfaceItemIdentifier("terminal.find")
		let clearButton = NSButton(title: L10n.string("Clear"), target: self, action: #selector(clearTerminal(_:)))
		let restartButton = NSButton(title: L10n.string("Restart"), target: self, action: #selector(restartTerminal(_:)))
		restartButton.identifier = NSUserInterfaceItemIdentifier("terminal.restart")
		let buttonStack = NSStackView(views: [
			newTabButton,
			splitHButton,
			splitVButton,
			closePaneButton,
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

	private func appendTab(currentDirectoryURL: URL, layout: TerminalPaneLayout = .leaf,
	                       paneCurrentDirectoryURLs: [URL] = [], select: Bool, start: Bool = true)
	{
		let tab = TerminalTab(
			currentDirectoryURL: currentDirectoryURL,
			layout: layout,
			paneCurrentDirectoryURLs: paneCurrentDirectoryURLs
		)
		appendTab(tab, select: select, start: start)
	}

	private func appendTab(workspaceState: TerminalTabState, select: Bool, start: Bool = true) {
		appendTab(TerminalTab(workspaceState: workspaceState), select: select, start: start)
	}

	private func appendTab(_ tab: TerminalTab, select: Bool, start: Bool) {
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
		let settings = settingsProvider()
		let editorFontName = editorFontProvider()
		for pane in tab.panes {
			pane.view.applyTerminalSettings(settings, inheriting: editorFontName)
			pane.view.applyTerminalTheme(AppTheme.palette.terminal)
			pane.view.onInput = { [weak self, weak pane] data in
				guard let self, let pane else { return }
				self.sessionLifecycle.send(data, to: pane.id)
			}
			pane.view.onResize = { [weak self, weak pane] columns, rows in
				guard let self, let pane else { return }
				self.sessionLifecycle.resize(columns: columns, rows: rows, for: pane.id)
			}
			pane.view.onFocus = { [weak self, weak tab, weak pane] in
				guard let self, let tab, let pane else {
					return
				}
				tab.activePaneID = pane.id
				if activeTab === tab {
					applySearch()
					updateTerminalStatus()
				}
			}
			pane.view.onOpenLocation = { [weak self] location in
				self?.openLocation(location)
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
			button.identifier = NSUserInterfaceItemIdentifier("terminal.tab.\(index)")
			button.bezelStyle = index == selectedTabIndex ? .rounded : .recessed
			button.font = .systemFont(ofSize: 11, weight: index == selectedTabIndex ? .semibold : .regular)
			button.contentTintColor = index == selectedTabIndex ? AppTheme.palette.tabActiveForeground : AppTheme.palette
				.tabInactiveForeground
			button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
			let closeButton = NSButton(title: L10n.string("×"), target: self, action: #selector(closeTerminalTab(_:)))
			closeButton.tag = index
			closeButton.identifier = NSUserInterfaceItemIdentifier("terminal.close-tab.\(index)")
			closeButton.bezelStyle = .inline
			closeButton.toolTip = L10n.string("Close Terminal Tab")
			let tabControls = NSStackView(views: [button, closeButton])
			tabControls.orientation = .horizontal
			tabControls.spacing = 2
			terminalTabStack.addArrangedSubview(tabControls)
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
		terminalHostWindow()?.makeFirstResponder(activeView)
		persistTerminalState()
	}

	@objc private func closeTerminalTab(_ sender: NSButton) {
		guard tabs.indices.contains(sender.tag) else {
			return
		}
		let tab = tabs.remove(at: sender.tag)
		for pane in tab.panes {
			sessionLifecycle.terminate(for: pane.id)
		}
		if tabs.isEmpty {
			selectedTabIndex = 0
		} else {
			selectedTabIndex = min(sender.tag, tabs.count - 1)
		}
		rebuildTabBar()
		rebuildTerminalLayout()
		startTerminalIfNeeded()
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
		let currentDirectoryURL = tab.activePane?.currentDirectoryURL ?? terminalWorkingDirectory()
		guard let newPane = tab.root.splitPane(id: activeID, vertical: vertical, currentDirectoryURL: currentDirectoryURL)
		else {
			return
		}
		configurePanes(for: tab)
		tab.activePaneID = newPane.id
		rebuildTerminalLayout()
		startTerminalIfNeeded(in: newPane, tab: tab)
		terminalHostWindow()?.makeFirstResponder(newPane.view)
		persistTerminalState()
	}

	@objc private func closeTerminalPane(_: Any?) {
		guard let tab = activeTab, tab.panes.count > 1, let pane = tab.root.pane(id: tab.activePaneID),
		      let root = tab.root.removingPane(id: pane.id)
		else {
			return
		}
		sessionLifecycle.terminate(for: pane.id)
		tab.root = root
		tab.activePaneID = root.panes.first?.id ?? UUID()
		configurePanes(for: tab)
		rebuildTerminalLayout()
		startTerminalIfNeeded()
		terminalHostWindow()?.makeFirstResponder(activeView)
		persistTerminalState()
	}

	private func startTerminalIfNeeded() {
		guard let tab = activeTab else {
			return
		}
		for pane in tab.panes {
			startTerminalIfNeeded(in: pane, tab: tab)
		}
		updateTerminalStatus()
	}

	private func startTerminalIfNeeded(in pane: TerminalPane, tab: TerminalTab, restarting: Bool = false) {
		guard restarting || !sessionLifecycle.isRunning(for: pane.id) else {
			return
		}
		let size = pane.view.terminalSize
		let callbacks = TerminalSessionLifecycle.Callbacks(
			onOutput: { [weak self, weak tab, weak pane] data in
				guard let self, let tab, let pane else { return }
				let output = String(decoding: data, as: UTF8.self)
				Task {
					await IntegrationOutputConsole.shared.append(
						service: .terminal,
						identifier: pane.currentDirectoryURL.path,
						kind: .standardOutput,
						text: output,
						errorReference: "terminal://\(pane.currentDirectoryURL.path)"
					)
				}
				self.ingest(data, into: pane, tab: tab)
			},
			onExit: { [weak self, weak tab] status in
				guard let self, let tab else { return }
				if let activeTab = self.activeTab, activeTab === tab {
					self.terminalStatusLabel?.textColor = .systemRed
					self.terminalStatusLabel?.stringValue = L10n.string("Shell exited \(status)")
				}
			},
			onStartFailure: { [weak self, weak pane] error in
				guard let self, let pane else { return }
				self.terminalStatusLabel?.textColor = .systemRed
				self.terminalStatusLabel?.stringValue = String(describing: error)
				pane.view.ingest(Data("failed to start shell: \(error)\r\n".utf8))
			}
		)
		if restarting {
			sessionLifecycle.restart(paneID: pane.id, currentDirectoryURL: pane.currentDirectoryURL, columns: size.columns, rows: size.rows, callbacks: callbacks)
		} else {
			sessionLifecycle.startIfNeeded(paneID: pane.id, currentDirectoryURL: pane.currentDirectoryURL, columns: size.columns, rows: size.rows, callbacks: callbacks)
		}
	}

	private func ingest(_ data: Data, into pane: TerminalPane, tab: TerminalTab) {
		pane.emulator.feed(data)
		pane.view.refreshAfterEmulatorUpdate()
		if let cwd = pane.view.currentDirectoryURL {
			pane.currentDirectoryURL = cwd
			pane.hasOSC7CWD = true
		}
		if tab.activePane === pane, let title = pane.emulator.snapshot(scrollbackOffset: 0).windowTitle, !title.isEmpty {
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
		guard let pane = activeTab?.activePane else {
			return
		}
		pane.emulator.clearScrollback()
		pane.view.clearScrollback()
	}

	@objc private func restartTerminal(_: Any?) {
		guard let tab = activeTab, let pane = tab.activePane else {
			return
		}
		pane.emulator.reset()
		pane.view.reset()
		startTerminalIfNeeded(in: pane, tab: tab, restarting: true)
		terminalHostWindow()?.makeFirstResponder(activeView)
	}

	@objc private func searchFieldChanged(_: Any?) {
		applySearch()
	}

	@objc private func closeTerminalFind(_: Any?) {
		searchStack?.isHidden = true
		searchField?.stringValue = ""
		applySearch()
		terminalHostWindow()?.makeFirstResponder(activeView)
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
		guard tabs.isEmpty, let store = terminalStateStore(), let restoration = store.restore(fallbackDirectoryURL: terminalWorkingDirectory())
		else {
			return
		}
		for tab in restoration.state.tabs {
			appendTab(workspaceState: tab, select: false, start: false)
		}
		selectedTabIndex = min(max(0, restoration.state.selectedTabIndex), max(0, tabs.count - 1))
		rebuildTabBar()
		rebuildTerminalLayout()
		if restoration.requiresPersistence {
			try? store.save(restoration.state)
		}
	}

	private func persistTerminalState() {
		guard let store = terminalStateStore() else { return }
		guard !tabs.isEmpty else {
			store.remove()
			return
		}
		let state = TerminalWorkspaceState(
			selectedTabIndex: selectedTabIndex,
			tabs: tabs.map(\.workspaceState)
		)
		try? store.save(state)
	}

	private func terminalStateStore() -> TerminalWorkspaceStateStore? {
		ItsyWorkspaceController.currentRootURL.map { TerminalWorkspaceStateStore(workspaceURL: $0) }
	}
}
