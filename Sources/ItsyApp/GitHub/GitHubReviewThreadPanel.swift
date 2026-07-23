import AppKit

@MainActor final class GitHubReviewThreadPanel: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let tableView = NSTableView()
	private let contextView = NSTextView()
	private var panel: NSPanel?
	private var contexts: [GitHubReviewThreadContext] = []

	func show(threads: [GitHubReviewThread], workspaceURL: URL, relativeTo hostWindow: NSWindow?) {
		contexts = threads.map { GitHubReviewThreadContext.make(thread: $0, workspaceURL: workspaceURL) }
		let panel = makePanelIfNeeded()
		tableView.reloadData()
		if !contexts.isEmpty { tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false) }
		updateContext()
		let frame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		panel.setFrame(NSRect(x: frame.midX - 360, y: frame.midY - 260, width: 720, height: 520), display: true)
		panel.makeKeyAndOrderFront(nil)
	}

	func numberOfRows(in _: NSTableView) -> Int { contexts.count }

	func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
		NSTextField(labelWithString: Self.threadSummary(contexts[row].thread))
	}

	func tableViewSelectionDidChange(_: Notification) {
		updateContext()
	}

	static func threadSummary(_ thread: GitHubReviewThread) -> String {
		let location: String
		if thread.line == nil {
			location = thread.originalLine.map { "deleted line \($0)" } ?? "deleted line"
		} else {
			location = thread.range.map { $0.lowerBound == $0.upperBound ? "\($0.lowerBound)" : "\($0.lowerBound)-\($0.upperBound)" } ?? "unknown line"
		}
		let state = thread.isResolved ? "resolved" : (thread.isOutdated ? "outdated" : "open")
		return "[\(state)] \(thread.path):\(location)"
	}

	static func contextText(_ context: GitHubReviewThreadContext?) -> String {
		guard let context else { return "No review thread selected" }
		let comments = context.thread.comments.nodes.map { "\($0.author?.login ?? "unknown"): \($0.body)" }.joined(separator: "\n\n")
		let source = context.sourceLines.map { "\($0.number): \($0.text)" }.joined(separator: "\n")
		let local = context.localFileURL == nil ? "No matching local source; Itsy did not fetch or apply remote file content." : source
		return "\(Self.threadSummary(context.thread))\n\n\(comments)\n\n\(local)"
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel { return panel }
		let content = NSView(frame: NSRect(x: 0, y: 0, width: 720, height: 520))
		let panel = NSPanel(contentRect: content.frame, styleMask: [.titled, .closable, .resizable, .utilityWindow], backing: .buffered, defer: false)
		panel.title = "Review Threads"
		panel.isReleasedWhenClosed = false
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("thread"))
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.dataSource = self
		tableView.delegate = self
		let tableScroll = NSScrollView()
		tableScroll.documentView = tableView
		tableScroll.hasVerticalScroller = true
		contextView.isEditable = false
		contextView.isSelectable = true
		contextView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		let contextScroll = NSScrollView()
		contextScroll.documentView = contextView
		contextScroll.hasVerticalScroller = true
		for view in [tableScroll, contextScroll] {
			view.translatesAutoresizingMaskIntoConstraints = false
			content.addSubview(view)
		}
		NSLayoutConstraint.activate([
			tableScroll.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 14),
			tableScroll.topAnchor.constraint(equalTo: content.topAnchor, constant: 14),
			tableScroll.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -14),
			tableScroll.widthAnchor.constraint(equalTo: content.widthAnchor, multiplier: 0.35, constant: -18),
			contextScroll.leadingAnchor.constraint(equalTo: tableScroll.trailingAnchor, constant: 12),
			contextScroll.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
			contextScroll.topAnchor.constraint(equalTo: tableScroll.topAnchor),
			contextScroll.bottomAnchor.constraint(equalTo: tableScroll.bottomAnchor),
		])
		panel.contentView = content
		self.panel = panel
		return panel
	}

	private func updateContext() {
		let selected = contexts.indices.contains(tableView.selectedRow) ? contexts[tableView.selectedRow] : nil
		contextView.string = Self.contextText(selected)
	}
}
