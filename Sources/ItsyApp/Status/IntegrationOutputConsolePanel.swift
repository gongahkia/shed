import AppKit
import Foundation
import ItsyEditor

struct IntegrationOutputConsolePanelSnapshot {
	var entries: [IntegrationOutputEntry]
	var scopes: [IntegrationOutputScope]
}

@MainActor final class IntegrationOutputConsolePanel: NSObject, NSSearchFieldDelegate {
	private var panel: NSPanel?
	private var textView: NSTextView?
	private var searchField: NSSearchField?
	private var scopePopup: NSPopUpButton?
	private var snapshot = IntegrationOutputConsolePanelSnapshot(entries: [], scopes: [])
	private var selectedScope: IntegrationOutputScope?

	func show(snapshot: IntegrationOutputConsolePanelSnapshot, relativeTo hostWindow: NSWindow?) {
		self.snapshot = snapshot
		let panel = makePanel()
		refreshScopeChoices()
		refreshText()
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
	}

	private func makePanel() -> NSPanel {
		if let panel {
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 820, height: 540),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Integration Output")
		panel.isReleasedWhenClosed = false
		let contentView = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		let scopePopup = NSPopUpButton()
		scopePopup.target = self
		scopePopup.action = #selector(selectScope(_:))
		let searchField = NSSearchField()
		searchField.placeholderString = L10n.string("Filter output")
		searchField.delegate = self
		let copyButton = NSButton(title: L10n.string("Copy"), target: self, action: #selector(copyOutput(_:)))
		let clearButton = NSButton(title: L10n.string("Clear"), target: self, action: #selector(clearOutput(_:)))
		let refreshButton = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshOutput(_:)))
		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textView.drawsBackground = false
		let scrollView = NSScrollView()
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.borderType = .bezelBorder
		for view in [scopePopup, searchField, copyButton, clearButton, refreshButton, scrollView] {
			view.translatesAutoresizingMaskIntoConstraints = false
			contentView.addSubview(view)
		}
		NSLayoutConstraint.activate([
			scopePopup.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			scopePopup.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
			scopePopup.widthAnchor.constraint(equalToConstant: 210),
			searchField.leadingAnchor.constraint(equalTo: scopePopup.trailingAnchor, constant: 8),
			searchField.topAnchor.constraint(equalTo: scopePopup.topAnchor),
			searchField.trailingAnchor.constraint(equalTo: copyButton.leadingAnchor, constant: -8),
			copyButton.topAnchor.constraint(equalTo: scopePopup.topAnchor),
			clearButton.leadingAnchor.constraint(equalTo: copyButton.trailingAnchor, constant: 8),
			clearButton.topAnchor.constraint(equalTo: scopePopup.topAnchor),
			refreshButton.leadingAnchor.constraint(equalTo: clearButton.trailingAnchor, constant: 8),
			refreshButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			refreshButton.topAnchor.constraint(equalTo: scopePopup.topAnchor),
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
			scrollView.topAnchor.constraint(equalTo: scopePopup.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -12),
		])
		panel.contentView = contentView
		self.panel = panel
		self.textView = textView
		self.searchField = searchField
		self.scopePopup = scopePopup
		return panel
	}

	func controlTextDidChange(_ notification: Notification) {
		refreshText()
	}

	@objc private func selectScope(_ sender: NSPopUpButton) {
		selectedScope = sender.indexOfSelectedItem == 0 ? nil : snapshot.scopes[sender.indexOfSelectedItem - 1]
		refreshText()
	}

	@objc private func copyOutput(_ sender: Any?) {
		let pasteboard = NSPasteboard.general
		pasteboard.clearContents()
		pasteboard.setString(outputText(), forType: .string)
	}

	@objc private func clearOutput(_ sender: Any?) {
		Task { [weak self] in
			await IntegrationOutputConsole.shared.clear(scope: self?.selectedScope)
			await self?.reload()
		}
	}

	@objc private func refreshOutput(_ sender: Any?) {
		Task { [weak self] in
			await self?.reload()
		}
	}

	private func reload() async {
		snapshot = IntegrationOutputConsolePanelSnapshot(
			entries: await IntegrationOutputConsole.shared.entries(),
			scopes: await IntegrationOutputConsole.shared.scopes()
		)
		if let selectedScope, !snapshot.scopes.contains(selectedScope) {
			self.selectedScope = nil
		}
		refreshScopeChoices()
		refreshText()
	}

	private func refreshScopeChoices() {
		guard let scopePopup else {
			return
		}
		scopePopup.removeAllItems()
		scopePopup.addItem(withTitle: L10n.string("All integrations"))
		for scope in snapshot.scopes {
			scopePopup.addItem(withTitle: "\(scope.service.rawValue): \(scope.identifier)")
		}
		if let selectedScope, let index = snapshot.scopes.firstIndex(of: selectedScope) {
			scopePopup.selectItem(at: index + 1)
		} else {
			scopePopup.selectItem(at: 0)
		}
	}

	private func refreshText() {
		textView?.string = outputText()
	}

	private func outputText() -> String {
		Self.outputText(snapshot, scope: selectedScope, query: searchField?.stringValue ?? "")
	}

	static func outputText(_ snapshot: IntegrationOutputConsolePanelSnapshot, scope: IntegrationOutputScope? = nil, query: String = "") -> String {
		let query = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
		let formatter = ISO8601DateFormatter()
		let entries = snapshot.entries.filter { entry in
			(scope == nil || entry.scope == scope) && (
				query.isEmpty || entry.text.lowercased().contains(query) || entry.kind.rawValue.contains(query) || entry.scope.identifier.lowercased().contains(query)
			)
		}
		guard !entries.isEmpty else {
			return L10n.string("No integration output")
		}
		return entries.map { entry in
			let prefix = "[\(formatter.string(from: entry.timestamp))] [\(entry.scope.service.rawValue):\(entry.scope.identifier)] [\(entry.kind.rawValue)]"
			let error = entry.errorReference.map { " error=\($0)" } ?? ""
			return "\(prefix)\(error)\n\(entry.text)"
		}.joined(separator: "\n")
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(920, max(620, hostFrame.width - 100))
		let height = min(680, max(400, hostFrame.height - 140))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}
}
