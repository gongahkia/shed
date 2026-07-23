import AppKit
import Foundation

@MainActor final class GitHubPullRequestDetailPanel: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private enum Section: Int { case overview, commits, checks, files }
	private let sectionPicker = NSSegmentedControl(labels: ["Overview", "Commits", "Checks", "Files"], trackingMode: .selectOne, target: nil, action: nil)
	private let overviewView = NSTextView()
	private let tableView = NSTableView()
	private let diffContextPanel = GitHubPullRequestDiffContextPanel()
	private var panel: NSPanel?
	private var detail: GitHubPullRequestDetail?
	private var workspaceURL: URL?

	func show(detail: GitHubPullRequestDetail, workspaceURL: URL, relativeTo hostWindow: NSWindow?) {
		self.detail = detail
		self.workspaceURL = workspaceURL
		let panel = makePanelIfNeeded()
		panel.title = "Pull Request #\(detail.number)"
		sectionPicker.selectedSegment = Section.overview.rawValue
		updateContent()
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel { return panel }
		let content = NSView(frame: NSRect(x: 0, y: 0, width: 720, height: 520))
		let panel = NSPanel(contentRect: content.frame, styleMask: [.titled, .closable, .resizable, .utilityWindow], backing: .buffered, defer: false)
		panel.isReleasedWhenClosed = false
		panel.contentView = content
		sectionPicker.target = self
		sectionPicker.action = #selector(sectionChanged(_:))

		overviewView.isEditable = false
		overviewView.isSelectable = true
		overviewView.font = .systemFont(ofSize: 13)
		overviewView.drawsBackground = false
		let overviewScroll = NSScrollView()
		overviewScroll.documentView = overviewView
		overviewScroll.hasVerticalScroller = true
		overviewScroll.borderType = .bezelBorder

		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("detail"))
		column.title = "Pull request details"
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedFile(_:))
		let tableScroll = NSScrollView()
		tableScroll.documentView = tableView
		tableScroll.hasVerticalScroller = true
		tableScroll.borderType = .bezelBorder

		for view in [sectionPicker, overviewScroll, tableScroll] {
			view.translatesAutoresizingMaskIntoConstraints = false
			content.addSubview(view)
		}
		NSLayoutConstraint.activate([
			sectionPicker.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 14),
			sectionPicker.topAnchor.constraint(equalTo: content.topAnchor, constant: 14),
			overviewScroll.leadingAnchor.constraint(equalTo: sectionPicker.leadingAnchor),
			overviewScroll.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
			overviewScroll.topAnchor.constraint(equalTo: sectionPicker.bottomAnchor, constant: 12),
			overviewScroll.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -14),
			tableScroll.leadingAnchor.constraint(equalTo: overviewScroll.leadingAnchor),
			tableScroll.trailingAnchor.constraint(equalTo: overviewScroll.trailingAnchor),
			tableScroll.topAnchor.constraint(equalTo: overviewScroll.topAnchor),
			tableScroll.bottomAnchor.constraint(equalTo: overviewScroll.bottomAnchor),
		])
		overviewScroll.identifier = NSUserInterfaceItemIdentifier("overview")
		tableScroll.identifier = NSUserInterfaceItemIdentifier("table")
		self.panel = panel
		return panel
	}

	private func updateContent() {
		guard let content = panel?.contentView,
			let overviewScroll = content.subviews.first(where: { $0.identifier?.rawValue == "overview" }),
			let tableScroll = content.subviews.first(where: { $0.identifier?.rawValue == "table" })
		else { return }
		let section = Section(rawValue: sectionPicker.selectedSegment) ?? .overview
		overviewScroll.isHidden = section != .overview
		tableScroll.isHidden = section == .overview
		overviewView.string = Self.overviewText(detail)
		tableView.reloadData()
	}

	func numberOfRows(in _: NSTableView) -> Int {
		guard let detail else { return 0 }
		switch Section(rawValue: sectionPicker.selectedSegment) ?? .overview {
		case .overview: return 0
		case .commits: return detail.commits.count
		case .checks:
			if case let .available(checks) = detail.checks { return checks.count }
			return 1
		case .files: return detail.files.count
		}
	}

	func tableView(_: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
		let label = NSTextField(labelWithString: rowText(row))
		label.lineBreakMode = .byTruncatingMiddle
		return label
	}

	@objc private func sectionChanged(_: NSSegmentedControl) {
		updateContent()
	}

	@objc private func openSelectedFile(_: NSTableView) {
		guard let detail, let workspaceURL,
			Section(rawValue: sectionPicker.selectedSegment) == .files,
			(0 ..< detail.files.count).contains(tableView.selectedRow)
		else { return }
		let context = GitHubPullRequestDiffContext.make(detail: detail, file: detail.files[tableView.selectedRow], workspaceURL: workspaceURL)
		diffContextPanel.show(context: context, relativeTo: panel)
	}

	private func rowText(_ row: Int) -> String {
		guard let detail else { return "" }
		switch Section(rawValue: sectionPicker.selectedSegment) ?? .overview {
		case .overview: return ""
		case .commits:
			let commit = detail.commits[row]
			return "\(commit.oid.prefix(10))  \(commit.messageHeadline)"
		case .checks:
			if case let .available(checks) = detail.checks {
				let check = checks[row]
				return "[\(check.state)] \(check.name)\(check.workflow.map { " · \($0)" } ?? "")"
			}
			return "Checks unavailable"
		case .files:
			let file = detail.files[row]
			return "\(file.path)   +\(file.additions)  -\(file.deletions)"
		}
	}

	static func overviewText(_ detail: GitHubPullRequestDetail?) -> String {
		guard let detail else { return "Pull request unavailable" }
		return [
			"#\(detail.number) \(detail.title)",
			"State: \(detail.state)\(detail.isDraft ? " · draft" : "")",
			"Branch: \(detail.headRepositoryOwner.map { "\($0.login):" } ?? "")\(detail.headRefName) → \(detail.baseRefName)",
			"Review: \(detail.reviewDecision ?? "pending")",
			"Merge: \(detail.mergeable ?? "unknown") · \(detail.mergeStateStatus ?? "unknown")",
			"Checks: \(checkSummary(detail.checks))",
			"\n\(detail.body?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? detail.body! : "No description")",
		].joined(separator: "\n")
	}

	private static func checkSummary(_ checks: GitHubPullRequestChecks) -> String {
		switch checks {
		case let .available(items): "\(items.count) reported"
		case .unavailable: "unavailable"
		}
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let frame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(780, max(620, frame.width - 120))
		let height = min(600, max(420, frame.height - 160))
		panel.setFrame(NSRect(x: frame.midX - width / 2, y: frame.midY - height / 2, width: width, height: height), display: true)
	}
}

