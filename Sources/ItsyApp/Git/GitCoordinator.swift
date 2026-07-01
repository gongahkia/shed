import AppKit
import Dispatch
import Foundation
import ItsyEditor
import ItsyRender
import ItsySyntax

private struct GitCommitDraft: Codable, Equatable {
	var summary: String
	var body: String
}

private enum GitCommitDraftStore {
	private static var fileURL: URL {
		let home = FileManager.default.homeDirectoryForCurrentUser
		return home
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("commit-drafts.json")
	}

	static func load(for root: URL) -> GitCommitDraft {
		loadAll()[key(for: root)] ?? GitCommitDraft(summary: "", body: "")
	}

	static func save(_ draft: GitCommitDraft, for root: URL) {
		var all = loadAll()
		if draft.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			all.removeValue(forKey: key(for: root))
		} else {
			all[key(for: root)] = draft
		}
		let url = fileURL
		try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		guard let data = try? JSONEncoder().encode(all) else {
			return
		}
		try? data.write(to: url, options: .atomic)
	}

	private static func loadAll() -> [String: GitCommitDraft] {
		guard
			let data = try? Data(contentsOf: fileURL),
			let drafts = try? JSONDecoder().decode([String: GitCommitDraft].self, from: data)
		else {
			return [:]
		}
		return drafts
	}

	private static func key(for root: URL) -> String {
		root.standardizedFileURL.path
	}
}

final class GitCoordinator: NSObject {
	private let documentController: ItsyDocumentController
	private let activeDocumentProvider: () -> NSDocument?
	private var gitPanel: NSPanel?
	private var gitStatusLabel: NSTextField?
	private var gitTableView: NSTableView?
	private var gitBranchButton: NSButton?
	private var gitBranchPopover: NSPopover?
	private var gitBranchTableView: NSTableView?
	private var gitBranches: [GitBranch] = []
	private var gitStashPanel: NSPanel?
	private var gitStashStatusLabel: NSTextField?
	private var gitStashTableView: NSTableView?
	private var gitStashEntries: [GitStashEntry] = []
	private var gitStashRootURL: URL?
	private var gitSummaryField: NSTextField?
	private var gitBodyTextView: NSTextView?
	private var gitCommitButton: NSButton?
	private var gitSignoffButton: NSButton?
	private var gitAmendButton: NSButton?
	private var gitComposerStatusLabel: NSTextField?
	private enum GitDiffMode { case unified, sideBySide }
	private var gitDiffMode: GitDiffMode = .unified
	private var gitDiffModeControl: NSSegmentedControl?
	private var gitDiffStatusLabel: NSTextField?
	private var gitUnifiedDiffView: MetalTextView?
	private var gitSideOldDiffView: MetalTextView?
	private var gitSideNewDiffView: MetalTextView?
	private var gitSideBySideSplitView: NSSplitView?
	private var gitHunkTableView: NSTableView?
	private struct GitDiffHunkItem {
		var fileIndex: Int
		var hunkIndex: Int
		var title: String
		var isStaged: Bool
	}
	private struct GitDiffLineItem {
		var fileIndex: Int
		var hunkIndex: Int
		var lineIndex: Int
		var range: Range<Int>
	}
	private enum GitLineSelectionError: Error {
		case unifiedModeRequired
		case noChangedLinesSelected
	}
	private var gitDraftRootURL: URL?
	private var gitDraftBeforeHistory: GitCommitDraft?
	private var gitRecentCommitMessages: [GitCommitDraft] = []
	private var gitRecentCommitIndex: Int?
	private var gitEntries: [GitStatusEntry] = []
	private var gitRootURL: URL?
	private var gitDiffFiles: [DiffFile] = []
	private var gitDiffPath: String?
	private var gitHunkItems: [GitDiffHunkItem] = []
	private var gitUnifiedLineItems: [GitDiffLineItem] = []
	private var gitRemoteProcess: Process?
	private var gitRemoteLog = ""
	private var gitConflictPanel: NSPanel?
	private var gitConflictRootURL: URL?
	private var gitConflictPath: String?
	private var gitConflictMergedTextView: NSTextView?
	private var gitConflictRegionStack: NSStackView?

	init(documentController: ItsyDocumentController, activeDocumentProvider: @escaping () -> NSDocument?) {
		self.documentController = documentController
		self.activeDocumentProvider = activeDocumentProvider
		super.init()
	}

	func applyEditorPreferences(_ preferences: EditorPreferences) {
		gitUnifiedDiffView?.configureEditorAppearance(fontName: preferences.fontName, fontSize: preferences.fontSize, showsLineNumbers: preferences.showLineNumbers)
		gitSideOldDiffView?.configureEditorAppearance(fontName: preferences.fontName, fontSize: preferences.fontSize, showsLineNumbers: preferences.showLineNumbers)
		gitSideNewDiffView?.configureEditorAppearance(fontName: preferences.fontName, fontSize: preferences.fontSize, showsLineNumbers: preferences.showLineNumbers)
	}

