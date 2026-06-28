import AppKit
import Dispatch

struct CommandPaletteItem {
	let title: String
	let run: () -> Void
}

final class CommandPaletteController: NSObject {
	private let documentController: PicoDocumentController
	private let openFolder: () -> Void
	private let closeDocument: () -> Void
	private var panel: CommandPalettePanel?
	private var contentView: CommandPaletteView?

	init(documentController: PicoDocumentController, openFolder: @escaping () -> Void, closeDocument: @escaping () -> Void) {
		self.documentController = documentController
		self.openFolder = openFolder
		self.closeDocument = closeDocument
	}

	func toggle(relativeTo hostWindow: NSWindow?) {
		if panel?.isVisible == true {
			close()
			return
		}
		show(relativeTo: hostWindow)
	}

	func close() {
		panel?.close()
	}

	private func show(relativeTo hostWindow: NSWindow?) {
		let panel = makePanelIfNeeded()
		contentView?.setItems(commandItems())
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		contentView?.focusInput()
	}

	private func makePanelIfNeeded() -> CommandPalettePanel {
		if let panel {
			return panel
		}
		let size = NSSize(width: 560, height: 280)
		let panel = CommandPalettePanel(
			contentRect: NSRect(origin: .zero, size: size),
			styleMask: [.borderless],
			backing: .buffered,
			defer: false
		)
		let contentView = CommandPaletteView(frame: NSRect(origin: .zero, size: size))
		contentView.onCancel = { [weak self] in self?.close() }
		contentView.onRun = { [weak self] item in
			self?.close()
			item.run()
		}
		panel.contentView = contentView
		panel.paletteDelegate = self
		panel.title = "Command Palette"
		panel.isReleasedWhenClosed = false
		panel.hasShadow = true
		panel.level = .floating
		panel.delegate = self
		self.panel = panel
		self.contentView = contentView
		return panel
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
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

	private func commandItems() -> [CommandPaletteItem] {
		[
			CommandPaletteItem(title: "New File") { [weak documentController] in
				documentController?.newDocument(nil)
			},
			CommandPaletteItem(title: "Open File") { [weak documentController] in
				documentController?.openDocument(nil)
			},
			CommandPaletteItem(title: "Open Folder") { [openFolder] in
				openFolder()
			},
			CommandPaletteItem(title: "Save File") { [weak documentController] in
				let document = NSApp.keyWindow?.windowController?.document as? NSDocument ?? documentController?.currentDocument
				document?.save(nil)
			},
			CommandPaletteItem(title: "Close File") { [closeDocument] in
				closeDocument()
			},
		]
	}
}

extension CommandPaletteController: NSWindowDelegate {
	func windowDidResignKey(_ notification: Notification) {
		guard let panel = notification.object as? NSPanel else {
			return
		}
		DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self, weak panel] in
			guard let panel, panel.isVisible, !panel.isKeyWindow else {
				return
			}
			self?.close()
		}
	}
}

extension CommandPaletteController: CommandPalettePanelDelegate {
	func commandPalettePanelDidCancel(_ panel: CommandPalettePanel) {
		close()
	}
}

protocol CommandPalettePanelDelegate: AnyObject {
	func commandPalettePanelDidCancel(_ panel: CommandPalettePanel)
}

final class CommandPalettePanel: NSPanel {
	weak var paletteDelegate: CommandPalettePanelDelegate?

	override var canBecomeKey: Bool {
		true
	}

	override var canBecomeMain: Bool {
		false
	}

	override func cancelOperation(_ sender: Any?) {
		paletteDelegate?.commandPalettePanelDidCancel(self)
	}

	override func keyDown(with event: NSEvent) {
		if event.keyCode == 53 {
			paletteDelegate?.commandPalettePanelDidCancel(self)
			return
		}
		super.keyDown(with: event)
	}
}

final class CommandPaletteView: NSView {
	var onCancel: (() -> Void)?
	var onRun: ((CommandPaletteItem) -> Void)?
	private let inputField = CommandPaletteInputField(frame: .zero)
	private let tableView = NSTableView()
	private let scrollView = NSScrollView()
	private var items: [CommandPaletteItem] = []
	private var filteredItems: [CommandPaletteItem] = []

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	func setItems(_ items: [CommandPaletteItem]) {
		self.items = items
		inputField.stringValue = ""
		filterItems()
	}

	func focusInput() {
		window?.makeFirstResponder(inputField)
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		layer?.cornerRadius = 8
		layer?.borderWidth = 1
		layer?.borderColor = NSColor.separatorColor.cgColor

		inputField.placeholderString = "Command"
		inputField.font = .systemFont(ofSize: 18)
		inputField.isBordered = false
		inputField.focusRingType = .none
		inputField.backgroundColor = .clear
		inputField.translatesAutoresizingMaskIntoConstraints = false
		inputField.onCancel = { [weak self] in self?.onCancel?() }
		inputField.onConfirm = { [weak self] in self?.runSelectedItem() }
		inputField.onMoveSelection = { [weak self] delta in self?.moveSelection(delta) }
		inputField.delegate = self
		addSubview(inputField)

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
		tableView.doubleAction = #selector(runTableSelection(_:))

		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		addSubview(scrollView)

		NSLayoutConstraint.activate([
			inputField.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 18),
			inputField.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -18),
			inputField.topAnchor.constraint(equalTo: topAnchor, constant: 14),
			inputField.heightAnchor.constraint(equalToConstant: 32),
			scrollView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
			scrollView.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
			scrollView.topAnchor.constraint(equalTo: inputField.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
		])
	}

	@objc private func runTableSelection(_ sender: Any?) {
		runSelectedItem()
	}

	private func filterItems() {
		let query = inputField.stringValue.lowercased()
		filteredItems = query.isEmpty ? items : items.filter { $0.title.lowercased().contains(query) }
		tableView.reloadData()
		if !filteredItems.isEmpty {
			tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func moveSelection(_ delta: Int) {
		guard !filteredItems.isEmpty else {
			return
		}
		let current = tableView.selectedRow >= 0 ? tableView.selectedRow : 0
		let next = min(max(current + delta, 0), filteredItems.count - 1)
		tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
		tableView.scrollRowToVisible(next)
	}

	private func runSelectedItem() {
		guard tableView.selectedRow >= 0, tableView.selectedRow < filteredItems.count else {
			return
		}
		onRun?(filteredItems[tableView.selectedRow])
	}
}

extension CommandPaletteView: NSTextFieldDelegate {
	func controlTextDidChange(_ notification: Notification) {
		filterItems()
	}
}

extension CommandPaletteView: NSTableViewDataSource, NSTableViewDelegate {
	func numberOfRows(in tableView: NSTableView) -> Int {
		filteredItems.count
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		let identifier = NSUserInterfaceItemIdentifier("CommandPaletteCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .systemFont(ofSize: 13)
		textField.lineBreakMode = .byTruncatingTail
		textField.stringValue = filteredItems[row].title
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

final class CommandPaletteInputField: NSTextField {
	var onCancel: (() -> Void)?
	var onConfirm: (() -> Void)?
	var onMoveSelection: ((Int) -> Void)?

	override func keyDown(with event: NSEvent) {
		switch event.keyCode {
		case 53:
			onCancel?()
		case 36:
			onConfirm?()
		case 125:
			onMoveSelection?(1)
		case 126:
			onMoveSelection?(-1)
		default:
			super.keyDown(with: event)
		}
	}
}
