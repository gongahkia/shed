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
		if draft.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
		   draft.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
		{
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

private enum GitNavigationError: Error, CustomStringConvertible {
	case noActiveFile
	case outsideRepository

	var description: String {
		switch self {
		case .noActiveFile:
			L10n.string("No active file")
		case .outsideRepository:
			L10n.string("File is outside the Git repository")
		}
	}
}

@MainActor final class GitCoordinator: NSObject {
	private static let historyDateFormatter: DateFormatter = {
		let formatter = DateFormatter()
		formatter.dateFormat = "yyyy-MM-dd HH:mm"
		return formatter
	}()

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
	private var gitSummaryHint: NSTextField?
	private var gitBodyHint: NSTextField?
	private var gitCommitButton: NSButton?
	private var gitSignoffButton: NSButton?
	private var gitAmendButton: NSButton?
	private var gitCommitOutputButton: NSButton?
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

	private typealias GitDiffLineItem = DiffSelectionContext

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
	private let gitStatusRefreshCoordinator = GitStatusRefreshCoordinator()
	private var gitStatusRefreshTask: Task<Void, Never>?
	private var gitDiffFiles: [DiffFile] = []
	private var gitDiffPath: String?
	private var gitHunkItems: [GitDiffHunkItem] = []
	private var gitUnifiedLineItems: [GitDiffLineItem] = []
	private var gitRemoteProcess: Process?
	private var gitRemoteCancelButton: NSButton?
	private var gitRemoteWasCancelled = false
	private var gitRemoteLog = ""
	private var gitCommitLog = ""
	private var gitHistoryPanel: NSPanel?
	private var gitGraphPanel: NSPanel?
	private var gitGraphTextView: NSTextView?
	private var gitGraphLoadMoreButton: NSButton?
	private var gitGraphCancelButton: NSButton?
	private var gitGraphEntries: [GitGraphEntry] = []
	private let gitHistoryPager = GitHistoryPager()
	private var gitGraphLoadTask: Task<Void, Never>?
	private var gitConflictPanel: NSPanel?
	private var gitConflictRootURL: URL?
	private var gitConflictPath: String?
	private var gitConflictMergedTextView: NSTextView?
	private var gitConflictRegionStack: NSStackView?
	private var gitConflictStateLabel: NSTextField?
	private var gitConflictSelectedRegionIndex = 0

	init(documentController: ItsyDocumentController, activeDocumentProvider: @escaping () -> NSDocument?) {
		self.documentController = documentController
		self.activeDocumentProvider = activeDocumentProvider
		super.init()
	}

	func applyEditorPreferences(_ preferences: EditorPreferences) {
		gitUnifiedDiffView?.configureEditorAppearance(
			fontName: preferences.fontName,
			fontSize: preferences.fontSize,
			showsLineNumbers: preferences.showLineNumbers
		)
		gitUnifiedDiffView?.applyEditorColorPalette(AppTheme.palette.editor)
		gitSideOldDiffView?.configureEditorAppearance(
			fontName: preferences.fontName,
			fontSize: preferences.fontSize,
			showsLineNumbers: preferences.showLineNumbers
		)
		gitSideOldDiffView?.applyEditorColorPalette(AppTheme.palette.editor)
		gitSideNewDiffView?.configureEditorAppearance(
			fontName: preferences.fontName,
			fontSize: preferences.fontSize,
			showsLineNumbers: preferences.showLineNumbers
		)
		gitSideNewDiffView?.applyEditorColorPalette(AppTheme.palette.editor)
		if let panel = gitPanel {
			AppThemeApplier.apply(AppTheme.palette, to: panel)
		}
	}

	@objc func showGitChanges(_: Any?) {
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
		let worktreesButton = NSButton(title: L10n.string("Worktrees"), target: self, action: #selector(showGitWorktrees(_:)))
		let historyButton = NSButton(title: L10n.string("History"), target: self, action: #selector(showGitRepositoryHistory(_:)))
		let fetchButton = NSButton(title: L10n.string("Fetch"), target: self, action: #selector(fetchGitRemote(_:)))
		let pullButton = NSButton(title: L10n.string("Pull"), target: self, action: #selector(pullGitRemote(_:)))
		let pushButton = NSButton(title: L10n.string("Push"), target: self, action: #selector(pushGitRemote(_:)))
		let cancelButton = NSButton(title: L10n.string("Cancel"), target: self, action: #selector(cancelGitRemote(_:)))
		cancelButton.isEnabled = false
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshGitChanges(_:)))
		let stageButton = NSButton(title: L10n.string("Stage"), target: self, action: #selector(stageSelectedGitEntries(_:)))
		let unstageButton = NSButton(
			title: L10n.string("Unstage"),
			target: self,
			action: #selector(unstageSelectedGitEntries(_:))
		)
		let buttonStack = NSStackView(views: [
			branchButton,
			worktreesButton,
			historyButton,
			fetchButton,
			pullButton,
			pushButton,
			cancelButton,
			refreshButton,
			stageButton,
			unstageButton,
		])
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
		gitRemoteCancelButton = cancelButton
	}

	private func makeGitDiffPane() -> NSView {
		let container = NSView()
		let titleLabel = NSTextField(labelWithString: L10n.string("Diff"))
		titleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		let modeControl = NSSegmentedControl(
			labels: [L10n.string("Unified"), L10n.string("Side")],
			trackingMode: .selectOne,
			target: self,
			action: #selector(changeGitDiffMode(_:))
		)
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
			view.configureEditorAppearance(
				fontName: preferences.fontName,
				fontSize: preferences.fontSize,
				showsLineNumbers: preferences.showLineNumbers
			)
			view.applyEditorColorPalette(AppTheme.palette.editor)
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
		let signoffButton = NSButton(
			checkboxWithTitle: L10n.string("--signoff"),
			target: self,
			action: #selector(updateGitComposerStateAction(_:))
		)
		let amendButton = NSButton(
			checkboxWithTitle: L10n.string("--amend"),
			target: self,
			action: #selector(updateGitComposerStateAction(_:))
		)
		let commitButton = NSButton(title: L10n.string("Commit"), target: self, action: #selector(commitGitChanges(_:)))
		let outputButton = NSButton(title: L10n.string("Output"), target: self, action: #selector(showGitCommitOutput(_:)))
		outputButton.isEnabled = false
		let footer = NSStackView(views: [statusLabel, signoffButton, amendButton, outputButton, commitButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		footer.distribution = .fill
		for item in [summaryRow, bodyHint, bodyScrollView, footer] {
			item.translatesAutoresizingMaskIntoConstraints = false
			container.addSubview(item)
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
		gitSummaryHint = summaryHint
		gitBodyHint = bodyHint
		gitCommitButton = commitButton
		gitSignoffButton = signoffButton
		gitAmendButton = amendButton
		gitCommitOutputButton = outputButton
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

	@objc private func showGitWorktrees(_: Any?) {
		guard let gitRootURL else {
			return
		}
		do {
			let worktrees = try GitRepository(root: gitRootURL).worktrees()
			let text = worktrees.map { worktree in
				let branch = worktree.branch ?? L10n.string("detached")
				let kind = worktree.isBare ? L10n.string("bare") : branch
				return "\(kind)\t\(worktree.url.path)"
			}.joined(separator: "\n")
			showGitTextPanel(title: L10n.string("Worktrees"), subtitle: gitRootURL.path, text: text.isEmpty ? L10n.string("No worktrees") : text)
		} catch {
			showGitTextPanel(title: L10n.string("Worktrees"), subtitle: gitRootURL.path, text: String(describing: error))
		}
	}

	@objc private func showGitRepositoryHistory(_: Any?) {
		guard let gitRootURL else {
			return
		}
		let panel = makeGitGraphPanelIfNeeded()
		panel.makeKeyAndOrderFront(nil)
		loadGitGraph(root: gitRootURL, reset: true)
	}

	private func makeGitGraphPanelIfNeeded() -> NSPanel {
		if let panel = gitGraphPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 860, height: 520),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Git History")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		let scrollView = NSScrollView()
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		let loadMoreButton = NSButton(title: L10n.string("Load More"), target: self, action: #selector(loadMoreGitHistory(_:)))
		let cancelButton = NSButton(title: L10n.string("Cancel"), target: self, action: #selector(cancelGitHistoryLoad(_:)))
		cancelButton.isEnabled = false
		let footer = NSStackView(views: [loadMoreButton, cancelButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		for view in [scrollView, footer] {
			view.translatesAutoresizingMaskIntoConstraints = false
			contentView.addSubview(view)
		}
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			scrollView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.bottomAnchor.constraint(equalTo: footer.topAnchor, constant: -8),
			footer.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			footer.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
		])
		panel.contentView = contentView
		centerGitPanel(panel, relativeTo: gitPanel ?? NSApp.keyWindow ?? NSApp.mainWindow)
		gitGraphPanel = panel
		gitGraphTextView = textView
		gitGraphLoadMoreButton = loadMoreButton
		gitGraphCancelButton = cancelButton
		return panel
	}

	@objc private func loadMoreGitHistory(_: Any?) {
		guard let gitRootURL else {
			return
		}
		loadGitGraph(root: gitRootURL, reset: false)
	}

	@objc private func cancelGitHistoryLoad(_: Any?) {
		gitGraphLoadTask?.cancel()
		gitGraphLoadTask = nil
		Task { [gitHistoryPager] in
			await gitHistoryPager.cancel()
		}
		gitGraphCancelButton?.isEnabled = false
		gitGraphLoadMoreButton?.isEnabled = true
	}

	private func loadGitGraph(root: URL, reset: Bool) {
		gitGraphLoadTask?.cancel()
		gitGraphLoadMoreButton?.isEnabled = false
		gitGraphCancelButton?.isEnabled = true
		if reset {
			gitGraphEntries = []
			gitGraphTextView?.string = L10n.string("Loading history...")
		}
		gitGraphLoadTask = Task { [weak self, root] in
			guard let self else {
				return
			}
			if reset {
				await gitHistoryPager.reset()
			}
			do {
				guard let page = try await gitHistoryPager.loadNext(loader: { offset, limit in
					try GitRepository(root: root).historyPage(limit: limit, offset: offset).entries
				}), !Task.isCancelled else {
					return
				}
				gitGraphEntries += page.entries
				gitGraphTextView?.string = renderGitGraph(gitGraphEntries)
				gitGraphLoadMoreButton?.isEnabled = page.hasMore
			} catch {
				gitGraphTextView?.string = String(describing: error)
				gitGraphLoadMoreButton?.isEnabled = true
			}
			gitGraphCancelButton?.isEnabled = false
			gitGraphLoadTask = nil
		}
	}

	private func renderGitGraph(_ entries: [GitGraphEntry]) -> String {
		guard !entries.isEmpty else {
			return L10n.string("No history")
		}
		return entries.map { entry in
			let refs = entry.references.isEmpty ? "" : " [\(entry.references.joined(separator: ", "))]"
			let parents = entry.parentOIDs.isEmpty ? "" : " ← \(entry.parentOIDs.map { shortOID($0) }.joined(separator: ", "))"
			return "\(shortOID(entry.history.oid))\(refs)\(parents)\n  \(formatGitDate(entry.history.date))  \(entry.history.author)  \(entry.history.summary)"
		}.joined(separator: "\n\n")
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
		let stashButton = NSButton(
			title: L10n.string("Stash Current Changes..."),
			target: self,
			action: #selector(stashCurrentGitChanges(_:))
		)
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
		   let gitRoot = try? GitRepository.discoverRoot(containing: root)
		{
			return gitRoot
		}
		if let fileURL = (activeDocumentProvider() as? ItsyDocument)?.fileURL,
		   let gitRoot = try? GitRepository.discoverRoot(containing: fileURL)
		{
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

	@objc func applyLatestGitStash(_: Any?) {
		runLatestGitStashAction(title: L10n.string("Apply")) { repository in
			try repository.applyStash("stash@{0}")
		}
	}

	@objc func popLatestGitStash(_: Any?) {
		runLatestGitStashAction(title: L10n.string("Pop")) { repository in
			try repository.popStash("stash@{0}")
		}
	}

	private func runLatestGitStashAction(title: String, action: (GitRepository) throws -> Void) {
		guard let root = currentGitRootURL() else {
			showGitStashAlert(title: L10n.string("\(title) failed"), message: L10n.string("Open a Git repository first"))
			return
		}
		do {
			try action(GitRepository(root: root))
			refreshGitStateAfterStashChange(status: L10n.string("\(title) complete"))
		} catch {
			setGitStashStatus(String(describing: error), isError: true)
			showGitStashAlert(title: L10n.string("\(title) failed"), message: String(describing: error))
		}
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

	@objc private func showGitStashDiffFromRow(_ sender: NSButton) {
		guard let root = gitStashRootURL, sender.tag >= 0, sender.tag < gitStashEntries.count else {
			return
		}
		let entry = gitStashEntries[sender.tag]
		do {
			let diff = try GitRepository(root: root).stashDiff(entry.ref)
			showGitTextPanel(
				title: L10n.string("Stash Diff"),
				subtitle: "\(entry.ref)  \(entry.message)",
				text: diff.isEmpty ? L10n.string("No stash diff") : diff
			)
		} catch {
			setGitStashStatus(String(describing: error), isError: true)
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

	@objc func fetchGitRemote(_: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Fetch"), arguments: repository.fetchArguments())
	}

	@objc func pullGitRemote(_: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Pull"), arguments: repository.pullArguments())
	}

	@objc func pullGitRemoteRebase(_: Any?) {
		guard let gitRootURL else {
			return
		}
		let repository = GitRepository(root: gitRootURL)
		runGitRemoteOperation(title: L10n.string("Pull Rebase"), arguments: repository.pullArguments(mode: .rebase))
	}

	@objc func pushGitRemote(_: Any?) {
		guard let gitRootURL else {
			return
		}
		do {
			let repository = GitRepository(root: gitRootURL)
			try runGitRemoteOperation(title: L10n.string("Push"), arguments: repository.pushArguments())
		} catch {
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc func cancelGitRemote(_: Any?) {
		guard let process = gitRemoteProcess else {
			return
		}
		gitRemoteWasCancelled = true
		gitStatusLabel?.textColor = .secondaryLabelColor
		gitStatusLabel?.stringValue = L10n.string("Canceling Git remote command...")
		if let gitRootURL {
			reportGitRemoteHealth(root: gitRootURL, lifecycle: .stopping, state: .retrying)
		}
		process.terminate()
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
		gitRemoteWasCancelled = false
		gitRemoteCancelButton?.isEnabled = true
		gitRemoteLog = "$ git \(arguments.joined(separator: " "))\n"
		gitStatusLabel?.textColor = .secondaryLabelColor
		gitStatusLabel?.stringValue = "\(title)..."
		reportGitRemoteHealth(root: gitRootURL, lifecycle: .starting, state: .retrying)
		stdout.fileHandleForReading.readabilityHandler = { [weak self] handle in
			let data = handle.availableData
			Task { @MainActor [weak self] in
				self?.appendGitRemoteOutput(data)
			}
		}
		stderr.fileHandleForReading.readabilityHandler = { [weak self] handle in
			let data = handle.availableData
			Task { @MainActor [weak self] in
				self?.appendGitRemoteOutput(data)
			}
		}
		process.terminationHandler = { [weak self] process in
			Task { @MainActor [weak self] in
				stdout.fileHandleForReading.readabilityHandler = nil
				stderr.fileHandleForReading.readabilityHandler = nil
				guard let self else {
					return
				}
				gitRemoteProcess = nil
				gitRemoteCancelButton?.isEnabled = false
				if gitRemoteWasCancelled {
					gitRemoteWasCancelled = false
					gitStatusLabel?.textColor = .secondaryLabelColor
					gitStatusLabel?.stringValue = "\(title) canceled"
					self.reportGitRemoteHealth(root: gitRootURL, lifecycle: .stopped, state: .healthy)
					return
				}
				if process.terminationStatus == 0 {
					gitStatusLabel?.textColor = .secondaryLabelColor
					gitStatusLabel?.stringValue = "\(title) complete"
					self.reportGitRemoteHealth(root: gitRootURL, lifecycle: .stopped, state: .healthy)
					refreshGitChanges(nil)
				} else {
					let outcome = GitRemoteOperationClassifier.classify(
						GitRemoteCommandResult(exitStatus: process.terminationStatus, output: gitRemoteLog)
					)
					gitStatusLabel?.textColor = .systemRed
					gitStatusLabel?.stringValue = "\(title) \(gitRemoteFailureDescription(outcome))"
					self.reportGitRemoteHealth(root: gitRootURL, lifecycle: .stopped, state: .degraded, lastError: "\(title) failed.", remediation: self.gitRemoteRemediation(outcome))
					showGitRemoteFailure(title: "\(title) \(gitRemoteFailureDescription(outcome))")
				}
			}
		}
		do {
			try process.run()
		} catch {
			gitRemoteProcess = nil
			gitRemoteCancelButton?.isEnabled = false
			gitRemoteWasCancelled = false
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
			reportGitRemoteHealth(root: gitRootURL, lifecycle: .stopped, state: .degraded, lastError: "\(title) could not start.", remediation: "Verify Git is installed and retry.")
		}
	}

	private func reportGitRemoteHealth(root: URL, lifecycle: IntegrationLifecycle, state: IntegrationHealthState, lastError: String? = nil, remediation: String? = nil) {
		let path = root.standardizedFileURL.path
		Task {
			await IntegrationHealthStore.shared.report(service: .git, identifier: path, lifecycle: lifecycle, state: state, lastError: lastError, remediation: remediation, detailLogReference: "git://\(path)/remote")
		}
	}

	private func appendGitRemoteOutput(_ data: Data) {
		guard let text = String(data: data, encoding: .utf8), !text.isEmpty else {
			return
		}
		gitRemoteLog += text
		let line = text.split(whereSeparator: \.isNewline).last.map(String.init) ?? text
			.trimmingCharacters(in: .whitespacesAndNewlines)
		if !line.isEmpty {
			gitStatusLabel?.textColor = .secondaryLabelColor
			gitStatusLabel?.stringValue = line
		}
	}

	private func showGitRemoteFailure(title: String) {
		showGitCommandLog(title: title, log: gitRemoteLog)
	}

	private func gitRemoteFailureDescription(_ outcome: GitRemoteOperationOutcome) -> String {
		guard case let .failed(failure) = outcome else {
			return L10n.string("complete")
		}
		return switch failure {
		case .authenticationRequired:
			L10n.string("needs authentication")
		case .nonFastForward:
			L10n.string("rejected: remote has newer commits")
		case .networkUnavailable:
			L10n.string("network unavailable")
		case .cancelled:
			L10n.string("canceled")
		case .commandFailed:
			L10n.string("failed")
		}
	}

	private func gitRemoteRemediation(_ outcome: GitRemoteOperationOutcome) -> String {
		guard case let .failed(failure) = outcome else {
			return "Retry the Git operation."
		}
		return switch failure {
		case .authenticationRequired:
			"Authenticate Git, then retry."
		case .nonFastForward:
			"Pull remote changes before pushing."
		case .networkUnavailable:
			"Check the network connection, then retry."
		case .cancelled:
			"Retry the Git operation when ready."
		case .commandFailed:
			"Review the Git command output and retry."
		}
	}

	private func showGitCommandLog(title: String, log: String) {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = title
		let scrollView = NSScrollView(frame: NSRect(x: 0, y: 0, width: 640, height: 260))
		let textView = NSTextView(frame: scrollView.bounds)
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = log
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		alert.accessoryView = scrollView
		alert.addButton(withTitle: L10n.string("OK"))
		alert.runModal()
	}

	@objc func showGitBlame(_: Any?) {
		do {
			let context = try currentGitFileContext()
			let lines = try GitRepository(root: context.root).blame(path: context.path)
			let text = lines.map { line in
				"\(line.line)\t\(shortOID(line.oid))\t\(line.author)\t\(line.summary)"
			}.joined(separator: "\n")
			showGitTextPanel(
				title: L10n.string("Git Blame"),
				subtitle: context.path,
				text: text.isEmpty ? L10n.string("No blame data") : text
			)
		} catch {
			showGitTextPanel(title: L10n.string("Git Blame"), subtitle: "", text: String(describing: error))
		}
	}

	@objc func showGitFileHistory(_: Any?) {
		do {
			let context = try currentGitFileContext()
			let entries = try GitRepository(root: context.root).fileHistory(path: context.path)
			showGitTextPanel(title: L10n.string("File History"), subtitle: context.path, text: renderGitHistory(entries))
		} catch {
			showGitTextPanel(title: L10n.string("File History"), subtitle: "", text: String(describing: error))
		}
	}

	@objc func showGitLineHistory(_: Any?) {
		do {
			let context = try currentGitFileContext()
			let line = context.document.editor.textStorage.line(forOffset: context.document.editor.selections.primary.head) + 1
			let entries = try GitRepository(root: context.root).lineHistory(path: context.path, line: line)
			showGitTextPanel(
				title: L10n.string("Line History"),
				subtitle: "\(context.path):\(line)",
				text: renderGitHistory(entries)
			)
		} catch {
			showGitTextPanel(title: L10n.string("Line History"), subtitle: "", text: String(describing: error))
		}
	}

	private func currentGitFileContext() throws -> (document: ItsyDocument, root: URL, path: String) {
		guard let document = activeDocumentProvider() as? ItsyDocument, let fileURL = document.fileURL else {
			throw GitNavigationError.noActiveFile
		}
		let root = try GitRepository.discoverRoot(containing: fileURL)
		return try (document, root, relativeGitPath(fileURL: fileURL, root: root))
	}

	private func relativeGitPath(fileURL: URL, root: URL) throws -> String {
		let filePath = fileURL.standardizedFileURL.path
		let rootPath = root.standardizedFileURL.path
		if filePath == rootPath {
			return fileURL.lastPathComponent
		}
		let prefix = rootPath.hasSuffix("/") ? rootPath : "\(rootPath)/"
		guard filePath.hasPrefix(prefix) else {
			throw GitNavigationError.outsideRepository
		}
		return String(filePath.dropFirst(prefix.count))
	}

	private func renderGitHistory(_ entries: [GitHistoryEntry]) -> String {
		guard !entries.isEmpty else {
			return L10n.string("No history")
		}
		return entries.map { entry in
			"\(shortOID(entry.oid))\t\(formatGitDate(entry.date))\t\(entry.author)\t\(entry.summary)"
		}.joined(separator: "\n")
	}

	private func showGitTextPanel(title: String, subtitle: String, text: String) {
		gitHistoryPanel?.close()
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 760, height: 460),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = title
		panel.isReleasedWhenClosed = false
		let contentView = NSView()
		let titleLabel = NSTextField(labelWithString: subtitle.isEmpty ? title : subtitle)
		titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
		let scrollView = NSScrollView()
		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.isRichText = false
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = text
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.hasHorizontalScroller = true
		scrollView.borderType = .bezelBorder
		contentView.addSubview(titleLabel)
		contentView.addSubview(scrollView)
		titleLabel.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		NSLayoutConstraint.activate([
			titleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			titleLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			titleLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			scrollView.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 8),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
		])
		panel.contentView = contentView
		centerGitPanel(panel, relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		panel.makeKeyAndOrderFront(nil)
		gitHistoryPanel = panel
	}

	private func shortOID(_ oid: String) -> String {
		String(oid.prefix(12))
	}

	private func formatGitDate(_ date: Date?) -> String {
		guard let date else {
			return ""
		}
		return Self.historyDateFormatter.string(from: date)
	}

	@objc func refreshGitChanges(_: Any?) {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setGitEntries([], root: nil, status: L10n.string("Open a folder first"), isError: true, branchLabel: nil)
			return
		}
		gitStatusRefreshTask?.cancel()
		gitStatusLabel?.textColor = .secondaryLabelColor
		gitStatusLabel?.stringValue = L10n.string("Refreshing Git status")
		gitStatusRefreshTask = Task { [weak self, root] in
			guard let self else {
				return
			}
			guard let result = await gitStatusRefreshCoordinator.refresh(root: root), !Task.isCancelled else {
				return
			}
			switch result {
			case let .snapshot(snapshot):
				let status = "\(snapshot.syncLabel) - \(snapshot.status.stagedCount) staged, \(snapshot.status.unstagedCount) unstaged"
				setGitEntries(
					snapshot.status.entries,
					root: snapshot.root,
					status: status,
					isError: false,
					branchLabel: snapshot.branchLabel
				)
				ItsyWorkspaceController.refreshGitStatus()
				ItsyGitHunkGutterCoordinator.applyAll()
			case let .failure(message):
				setGitEntries([], root: nil, status: message, isError: true, branchLabel: nil)
			}
		}
	}

	private func setGitEntries(
		_ entries: [GitStatusEntry],
		root: URL?,
		status: String,
		isError: Bool,
		branchLabel: String?
	) {
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

	@objc private func changeGitDiffMode(_: Any?) {
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
		DiffSelectionMapper.contexts(files: files, document: document)
	}

	private func selectedGitLineIndexes(for item: GitDiffHunkItem) throws -> IndexSet {
		guard gitDiffMode == .unified else {
			throw GitLineSelectionError.unifiedModeRequired
		}
		guard let selection = gitUnifiedDiffView?.editor.selections.primary else {
			throw GitLineSelectionError.noChangedLinesSelected
		}
		let indexes = DiffSelectionMapper.lineIndexes(
			selection: selection.range,
			fileIndex: item.fileIndex,
			hunkIndex: item.hunkIndex,
			contexts: gitUnifiedLineItems
		)
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
			SIMD4<Float>(0.56, 0.62, 0.70, 1)
		case .addition:
			SIMD4<Float>(0.28, 0.78, 0.46, 1)
		case .removal:
			SIMD4<Float>(0.93, 0.37, 0.37, 1)
		case .context, .blank:
			nil
		}
	}

	private func syntaxHighlightSpans(for document: RenderedDiffDocument, path: String?) -> [TextHighlightSpan] {
		guard let path,
		      let gitRootURL,
		      let language = SyntaxPipeline.language(forFileURL: gitRootURL.appendingPathComponent(path)),
		      let theme = try? ItsyTheme.loadUserOrDefault().syntax
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
			return try pipeline.highlights(in: tree, source: source, includeInjections: true)
				.flatMap { span -> [TextHighlightSpan] in
					mappings.compactMap { mapping -> TextHighlightSpan? in
						let lower = max(span.range.lowerBound, mapping.source.lowerBound)
						let upper = min(span.range.upperBound, mapping.source.upperBound)
						guard lower < upper, let color = theme.color(for: span.capture) else {
							return nil
						}
						let renderedLower = mapping.rendered.lowerBound + lower - mapping.source.lowerBound
						let renderedUpper = mapping.rendered.lowerBound + upper - mapping.source.lowerBound
						return TextHighlightSpan(
							range: renderedLower ..< renderedUpper,
							color: SIMD4<Float>(color.red, color.green, color.blue, color.alpha)
						)
					}
				}
		} catch {
			return []
		}
	}

	@objc private func updateGitComposerStateAction(_: Any?) {
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
		setGitComposerDraft(
			root.map { GitCommitDraftStore.load(for: $0) } ?? GitCommitDraft(summary: "", body: ""),
			persist: false
		)
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
		let conflictCount = gitEntries.filter(\.isConflict).count
		let summary = gitSummaryField?.stringValue.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
		let body = gitBodyTextView?.string ?? ""
		let amend = gitAmendButton?.state == .on
		let summaryLength = summary.count
		let longestBodyLine = body.split(separator: "\n", omittingEmptySubsequences: false).map(\.count).max() ?? 0
		gitSummaryHint?.stringValue = "\(summaryLength)/50"
		gitSummaryHint?.textColor = summaryLength > 50 ? .systemOrange : .secondaryLabelColor
		gitBodyHint?.stringValue = "Body \(longestBodyLine)/72"
		gitBodyHint?.textColor = longestBodyLine > 72 ? .systemOrange : .secondaryLabelColor
		gitComposerStatusLabel?.textColor = .secondaryLabelColor
		if conflictCount > 0 {
			gitComposerStatusLabel?.stringValue = L10n.string("\(conflictCount) unresolved conflicts")
			gitCommitButton?.isEnabled = false
			return
		}
		let paths = gitEntries.filter(\.isStaged).map(\.path)
		let scope = paths.prefix(3).joined(separator: ", ")
		let suffix = paths.count > 3 ? ", +\(paths.count - 3)" : ""
		let stagedScope = paths.isEmpty ? L10n.string("no staged files") : "\(stagedCount) staged: \(scope)\(suffix)"
		let messageWarning = summaryLength > 50 || longestBodyLine > 72 ? L10n.string("message exceeds recommended width") : ""
		let amendState = amend ? L10n.string("Amend HEAD; ") : ""
		gitComposerStatusLabel?.stringValue = "\(amendState)\(stagedScope)\(messageWarning.isEmpty ? "" : " — \(messageWarning)")"
		gitCommitButton?.title = amend ? L10n.string("Amend") : L10n.string("Commit")
		gitCommitButton?.isEnabled = gitRootURL != nil && (stagedCount > 0 || amend) && !summary.isEmpty
	}

	@objc private func commitGitChanges(_: Any?) {
		guard let gitRootURL else {
			updateGitComposerState()
			return
		}
		let summary = gitSummaryField?.stringValue ?? ""
		let body = gitBodyTextView?.string ?? ""
		let amend = gitAmendButton?.state == .on
		guard (gitEntries.contains(where: \.isStaged) || amend), !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			updateGitComposerState()
			return
		}
		do {
			let result = try GitRepository(root: gitRootURL).commit(
				summary: summary,
				body: body,
				signoff: gitSignoffButton?.state == .on,
				amend: amend
			)
			gitCommitLog = commitCommandLog(summary: summary, body: body, signoff: gitSignoffButton?.state == .on, amend: amend, output: result.output)
			gitCommitOutputButton?.isEnabled = true
			setGitComposerDraft(GitCommitDraft(summary: "", body: ""), persist: true)
			refreshGitChanges(nil)
		} catch {
			gitCommitLog = "$ git commit\n\(String(describing: error))\n"
			gitCommitOutputButton?.isEnabled = true
			gitComposerStatusLabel?.textColor = .systemRed
			gitComposerStatusLabel?.stringValue = L10n.string("Commit failed; view Output")
			gitStatusLabel?.textColor = .systemRed
			gitStatusLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func showGitCommitOutput(_: Any?) {
		showGitCommandLog(title: L10n.string("Commit Output"), log: gitCommitLog)
	}

	private func commitCommandLog(summary: String, body: String, signoff: Bool, amend: Bool, output: String) -> String {
		var arguments = ["commit"]
		if signoff {
			arguments.append("--signoff")
		}
		if amend {
			arguments.append("--amend")
		}
		arguments += ["-m", summary]
		if !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
			arguments += ["-m", body]
		}
		return "$ git \(arguments.joined(separator: " "))\n\(output)"
	}

	private func showPreviousGitCommitMessage() -> Bool {
		guard let gitRootURL,
		      gitSummaryField?.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
		      .isEmpty == true || gitRecentCommitIndex != nil
		else {
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

	@objc private func stageSelectedGitEntries(_: Any?) {
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
			setGitEntries(
				gitEntries,
				root: gitRootURL,
				status: String(describing: error),
				isError: true,
				branchLabel: gitBranchButton?.title
			)
		}
	}

	@objc private func unstageSelectedGitEntries(_: Any?) {
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
			setGitEntries(
				gitEntries,
				root: gitRootURL,
				status: String(describing: error),
				isError: true,
				branchLabel: gitBranchButton?.title
			)
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

	@objc private func openSelectedGitEntry(_: Any?) {
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
		let stateLabel = NSTextField(labelWithString: "")
		stateLabel.font = .systemFont(ofSize: 11)
		stateLabel.textColor = .secondaryLabelColor
		let titleStack = NSStackView(views: [titleLabel, stateLabel])
		titleStack.orientation = .horizontal
		titleStack.alignment = .centerY
		titleStack.spacing = 8
		let sourceSplit = NSSplitView()
		sourceSplit.isVertical = true
		sourceSplit.dividerStyle = .thin
		sourceSplit
			.addArrangedSubview(makeGitConflictPane(title: L10n.string("Ours (:2)"), text: ours, isEditable: false).view)
		sourceSplit
			.addArrangedSubview(makeGitConflictPane(title: L10n.string("Base (:1)"), text: base, isEditable: false).view)
		sourceSplit
			.addArrangedSubview(makeGitConflictPane(title: L10n.string("Theirs (:3)"), text: theirs, isEditable: false).view)
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
		let previousButton = NSButton(
			title: L10n.string("Previous Conflict"),
			target: self,
			action: #selector(previousGitConflict(_:))
		)
		let nextButton = NSButton(title: L10n.string("Next Conflict"), target: self, action: #selector(nextGitConflict(_:)))
		let reloadButton = NSButton(title: L10n.string("Reload"), target: self, action: #selector(reloadGitConflict(_:)))
		let abortButton = NSButton(title: L10n.string("Abort Resolution"), target: self, action: #selector(abortGitConflictResolution(_:)))
		let closeButton = NSButton(title: L10n.string("Close"), target: self, action: #selector(closeGitConflict(_:)))
		let footer = NSStackView(views: [saveButton, previousButton, nextButton, reloadButton, abortButton, closeButton])
		footer.orientation = .horizontal
		footer.alignment = .centerY
		footer.spacing = 8
		let stack = NSStackView(views: [titleStack, sourceSplit, mergedPane.view, regionScrollView, footer])
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
		gitConflictStateLabel = stateLabel
		gitConflictSelectedRegionIndex = 0
		refreshGitConflictRegions()
		refreshGitConflictResolutionState()
		selectGitConflictRegion(index: 0)
		panel.center()
		panel.makeKeyAndOrderFront(nil)
	}

	private func makeGitConflictPane(title: String, text: String,
	                                 isEditable: Bool) -> (view: NSView, textView: NSTextView)
	{
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
			let label = NSTextField(labelWithString: L10n
				.string("Region \(index + 1), lines \(region.startLine + 1)-\(region.endLine)"))
			label.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
			let ours = NSButton(title: L10n.string("Accept Ours"), target: self, action: #selector(acceptGitConflictOurs(_:)))
			let theirs = NSButton(
				title: L10n.string("Accept Theirs"),
				target: self,
				action: #selector(acceptGitConflictTheirs(_:))
			)
			let both = NSButton(title: L10n.string("Accept Both"), target: self, action: #selector(acceptGitConflictBoth(_:)))
			let edit = NSButton(
				title: L10n.string("Edit Manually"),
				target: self,
				action: #selector(editGitConflictManually(_:))
			)
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
		let index = sender.tag
		textView.string = GitConflictParser.resolvedText(textView.string, regionIndex: index, resolution: resolution)
		refreshGitConflictRegions()
		let regions = GitConflictParser.parse(textView.string)
		guard !regions.isEmpty else {
			gitConflictSelectedRegionIndex = 0
			return
		}
		selectGitConflictRegion(index: min(index, regions.count - 1))
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
		selectGitConflictRegion(index: sender.tag)
	}

	@objc private func previousGitConflict(_: Any?) {
		moveGitConflictSelection(delta: -1)
	}

	@objc private func nextGitConflict(_: Any?) {
		moveGitConflictSelection(delta: 1)
	}

	private func moveGitConflictSelection(delta: Int) {
		guard let textView = gitConflictMergedTextView else {
			return
		}
		let regions = GitConflictParser.parse(textView.string)
		guard !regions.isEmpty else {
			return
		}
		let next = (gitConflictSelectedRegionIndex + delta + regions.count) % regions.count
		selectGitConflictRegion(index: next)
	}

	private func selectGitConflictRegion(index: Int) {
		guard let textView = gitConflictMergedTextView else {
			return
		}
		let regions = GitConflictParser.parse(textView.string)
		guard index >= 0, index < regions.count else {
			return
		}
		gitConflictSelectedRegionIndex = index
		textView.setSelectedRange(nsRangeForLines(
			regions[index].startLine ..< regions[index].endLine,
			in: textView.string
		))
		gitConflictPanel?.makeFirstResponder(textView)
	}

	@objc private func saveGitConflict(_: Any?) {
		guard let root = gitConflictRootURL, let path = gitConflictPath, let textView = gitConflictMergedTextView else {
			return
		}
		do {
			try textView.string.write(to: root.appendingPathComponent(path), atomically: true, encoding: .utf8)
			try GitRepository(root: root).stage(paths: [path])
			refreshGitConflictRegions()
			refreshGitConflictResolutionState()
			refreshGitChanges(nil)
		} catch {
			gitConflictStateLabel?.textColor = .systemRed
			gitConflictStateLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func reloadGitConflict(_: Any?) {
		guard let root = gitConflictRootURL, let path = gitConflictPath, let textView = gitConflictMergedTextView else {
			return
		}
		do {
			textView.string = try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
			refreshGitConflictRegions()
			refreshGitConflictResolutionState()
		} catch {
			gitConflictStateLabel?.textColor = .systemRed
			gitConflictStateLabel?.stringValue = String(describing: error)
		}
	}

	@objc private func abortGitConflictResolution(_: Any?) {
		guard let root = gitConflictRootURL, let path = gitConflictPath, confirmAbortGitConflictResolution() else {
			return
		}
		do {
			try GitRepository(root: root).restoreConflictMarkers(path: path)
			reloadGitConflict(nil)
			refreshGitChanges(nil)
		} catch {
			gitConflictStateLabel?.textColor = .systemRed
			gitConflictStateLabel?.stringValue = String(describing: error)
		}
	}

	private func refreshGitConflictResolutionState() {
		guard let root = gitConflictRootURL else {
			return
		}
		do {
			let state = try GitRepository(root: root).conflictResolutionState()
			gitConflictStateLabel?.textColor = .secondaryLabelColor
			gitConflictStateLabel?.stringValue = state.isComplete
				? L10n.string("Resolved in index")
				: L10n.string("\(state.unresolvedPaths.count) unresolved in index")
		} catch {
			gitConflictStateLabel?.textColor = .systemRed
			gitConflictStateLabel?.stringValue = String(describing: error)
		}
	}

	private func confirmAbortGitConflictResolution() -> Bool {
		let alert = NSAlert()
		alert.alertStyle = .warning
		alert.messageText = L10n.string("Abort Conflict Resolution?")
		alert.informativeText = L10n.string("Discard unsaved edits and restore conflict markers for this file.")
		alert.addButton(withTitle: L10n.string("Restore Markers"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		return alert.runModal() == .alertFirstButtonReturn
	}

	@objc private func closeGitConflict(_: Any?) {
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

	func control(_ control: NSControl, textView _: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
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
		if textView === gitBodyTextView, commandSelector == #selector(NSResponder.insertNewline(_:)),
		   currentEventHasCommandModifier()
		{
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

	func tableView(_ tableView: NSTableView, viewFor _: NSTableColumn?, row: Int) -> NSView? {
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
			let hunkButton = NSButton(
				title: item.isStaged ? L10n.string("Unstage Hunk") : L10n.string("Stage Hunk"),
				target: self,
				action: #selector(applyGitHunk(_:))
			)
			hunkButton.bezelStyle = .rounded
			hunkButton.font = .systemFont(ofSize: 10)
			hunkButton.tag = row
			let lineButton = NSButton(
				title: item.isStaged ? L10n.string("Unstage Lines") : L10n.string("Stage Lines"),
				target: self,
				action: #selector(applyGitSelectedLines(_:))
			)
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
			let createButton = NSButton(
				title: L10n.string("Create"),
				target: self,
				action: #selector(createGitBranchFromRow(_:))
			)
			let deleteButton = NSButton(
				title: L10n.string("Delete"),
				target: self,
				action: #selector(deleteGitBranchFromRow(_:))
			)
			for item in [switchButton, createButton, deleteButton] {
				item.bezelStyle = .rounded
				item.font = .systemFont(ofSize: 10)
				item.tag = row
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
			let diffButton = NSButton(
				title: L10n.string("Show Diff"),
				target: self,
				action: #selector(showGitStashDiffFromRow(_:))
			)
			for item in [applyButton, popButton, dropButton, diffButton] {
				item.bezelStyle = .rounded
				item.font = .systemFont(ofSize: 10)
				item.tag = row
			}
			let buttonStack = NSStackView(views: [applyButton, popButton, dropButton, diffButton])
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
				buttonStack.widthAnchor.constraint(equalToConstant: 250),
			])
			return cell
		}
		return nil
	}
}