	@objc func showGitChanges(_ sender: Any?) {
		toggleGitChanges(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleGitChanges(relativeTo hostWindow: NSWindow?) {
		if gitPanel?.isVisible == true {
			closeGitChanges()
			return
		}
		showGitChanges(relativeTo: hostWindow)
	}

	private func closeGitChanges() {
		gitPanel?.close()
	}

	private func showGitChanges(relativeTo hostWindow: NSWindow?) {
		let panel = makeGitPanelIfNeeded()
		centerGitPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshGitChanges(nil)
	}

	private func makeGitPanelIfNeeded() -> NSPanel {
		if let panel = gitPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 980, height: 560),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Git Changes")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureGitView(contentView)
		gitPanel = panel
		return panel
	}

	private func configureGitView(_ contentView: NSView) {
		let composer = makeGitComposerView()
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let branchButton = NSButton(title: L10n.string("Branch"), target: self, action: #selector(showGitBranches(_:)))
		let fetchButton = NSButton(title: L10n.string("Fetch"), target: self, action: #selector(fetchGitRemote(_:)))
		let pullButton = NSButton(title: L10n.string("Pull"), target: self, action: #selector(pullGitRemote(_:)))
		let pushButton = NSButton(title: L10n.string("Push"), target: self, action: #selector(pushGitRemote(_:)))
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshGitChanges(_:)))
		let stageButton = NSButton(title: L10n.string("Stage"), target: self, action: #selector(stageSelectedGitEntries(_:)))
		let unstageButton = NSButton(title: L10n.string("Unstage"), target: self, action: #selector(unstageSelectedGitEntries(_:)))
		let buttonStack = NSStackView(views: [branchButton, fetchButton, pullButton, pushButton, refreshButton, stageButton, unstageButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("git"))
		column.title = L10n.string("Changes")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.usesAlternatingRowBackgroundColors = false
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedGitEntry(_:))
		let listScrollView = NSScrollView()
		listScrollView.documentView = tableView
		listScrollView.hasVerticalScroller = true
		listScrollView.drawsBackground = false
		let diffPane = makeGitDiffPane()
		let splitView = NSSplitView()
		splitView.isVertical = true
		splitView.dividerStyle = .thin
		splitView.addArrangedSubview(listScrollView)
		splitView.addArrangedSubview(diffPane)
		composer.translatesAutoresizingMaskIntoConstraints = false
		header.translatesAutoresizingMaskIntoConstraints = false
		splitView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(composer)
		contentView.addSubview(header)
		contentView.addSubview(splitView)
		NSLayoutConstraint.activate([
			composer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			composer.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			composer.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			header.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			header.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			header.topAnchor.constraint(equalTo: composer.bottomAnchor, constant: 10),
			splitView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			splitView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			splitView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 10),
			splitView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
			listScrollView.widthAnchor.constraint(greaterThanOrEqualToConstant: 220),
			listScrollView.widthAnchor.constraint(lessThanOrEqualToConstant: 360),
			diffPane.widthAnchor.constraint(greaterThanOrEqualToConstant: 420),
		])
		gitStatusLabel = statusLabel
		gitTableView = tableView
		gitBranchButton = branchButton
	}

	private func makeGitDiffPane() -> NSView {
		let container = NSView()
		let titleLabel = NSTextField(labelWithString: L10n.string("Diff"))
		titleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		let modeControl = NSSegmentedControl(labels: [L10n.string("Unified"), L10n.string("Side")], trackingMode: .selectOne, target: self, action: #selector(changeGitDiffMode(_:)))
		modeControl.selectedSegment = 0
		modeControl.segmentStyle = .rounded
		let header = NSStackView(views: [titleLabel, statusLabel, modeControl])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.spacing = 8
		header.distribution = .fill
		let unifiedView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 640, height: 360))
		let oldView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 320, height: 360))
		let newView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 320, height: 360))
		let preferences = EditorPreferences.load()
		for view in [unifiedView, oldView, newView] {
			view.configureEditorAppearance(fontName: preferences.fontName, fontSize: preferences.fontSize, showsLineNumbers: preferences.showLineNumbers)
		}
		let sideSplitView = NSSplitView()
		sideSplitView.isVertical = true
		sideSplitView.dividerStyle = .thin
		sideSplitView.addArrangedSubview(oldView)
		sideSplitView.addArrangedSubview(newView)
		let diffContentView = NSView()
		let hunkTableView = NSTableView()
		let hunkColumn = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("hunk"))
		hunkColumn.title = L10n.string("Hunks")
		hunkColumn.resizingMask = .autoresizingMask
		hunkTableView.addTableColumn(hunkColumn)
		hunkTableView.headerView = nil
		hunkTableView.rowHeight = 70
		hunkTableView.dataSource = self
		hunkTableView.delegate = self
		let hunkScrollView = NSScrollView()
		hunkScrollView.documentView = hunkTableView
		hunkScrollView.hasVerticalScroller = true
		hunkScrollView.drawsBackground = false
		let bodySplitView = NSSplitView()
		bodySplitView.isVertical = true
		bodySplitView.dividerStyle = .thin
		bodySplitView.addArrangedSubview(hunkScrollView)
		bodySplitView.addArrangedSubview(diffContentView)
		header.translatesAutoresizingMaskIntoConstraints = false
		unifiedView.translatesAutoresizingMaskIntoConstraints = false
		sideSplitView.translatesAutoresizingMaskIntoConstraints = false
		bodySplitView.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(header)
		container.addSubview(bodySplitView)
		diffContentView.addSubview(unifiedView)
		diffContentView.addSubview(sideSplitView)
		NSLayoutConstraint.activate([
			header.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 10),
			header.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -10),
			header.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
			modeControl.widthAnchor.constraint(equalToConstant: 136),
			bodySplitView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			bodySplitView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			bodySplitView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 8),
			bodySplitView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
			hunkScrollView.widthAnchor.constraint(equalToConstant: 136),
			unifiedView.leadingAnchor.constraint(equalTo: diffContentView.leadingAnchor),
			unifiedView.trailingAnchor.constraint(equalTo: diffContentView.trailingAnchor),
			unifiedView.topAnchor.constraint(equalTo: diffContentView.topAnchor),
			unifiedView.bottomAnchor.constraint(equalTo: diffContentView.bottomAnchor),
			sideSplitView.leadingAnchor.constraint(equalTo: diffContentView.leadingAnchor),
			sideSplitView.trailingAnchor.constraint(equalTo: diffContentView.trailingAnchor),
			sideSplitView.topAnchor.constraint(equalTo: diffContentView.topAnchor),
			sideSplitView.bottomAnchor.constraint(equalTo: diffContentView.bottomAnchor),
			oldView.widthAnchor.constraint(equalTo: newView.widthAnchor),
		])
		sideSplitView.isHidden = true
		gitDiffModeControl = modeControl
		gitDiffStatusLabel = statusLabel
		gitUnifiedDiffView = unifiedView
		gitSideOldDiffView = oldView
		gitSideNewDiffView = newView
		gitSideBySideSplitView = sideSplitView
		gitHunkTableView = hunkTableView
		return container
	}

	private func makeGitComposerView() -> NSView {
		let container = NSView()
		let summaryField = NSTextField()
		summaryField.placeholderString = L10n.string("Summary 50")
		summaryField.font = .systemFont(ofSize: 12)
		summaryField.delegate = self
		let summaryHint = NSTextField(labelWithString: L10n.string("50"))
		summaryHint.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
		summaryHint.textColor = .secondaryLabelColor
		let summaryRow = NSStackView(views: [summaryField, summaryHint])
		summaryRow.orientation = .horizontal
		summaryRow.alignment = .centerY
		summaryRow.spacing = 8
		let bodyTextView = NSTextView()
		bodyTextView.font = .systemFont(ofSize: 12)
		bodyTextView.isRichText = false
		bodyTextView.allowsUndo = true
		bodyTextView.delegate = self
		bodyTextView.textContainerInset = NSSize(width: 4, height: 4)
		bodyTextView.textContainer?.widthTracksTextView = true
		bodyTextView.isHorizontallyResizable = false
		bodyTextView.isVerticallyResizable = true
		let bodyScrollView = NSScrollView()
		bodyScrollView.documentView = bodyTextView
		bodyScrollView.hasVerticalScroller = true
		bodyScrollView.borderType = .bezelBorder
		let bodyHint = NSTextField(labelWithString: L10n.string("Body 72"))
		bodyHint.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
		bodyHint.textColor = .secondaryLabelColor
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		let signoffButton = NSButton(checkboxWithTitle: L10n.string("--signoff"), target: self, action: #selector(updateGitComposerStateAction(_:)))
		let amendButton = NSButton(checkboxWithTitle: L10n.string("--amend"), target: self, action: #selector(updateGitComposerStateAction(_:)))
		let commitButton = NSButton(title: L10n.string("Commit"), target: self, action: #selector(commitGitChanges(_:)))
		let footer = NSStackView(views: [statusLabel, signoffButton, amendButton, commitButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		footer.distribution = .fill
		[summaryRow, bodyHint, bodyScrollView, footer].forEach {
			$0.translatesAutoresizingMaskIntoConstraints = false
			container.addSubview($0)
		}
		NSLayoutConstraint.activate([
			summaryHint.widthAnchor.constraint(equalToConstant: 28),
			summaryRow.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			summaryRow.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			summaryRow.topAnchor.constraint(equalTo: container.topAnchor),
			bodyHint.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			bodyHint.topAnchor.constraint(equalTo: summaryRow.bottomAnchor, constant: 6),
			bodyScrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			bodyScrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			bodyScrollView.topAnchor.constraint(equalTo: bodyHint.bottomAnchor, constant: 4),
			bodyScrollView.heightAnchor.constraint(equalToConstant: 74),
			footer.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			footer.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			footer.topAnchor.constraint(equalTo: bodyScrollView.bottomAnchor, constant: 8),
			footer.bottomAnchor.constraint(equalTo: container.bottomAnchor),
		])
		gitSummaryField = summaryField
		gitBodyTextView = bodyTextView
		gitCommitButton = commitButton
		gitSignoffButton = signoffButton
		gitAmendButton = amendButton
		gitComposerStatusLabel = statusLabel
		updateGitComposerState()
		return container
	}

	private func centerGitPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(1100, max(860, hostFrame.width - 100))
		let height = min(660, max(420, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	@objc private func showGitBranches(_ sender: NSButton) {
		guard gitRootURL != nil else {
			return
		}
		let popover = makeGitBranchPopover()
		refreshGitBranches()
		popover.show(relativeTo: sender.bounds, of: sender, preferredEdge: .maxY)
	}

	private func makeGitBranchPopover() -> NSPopover {
		let popover = NSPopover()
		popover.behavior = .transient
		let controller = NSViewController()
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 360))
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("branch"))
		column.title = L10n.string("Branches")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowHeight = 54
		tableView.dataSource = self
		tableView.delegate = self
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: contentView.topAnchor),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		controller.view = contentView
		popover.contentViewController = controller
		gitBranchPopover = popover
		gitBranchTableView = tableView
		return popover
	}

	private func refreshGitBranches() {
		guard let gitRootURL else {
			gitBranches = []
			gitBranchTableView?.reloadData()
			return
		}
		do {
			gitBranches = try GitRepository(root: gitRootURL).branches()
			gitBranchTableView?.reloadData()
			if let current = gitBranches.first(where: \.isCurrent) {
				gitBranchButton?.title = current.name
			}
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func switchGitBranch(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitBranches.count else {
			return
		}
		let branch = gitBranches[sender.tag]
		do {
			let repository = GitRepository(root: gitRootURL)
			guard let shouldStash = try shouldStashBeforeBranchChange(targetBranch: branch.name, repository: repository) else {
				return
			}
			try repository.switchBranch(branch.name, stashingDirtyChanges: shouldStash)
			gitBranchPopover?.close()
			refreshGitChanges(nil)
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func createGitBranchFromRow(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitBranches.count else {
			return
		}
		let source = gitBranches[sender.tag]
		guard let name = promptGitBranchName(defaultName: "") else {
			return
		}
		do {
			let repository = GitRepository(root: gitRootURL)
			guard let shouldStash = try shouldStashBeforeBranchChange(targetBranch: name, repository: repository) else {
				return
			}
			try repository.createBranch(named: name, from: source.name, stashingDirtyChanges: shouldStash)
			gitBranchPopover?.close()
			refreshGitChanges(nil)
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func shouldStashBeforeBranchChange(targetBranch: String, repository: GitRepository) throws -> Bool? {
		guard try repository.status().hasChanges else {
			return false
		}
		return confirmStashAndSwitch(targetBranch) ? true : nil
	}

	@objc private func deleteGitBranchFromRow(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitBranches.count else {
			return
		}
		let branch = gitBranches[sender.tag]
		do {
			try GitRepository(root: gitRootURL).deleteBranch(branch.name)
			refreshGitBranches()
			refreshGitChanges(nil)
		} catch {
			guard confirmForceDeleteBranch(branch.name, error: error) else {
				gitStatusLabel?.textColor = .systemRed
				gitStatusLabel?.stringValue = String(describing: error)
				return
			}
			do {
				try GitRepository(root: gitRootURL).deleteBranch(branch.name, force: true)
				refreshGitBranches()
				refreshGitChanges(nil)
			} catch {
				gitStatusLabel?.textColor = .systemRed
				gitStatusLabel?.stringValue = String(describing: error)
			}
		}
	}

	private func promptGitBranchName(defaultName: String) -> String? {
		let field = NSTextField(string: defaultName)
		field.placeholderString = L10n.string("branch-name")
		let alert = NSAlert()
		alert.messageText = L10n.string("Create Branch")
		alert.accessoryView = field
		alert.addButton(withTitle: L10n.string("Create"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			return nil
		}
		let name = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		return name.isEmpty ? nil : name
	}

	private func confirmForceDeleteBranch(_ name: String, error: Error) -> Bool {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = L10n.string("Force Delete Branch?")
		alert.informativeText = "\(name)\n\(String(describing: error))"
		alert.addButton(withTitle: L10n.string("Force Delete"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	private func confirmStashAndSwitch(_ branch: String) -> Bool {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = L10n.string("Working Tree Dirty")
		alert.informativeText = L10n.string("Stash current changes and switch to \(branch)?")
		alert.addButton(withTitle: L10n.string("Stash and Switch"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	@objc func showGitStashes(_: Any?) {
		let panel = makeGitStashPanelIfNeeded()
		centerGitStashPanel(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		refreshGitStashes(nil)
	}

	private func makeGitStashPanelIfNeeded() -> NSPanel {
		if let panel = gitStashPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 760, height: 420),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Stashes")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureGitStashView(contentView)
		gitStashPanel = panel
		return panel
	}

	private func configureGitStashView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshGitStashes(_:)))
		let stashButton = NSButton(title: L10n.string("Stash Current Changes..."), target: self, action: #selector(stashCurrentGitChanges(_:)))
		let buttonStack = NSStackView(views: [refreshButton, stashButton])
		buttonStack.orientation = .horizontal
		buttonStack.spacing = 8
		let header = NSStackView(views: [statusLabel, buttonStack])
		header.orientation = .horizontal
		header.alignment = .centerY
		header.distribution = .fill
		header.spacing = 12
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("stash"))
		column.title = L10n.string("Stashes")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowHeight = 56
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
		gitStashStatusLabel = statusLabel
		gitStashTableView = tableView
	}

	private func centerGitStashPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(820, max(620, hostFrame.width - 100))
		let height = min(520, max(320, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	@objc private func refreshGitStashes(_: Any?) {
		guard let root = currentGitRootURL() else {
			setGitStashes([], root: nil, status: L10n.string("Open a Git repository first"), isError: true)
			return
		}
		do {
			let entries = try GitRepository(root: root).stashes()
			let count = entries.count == 1 ? L10n.string("1 stash") : L10n.string("\(entries.count) stashes")
			setGitStashes(entries, root: root, status: "\(root.path) - \(count)", isError: false)
		} catch {
			setGitStashes([], root: root, status: String(describing: error), isError: true)
		}
	}

	private func setGitStashes(_ entries: [GitStashEntry], root: URL?, status: String, isError: Bool) {
		gitStashEntries = entries
		gitStashRootURL = root
		setGitStashStatus(status, isError: isError)
		gitStashTableView?.reloadData()
		if !entries.isEmpty {
			gitStashTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func setGitStashStatus(_ status: String, isError: Bool) {
		gitStashStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		gitStashStatusLabel?.stringValue = status
	}

	private func currentGitRootURL() -> URL? {
		if let root = ItsyWorkspaceController.currentRootURL,
		   let gitRoot = try? GitRepository.discoverRoot(containing: root) {
			return gitRoot
		}
		if let fileURL = (activeDocumentProvider() as? ItsyDocument)?.fileURL,
		   let gitRoot = try? GitRepository.discoverRoot(containing: fileURL) {
			return gitRoot
		}
		if let gitRootURL {
			return gitRootURL
		}
		return nil
	}

	@objc func stashCurrentGitChanges(_: Any?) {
		guard let root = currentGitRootURL() else {
			showGitStashAlert(title: L10n.string("Stash failed"), message: L10n.string("Open a Git repository first"))
			return
		}
		guard let message = promptGitStashMessage() else {
			return
		}
		do {
			try GitRepository(root: root).stash(message: message)
			refreshGitStateAfterStashChange(status: L10n.string("Stash saved"))
		} catch {
			setGitStashStatus(String(describing: error), isError: true)
			showGitStashAlert(title: L10n.string("Stash failed"), message: String(describing: error))
		}
	}

	private func promptGitStashMessage() -> String? {
		let field = NSTextField(string: "")
		field.placeholderString = L10n.string("stash message")
		field.frame = NSRect(x: 0, y: 0, width: 360, height: 24)
		let alert = NSAlert()
		alert.messageText = L10n.string("Stash Current Changes")
		alert.informativeText = L10n.string("Message")
		alert.accessoryView = field
		alert.addButton(withTitle: L10n.string("Stash"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			return nil
		}
		let message = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		return message.isEmpty ? nil : message
	}

	@objc private func applyGitStashFromRow(_ sender: NSButton) {
		runGitStashEntryAction(row: sender.tag, title: L10n.string("Apply")) { repository, entry in
			try repository.applyStash(entry.ref)
		}
	}

	@objc private func popGitStashFromRow(_ sender: NSButton) {
		runGitStashEntryAction(row: sender.tag, title: L10n.string("Pop")) { repository, entry in
			try repository.popStash(entry.ref)
		}
	}

	@objc private func dropGitStashFromRow(_ sender: NSButton) {
		guard sender.tag >= 0, sender.tag < gitStashEntries.count else {
			return
		}
		let entry = gitStashEntries[sender.tag]
		guard confirmDropGitStash(entry) else {
			return
		}
		runGitStashEntryAction(row: sender.tag, title: L10n.string("Drop")) { repository, entry in
			try repository.dropStash(entry.ref)
		}
	}

	private func runGitStashEntryAction(
		row: Int,
		title: String,
		action: (GitRepository, GitStashEntry) throws -> Void
	) {
		guard let root = gitStashRootURL, row >= 0, row < gitStashEntries.count else {
			return
		}
		let entry = gitStashEntries[row]
		do {
			try action(GitRepository(root: root), entry)
			refreshGitStateAfterStashChange(status: L10n.string("\(title) complete"))
		} catch {
			setGitStashStatus(String(describing: error), isError: true)
		}
	}

	private func confirmDropGitStash(_ entry: GitStashEntry) -> Bool {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = L10n.string("Drop Stash?")
		alert.informativeText = "\(entry.ref)\n\(entry.message)"
		alert.addButton(withTitle: L10n.string("Drop"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	private func refreshGitStateAfterStashChange(status: String) {
		refreshGitStashes(nil)
		setGitStashStatus(status, isError: false)
		if ItsyWorkspaceController.currentRootURL != nil {
			refreshGitChanges(nil)
		} else {
			ItsyWorkspaceController.refreshGitStatus()
		}
	}

	private func showGitStashAlert(title: String, message: String) {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = title
		alert.informativeText = message
		alert.addButton(withTitle: L10n.string("OK"))
		alert.runModal()
	}

	@objc func fetchGitRemote(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Fetch"), arguments: repository.fetchArguments())
	}

	@objc func pullGitRemote(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Pull"), arguments: repository.pullArguments())
	}

	@objc func pullGitRemoteRebase(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Pull Rebase"), arguments: repository.pullArguments(mode: .rebase))
	}

	@objc func pushGitRemote(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		do {
			let repository = GitRepository(root: gitRootURL)
			runGitRemoteOperation(title: L10n.string("Push"), arguments: try repository.pushArguments())
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func runGitRemoteOperation(title: String, arguments: [String]) {
		guard let gitRootURL, gitRemoteProcess == nil else {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = L10n.string("Git remote command already running")
			return
		}
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
		process.arguments = arguments
		process.currentDirectoryURL = gitRootURL
		let stdout = Pipe()
		let stderr = Pipe()
		process.standardOutput = stdout
		process.standardError = stderr
		gitRemoteProcess = process
		gitRemoteLog = "$ git \(arguments.joined(separator: " "))\n"
		gitStatusLabel?.textColor = .secondaryLabelColor
		gitStatusLabel?.stringValue = "\(title)..."
		let appendOutput: (Data) -> Void = { [weak self] data in
			guard let text = String(data: data, encoding: .utf8), !text.isEmpty else {
				return
			}
			DispatchQueue.main.async {
				guard let self else {
					return
				}
				self.gitRemoteLog += text
				let line = text.split(whereSeparator: \.isNewline).last.map(String.init) ?? text.trimmingCharacters(in: .whitespacesAndNewlines)
				if !line.isEmpty {
					self.gitStatusLabel?.textColor = .secondaryLabelColor
					self.gitStatusLabel?.stringValue = line
				}
			}
		}
		stdout.fileHandleForReading.readabilityHandler = { handle in appendOutput(handle.availableData) }
		stderr.fileHandleForReading.readabilityHandler = { handle in appendOutput(handle.availableData) }
		process.terminationHandler = { [weak self] process in
			DispatchQueue.main.async {
				stdout.fileHandleForReading.readabilityHandler = nil
				stderr.fileHandleForReading.readabilityHandler = nil
				guard let self else {
					return
				}
				self.gitRemoteProcess = nil
				if process.terminationStatus == 0 {
					self.gitStatusLabel?.textColor = .secondaryLabelColor
					self.gitStatusLabel?.stringValue = "\(title) complete"
					self.refreshGitChanges(nil)
				} else {
					self.gitStatusLabel?.textColor = .systemRed
					self.gitStatusLabel?.stringValue = "\(title) failed"
					self.showGitRemoteFailure(title: title)
				}
			}
		}
		do {
			try process.run()
		} catch {
			gitRemoteProcess = nil
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func showGitRemoteFailure(title: String) {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = "\(title) failed"
		let scrollView = NSScrollView(frame: NSRect(x: 0, y: 0, width: 640, height: 260))
		let textView = NSTextView(frame: scrollView.bounds)
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = gitRemoteLog
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		alert.accessoryView = scrollView
		alert.addButton(withTitle: L10n.string("OK"))
		alert.runModal()
	}

	@objc func refreshGitChanges(_ sender: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setGitEntries([], root: nil, status: L10n.string("Open a folder first"), isError: true, branchLabel: nil)
			return
		}
		guard let gitRoot = try? GitRepository.discoverRoot(containing: root) else {
			setGitEntries([], root: nil, status: L10n.string("Not a Git repository"), isError: true, branchLabel: nil)
			ItsyWorkspaceController.refreshGitStatus()
			return
		}
		do {
			let snapshot = try GitRepository(root: gitRoot).snapshot()
			let status = "\(snapshot.branchLabel) - \(snapshot.status.stagedCount) staged, \(snapshot.status.unstagedCount) unstaged"
			setGitEntries(snapshot.status.entries, root: gitRoot, status: status, isError: false, branchLabel: snapshot.branchLabel)
			ItsyWorkspaceController.refreshGitStatus()
		} catch {
			setGitEntries([], root: gitRoot, status: String(describing: error), isError: true, branchLabel: nil)
		}
	}

	private func setGitEntries(_ entries: [GitStatusEntry], root: URL?, status: String, isError: Bool, branchLabel: String?) {
		syncGitDraftRoot(root)
		gitEntries = entries
		gitRootURL = root
		gitStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		gitStatusLabel?.stringValue = status
		gitBranchButton?.title = branchLabel ?? L10n.string("Branch")
		gitBranchButton?.isEnabled = root != nil
		gitTableView?.reloadData()
		if !entries.isEmpty {
			gitTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		updateSelectedGitDiff()
		updateGitComposerState()
	}

	@objc private func changeGitDiffMode(_ sender: Any?) {
		gitDiffMode = gitDiffModeControl?.selectedSegment == 1 ? .sideBySide : .unified
		renderGitDiff()
	}

	private func updateSelectedGitDiff() {
		guard let tableView = gitTableView,
		      let gitRootURL,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < gitEntries.count
		else {
			gitDiffPath = nil
			setGitDiffMessage(L10n.string("No file selected"))
			return
		}
		let entry = gitEntries[tableView.selectedRow]
		do {
			let files: [DiffFile]
			let label: String
			let isStagedDiff: Bool
			if entry.kind == .untracked {
				let contents = try String(contentsOf: gitRootURL.appendingPathComponent(entry.path), encoding: .utf8)
				files = [DiffTextRenderer.newFile(path: entry.path, contents: contents)]
				label = L10n.string("untracked")
				isStagedDiff = false
			} else {
				let staged = entry.isStaged && !entry.isUnstaged
				files = try GitRepository(root: gitRootURL).diffFiles(path: entry.path, staged: staged)
				label = staged ? L10n.string("staged") : L10n.string("unstaged")
				isStagedDiff = staged
			}
			gitDiffFiles = files
			gitDiffPath = entry.path
			setGitHunkItems(files: files, isStaged: isStagedDiff)
			gitDiffStatusLabel?.textColor = .secondaryLabelColor
			gitDiffStatusLabel?.stringValue = files.flatMap(\.hunks).isEmpty ? L10n.string("No text diff") : "\(entry.path) (\(label))"
			renderGitDiff()
		} catch {
			gitDiffFiles = []
			gitDiffPath = entry.path
			setGitDiffMessage(String(describing: error), isError: true)
		}
	}

	private func setGitDiffMessage(_ message: String, isError: Bool = false) {
		gitDiffFiles = []
		gitHunkItems = []
		gitUnifiedLineItems = []
		gitHunkTableView?.reloadData()
		gitDiffStatusLabel?.textColor = isError ? .systemRed : .secondaryLabelColor
		gitDiffStatusLabel?.stringValue = message
		let document = RenderedDiffDocument(text: "\(message)\n", lines: [
			RenderedDiffLine(kind: .header, fullRange: 0 ..< message.utf8.count),
		])
		applyGitDiff(document, to: gitUnifiedDiffView, path: gitDiffPath)
		applyGitDiff(document, to: gitSideOldDiffView, path: gitDiffPath)
		applyGitDiff(document, to: gitSideNewDiffView, path: gitDiffPath)
	}

	private func setGitHunkItems(files: [DiffFile], isStaged: Bool) {
		gitHunkItems = files.enumerated().flatMap { fileIndex, file in
			file.hunks.enumerated().map { hunkIndex, hunk in
				let title = "\(file.newPath ?? file.oldPath ?? "file"):\(hunk.oldStart)->\(hunk.newStart)"
				return GitDiffHunkItem(fileIndex: fileIndex, hunkIndex: hunkIndex, title: title, isStaged: isStaged)
			}
		}
		gitHunkTableView?.reloadData()
	}

	@objc private func applyGitHunk(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitHunkItems.count else {
			return
		}
		let item = gitHunkItems[sender.tag]
		guard item.fileIndex < gitDiffFiles.count, item.hunkIndex < gitDiffFiles[item.fileIndex].hunks.count else {
			return
		}
		let file = gitDiffFiles[item.fileIndex]
		let hunk = file.hunks[item.hunkIndex]
		do {
			let repository = GitRepository(root: gitRootURL)
			if item.isStaged {
				try repository.unstage(hunk: hunk, in: file)
			} else {
				try repository.stage(hunk: hunk, in: file)
			}
			refreshGitChanges(nil)
		} catch {
			gitDiffStatusLabel?.textColor = .systemRed
			gitDiffStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func applyGitSelectedLines(_ sender: NSButton) {
		guard let gitRootURL, sender.tag >= 0, sender.tag < gitHunkItems.count else {
			return
		}
		let item = gitHunkItems[sender.tag]
		guard item.fileIndex < gitDiffFiles.count, item.hunkIndex < gitDiffFiles[item.fileIndex].hunks.count else {
			return
		}
		let file = gitDiffFiles[item.fileIndex]
		let hunk = file.hunks[item.hunkIndex]
		do {
			let lineIndexes = try selectedGitLineIndexes(for: item)
			let repository = GitRepository(root: gitRootURL)
			if item.isStaged {
				try repository.unstage(lineIndexes: lineIndexes, in: hunk, file: file)
			} else {
				try repository.stage(lineIndexes: lineIndexes, in: hunk, file: file)
			}
			refreshGitChanges(nil)
		} catch {
			gitDiffStatusLabel?.textColor = .systemRed
			gitDiffStatusLabel?.stringValue = String(describing: error)
		}
	}

	private func renderGitDiff() {
		let hasSideBySide = gitDiffMode == .sideBySide
		gitUnifiedDiffView?.isHidden = hasSideBySide
		gitSideBySideSplitView?.isHidden = !hasSideBySide
		guard !gitDiffFiles.isEmpty else {
			gitUnifiedLineItems = []
			return
		}
		switch gitDiffMode {
		case .unified:
			let document = DiffTextRenderer.unified(files: gitDiffFiles)
			gitUnifiedLineItems = unifiedGitDiffLineItems(files: gitDiffFiles, document: document)
			applyGitDiff(document, to: gitUnifiedDiffView, path: gitDiffPath)
		case .sideBySide:
			gitUnifiedLineItems = []
			let rendered = DiffTextRenderer.sideBySide(files: gitDiffFiles)
			applyGitDiff(rendered.old, to: gitSideOldDiffView, path: gitDiffFiles.first?.oldPath ?? gitDiffPath)
			applyGitDiff(rendered.new, to: gitSideNewDiffView, path: gitDiffFiles.first?.newPath ?? gitDiffPath)
		}
	}

	private func unifiedGitDiffLineItems(files: [DiffFile], document: RenderedDiffDocument) -> [GitDiffLineItem] {
		var items: [GitDiffLineItem] = []
		var renderedLine = 0
		for (fileIndex, file) in files.enumerated() {
			renderedLine += 1
			if file.isNewFile, file.newMode != nil {
				renderedLine += 1
			}
			if file.isDeletedFile, file.oldMode != nil {
				renderedLine += 1
			}
			if file.indexLine != nil {
				renderedLine += 1
			}
			renderedLine += 2
			for (hunkIndex, hunk) in file.hunks.enumerated() {
				renderedLine += 1
				for lineIndex in hunk.lines.indices {
					if renderedLine < document.lines.count {
						items.append(GitDiffLineItem(
							fileIndex: fileIndex,
							hunkIndex: hunkIndex,
							lineIndex: lineIndex,
							range: document.lines[renderedLine].fullRange
						))
					}
					renderedLine += 1
				}
			}
		}
		return items
	}

	private func selectedGitLineIndexes(for item: GitDiffHunkItem) throws -> IndexSet {
		guard gitDiffMode == .unified else {
			throw GitLineSelectionError.unifiedModeRequired
		}
		guard let selection = gitUnifiedDiffView?.editor.selections.primary else {
			throw GitLineSelectionError.noChangedLinesSelected
		}
		let selectedItems = gitUnifiedLineItems.filter { lineItem in
			guard lineItem.fileIndex == item.fileIndex, lineItem.hunkIndex == item.hunkIndex else {
				return false
			}
			if selection.isCaret {
				return lineItem.range.contains(selection.head) || lineItem.range.upperBound == selection.head
			}
			return lineItem.range.overlaps(selection.range)
		}
		let indexes = IndexSet(selectedItems.map(\.lineIndex))
		guard !indexes.isEmpty else {
			throw GitLineSelectionError.noChangedLinesSelected
		}
		return indexes
	}

	private func applyGitDiff(_ document: RenderedDiffDocument, to view: MetalTextView?, path: String?) {
		view?.editor = Editor(text: document.text)
		view?.highlightSpans = gitDiffHighlightSpans(for: document, path: path)
	}

	private func gitDiffHighlightSpans(for document: RenderedDiffDocument, path: String?) -> [TextHighlightSpan] {
		var spans = document.lines.compactMap { line -> TextHighlightSpan? in
			guard !line.fullRange.isEmpty, let color = gitDiffColor(for: line.kind) else {
				return nil
			}
			return TextHighlightSpan(range: line.fullRange, color: color)
		}
		spans += syntaxHighlightSpans(for: document, path: path)
		return spans
	}

	private func gitDiffColor(for kind: RenderedDiffLineKind) -> SIMD4<Float>? {
		switch kind {
		case .header:
			return SIMD4<Float>(0.56, 0.62, 0.70, 1)
		case .addition:
			return SIMD4<Float>(0.28, 0.78, 0.46, 1)
		case .removal:
			return SIMD4<Float>(0.93, 0.37, 0.37, 1)
		case .context, .blank:
			return nil
		}
	}

	private func syntaxHighlightSpans(for document: RenderedDiffDocument, path: String?) -> [TextHighlightSpan] {
		guard let path,
		      let gitRootURL,
		      let language = SyntaxPipeline.language(forFileURL: gitRootURL.appendingPathComponent(path)),
		      let theme = try? SyntaxTheme.loadUserOrDefault()
		else {
			return []
		}
		var source = ""
		var mappings: [(source: Range<Int>, rendered: Range<Int>)] = []
		for line in document.lines {
			guard let content = line.content, let contentRange = line.contentRange else {
				continue
			}
			let start = source.utf8.count
			source += content
			let end = source.utf8.count
			mappings.append((start ..< end, contentRange))
			source += "\n"
		}
		guard !source.isEmpty else {
			return []
		}
		do {
			var pipeline = SyntaxPipeline(language: language)
			let tree = try pipeline.parse(Rope(source))
			return try pipeline.highlights(in: tree).flatMap { span -> [TextHighlightSpan] in
				mappings.compactMap { mapping -> TextHighlightSpan? in
					let lower = max(span.range.lowerBound, mapping.source.lowerBound)
					let upper = min(span.range.upperBound, mapping.source.upperBound)
					guard lower < upper, let color = theme.color(for: span.capture) else {
						return nil
					}
					let renderedLower = mapping.rendered.lowerBound + lower - mapping.source.lowerBound
					let renderedUpper = mapping.rendered.lowerBound + upper - mapping.source.lowerBound
					return TextHighlightSpan(range: renderedLower ..< renderedUpper, color: SIMD4<Float>(color.red, color.green, color.blue, color.alpha))
				}
			}
		} catch {
			return []
		}
	}

	@objc private func updateGitComposerStateAction(_ sender: Any?) {
		updateGitComposerState()
	}

	private func syncGitDraftRoot(_ root: URL?) {
		if gitDraftRootURL?.standardizedFileURL.path == root?.standardizedFileURL.path {
			return
		}
		persistGitCommitDraft()
		gitDraftRootURL = root
		gitRecentCommitMessages = []
		gitRecentCommitIndex = nil
		gitDraftBeforeHistory = nil
		setGitComposerDraft(root.map { GitCommitDraftStore.load(for: $0) } ?? GitCommitDraft(summary: "", body: ""), persist: false)
	}

	private func currentGitCommitDraft() -> GitCommitDraft {
		GitCommitDraft(summary: gitSummaryField?.stringValue ?? "", body: gitBodyTextView?.string ?? "")
	}

	private func setGitComposerDraft(_ draft: GitCommitDraft, persist: Bool) {
		gitSummaryField?.stringValue = draft.summary
		gitBodyTextView?.string = draft.body
		if persist {
			persistGitCommitDraft()
		}
		updateGitComposerState()
	}

	private func persistGitCommitDraft() {
		guard let root = gitDraftRootURL else {
			return
		}
		GitCommitDraftStore.save(currentGitCommitDraft(), for: root)
	}

	private func clearGitCommitHistorySelection() {
		gitRecentCommitIndex = nil
		gitDraftBeforeHistory = nil
	}

	private func updateGitComposerState() {
		let stagedCount = gitEntries.filter(\.isStaged).count
		let summary = gitSummaryField?.stringValue.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		gitComposerStatusLabel?.stringValue = L10n.string("\(stagedCount) staged files")
		gitCommitButton?.isEnabled = gitRootURL != nil && stagedCount > 0 && !summary.isEmpty
	}

	@objc private func commitGitChanges(_ sender: Any?) {
		guard let gitRootURL else {
			updateGitComposerState()
			return
		}
		let summary = gitSummaryField?.stringValue ?? ""
		let body = gitBodyTextView?.string ?? ""
		guard gitEntries.contains(where: \.isStaged), !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			updateGitComposerState()
			return
		}
		do {
			try GitRepository(root: gitRootURL).commit(
				summary: summary,
				body: body,
				signoff: gitSignoffButton?.state == .on,
				amend: gitAmendButton?.state == .on
			)
			setGitComposerDraft(GitCommitDraft(summary: "", body: ""), persist: true)
			refreshGitChanges(nil)
		} catch {
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true, branchLabel: gitBranchButton?.title)
		}
	}

	private func showPreviousGitCommitMessage() -> Bool {
		guard let gitRootURL, gitSummaryField?.stringValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true || gitRecentCommitIndex != nil else {
			return false
		}
		if gitRecentCommitIndex == nil {
			gitDraftBeforeHistory = currentGitCommitDraft()
			do {
				gitRecentCommitMessages = try GitRepository(root: gitRootURL).recentCommitMessages().map(commitDraft(from:))
			} catch {
				gitComposerStatusLabel?.stringValue = String(describing: error)
				return true
			}
		}
		guard !gitRecentCommitMessages.isEmpty else {
			gitComposerStatusLabel?.stringValue = L10n.string("No recent commits")
			return true
		}
		let nextIndex = ((gitRecentCommitIndex ?? -1) + 1) % gitRecentCommitMessages.count
		gitRecentCommitIndex = nextIndex
		setGitComposerDraft(gitRecentCommitMessages[nextIndex], persist: false)
		return true
	}

	private func restoreGitCommitDraftFromHistory() -> Bool {
		guard gitRecentCommitIndex != nil else {
			return false
		}
		let draft = gitDraftBeforeHistory ?? GitCommitDraft(summary: "", body: "")
		clearGitCommitHistorySelection()
		setGitComposerDraft(draft, persist: true)
		return true
	}

	private func commitDraft(from message: String) -> GitCommitDraft {
		var lines = message.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
		let summary = lines.isEmpty ? "" : lines.removeFirst()
		let body = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
		return GitCommitDraft(summary: summary, body: body)
	}

	@objc private func stageSelectedGitEntries(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let paths = selectedGitPaths()
		guard !paths.isEmpty else {
			return
		}
		do {
			try GitRepository(root: gitRootURL).stage(paths: paths)
			refreshGitChanges(nil)
		} catch {
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true, branchLabel: gitBranchButton?.title)
		}
	}

	@objc private func unstageSelectedGitEntries(_ sender: Any?) {
		guard let gitRootURL else {
			return
		}
		let paths = selectedGitPaths()
		guard !paths.isEmpty else {
			return
		}
		do {
			try GitRepository(root: gitRootURL).unstage(paths: paths)
			refreshGitChanges(nil)
		} catch {
			setGitEntries(gitEntries, root: gitRootURL, status: String(describing: error), isError: true, branchLabel: gitBranchButton?.title)
		}
	}

	private func selectedGitPaths() -> [String] {
		guard let tableView = gitTableView else {
			return []
		}
		return tableView.selectedRowIndexes.compactMap { row in
			guard row >= 0, row < gitEntries.count else {
				return nil
			}
			return gitEntries[row].path
		}
	}

	@objc private func openSelectedGitEntry(_ sender: Any?) {
		guard let tableView = gitTableView,
		      let gitRootURL,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < gitEntries.count
		else {
			return
		}
		let entry = gitEntries[tableView.selectedRow]
		if entry.isConflict {
			showGitConflict(entry: entry, root: gitRootURL)
			return
		}
		_ = documentController.openDocument(at: gitRootURL.appendingPathComponent(entry.path))
	}

	private func showGitConflict(entry: GitStatusEntry, root: URL) {
		let repository = GitRepository(root: root)
		let base = (try? repository.conflictBlob(path: entry.path, stage: 1)) ?? ""
		let ours = (try? repository.conflictBlob(path: entry.path, stage: 2)) ?? ""
		let theirs = (try? repository.conflictBlob(path: entry.path, stage: 3)) ?? ""
		let mergedURL = root.appendingPathComponent(entry.path)
		let merged = (try? String(contentsOf: mergedURL, encoding: .utf8)) ?? ""
		gitConflictPanel?.close()
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 980, height: 760),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Resolve Conflict")
		panel.isFloatingPanel = false
		let contentView = NSView()
		let titleLabel = NSTextField(labelWithString: entry.path)
		titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
		let sourceSplit = NSSplitView()
		sourceSplit.isVertical = true
		sourceSplit.dividerStyle = .thin
		sourceSplit.addArrangedSubview(makeGitConflictPane(title: L10n.string("Ours (:2)"), text: ours, isEditable: false).view)
		sourceSplit.addArrangedSubview(makeGitConflictPane(title: L10n.string("Base (:1)"), text: base, isEditable: false).view)
		sourceSplit.addArrangedSubview(makeGitConflictPane(title: L10n.string("Theirs (:3)"), text: theirs, isEditable: false).view)
		let mergedPane = makeGitConflictPane(title: L10n.string("Merged result"), text: merged, isEditable: true)
		let regionStack = NSStackView()
		regionStack.orientation = .vertical
		regionStack.alignment = .leading
		regionStack.spacing = 6
		let regionScrollView = NSScrollView()
		regionScrollView.documentView = regionStack
		regionScrollView.hasVerticalScroller = true
		regionScrollView.drawsBackground = false
		let saveButton = NSButton(title: L10n.string("Save and Add"), target: self, action: #selector(saveGitConflict(_:)))
		let closeButton = NSButton(title: L10n.string("Close"), target: self, action: #selector(closeGitConflict(_:)))
		let footer = NSStackView(views: [saveButton, closeButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		let stack = NSStackView(views: [titleLabel, sourceSplit, mergedPane.view, regionScrollView, footer])
		stack.orientation = .vertical
		stack.alignment = .leading
		stack.spacing = 10
		stack.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(stack)
		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
			sourceSplit.widthAnchor.constraint(equalTo: stack.widthAnchor),
			sourceSplit.heightAnchor.constraint(equalToConstant: 180),
			mergedPane.view.widthAnchor.constraint(equalTo: stack.widthAnchor),
			mergedPane.view.heightAnchor.constraint(equalToConstant: 300),
			regionScrollView.widthAnchor.constraint(equalTo: stack.widthAnchor),
			regionScrollView.heightAnchor.constraint(equalToConstant: 140),
		])
		panel.contentView = contentView
		gitConflictPanel = panel
		gitConflictRootURL = root
		gitConflictPath = entry.path
		gitConflictMergedTextView = mergedPane.textView
		gitConflictRegionStack = regionStack
		refreshGitConflictRegions()
		panel.center()
		panel.makeKeyAndOrderFront(nil)
	}

	private func makeGitConflictPane(title: String, text: String, isEditable: Bool) -> (view: NSView, textView: NSTextView) {
		let container = NSView()
		let label = NSTextField(labelWithString: title)
		label.font = .systemFont(ofSize: 11, weight: .semibold)
		let scrollView = NSScrollView()
		let textView = NSTextView()
		textView.isEditable = isEditable
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = text
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.hasHorizontalScroller = true
		label.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		container.addSubview(label)
		container.addSubview(scrollView)
		NSLayoutConstraint.activate([
			label.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			label.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			label.topAnchor.constraint(equalTo: container.topAnchor),
			scrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 4),
			scrollView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
		])
		return (container, textView)
	}

	private func refreshGitConflictRegions() {
		guard let stack = gitConflictRegionStack, let textView = gitConflictMergedTextView else {
			return
		}
		for view in stack.arrangedSubviews {
			stack.removeArrangedSubview(view)
			view.removeFromSuperview()
		}
		let regions = GitConflictParser.parse(textView.string)
		if regions.isEmpty {
			let label = NSTextField(labelWithString: L10n.string("No conflict markers remain"))
			label.textColor = .secondaryLabelColor
			stack.addArrangedSubview(label)
			return
		}
		for (index, region) in regions.enumerated() {
			let label = NSTextField(labelWithString: L10n.string("Region \(index + 1), lines \(region.startLine + 1)-\(region.endLine)"))
			label.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
			let ours = NSButton(title: L10n.string("Accept Ours"), target: self, action: #selector(acceptGitConflictOurs(_:)))
			let theirs = NSButton(title: L10n.string("Accept Theirs"), target: self, action: #selector(acceptGitConflictTheirs(_:)))
			let both = NSButton(title: L10n.string("Accept Both"), target: self, action: #selector(acceptGitConflictBoth(_:)))
			let edit = NSButton(title: L10n.string("Edit Manually"), target: self, action: #selector(editGitConflictManually(_:)))
			for button in [ours, theirs, both, edit] {
				button.bezelStyle = .rounded
				button.font = .systemFont(ofSize: 11)
				button.tag = index
			}
			let row = NSStackView(views: [label, ours, theirs, both, edit])
			row.orientation = .horizontal
			row.alignment = .centerY
			row.spacing = 8
			stack.addArrangedSubview(row)
		}
	}

	private func applyGitConflictResolution(_ resolution: GitConflictResolution, sender: NSButton) {
		guard let textView = gitConflictMergedTextView else {
			return
		}
		textView.string = GitConflictParser.resolvedText(textView.string, regionIndex: sender.tag, resolution: resolution)
		refreshGitConflictRegions()
	}

	@objc private func acceptGitConflictOurs(_ sender: NSButton) {
		applyGitConflictResolution(.ours, sender: sender)
	}

	@objc private func acceptGitConflictTheirs(_ sender: NSButton) {
		applyGitConflictResolution(.theirs, sender: sender)
	}

	@objc private func acceptGitConflictBoth(_ sender: NSButton) {
		applyGitConflictResolution(.both, sender: sender)
	}

	@objc private func editGitConflictManually(_ sender: NSButton) {
		guard let textView = gitConflictMergedTextView else {
			return
		}
		let regions = GitConflictParser.parse(textView.string)
		guard sender.tag >= 0, sender.tag < regions.count else {
			return
		}
		textView.setSelectedRange(nsRangeForLines(regions[sender.tag].startLine ..< regions[sender.tag].endLine, in: textView.string))
		gitConflictPanel?.makeFirstResponder(textView)
	}

	@objc private func saveGitConflict(_ sender: Any?) {
		guard let root = gitConflictRootURL, let path = gitConflictPath, let textView = gitConflictMergedTextView else {
			return
		}
		do {
			try textView.string.write(to: root.appendingPathComponent(path), atomically: true, encoding: .utf8)
			try GitRepository(root: root).stage(paths: [path])
			gitConflictPanel?.close()
			refreshGitChanges(nil)
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func closeGitConflict(_ sender: Any?) {
		gitConflictPanel?.close()
	}

	private func nsRangeForLines(_ lineRange: Range<Int>, in text: String) -> NSRange {
		let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
		var offset = 0
		var start = 0
		var end = 0
		for index in 0 ... lines.count {
			if index == lineRange.lowerBound {
				start = offset
			}
			if index == lineRange.upperBound {
				end = offset
				break
			}
			guard index < lines.count else {
				break
			}
			offset += lines[index].utf16.count
			if index < lines.count - 1 {
				offset += 1
			}
		}
		return NSRange(location: start, length: max(0, end - start))
	}

	private func gitEntryTitle(_ entry: GitStatusEntry) -> String {
		let original = entry.originalPath.map { " <- \($0)" } ?? ""
		return "\(gitEntryStatus(entry))  \(entry.path)\(original)"
	}

	private func gitEntryStatus(_ entry: GitStatusEntry) -> String {
		if entry.kind == .untracked {
			return "??"
		}
		let index = entry.indexStatus.map(String.init) ?? "."
		let worktree = entry.worktreeStatus.map(String.init) ?? "."
		return index + worktree
	}

	private func branchTitle(_ branch: GitBranch) -> String {
		let marker = branch.isCurrent ? "* " : ""
		let kind = branch.kind == .remote ? "remote" : "local"
		return "\(marker)\(branch.name)  \(kind)"
	}

	private func branchDetail(_ branch: GitBranch) -> String {
		let upstream = branch.upstream.map { "upstream \($0)" } ?? "no upstream"
		return "\(upstream) - \(branch.committerDateRelative)"
	}

}

extension GitCoordinator: NSTextFieldDelegate, NSTextViewDelegate, NSTableViewDataSource, NSTableViewDelegate {
	func controlTextDidChange(_ notification: Notification) {
		guard let field = notification.object as? NSTextField else {
			return
		}
		if field === gitSummaryField {
			clearGitCommitHistorySelection()
			persistGitCommitDraft()
			updateGitComposerState()
		}
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		if control === gitSummaryField {
			switch commandSelector {
			case #selector(NSResponder.insertNewline(_:)) where currentEventHasCommandModifier():
				commitGitChanges(nil)
				return true
			case #selector(NSResponder.moveUp(_:)):
				return showPreviousGitCommitMessage()
			case #selector(NSResponder.moveDown(_:)):
				return restoreGitCommitDraftFromHistory()
			default:
				break
			}
		}
		return false
	}

	func textDidChange(_ notification: Notification) {
		guard let textView = notification.object as? NSTextView, textView === gitBodyTextView else {
			return
		}
		clearGitCommitHistorySelection()
		persistGitCommitDraft()
		updateGitComposerState()
	}

	func textView(_ textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		if textView === gitBodyTextView, commandSelector == #selector(NSResponder.insertNewline(_:)), currentEventHasCommandModifier() {
			commitGitChanges(nil)
			return true
		}
		return false
	}

	private func currentEventHasCommandModifier() -> Bool {
		NSApp.currentEvent?.modifierFlags.intersection(.deviceIndependentFlagsMask).contains(.command) == true
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		if tableView === gitTableView {
			return gitEntries.count
		}
		if tableView === gitBranchTableView {
			return gitBranches.count
		}
		if tableView === gitStashTableView {
			return gitStashEntries.count
		}
		if tableView === gitHunkTableView {
			return gitHunkItems.count
		}
		return 0
	}

	func tableViewSelectionDidChange(_ notification: Notification) {
		guard let tableView = notification.object as? NSTableView, tableView === gitTableView else {
			return
		}
		updateSelectedGitDiff()
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		if tableView === gitTableView {
			let identifier = NSUserInterfaceItemIdentifier("GitCell")
			let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
			cell.identifier = identifier
			let textField = cell.textField ?? NSTextField(labelWithString: "")
			textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
			textField.lineBreakMode = .byTruncatingTail
			textField.stringValue = gitEntryTitle(gitEntries[row])
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
		if tableView === gitHunkTableView {
			let item = gitHunkItems[row]
			let cell = NSTableCellView()
			let hunkButton = NSButton(title: item.isStaged ? L10n.string("Unstage Hunk") : L10n.string("Stage Hunk"), target: self, action: #selector(applyGitHunk(_:)))
			hunkButton.bezelStyle = .rounded
			hunkButton.font = .systemFont(ofSize: 10)
			hunkButton.tag = row
			let lineButton = NSButton(title: item.isStaged ? L10n.string("Unstage Lines") : L10n.string("Stage Lines"), target: self, action: #selector(applyGitSelectedLines(_:)))
			lineButton.bezelStyle = .rounded
			lineButton.font = .systemFont(ofSize: 10)
			lineButton.tag = row
			let label = NSTextField(labelWithString: item.title)
			label.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
			label.textColor = .secondaryLabelColor
			label.lineBreakMode = .byTruncatingMiddle
			let stack = NSStackView(views: [hunkButton, lineButton, label])
			stack.orientation = .vertical
			stack.alignment = .leading
			stack.spacing = 2
			stack.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(stack)
			NSLayoutConstraint.activate([
				stack.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 6),
				stack.trailingAnchor.constraint(lessThanOrEqualTo: cell.trailingAnchor, constant: -6),
				stack.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				hunkButton.widthAnchor.constraint(equalToConstant: 118),
				lineButton.widthAnchor.constraint(equalToConstant: 118),
			])
			return cell
		}
		if tableView === gitBranchTableView {
			let branch = gitBranches[row]
			let cell = NSTableCellView()
			let title = NSTextField(labelWithString: branchTitle(branch))
			title.font = .systemFont(ofSize: 12, weight: branch.isCurrent ? .semibold : .regular)
			title.lineBreakMode = .byTruncatingMiddle
			let detail = NSTextField(labelWithString: branchDetail(branch))
			detail.font = .systemFont(ofSize: 10)
			detail.textColor = .secondaryLabelColor
			detail.lineBreakMode = .byTruncatingMiddle
			let textStack = NSStackView(views: [title, detail])
			textStack.orientation = .vertical
			textStack.alignment = .leading
			textStack.spacing = 2
			let switchButton = NSButton(title: L10n.string("Switch"), target: self, action: #selector(switchGitBranch(_:)))
			let createButton = NSButton(title: L10n.string("Create"), target: self, action: #selector(createGitBranchFromRow(_:)))
			let deleteButton = NSButton(title: L10n.string("Delete"), target: self, action: #selector(deleteGitBranchFromRow(_:)))
			[switchButton, createButton, deleteButton].forEach {
				$0.bezelStyle = .rounded
				$0.font = .systemFont(ofSize: 10)
				$0.tag = row
			}
			switchButton.isEnabled = branch.kind == .local && !branch.isCurrent
			deleteButton.isEnabled = branch.kind == .local && !branch.isCurrent
			let buttonStack = NSStackView(views: [switchButton, createButton, deleteButton])
			buttonStack.orientation = .horizontal
			buttonStack.spacing = 6
			let rowStack = NSStackView(views: [textStack, buttonStack])
			rowStack.orientation = .horizontal
			rowStack.alignment = .centerY
			rowStack.spacing = 10
			rowStack.distribution = .fill
			rowStack.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(rowStack)
			NSLayoutConstraint.activate([
				rowStack.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
				rowStack.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
				rowStack.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				buttonStack.widthAnchor.constraint(equalToConstant: 190),
			])
			return cell
		}
		if tableView === gitStashTableView {
			let entry = gitStashEntries[row]
			let cell = NSTableCellView()
			let title = NSTextField(labelWithString: "\(entry.ref)  \(entry.message)")
			title.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
			title.lineBreakMode = .byTruncatingMiddle
			let detail = NSTextField(labelWithString: entry.date)
			detail.font = .systemFont(ofSize: 10)
			detail.textColor = .secondaryLabelColor
			detail.lineBreakMode = .byTruncatingTail
			let textStack = NSStackView(views: [title, detail])
			textStack.orientation = .vertical
			textStack.alignment = .leading
			textStack.spacing = 2
			let applyButton = NSButton(title: L10n.string("Apply"), target: self, action: #selector(applyGitStashFromRow(_:)))
			let popButton = NSButton(title: L10n.string("Pop"), target: self, action: #selector(popGitStashFromRow(_:)))
			let dropButton = NSButton(title: L10n.string("Drop"), target: self, action: #selector(dropGitStashFromRow(_:)))
			[applyButton, popButton, dropButton].forEach {
				$0.bezelStyle = .rounded
				$0.font = .systemFont(ofSize: 10)
				$0.tag = row
			}
			let buttonStack = NSStackView(views: [applyButton, popButton, dropButton])
			buttonStack.orientation = .horizontal
			buttonStack.spacing = 6
			let rowStack = NSStackView(views: [textStack, buttonStack])
			rowStack.orientation = .horizontal
			rowStack.alignment = .centerY
			rowStack.spacing = 10
			rowStack.distribution = .fill
			rowStack.translatesAutoresizingMaskIntoConstraints = false
			cell.addSubview(rowStack)
			NSLayoutConstraint.activate([
				rowStack.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8),
				rowStack.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
				rowStack.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
				buttonStack.widthAnchor.constraint(equalToConstant: 156),
			])
			return cell
		}
		return nil
	}
}
