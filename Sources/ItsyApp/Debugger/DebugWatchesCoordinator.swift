import AppKit
import Foundation
import ItsyDAP
import ItsyDebugger

@MainActor final class DebugWatchesCoordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let store: WatchStore
	private let activeSessionProvider: () -> DebugAppSession?
	private var panel: NSPanel?
	private var contentView: NSView?
	private var statusLabel: NSTextField?
	private var tableView: NSTableView?
	private var items: [DebugWatchItem] = []
	private var generation = 0
	private var stoppedTask: Task<Void, Never>?

	init(store: WatchStore = WatchStore(), activeSessionProvider: @escaping () -> DebugAppSession?) {
		self.store = store
		self.activeSessionProvider = activeSessionProvider
		super.init()
	}

	@objc func showWatches(_ sender: Any?) {
		let panel = makePanelIfNeeded()
		center(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		loadWatches()
	}

	func sessionDidStart(_ session: DebugAppSession) {
		stoppedTask?.cancel()
		stoppedTask = Task { [weak self] in
			let stream = await session.client.on(event: DAPEvent.stopped)
			for await _ in stream {
				Task { @MainActor in
					self?.refreshIfVisible()
				}
			}
		}
		refreshIfVisible()
	}

	func refreshIfVisible() {
		if panel?.isVisible == true || (contentView?.window != nil && contentView?.window !== panel) {
			evaluateWatches()
		}
	}

	func debuggerContentView() -> NSView {
		let contentView = makeContentViewIfNeeded()
		panel?.orderOut(nil)
		return contentView
	}

	func prepareForDebuggerPresentation() {
		panel?.orderOut(nil)
		loadWatches()
	}

	func clear() {
		generation += 1
		stoppedTask?.cancel()
		stoppedTask = nil
		items = []
		tableView?.reloadData()
		setStatus(L10n.string("No active debug session"), isError: true)
	}

	private func makePanelIfNeeded() -> NSPanel {
		let panel: NSPanel
		if let existing = self.panel {
			panel = existing
		} else {
			panel = NSPanel(
				contentRect: NSRect(x: 0, y: 0, width: 620, height: 420),
				styleMask: [.titled, .closable, .resizable, .utilityWindow],
				backing: .buffered,
				defer: false
			)
			panel.title = L10n.string("Watches")
			panel.isReleasedWhenClosed = false
			self.panel = panel
		}
		let contentView = makeContentViewIfNeeded()
		contentView.removeFromSuperview()
		panel.contentView = contentView
		return panel
	}

	private func makeContentViewIfNeeded() -> NSView {
		if let contentView {
			return contentView
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 620, height: 420))
		configureView(contentView)
		self.contentView = contentView
		return contentView
	}

	private func configureView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let addButton = NSButton(title: L10n.string("Add"), target: self, action: #selector(addWatch(_:)))
		let removeButton = NSButton(title: L10n.string("Remove"), target: self, action: #selector(removeSelectedWatch(_:)))
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshWatches(_:)))
		let buttonStack = NSStackView(views: [addButton, removeButton, refreshButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("watch"))
		column.title = L10n.string("Watches")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
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
		self.statusLabel = statusLabel
		self.tableView = tableView
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(660, max(500, hostFrame.width - 140))
		let height = min(480, max(300, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	private func loadWatches() {
		guard let root = workspaceRoot() else {
			setItems([], status: L10n.string("Open a folder first"), isError: true)
			return
		}
		generation += 1
		let currentGeneration = generation
		Task(priority: .userInitiated) { [weak self] in
			do {
				try await self?.store.load()
				let expressions = await self?.store.expressions(for: root) ?? []
				Task { @MainActor in
					guard let self, self.generation == currentGeneration else {
						return
					}
					self.setItems(expressions.map { DebugWatchItem(expression: $0) }, status: L10n.string("\(expressions.count) watches"), isError: false)
					self.evaluateWatches()
				}
			} catch {
				Task { @MainActor in
					guard let self, self.generation == currentGeneration else {
						return
					}
					self.setItems([], status: String(describing: error), isError: true)
				}
			}
		}
	}

	@objc private func addWatch(_ sender: Any?) {
		guard let root = workspaceRoot(),
		      let expression = WatchExpressionPanel.expression()
		else {
			return
		}
		Task(priority: .userInitiated) { [weak self] in
			guard let self else {
				return
			}
			_ = await store.add(expression, for: root)
			do {
				try await store.save()
				let expressions = await store.expressions(for: root)
				Task { @MainActor in
					self.setItems(expressions.map { DebugWatchItem(expression: $0) }, status: L10n.string("\(expressions.count) watches"), isError: false)
					self.evaluateWatches()
				}
			} catch {
				Task { @MainActor in
					self.setStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	@objc private func removeSelectedWatch(_ sender: Any?) {
		guard let root = workspaceRoot(),
		      let tableView,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < items.count
		else {
			return
		}
		let expression = items[tableView.selectedRow].expression
		Task(priority: .userInitiated) { [weak self] in
			guard let self else {
				return
			}
			await store.remove(expression, for: root)
			do {
				try await store.save()
				let expressions = await store.expressions(for: root)
				Task { @MainActor in
					self.setItems(expressions.map { DebugWatchItem(expression: $0) }, status: L10n.string("\(expressions.count) watches"), isError: false)
					self.evaluateWatches()
				}
			} catch {
				Task { @MainActor in
					self.setStatus(String(describing: error), isError: true)
				}
			}
		}
	}

	@objc private func refreshWatches(_ sender: Any?) {
		evaluateWatches()
	}

	private func evaluateWatches() {
		guard !items.isEmpty else {
			tableView?.reloadData()
			return
		}
		guard let session = activeSessionProvider() else {
			setStatus(L10n.string("No active debug session"), isError: true)
			return
		}
		let expressions = items.map(\.expression)
		generation += 1
		let currentGeneration = generation
		setStatus(L10n.string("Evaluating"), isError: false)
		Task(priority: .userInitiated) { [weak self] in
			guard let frameID = await session.debugSession.focusedFrameID else {
				Task { @MainActor in
					guard let self, self.generation == currentGeneration else {
						return
					}
					self.setStatus(L10n.string("Select a stack frame first"), isError: true)
				}
				return
			}
			var evaluated: [DebugWatchItem] = []
			for expression in expressions {
				do {
					let value = try await session.debugSession.evaluate(expression: expression, frameID: frameID, context: "watch")
					evaluated.append(DebugWatchItem(expression: expression, value: value.result, type: value.type, isError: false))
				} catch {
					evaluated.append(DebugWatchItem(expression: expression, value: String(describing: error), type: nil, isError: true))
				}
			}
			Task { @MainActor in
				guard let self, self.generation == currentGeneration, self.activeSessionProvider() === session else {
					return
				}
				self.items = evaluated
				self.tableView?.reloadData()
				self.setStatus(L10n.string("\(evaluated.count) watches"), isError: evaluated.contains { $0.isError })
			}
		}
	}

	private func setItems(_ items: [DebugWatchItem], status: String, isError: Bool) {
		self.items = items
		tableView?.reloadData()
		if !items.isEmpty {
			tableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		setStatus(status, isError: isError)
	}

	private func setStatus(_ status: String, isError: Bool) {
		statusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		statusLabel?.stringValue = status
	}

	private func workspaceRoot() -> URL? {
		ItsyWorkspaceController.currentRootURL?.standardizedFileURL
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		tableView === self.tableView ? items.count : 0
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard tableView === self.tableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("WatchCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textField.lineBreakMode = .byTruncatingMiddle
		textField.textColor = items[row].isError ? .systemRed : .labelColor
		textField.stringValue = items[row].title
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

private struct DebugWatchItem {
	var expression: String
	var value: String?
	var type: String?
	var isError = false

	var title: String {
		guard let value else {
			return expression
		}
		let typeSuffix = type.map { " : \($0)" } ?? ""
		return "\(expression) = \(value)\(typeSuffix)"
	}
}

@MainActor private enum WatchExpressionPanel {
	static func expression() -> String? {
		let field = NSTextField(string: "")
		field.frame = NSRect(x: 0, y: 0, width: 320, height: 24)
		let alert = NSAlert()
		alert.messageText = L10n.string("Watch Expression")
		alert.accessoryView = field
		alert.addButton(withTitle: L10n.string("Add"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			return nil
		}
		let expression = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		return expression.isEmpty ? nil : expression
	}
}