@MainActor private final class GitHubPullRequestDiffContextPanel: NSObject {
	private var panel: NSPanel?

	func show(context: GitHubPullRequestDiffContext, relativeTo hostWindow: NSWindow?) {
		let panel = makePanelIfNeeded()
		let text = panel.contentView?.subviews.compactMap { $0 as? NSTextField }.first
		text?.stringValue = Self.text(context)
		let frame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		panel.setFrame(NSRect(x: frame.midX - 240, y: frame.midY - 100, width: 480, height: 200), display: true)
		panel.makeKeyAndOrderFront(nil)
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel { return panel }
		let content = NSView(frame: NSRect(x: 0, y: 0, width: 480, height: 200))
		let panel = NSPanel(contentRect: content.frame, styleMask: [.titled, .closable, .utilityWindow], backing: .buffered, defer: false)
		panel.title = "Diff Context"
		panel.isReleasedWhenClosed = false
		let text = NSTextField(wrappingLabelWithString: "")
		text.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(text)
		NSLayoutConstraint.activate([
			text.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 14),
			text.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
			text.topAnchor.constraint(equalTo: content.topAnchor, constant: 14),
		])
		panel.contentView = content
		self.panel = panel
		return panel
	}

	private static func text(_ context: GitHubPullRequestDiffContext) -> String {
		let local = context.localFileURL?.path ?? "No matching local file; Itsy did not download a remote workspace file."
		return "PR #\(context.pullRequestNumber) · \(context.baseRefName) → \(context.headRefName)\n\(context.file.path)  +\(context.file.additions) -\(context.file.deletions)\n\n\(local)"
	}
}
