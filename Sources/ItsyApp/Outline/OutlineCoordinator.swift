import AppKit
import Foundation
import ItsyEditor

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

@MainActor final class OutlineCoordinator: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate {
	private let documentController: ItsyDocumentController
	private let activeDocumentProvider: () -> NSDocument?
	private var outlinePanel: NSPanel?
	private var outlineStatusLabel: NSTextField?
	private var outlineOutlineView: NSOutlineView?
	private var outlineKindNodes: [OutlineKindNode] = []
	private var outlineWindowObserverInstalled = false
	private var outlineCollapseStateByURL: [String: Set<String>] = OutlineCollapseStore.load()
	private var outlineActiveURLKey: String?
	private var outlineSuppressPersist = false

	init(documentController: ItsyDocumentController, activeDocumentProvider: @escaping () -> NSDocument?) {
		self.documentController = documentController
		self.activeDocumentProvider = activeDocumentProvider
	}

	@objc func showOutline(_ sender: Any?) {
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
		guard !outlineWindowObserverInstalled else {
			return
		}
		outlineWindowObserverInstalled = true
		NotificationCenter.default.addObserver(self, selector: #selector(outlineWindowDidBecomeKey(_:)), name: NSWindow.didBecomeKeyNotification, object: nil)
	}

	@objc private func outlineWindowDidBecomeKey(_ notification: Notification) {
		guard
			outlinePanel?.isVisible == true,
			let window = notification.object as? NSWindow,
			window !== outlinePanel
		else {
			return
		}
		refreshOutline()
	}

	@objc private func refreshOutlineAction(_ sender: Any?) {
		refreshOutline()
	}

	private func refreshOutline() {
		guard
			let url = (activeDocumentProvider() as? ItsyDocument)?.fileURL,
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

	private func recordOutlineCollapseChange() {
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
