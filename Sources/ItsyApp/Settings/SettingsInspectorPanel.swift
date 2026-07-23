import AppKit
import ItsyConfig

@MainActor final class SettingsInspectorPanel: NSObject, NSWindowDelegate, NSSearchFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
	struct Item: Equatable {
		let entry: ItsySettingsCatalog.Entry
		let effectiveValue: String
		let sourceLabel: String
		let sourceURL: URL?
	}

	struct UpdateResult {
		let items: [Item]
		let validationError: String?
	}

	private let resetEntry: (String) -> [Item]
	private let updateEntry: (String, String) -> UpdateResult
	private var panel: NSPanel?
	private var searchField: NSTextField?
	private var tableView: NSTableView?
	private var detailLabel: NSTextField?
	private var valueField: NSTextField?
	private var applyButton: NSButton?
	private var resetButton: NSButton?
	private var sourceButton: NSButton?
	private var items: [Item] = []
	private var filteredItems: [Item] = []
	private var validationError: String?

	init(resetEntry: @escaping (String) -> [Item], updateEntry: @escaping (String, String) -> UpdateResult) {
		self.resetEntry = resetEntry
		self.updateEntry = updateEntry
		super.init()
	}

	func show(relativeTo window: NSWindow?) {
		let panel = makePanelIfNeeded()
		let hostFrame = window?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1_024, height: 768)
		panel.setFrame(NSRect(x: hostFrame.midX - 430, y: hostFrame.midY - 250, width: 860, height: 500), display: true)
		panel.makeKeyAndOrderFront(nil)
		NSApp.activate(ignoringOtherApps: true)
		panel.makeFirstResponder(searchField)
	}

	func update(items: [Item]) {
		self.items = items
		validationError = nil
		applyFilter()
	}

	func makeContentViewForTesting() -> NSView {
		makePanelIfNeeded().contentView ?? NSView()
	}

	static func filtered(items: [Item], query: String) -> [Item] {
		let matchingKeys = Set(ItsySettingsCatalog.matching(query).map(\.key))
		return items.filter { matchingKeys.contains($0.entry.key) }
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 860, height: 500),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Settings Catalog")
		panel.isReleasedWhenClosed = false
		panel.delegate = self
		let contentView = NSView(frame: panel.contentView?.bounds ?? .zero)
		contentView.translatesAutoresizingMaskIntoConstraints = false
		panel.contentView = contentView

		let searchField = NSSearchField(frame: .zero)
		searchField.placeholderString = L10n.string("Search setting name, key, or description")
		searchField.setAccessibilityLabel(L10n.string("Settings catalog search"))
		searchField.delegate = self
		searchField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(searchField)

		let tableView = NSTableView()
		tableView.headerView = NSTableHeaderView()
		tableView.rowHeight = 26
		tableView.usesAlternatingRowBackgroundColors = true
		tableView.setAccessibilityLabel(L10n.string("Settings catalog results"))
		tableView.dataSource = self
		tableView.delegate = self
		for (identifier, title, width) in [
			("key", "Setting", 250.0),
			("value", "Effective Value", 180.0),
			("source", "Source", 160.0),
			("reload", "Apply", 110.0),
		] {
			let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(identifier))
			column.title = title
			column.width = width
			column.minWidth = 90
			tableView.addTableColumn(column)
		}
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)

		let detailLabel = NSTextField(wrappingLabelWithString: "")
		detailLabel.font = .systemFont(ofSize: 12)
		detailLabel.textColor = .secondaryLabelColor
		detailLabel.maximumNumberOfLines = 2
		detailLabel.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(detailLabel)

		let valueField = NSTextField(frame: .zero)
		valueField.placeholderString = L10n.string("Value")
		valueField.setAccessibilityLabel(L10n.string("Selected setting value"))
		valueField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(valueField)

		let applyButton = NSButton(title: L10n.string("Apply"), target: self, action: #selector(applySelected(_:)))
		applyButton.bezelStyle = .rounded
		applyButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(applyButton)

		let resetButton = NSButton(title: L10n.string("Reset Selected"), target: self, action: #selector(resetSelected(_:)))
		resetButton.bezelStyle = .rounded
		resetButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(resetButton)

		let sourceButton = NSButton(title: L10n.string("Open Source"), target: self, action: #selector(openSource(_:)))
		sourceButton.bezelStyle = .rounded
		sourceButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(sourceButton)

		NSLayoutConstraint.activate([
			searchField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
			searchField.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
			searchField.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
			scrollView.topAnchor.constraint(equalTo: searchField.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: detailLabel.topAnchor, constant: -10),
			detailLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
			detailLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
			valueField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
			valueField.topAnchor.constraint(equalTo: detailLabel.bottomAnchor, constant: 10),
			valueField.widthAnchor.constraint(equalToConstant: 250),
			applyButton.leadingAnchor.constraint(equalTo: valueField.trailingAnchor, constant: 8),
			applyButton.centerYAnchor.constraint(equalTo: valueField.centerYAnchor),
			resetButton.leadingAnchor.constraint(equalTo: applyButton.trailingAnchor, constant: 8),
			resetButton.centerYAnchor.constraint(equalTo: valueField.centerYAnchor),
			sourceButton.leadingAnchor.constraint(equalTo: resetButton.trailingAnchor, constant: 8),
			sourceButton.centerYAnchor.constraint(equalTo: valueField.centerYAnchor),
			valueField.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
		])
		self.panel = panel
		self.searchField = searchField
		self.tableView = tableView
		self.detailLabel = detailLabel
		self.valueField = valueField
		self.applyButton = applyButton
		self.resetButton = resetButton
		self.sourceButton = sourceButton
		applyFilter()
		return panel
	}

	private func applyFilter() {
		filteredItems = Self.filtered(items: items, query: searchField?.stringValue ?? "")
		tableView?.reloadData()
		if filteredItems.isEmpty {
			tableView?.deselectAll(nil)
		} else if !(tableView.map { filteredItems.indices.contains($0.selectedRow) } ?? false) {
			tableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		updateSelectionDetails()
	}

	private var selectedItem: Item? {
		guard let tableView, filteredItems.indices.contains(tableView.selectedRow) else {
			return nil
		}
		return filteredItems[tableView.selectedRow]
	}

	private func updateSelectionDetails() {
		guard let item = selectedItem else {
			detailLabel?.stringValue = L10n.string("Every setting is validated when it is loaded. Select a setting to inspect its effective value and source.")
			resetButton?.isEnabled = false
			valueField?.isEnabled = false
			applyButton?.isEnabled = false
			sourceButton?.isEnabled = false
			return
		}
		let overrideText = item.entry.isLanguageTemplate ? L10n.string(" Use a concrete language ID in settings.toml.") : ""
		let errorText = validationError.map { " Validation: \($0)" } ?? ""
		detailLabel?.stringValue = "\(item.entry.description) \(item.entry.reloadBehavior.displayName).\(overrideText)\(errorText)"
		detailLabel?.textColor = validationError == nil ? .secondaryLabelColor : .systemRed
		resetButton?.isEnabled = item.entry.isResettable && !item.entry.isLanguageTemplate
		valueField?.stringValue = item.effectiveValue
		valueField?.isEnabled = item.entry.isResettable && !item.entry.isLanguageTemplate
		applyButton?.isEnabled = item.entry.isResettable && !item.entry.isLanguageTemplate
		sourceButton?.isEnabled = item.sourceURL != nil
		sourceButton?.title = item.sourceURL == nil ? L10n.string("Built-in Default") : L10n.string("Open Source")
	}

	func numberOfRows(in _: NSTableView) -> Int {
		filteredItems.count
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard let tableColumn, filteredItems.indices.contains(row) else {
			return nil
		}
		let item = filteredItems[row]
		let value: String
		switch tableColumn.identifier.rawValue {
		case "key": value = item.entry.key
		case "value": value = item.effectiveValue
		case "source": value = item.sourceLabel
		case "reload": value = item.entry.reloadBehavior.displayName
		default: return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("SettingsInspectorCell-\(tableColumn.identifier.rawValue)")
		let cell = (tableView.makeView(withIdentifier: identifier, owner: self) as? NSTextField) ?? NSTextField(labelWithString: "")
		cell.identifier = identifier
		cell.stringValue = value
		cell.lineBreakMode = NSLineBreakMode.byTruncatingMiddle
		cell.toolTip = value
		return cell
	}

	func controlTextDidChange(_: Notification) {
		validationError = nil
		applyFilter()
	}

	func tableViewSelectionDidChange(_: Notification) {
		updateSelectionDetails()
	}

	@objc private func resetSelected(_: Any?) {
		guard let item = selectedItem else {
			return
		}
		items = resetEntry(item.entry.key)
		validationError = nil
		applyFilter()
	}

	@objc private func applySelected(_: Any?) {
		guard let item = selectedItem, let value = valueField?.stringValue else {
			return
		}
		let result = updateEntry(item.entry.key, value)
		guard let error = result.validationError else {
			items = result.items
			validationError = nil
			applyFilter()
			return
		}
		validationError = error
		updateSelectionDetails()
	}

	@objc private func openSource(_: Any?) {
		guard let sourceURL = selectedItem?.sourceURL else {
			return
		}
		NSWorkspace.shared.open(sourceURL)
	}
}
