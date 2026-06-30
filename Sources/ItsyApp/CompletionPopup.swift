import AppKit
import ItsyLSP
import ItsyRender

final class CompletionPopupController: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let panel: NSPanel
	private let tableView = NSTableView()
	private let scrollView = NSScrollView()
	private weak var hostWindow: NSWindow?
	private weak var editorView: MetalTextView?
	private var keyMonitor: Any?
	private var allItems: [LSPCompletionItem] = []
	private var filteredItems: [LSPCompletionItem] = []
	private var isIncomplete = false
	private var lastPrefix = ""
	private var requestAgain: (() -> Void)?
	private var acceptItem: ((LSPCompletionItem) -> Void)?
	private var resolveItem: ((LSPCompletionItem, @escaping (LSPCompletionItem) -> Void) -> Void)?
	private var resolvedItemsByKey: [String: LSPCompletionItem] = [:]
	private var pendingResolveKeys = Set<String>()
	private var resolveTimer: Timer?
	private var resolveGeneration = 0
	private var detailPopover: NSPopover?

	override init() {
		panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 340, height: 220),
			styleMask: [.nonactivatingPanel, .fullSizeContentView],
			backing: .buffered,
			defer: false
		)
		super.init()
		configurePanel()
	}

	deinit {
		removeKeyMonitor()
	}

	func show(
		result: LSPCompletionResult,
		relativeTo hostWindow: NSWindow?,
		editorView: MetalTextView,
		requestAgain: @escaping () -> Void,
		resolve: ((LSPCompletionItem, @escaping (LSPCompletionItem) -> Void) -> Void)?,
		accept: @escaping (LSPCompletionItem) -> Void
	) {
		if !panel.isVisible {
			resolvedItemsByKey = [:]
			pendingResolveKeys = []
		}
		self.hostWindow = hostWindow
		self.editorView = editorView
		self.requestAgain = requestAgain
		resolveItem = resolve
		acceptItem = accept
		allItems = result.items
		isIncomplete = result.isIncomplete
		lastPrefix = Self.completionPrefix(in: editorView.editor.text, cursorOffset: editorView.editor.selections.primary.head)
		filterItems(prefix: lastPrefix)
		guard !filteredItems.isEmpty else {
			dismiss()
			return
		}
		reloadAndSelectFirst()
		position(relativeTo: editorView, hostWindow: hostWindow)
		installKeyMonitor()
		if panel.parent !== hostWindow {
			panel.parent?.removeChildWindow(panel)
			hostWindow?.addChildWindow(panel, ordered: .above)
		}
		panel.orderFront(nil)
	}

	func dismiss() {
		removeKeyMonitor()
		cancelResolveTimer()
		closeDetailPopover()
		panel.parent?.removeChildWindow(panel)
		panel.orderOut(nil)
		allItems = []
		filteredItems = []
		requestAgain = nil
		resolveItem = nil
		acceptItem = nil
		resolvedItemsByKey = [:]
		pendingResolveKeys = []
	}

	func numberOfRows(in _: NSTableView) -> Int {
		filteredItems.count
	}

	func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
		guard row >= 0, row < filteredItems.count else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("CompletionCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let label = cell.textField ?? NSTextField(labelWithString: "")
		label.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		label.lineBreakMode = .byTruncatingTail
		label.translatesAutoresizingMaskIntoConstraints = false
		if label.superview == nil {
			cell.addSubview(label)
			NSLayoutConstraint.activate([
				label.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
				label.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
				label.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
			])
			cell.textField = label
		}
		let item = displayItem(for: filteredItems[row])
		label.stringValue = item.detail.map { "\(item.label)  \($0)" } ?? item.label
		cell.toolTip = item.detail
		return cell
	}

	func tableViewSelectionDidChange(_: Notification) {
		scheduleResolveForSelection()
	}

	private func configurePanel() {
		panel.isReleasedWhenClosed = false
		panel.hasShadow = true
		panel.level = .floating
		panel.collectionBehavior = [.transient, .ignoresCycle]
		panel.hidesOnDeactivate = true
		panel.backgroundColor = .clear
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 220))
		contentView.wantsLayer = true
		contentView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		contentView.layer?.cornerRadius = 6
		contentView.layer?.borderWidth = 1
		contentView.layer?.borderColor = NSColor.separatorColor.cgColor
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		scrollView.drawsBackground = false
		scrollView.hasVerticalScroller = true
		scrollView.borderType = .noBorder
		tableView.headerView = nil
		tableView.rowHeight = 22
		tableView.usesAlternatingRowBackgroundColors = false
		tableView.selectionHighlightStyle = .regular
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(acceptDoubleClick(_:))
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("completion"))
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		scrollView.documentView = tableView
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: contentView.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		panel.contentView = contentView
	}

	private func installKeyMonitor() {
		removeKeyMonitor()
		keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
			guard let self, self.panel.isVisible, self.hostWindow?.isKeyWindow == true else {
				return event
			}
			switch event.keyCode {
			case 53:
				self.dismiss()
				return nil
			case 125:
				self.moveSelection(delta: 1)
				return nil
			case 126:
				self.moveSelection(delta: -1)
				return nil
			case 36, 48, 76:
				self.acceptSelected()
				return nil
			default:
				DispatchQueue.main.async { [weak self] in
					self?.refreshAfterEditorInput()
				}
				return event
			}
		}
	}

	private func removeKeyMonitor() {
		if let keyMonitor {
			NSEvent.removeMonitor(keyMonitor)
			self.keyMonitor = nil
		}
	}

	private func refreshAfterEditorInput() {
		guard let editorView else {
			dismiss()
			return
		}
		let prefix = Self.completionPrefix(in: editorView.editor.text, cursorOffset: editorView.editor.selections.primary.head)
		guard prefix != lastPrefix else {
			return
		}
		lastPrefix = prefix
		if isIncomplete {
			requestAgain?()
		} else {
			filterItems(prefix: prefix)
			if filteredItems.isEmpty {
				dismiss()
			} else {
				reloadAndSelectFirst()
			}
		}
	}

	private func filterItems(prefix: String) {
		guard !isIncomplete, !prefix.isEmpty else {
			filteredItems = allItems
			return
		}
		filteredItems = allItems.filter { item in
			comparisonText(for: item).range(of: prefix, options: [.caseInsensitive, .diacriticInsensitive, .anchored]) != nil
		}
	}

	private func reloadAndSelectFirst() {
		tableView.reloadData()
		if !filteredItems.isEmpty {
			tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
			tableView.scrollRowToVisible(0)
			scheduleResolveForSelection()
		}
	}

	private func moveSelection(delta: Int) {
		guard !filteredItems.isEmpty else {
			return
		}
		let row = tableView.selectedRow >= 0 ? tableView.selectedRow : 0
		let next = (row + delta + filteredItems.count) % filteredItems.count
		tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
		tableView.scrollRowToVisible(next)
	}

	private func acceptSelected() {
		let row = tableView.selectedRow
		guard row >= 0, row < filteredItems.count else {
			return
		}
		let item = displayItem(for: filteredItems[row])
		dismiss()
		acceptItem?(item)
	}

	private func scheduleResolveForSelection() {
		cancelResolveTimer()
		let row = tableView.selectedRow
		guard row >= 0, row < filteredItems.count else {
			closeDetailPopover()
			return
		}
		let item = filteredItems[row]
		let key = cacheKey(for: item)
		if let resolved = resolvedItemsByKey[key] {
			showDetail(for: resolved, row: row)
			return
		}
		if item.documentation != nil || item.detail != nil {
			showDetail(for: item, row: row)
		} else {
			closeDetailPopover()
		}
		guard let resolveItem, !pendingResolveKeys.contains(key) else {
			return
		}
		resolveGeneration += 1
		let generation = resolveGeneration
		resolveTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: false) { [weak self] _ in
			guard let self, generation == resolveGeneration, panel.isVisible else {
				return
			}
			pendingResolveKeys.insert(key)
			resolveItem(item) { [weak self] resolved in
				DispatchQueue.main.async { [weak self] in
					guard let self else {
						return
					}
					pendingResolveKeys.remove(key)
					storeResolvedItem(resolved, forKey: key)
				}
			}
		}
	}

	private func storeResolvedItem(_ item: LSPCompletionItem, forKey key: String) {
		guard panel.isVisible else {
			return
		}
		resolvedItemsByKey[key] = item
		if let row = filteredItems.firstIndex(where: { cacheKey(for: $0) == key }) {
			tableView.reloadData(forRowIndexes: IndexSet(integer: row), columnIndexes: IndexSet(integer: 0))
		}
		let row = tableView.selectedRow
		guard row >= 0, row < filteredItems.count, cacheKey(for: filteredItems[row]) == key else {
			return
		}
		showDetail(for: item, row: row)
	}

	private func showDetail(for item: LSPCompletionItem, row: Int) {
		guard panel.isVisible, let hover = completionDetailHover(for: item) else {
			closeDetailPopover()
			return
		}
		let popover = detailPopover ?? NSPopover()
		popover.behavior = .transient
		popover.animates = false
		popover.contentViewController = HoverTooltipViewController(hover: hover)
		detailPopover = popover
		let rect = tableView.rect(ofRow: row)
		if popover.isShown {
			popover.close()
		}
		popover.show(relativeTo: rect, of: tableView, preferredEdge: .maxX)
	}

	private func completionDetailHover(for item: LSPCompletionItem) -> LSPHover? {
		guard let documentation = Self.documentationMarkup(for: item) else {
			guard let detail = item.detail, !detail.isEmpty else {
				return nil
			}
			return LSPHover(contents: .markup(LSPMarkupContent(kind: .markdown, value: "```text\n\(detail)\n```")))
		}
		guard let detail = item.detail, !detail.isEmpty else {
			return LSPHover(contents: .markup(documentation))
		}
		let value = "```text\n\(detail)\n```\n\n\(documentation.value)"
		return LSPHover(contents: .markup(LSPMarkupContent(kind: .markdown, value: value)))
	}

	private func closeDetailPopover() {
		detailPopover?.close()
		detailPopover = nil
	}

	private func cancelResolveTimer() {
		resolveTimer?.invalidate()
		resolveTimer = nil
		resolveGeneration += 1
	}

	private func displayItem(for item: LSPCompletionItem) -> LSPCompletionItem {
		resolvedItemsByKey[cacheKey(for: item)] ?? item
	}

	private func cacheKey(for item: LSPCompletionItem) -> String {
		if let data = item.data {
			return "data:\(Self.stableKey(for: data))"
		}
		return [
			"label:\(Self.cacheComponent(item.label))",
			"detail:\(Self.cacheComponent(item.detail ?? ""))",
			"sort:\(Self.cacheComponent(item.sortText ?? ""))",
			"filter:\(Self.cacheComponent(item.filterText ?? ""))",
		].joined(separator: "|")
	}

	private static func documentationMarkup(for item: LSPCompletionItem) -> LSPMarkupContent? {
		guard let documentation = item.documentation else {
			return nil
		}
		if case let .string(value) = documentation {
			return LSPMarkupContent(kind: .markdown, value: value)
		}
		guard
			let data = try? JSONEncoder().encode(documentation),
			let content = try? JSONDecoder().decode(LSPMarkupContent.self, from: data)
		else {
			return nil
		}
		return content
	}

	private static func stableKey(for value: LSPAny) -> String {
		switch value {
		case .null:
			"n"
		case let .bool(value):
			"b:\(value)"
		case let .int(value):
			"i:\(value)"
		case let .double(value):
			"d:\(value)"
		case let .string(value):
			"s:\(cacheComponent(value))"
		case let .array(items):
			"a:[\(items.map(stableKey).joined(separator: ","))]"
		case let .object(object):
			"o:{\(object.keys.sorted().map { "\(cacheComponent($0))=\(stableKey(for: object[$0]!))" }.joined(separator: ","))}"
		}
	}

	private static func cacheComponent(_ value: String) -> String {
		"\(value.utf8.count):\(value)"
	}

	@objc private func acceptDoubleClick(_: Any?) {
		acceptSelected()
	}

	private func position(relativeTo editorView: MetalTextView, hostWindow: NSWindow?) {
		let rowCount = min(max(filteredItems.count, 1), 10)
		let size = NSSize(width: 340, height: CGFloat(rowCount) * tableView.rowHeight + 2)
		let caret = editorView.firstRect(forCharacterRange: editorView.selectedRange(), actualRange: nil)
		let screen = hostWindow?.screen ?? NSScreen.main
		let visible = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
		let x = min(max(caret.minX, visible.minX), visible.maxX - size.width)
		let belowY = caret.minY - size.height - 3
		let aboveY = caret.maxY + 3
		let y = belowY >= visible.minY ? belowY : min(aboveY, visible.maxY - size.height)
		panel.setFrame(NSRect(origin: NSPoint(x: x, y: y), size: size), display: false)
	}

	private func comparisonText(for item: LSPCompletionItem) -> String {
		item.filterText ?? item.label
	}

	private static func completionPrefix(in text: String, cursorOffset: Int) -> String {
		let cursor = stringIndex(in: text, utf8Offset: cursorOffset)
		var start = cursor
		while start > text.startIndex {
			let previous = text.index(before: start)
			guard isIdentifierCharacter(text[previous]) else {
				break
			}
			start = previous
		}
		return String(text[start ..< cursor])
	}

	private static func isIdentifierCharacter(_ character: Character) -> Bool {
		character == "_" || character.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) }
	}

	private static func stringIndex(in text: String, utf8Offset target: Int) -> String.Index {
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
}
