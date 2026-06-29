import AppKit
import Dispatch
import ItsyEditor

final class ProjectFindController {
	private let workspaceURL: () -> URL?
	private let openFile: (URL) -> Void
	private var panel: ProjectFindPanel?
	private var contentView: ProjectFindView?
	private var searchGeneration = 0

	init(workspaceURL: @escaping () -> URL?, openFile: @escaping (URL) -> Void) {
		self.workspaceURL = workspaceURL
		self.openFile = openFile
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
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		contentView?.focusInput()
		updateStatusForCurrentWorkspace()
	}

	private func makePanelIfNeeded() -> ProjectFindPanel {
		if let panel {
			return panel
		}
		let size = NSSize(width: 760, height: 380)
		let panel = ProjectFindPanel(
			contentRect: NSRect(origin: .zero, size: size),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		let contentView = ProjectFindView(frame: NSRect(origin: .zero, size: size))
		contentView.onCancel = { [weak self] in self?.close() }
		contentView.onSearch = { [weak self] query in self?.search(query: query) }
		contentView.onOpenMatch = { [weak self] match in self?.openFile(match.url) }
		panel.contentView = contentView
		panel.onCancel = { [weak self] in self?.close() }
		panel.title = L10n.string("Find in Project")
		panel.isReleasedWhenClosed = false
		panel.minSize = NSSize(width: 520, height: 260)
		self.panel = panel
		self.contentView = contentView
		return panel
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(760, max(520, hostFrame.width - 80))
		let height = min(420, max(260, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func updateStatusForCurrentWorkspace() {
		guard let root = workspaceURL() else {
			contentView?.setResults([])
			contentView?.setStatus(L10n.string("Open a folder first"))
			return
		}
		contentView?.setResults([])
		contentView?.setStatus(root.path)
	}

	private func search(query: String) {
		searchGeneration += 1
		let generation = searchGeneration
		guard !query.isEmpty else {
			updateStatusForCurrentWorkspace()
			return
		}
		guard let root = workspaceURL() else {
			contentView?.setResults([])
			contentView?.setStatus(L10n.string("Open a folder first"))
			return
		}
		contentView?.setStatus(L10n.string("Searching..."))
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			let matches = ProjectFind.search(root: root, options: ProjectFindOptions(query: query))
			DispatchQueue.main.async { [weak self] in
				guard let self, self.searchGeneration == generation else {
					return
				}
				self.contentView?.setResults(matches)
				self.contentView?.setStatus(L10n.string("\(matches.count) matches"))
			}
		}
	}
}

final class ProjectFindPanel: NSPanel {
	var onCancel: (() -> Void)?

	override var canBecomeKey: Bool {
		true
	}

	override func cancelOperation(_ sender: Any?) {
		onCancel?()
	}
}

final class ProjectFindView: NSView {
	var onCancel: (() -> Void)?
	var onSearch: ((String) -> Void)?
	var onOpenMatch: ((ProjectFindMatch) -> Void)?
	private let queryField = ItsyActionTextField(frame: .zero)
	private let statusLabel = NSTextField(labelWithString: "")
	private let tableView = NSTableView()
	private let scrollView = NSScrollView()
	private var matches: [ProjectFindMatch] = []

	override init(frame frameRect: NSRect) {
		super.init(frame: frameRect)
		configure()
	}

	required init?(coder: NSCoder) {
		super.init(coder: coder)
		configure()
	}

	func focusInput() {
		window?.makeFirstResponder(queryField)
	}

	func setStatus(_ status: String) {
		statusLabel.stringValue = status
	}

	func setResults(_ matches: [ProjectFindMatch]) {
		self.matches = matches
		tableView.reloadData()
		if !matches.isEmpty {
			tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func configure() {
		wantsLayer = true
		layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

		queryField.placeholderString = L10n.string("Find in project")
		queryField.font = .systemFont(ofSize: 15)
		queryField.isBordered = true
		queryField.focusRingType = .default
		queryField.translatesAutoresizingMaskIntoConstraints = false
		queryField.onCancel = { [weak self] in self?.onCancel?() }
		queryField.delegate = self
		addSubview(queryField)

		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		addSubview(statusLabel)

		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("result"))
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowHeight = 24
		tableView.intercellSpacing = NSSize(width: 0, height: 0)
		tableView.usesAlternatingRowBackgroundColors = true
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedMatch(_:))

		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = true
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		addSubview(scrollView)

		NSLayoutConstraint.activate([
			queryField.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
			queryField.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
			queryField.topAnchor.constraint(equalTo: topAnchor, constant: 14),
			queryField.heightAnchor.constraint(equalToConstant: 28),
			statusLabel.leadingAnchor.constraint(equalTo: queryField.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: queryField.trailingAnchor),
			statusLabel.topAnchor.constraint(equalTo: queryField.bottomAnchor, constant: 6),
			statusLabel.heightAnchor.constraint(equalToConstant: 16),
			scrollView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
			scrollView.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 8),
			scrollView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -14),
		])
	}

	@objc private func openSelectedMatch(_ sender: Any?) {
		guard tableView.selectedRow >= 0, tableView.selectedRow < matches.count else {
			return
		}
		onOpenMatch?(matches[tableView.selectedRow])
	}
}

extension ProjectFindView: NSTextFieldDelegate {
	func controlTextDidChange(_ notification: Notification) {
		onSearch?(queryField.stringValue)
	}
}

extension ProjectFindView: NSTableViewDataSource, NSTableViewDelegate {
	func numberOfRows(in tableView: NSTableView) -> Int {
		matches.count
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		let identifier = NSUserInterfaceItemIdentifier("ProjectFindCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		let match = matches[row]
		textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textField.lineBreakMode = .byTruncatingTail
		textField.stringValue = "\(match.relativePath):\(match.line):\(match.column)  \(match.lineText)"
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
