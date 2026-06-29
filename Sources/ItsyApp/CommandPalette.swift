import AppKit
import ItsyEditor

enum ItsyCommandPaletteBridge {
	static var showExCommand: ((NSWindow?, @escaping (String?) -> Void) -> Bool)?

	static func requestExCommand(relativeTo window: NSWindow?, completion: @escaping (String?) -> Void) -> Bool {
		showExCommand?(window, completion) ?? false
	}
}

final class CommandPaletteView: NSView {
	var onCancel: (() -> Void)?
	var onRun: ((Command) -> Void)?
	var onRunText: ((String) -> Void)?
	private let inputField = ItsyActionTextField(frame: .zero)
	private let tableView = NSTableView()
	private let scrollView = NSScrollView()
	private var items: [Command] = []
	private var filteredItems: [Command] = []
	private var acceptsRawText = false

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	func setItems(_ items: [Command]) {
		acceptsRawText = false
		self.items = items
		inputField.stringValue = ""
		inputField.placeholderString = L10n.string("Command")
		scrollView.isHidden = false
		filterItems()
	}

	func setCommandLine(_ value: String) {
		acceptsRawText = true
		items = []
		filteredItems = []
		inputField.placeholderString = ""
		inputField.stringValue = value
		scrollView.isHidden = true
		tableView.reloadData()
	}

	func focusInput() {
		window?.makeFirstResponder(inputField)
		inputField.currentEditor()?.selectedRange = NSRange(location: inputField.stringValue.count, length: 0)
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
		layer?.cornerRadius = 8
		layer?.borderWidth = 1
		layer?.borderColor = NSColor.separatorColor.cgColor

		inputField.placeholderString = L10n.string("Command")
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
		guard !acceptsRawText else {
			return
		}
		let query = inputField.stringValue.lowercased()
		filteredItems = FuzzyMatcher.ranked(items, query: query, includeUnmatched: false, by: \.title)
		tableView.reloadData()
		if !filteredItems.isEmpty {
			tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func moveSelection(_ delta: Int) {
		guard !acceptsRawText else {
			return
		}
		guard !filteredItems.isEmpty else {
			return
		}
		let current = tableView.selectedRow >= 0 ? tableView.selectedRow : 0
		let next = min(max(current + delta, 0), filteredItems.count - 1)
		tableView.selectRowIndexes(IndexSet(integer: next), byExtendingSelection: false)
		tableView.scrollRowToVisible(next)
	}

	private func runSelectedItem() {
		if acceptsRawText {
			onRunText?(inputField.stringValue)
			return
		}
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
