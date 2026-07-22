import AppKit
import Foundation
import ItsyEditor

@MainActor enum ItsyProblemsBridge {
	static var publishDiagnostics: ((WorkspaceProblemSnapshot, String) -> Void)?
	static var resetProblems: ((URL) -> Void)?

	static func publishDiagnostics(_ snapshot: WorkspaceProblemSnapshot, sourceID: String) {
		publishDiagnostics?(snapshot, sourceID)
	}

	static func resetProblems(root: URL) {
		resetProblems?(root)
	}
}

@MainActor final class ProblemsCoordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate {
	private let documentController: ItsyDocumentController
	private var problemsPanel: NSPanel?
	private var problemsStatusLabel: NSTextField?
	private var problemsTableView: NSTableView?
	private var workspaceProblems: [WorkspaceProblem] = []
	private var problemsRootURL: URL?
	private var problemSnapshotsBySource: [String: WorkspaceProblemSnapshot] = [:]

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	@objc func showProblems(_ sender: Any?) {
		toggleProblems(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	func setProblems(_ snapshot: WorkspaceProblemSnapshot) {
		problemSnapshotsBySource.removeAll()
		applyProblems(snapshot)
	}

	func setProblems(_ snapshot: WorkspaceProblemSnapshot, sourceID: String) {
		if let problemsRootURL, problemsRootURL != snapshot.root {
			problemSnapshotsBySource.removeAll()
		}
		problemSnapshotsBySource[sourceID] = snapshot
		let problems = problemSnapshotsBySource.values
			.filter { $0.root == snapshot.root }
			.flatMap(\.problems)
		applyProblems(WorkspaceProblemSnapshot(root: snapshot.root, problems: problems))
	}

	func resetProblems(root: URL) {
		setProblems(WorkspaceProblemSnapshot(root: root, problems: []))
	}

	@objc func showNextProblem(_ sender: Any?) {
		navigateProblem(delta: 1)
	}

	@objc func showPreviousProblem(_ sender: Any?) {
		navigateProblem(delta: -1)
	}

	private func applyProblems(_ snapshot: WorkspaceProblemSnapshot) {
		workspaceProblems = snapshot.problems
		problemsRootURL = snapshot.root
		problemsTableView?.reloadData()
		refreshProblemsStatus()
		if !workspaceProblems.isEmpty {
			problemsTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		ItsyProblemGutterCoordinator.setProblems(
			root: problemsRootURL,
			problems: workspaceProblems,
			selectProblem: { [weak self] index in
				self?.focusProblem(index: index)
			},
			openRelated: { [weak self] related in
				self?.openRelatedProblemLocation(related)
			}
		)
	}

	private func toggleProblems(relativeTo hostWindow: NSWindow?) {
		if problemsPanel?.isVisible == true {
			closeProblems()
			return
		}
		showProblems(relativeTo: hostWindow)
	}

	private func closeProblems() {
		problemsPanel?.close()
	}

	private func showProblems(relativeTo hostWindow: NSWindow?) {
		let panel = makeProblemsPanelIfNeeded()
		centerProblemsPanel(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		problemsTableView?.reloadData()
		if !workspaceProblems.isEmpty, problemsTableView?.selectedRow ?? -1 < 0 {
			problemsTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
		refreshProblemsStatus()
	}

	private func makeProblemsPanelIfNeeded() -> NSPanel {
		if let panel = problemsPanel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 720, height: 420),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Problems")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		panel.contentView = contentView
		configureProblemsView(contentView)
		problemsPanel = panel
		return panel
	}

	private func configureProblemsView(_ contentView: NSView) {
		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 12)
		statusLabel.textColor = .secondaryLabelColor
		let tableView = NSTableView()
		let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("problem"))
		column.title = L10n.string("Problems")
		column.resizingMask = .autoresizingMask
		tableView.addTableColumn(column)
		tableView.headerView = nil
		tableView.rowSizeStyle = .small
		tableView.dataSource = self
		tableView.delegate = self
		tableView.target = self
		tableView.doubleAction = #selector(openSelectedProblem(_:))
		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			statusLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			statusLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			statusLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
		])
		problemsStatusLabel = statusLabel
		problemsTableView = tableView
	}

