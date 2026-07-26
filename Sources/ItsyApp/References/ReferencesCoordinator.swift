import AppKit
import ItsyLSP
import ItsyWorkbenchLayout

struct LSPReferenceEntry: Equatable {
	let url: URL
	let path: String
	let line: Int
	let column: Int
	let preview: String
}

struct LSPReferencesSnapshot: Equatable {
	let entries: [LSPReferenceEntry]

	init(locations: [LSPLocation], rootURL: URL?, currentFileURL: URL, currentText: String) {
		var textCache: [URL: String] = [currentFileURL.standardizedFileURL: currentText]
		entries = locations.compactMap { location in
			guard let url = URL(string: location.uri), url.isFileURL else {
				return nil
			}
			let fileURL = url.standardizedFileURL
			let text = Self.text(for: fileURL, cache: &textCache)
			let line = max(1, location.range.start.line + 1)
			let column = max(1, location.range.start.character + 1)
			return LSPReferenceEntry(
				url: fileURL,
				path: Self.displayPath(for: fileURL, rootURL: rootURL),
				line: line,
				column: column,
				preview: Self.previewLine(in: text, zeroBasedLine: line - 1)
			)
		}.sorted {
			($0.path, $0.line, $0.column) < ($1.path, $1.line, $1.column)
		}
	}

	private static func text(for url: URL, cache: inout [URL: String]) -> String {
		if let text = cache[url] {
			return text
		}
		let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
		cache[url] = text
		return text
	}

	private static func displayPath(for url: URL, rootURL: URL?) -> String {
		guard let rootURL else {
			return url.path
		}
		let rootPath = rootURL.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		let path = url.standardizedFileURL.path
		guard path.hasPrefix(prefix) else {
			return path
		}
		return String(path.dropFirst(prefix.count))
	}

	private static func previewLine(in text: String, zeroBasedLine: Int) -> String {
		let lines = text.components(separatedBy: .newlines)
		guard zeroBasedLine >= 0, zeroBasedLine < lines.count else {
			return ""
		}
		return lines[zeroBasedLine].trimmingCharacters(in: .whitespaces)
	}
}

@MainActor final class ReferencesCoordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private enum Row {
		case file(path: String, count: Int)
		case reference(LSPReferenceEntry)
	}

	private let surface: WorkbenchPanelSurface
	private var panel: NSPanel { surface.panel }
	private let modeControl = NSSegmentedControl(labels: [L10n.string("References"), L10n.string("Call Hierarchy")], trackingMode: .selectOne, target: nil, action: nil)
	private let statusLabel = NSTextField(labelWithString: "")
	private let tableView = NSTableView()
	private var rows: [Row] = []
	private var entries: [LSPReferenceEntry] = []
	private var openReference: ((LSPReferenceEntry) -> Void)?

	override init() {
		surface = WorkbenchPanelSurface(id: .references, title: L10n.string("References"), size: NSSize(width: 760, height: 420))
		super.init()
		configurePanel(surface.contentView)
	}

	func showLoading(relativeTo hostWindow: NSWindow?) {
		showLoading(title: L10n.string("Finding references..."), selectedSegment: 0, relativeTo: hostWindow)
	}

	func showCallHierarchyLoading(relativeTo hostWindow: NSWindow?) {
		showLoading(title: L10n.string("Finding call hierarchy..."), selectedSegment: 1, relativeTo: hostWindow)
	}

	private func showLoading(title: String, selectedSegment: Int, relativeTo hostWindow: NSWindow?) {
		rows = []
		entries = []
		tableView.reloadData()
		modeControl.selectedSegment = selectedSegment
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.stringValue = title
		show(relativeTo: hostWindow)
	}

	func show(
		snapshot: LSPReferencesSnapshot,
		relativeTo hostWindow: NSWindow?,
		open: @escaping (LSPReferenceEntry) -> Void
	) {
		entries = snapshot.entries
		rows = Self.rows(for: snapshot.entries)
		openReference = open
		tableView.reloadData()
		modeControl.selectedSegment = 0
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.stringValue = L10n.string("\(entries.count) references in \(Set(entries.map(\.path)).count) files")
		selectFirstReference()
		show(relativeTo: hostWindow)
	}

	func showCallHierarchy(
		snapshot: LSPReferencesSnapshot,
		relativeTo hostWindow: NSWindow?,
		open: @escaping (LSPReferenceEntry) -> Void
	) {
		entries = snapshot.entries
		rows = Self.rows(for: snapshot.entries)
		openReference = open
		tableView.reloadData()
		modeControl.selectedSegment = 1
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.stringValue = L10n.string("\(entries.count) call sites in \(Set(entries.map(\.path)).count) files")
		selectFirstReference()
		show(relativeTo: hostWindow)
	}

	private func selectFirstReference() {
		if let firstReferenceRow = rows.firstIndex(where: {
			if case .reference = $0 { return true }
			return false
		}) {
			tableView.selectRowIndexes(IndexSet(integer: firstReferenceRow), byExtendingSelection: false)
			tableView.scrollRowToVisible(firstReferenceRow)
		}
	}

	func show(error: Error, relativeTo hostWindow: NSWindow?) {
		statusLabel.textColor = .systemRed
		statusLabel.stringValue = String(describing: error)
		show(relativeTo: hostWindow)
	}

	func numberOfRows(in _: NSTableView) -> Int {
		rows.count
	}

	func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
		guard row >= 0, row < rows.count else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("ReferenceCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.lineBreakMode = .byTruncatingTail
		switch rows[row] {
		case let .file(path, count):
			textField.font = .boldSystemFont(ofSize: 12)
			textField.stringValue = "\(path) · \(count)"
		case let .reference(entry):
			textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
			textField.stringValue = "  \(entry.line):\(entry.column)  \(entry.preview)"
		}
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

	@objc private func openSelectedReference(_: Any?) {
		let row = tableView.selectedRow
		guard row >= 0, row < rows.count, case let .reference(entry) = rows[row] else {
			return
		}
		openReference?(entry)
	}

	private func configurePanel(_ contentView: NSView) {
		modeControl.selectedSegment = 0
		modeControl.setEnabled(false, forSegment: 0)
		modeControl.setEnabled(false, forSegment: 1)
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("reference"))
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedReference(_:))
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		modeControl.translatesAutoresizingMaskIntoConstraints = false
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(modeControl)
		contentView.addSubview(statusLabel)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			modeControl.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			modeControl.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
			statusLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			statusLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			statusLabel.topAnchor.constraint(equalTo: modeControl.bottomAnchor, constant: 8),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
	}

	private func show(relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(780, max(560, hostFrame.width - 100))
		let height = min(460, max(300, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
		surface.show()
	}

	private static func rows(for entries: [LSPReferenceEntry]) -> [Row] {
		let grouped = Dictionary(grouping: entries, by: \.path)
		return grouped.keys.sorted().flatMap { path -> [Row] in
			let references = grouped[path] ?? []
			return [.file(path: path, count: references.count)] + references.map(Row.reference)
		}
	}
}
