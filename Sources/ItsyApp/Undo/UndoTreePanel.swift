import AppKit
import ItsyEditor

@MainActor final class UndoTreePanelController: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private struct Row {
		var node: UndoTreeNode
		var depth: Int
		var title: String
	}

	private var panel: NSPanel?
	private let tableView = NSTableView()
	private var rows: [Row] = []
	private var currentID: Int?
	private var applyingSelection = false
	var jumpRequested: ((Int) -> Void)?

	func toggle(relativeTo hostWindow: NSWindow?, tree: UndoTree) {
		if panel?.isVisible == true {
			panel?.close()
			return
		}
		show(relativeTo: hostWindow, tree: tree)
	}

	func update(tree: UndoTree) {
		rows = Self.rows(for: tree)
		currentID = tree.currentID
		tableView.reloadData()
		selectCurrentRow()
	}

	private func show(relativeTo hostWindow: NSWindow?, tree: UndoTree) {
		let panel = makePanelIfNeeded()
		update(tree: tree)
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 360, height: 420),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Undo Tree")
		panel.isReleasedWhenClosed = false
		let scrollView = NSScrollView(frame: panel.contentRect(forFrameRect: panel.frame))
		scrollView.autoresizingMask = [.width, .height]
		scrollView.hasVerticalScroller = true
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("undo"))
		column.title = L10n.string("History")
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.delegate = self
		tableView.dataSource = self
		tableView.target = self
		tableView.doubleAction = #selector(jumpToSelectedRow(_:))
		tableView.rowHeight = 24
		scrollView.documentView = tableView
		panel.contentView = scrollView
		self.panel = panel
		return panel
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let base = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1200, height: 800)
		let size = panel.frame.size
		let origin = NSPoint(x: base.midX - size.width / 2, y: base.midY - size.height / 2)
		panel.setFrame(NSRect(origin: origin, size: size), display: true)
	}

	private static func rows(for tree: UndoTree) -> [Row] {
		func append(nodeID: Int, depth: Int, tree: UndoTree, rows: inout [Row]) {
			guard let node = tree.node(id: nodeID) else {
				return
			}
			let marker = node.id == tree.currentID ? "*" : " "
			let prefix = String(repeating: "  ", count: depth)
			rows.append(Row(node: node, depth: depth, title: "\(prefix)\(marker) \(node.summary)"))
			for childID in node.childIDs {
				append(nodeID: childID, depth: depth + 1, tree: tree, rows: &rows)
			}
		}
		var rows: [Row] = []
		append(nodeID: tree.rootID, depth: 0, tree: tree, rows: &rows)
		return rows
	}

	private func selectCurrentRow() {
		guard let currentID, let row = rows.firstIndex(where: { $0.node.id == currentID }) else {
			return
		}
		applyingSelection = true
		tableView.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
		tableView.scrollRowToVisible(row)
		applyingSelection = false
	}

	func numberOfRows(in _: NSTableView) -> Int {
		rows.count
	}

	func tableView(_ tableView: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
		let identifier = NSUserInterfaceItemIdentifier("UndoTreeCell")
		let textField = (tableView.makeView(withIdentifier: identifier, owner: self) as? NSTextField) ??
			NSTextField(labelWithString: "")
		textField.identifier = identifier
		textField.lineBreakMode = .byTruncatingTail
		textField.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
		textField.stringValue = rows[row].title
		return textField
	}

	func tableViewSelectionDidChange(_: Notification) {
		guard !applyingSelection, tableView.selectedRow >= 0, tableView.selectedRow < rows.count else {
			return
		}
		jumpRequested?(rows[tableView.selectedRow].node.id)
	}

	@objc private func jumpToSelectedRow(_: Any?) {
		guard tableView.selectedRow >= 0, tableView.selectedRow < rows.count else {
			return
		}
		jumpRequested?(rows[tableView.selectedRow].node.id)
	}
}