	private func centerProblemsPanel(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(760, max(560, hostFrame.width - 100))
		let height = min(460, max(300, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func refreshProblemsStatus() {
		let errors = workspaceProblems.filter { $0.severity == .error }.count
		let warnings = workspaceProblems.filter { $0.severity == .warning }.count
		problemsStatusLabel?.stringValue = L10n.string("\(errors) errors, \(warnings) warnings, \(workspaceProblems.count) total")
	}

	@objc private func openSelectedProblem(_ sender: Any?) {
		guard let tableView = problemsTableView,
		      tableView.selectedRow >= 0,
		      tableView.selectedRow < workspaceProblems.count
		else {
			return
		}
		openProblem(at: tableView.selectedRow)
	}

	private func navigateProblem(delta: Int) {
		guard !workspaceProblems.isEmpty else {
			return
		}
		let selected = problemsTableView?.selectedRow ?? -1
		let current = selected >= 0 && selected < workspaceProblems.count ? selected : (delta > 0 ? -1 : 0)
		let next = (current + delta + workspaceProblems.count) % workspaceProblems.count
		openProblem(at: next)
		focusProblem(index: next)
	}

	private func openProblem(at index: Int) {
		guard let problemsRootURL, index >= 0, index < workspaceProblems.count else {
			return
		}
		let problem = workspaceProblems[index]
		let url = problemsRootURL.appendingPathComponent(problem.path)
		_ = documentController.openDocument(at: url, line: problem.line, column: problem.column ?? 1)
		if let document = documentController.document(for: url) as? ItsyDocument {
			ItsyProblemGutterCoordinator.apply(to: document)
		}
	}

	private func focusProblem(index: Int) {
		guard index >= 0, index < workspaceProblems.count else {
			return
		}
		showProblems(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		problemsTableView?.selectRowIndexes(IndexSet(integer: index), byExtendingSelection: false)
		problemsTableView?.scrollRowToVisible(index)
		problemsPanel?.makeKeyAndOrderFront(nil)
	}

	private func openRelatedProblemLocation(_ related: WorkspaceProblemRelatedInformation) {
		guard let problemsRootURL else {
			return
		}
		let url = problemsRootURL.appendingPathComponent(related.path)
		_ = documentController.openDocument(at: url, line: related.line, column: related.column ?? 1)
		if let document = documentController.document(for: url) as? ItsyDocument {
			ItsyProblemGutterCoordinator.apply(to: document)
		}
	}

	private func problemTitle(_ problem: WorkspaceProblem) -> String {
		let column = problem.column.map { ":\($0)" } ?? ""
		return "\(problem.severity.rawValue)  \(problem.path):\(problem.line)\(column)  \(problem.message)"
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		tableView === problemsTableView ? workspaceProblems.count : 0
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard tableView === problemsTableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("ProblemCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		textField.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textField.lineBreakMode = .byTruncatingTail
		textField.stringValue = problemTitle(workspaceProblems[row])
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

	#if DEBUG
	var problemCountForTesting: Int {
		workspaceProblems.count
	}

	var problemsForTesting: [WorkspaceProblem] {
		workspaceProblems
	}

	var selectedProblemIndexForTesting: Int? {
		guard let selectedRow = problemsTableView?.selectedRow, selectedRow >= 0 else {
			return nil
		}
		return selectedRow
	}

	var statusTextForTesting: String? {
		problemsStatusLabel?.stringValue
	}

	func showProblemsForTesting() {
		showProblems(relativeTo: nil)
	}

	func focusProblemForTesting(index: Int) {
		focusProblem(index: index)
	}

	func openSelectedProblemForTesting() {
		openSelectedProblem(nil)
	}

	func showNextProblemForTesting() {
		showNextProblem(nil)
	}

	func showPreviousProblemForTesting() {
		showPreviousProblem(nil)
	}

	func closeProblemsForTesting() {
		closeProblems()
	}
	#endif
}
