import AppKit
import Dispatch
import Foundation
import ItsyEditor

@MainActor final class ProjectFindCoordinator: NSObject, NSTextFieldDelegate, NSTableViewDataSource, NSTableViewDelegate {
	private let documentController: ItsyDocumentController
	private var projectFindPanel: NSPanel?
	private var projectFindInputField: NSTextField?
	private var projectReplaceInputField: NSTextField?
	private var projectFindStatusLabel: NSTextField?
	private var projectFindTableView: NSTableView?
	private var projectReplacePreviewButton: NSButton?
	private var projectFindMatches: [ProjectFindMatch] = []
	private var projectFindGeneration = 0

	init(documentController: ItsyDocumentController) {
		self.documentController = documentController
	}

	@objc func showProjectFind(_ sender: Any?) {
		toggleProjectFind(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	private func toggleProjectFind(relativeTo hostWindow: NSWindow?) {
		if projectFindPanel?.isVisible == true {
			closeProjectFind()
			return
		}
		showProjectFind(relativeTo: hostWindow)
	}

	private func closeProjectFind() {
		projectFindPanel?.close()
	}

	private func showProjectFind(relativeTo hostWindow: NSWindow?) {
		let panel = makeProjectFindPanelIfNeeded()
		centerProjectFind(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		panel.orderFrontRegardless()
		projectFindPanel?.makeFirstResponder(projectFindInputField)
		updateProjectFindStatusForCurrentWorkspace()
	}

	private func makeProjectFindPanelIfNeeded() -> NSPanel {
		if let panel = projectFindPanel {
			return panel
		}
		let size = NSSize(width: 760, height: 380)
		let panel = NSPanel(
			contentRect: NSRect(origin: .zero, size: size),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		let contentView = NSView(frame: NSRect(origin: .zero, size: size))
		configureProjectFindView(contentView)
		panel.contentView = contentView
		panel.title = L10n.string("Find in Project")
		panel.isReleasedWhenClosed = false
		panel.minSize = NSSize(width: 520, height: 260)
		projectFindPanel = panel
		return panel
	}

	private func configureProjectFindView(_ contentView: NSView) {
		contentView.wantsLayer = true
		contentView.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor

		let queryField = NSTextField(frame: .zero)
		queryField.placeholderString = L10n.string("Find in project")
		queryField.font = .systemFont(ofSize: 15)
		queryField.isBordered = true
		queryField.focusRingType = .default
		queryField.translatesAutoresizingMaskIntoConstraints = false
		queryField.delegate = self
		contentView.addSubview(queryField)
		let replaceField = NSTextField(frame: .zero)
		replaceField.placeholderString = L10n.string("Replace with")
		replaceField.font = .systemFont(ofSize: 13)
		replaceField.isBordered = true
		replaceField.focusRingType = .default
		replaceField.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(replaceField)

		let previewButton = NSButton(title: L10n.string("Preview Replace"), target: self, action: #selector(previewProjectReplace(_:)))
		previewButton.bezelStyle = .rounded
		previewButton.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(previewButton)

		let statusLabel = NSTextField(labelWithString: "")
		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(statusLabel)

		let tableView = NSTableView()
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
		tableView.doubleAction = #selector(openSelectedProjectFindMatch(_:))

		let scrollView = NSScrollView()
		scrollView.documentView = tableView
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = true
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)

		NSLayoutConstraint.activate([
			queryField.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			queryField.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			queryField.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			queryField.heightAnchor.constraint(equalToConstant: 28),
			statusLabel.leadingAnchor.constraint(equalTo: queryField.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: queryField.trailingAnchor),
			replaceField.leadingAnchor.constraint(equalTo: queryField.leadingAnchor),
			replaceField.trailingAnchor.constraint(equalTo: previewButton.leadingAnchor, constant: -8),
			replaceField.topAnchor.constraint(equalTo: queryField.bottomAnchor, constant: 8),
			replaceField.heightAnchor.constraint(equalToConstant: 26),
			previewButton.trailingAnchor.constraint(equalTo: queryField.trailingAnchor),
			previewButton.centerYAnchor.constraint(equalTo: replaceField.centerYAnchor),
			statusLabel.topAnchor.constraint(equalTo: replaceField.bottomAnchor, constant: 6),
			statusLabel.heightAnchor.constraint(equalToConstant: 16),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			scrollView.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 8),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
		])
		projectFindInputField = queryField
		projectReplaceInputField = replaceField
		projectFindStatusLabel = statusLabel
		projectFindTableView = tableView
		projectReplacePreviewButton = previewButton
	}

	private func centerProjectFind(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(760, max(520, hostFrame.width - 80))
		let height = min(420, max(260, hostFrame.height - 120))
		let frame = NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: true)
	}

	private func updateProjectFindStatusForCurrentWorkspace() {
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setProjectFindResults([])
			setProjectFindStatus(L10n.string("Open a folder first"))
			return
		}
		setProjectFindResults([])
		setProjectFindStatus(root.path)
	}

	private func setProjectFindStatus(_ status: String) {
		projectFindStatusLabel?.stringValue = status
	}

	private func setProjectFindResults(_ matches: [ProjectFindMatch]) {
		projectFindMatches = matches
		projectFindTableView?.reloadData()
		if !matches.isEmpty {
			projectFindTableView?.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
		}
	}

	private func searchProjectFind(query: String) {
		projectFindGeneration += 1
		let generation = projectFindGeneration
		guard !query.isEmpty else {
			updateProjectFindStatusForCurrentWorkspace()
			return
		}
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setProjectFindResults([])
			setProjectFindStatus(L10n.string("Open a folder first"))
			return
		}
		setProjectFindStatus(L10n.string("Searching..."))
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			let matches = ProjectFind.search(root: root, options: ProjectFindOptions(query: query))
			DispatchQueue.main.async { [weak self] in
				guard let self, self.projectFindGeneration == generation else {
					return
				}
				self.setProjectFindResults(matches)
				self.setProjectFindStatus(L10n.string("\(matches.count) matches"))
			}
		}
	}

	@objc private func previewProjectReplace(_ sender: Any?) {
		guard let query = projectFindInputField?.stringValue, !query.isEmpty else {
			setProjectFindStatus(L10n.string("Enter text to replace"))
			return
		}
		guard let root = ItsyWorkspaceController.currentRootURL else {
			setProjectFindStatus(L10n.string("Open a folder first"))
			return
		}
		let replacement = projectReplaceInputField?.stringValue ?? ""
		projectReplacePreviewButton?.isEnabled = false
		setProjectFindStatus(L10n.string("Preparing replace preview..."))
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			let preview = ProjectReplace.preview(root: root, options: ProjectReplaceOptions(query: query, replacement: replacement))
			DispatchQueue.main.async { [weak self] in
				guard let self else {
					return
				}
				self.projectReplacePreviewButton?.isEnabled = true
				self.confirmProjectReplace(preview)
			}
		}
	}

	private func confirmProjectReplace(_ preview: ProjectReplacePreview) {
		guard !preview.files.isEmpty else {
			setProjectFindStatus(L10n.string("No replacements"))
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("Replace (preview.replacementCount) matches in (preview.files.count) files?")
		alert.informativeText = preview.files.map { "\($0.relativePath) (\($0.replacementCount))" }.joined(separator: "\n")
		alert.addButton(withTitle: L10n.string("Replace"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else {
			setProjectFindStatus(L10n.string("Replace cancelled"))
			return
		}
		setProjectFindStatus(L10n.string("Replacing..."))
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			do {
				let result = try ProjectReplace.apply(preview)
				DispatchQueue.main.async {
					if let journalURL = result.recoveryJournalURL {
						self?.setProjectFindStatus(L10n.string("Replace complete; recovery journal: \(journalURL.path)"))
					} else {
						self?.setProjectFindStatus(L10n.string("Replaced \(result.replacementCount) matches in \(result.fileCount) files"))
						self?.searchProjectFind(query: self?.projectFindInputField?.stringValue ?? "")
					}
				}
			} catch let error as ProjectReplaceError {
				DispatchQueue.main.async {
					switch error {
					case let .recoveryJournal(url):
						self?.setProjectFindStatus(L10n.string("Replace failed; recovery journal: \(url.path)"))
					default:
						let description = String(describing: error)
						self?.setProjectFindStatus(L10n.string("Replace failed: \(description)"))
					}
				}
			} catch {
				DispatchQueue.main.async {
					self?.setProjectFindStatus(L10n.string("Replace failed: \(error.localizedDescription)"))
				}
			}
		}
	}

	@objc private func openSelectedProjectFindMatch(_ sender: Any?) {
		guard let tableView = projectFindTableView, tableView.selectedRow >= 0, tableView.selectedRow < projectFindMatches.count else {
			return
		}
		_ = documentController.openDocument(at: projectFindMatches[tableView.selectedRow].url)
	}

	func controlTextDidChange(_ notification: Notification) {
		guard let field = notification.object as? NSTextField, field === projectFindInputField else {
			return
		}
		searchProjectFind(query: field.stringValue)
	}

	func control(_ control: NSControl, textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
		if control === projectFindInputField, commandSelector == #selector(NSResponder.cancelOperation(_:)) {
			closeProjectFind()
			return true
		}
		return false
	}

	func numberOfRows(in tableView: NSTableView) -> Int {
		tableView === projectFindTableView ? projectFindMatches.count : 0
	}

	func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
		guard tableView === projectFindTableView else {
			return nil
		}
		let identifier = NSUserInterfaceItemIdentifier("ProjectFindCell")
		let cell = tableView.makeView(withIdentifier: identifier, owner: self) as? NSTableCellView ?? NSTableCellView()
		cell.identifier = identifier
		let textField = cell.textField ?? NSTextField(labelWithString: "")
		let match = projectFindMatches[row]
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
