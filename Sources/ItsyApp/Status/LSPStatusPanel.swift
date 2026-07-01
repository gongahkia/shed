import AppKit
import Foundation
import ItsyEditor

struct LSPStatusPanelSnapshot {
	var key: LSPSessionKey
	var status: String
	var server: String
	var pid: Int32?
	var startDate: Date?
	var lastError: String
}

final class LSPStatusPanel: NSObject {
	var restartRequested: ((LSPSessionKey) -> Void)?
	var stopRequested: ((LSPSessionKey) -> Void)?

	private var panel: NSPanel?
	private var snapshot: LSPStatusPanelSnapshot?

	func show(snapshot: LSPStatusPanelSnapshot, relativeTo hostWindow: NSWindow?) {
		self.snapshot = snapshot
		let panel = makePanel(snapshot: snapshot)
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
	}

	private func makePanel(snapshot: LSPStatusPanelSnapshot) -> NSPanel {
		if let panel {
			panel.contentView = contentView(snapshot: snapshot)
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 520, height: 300),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("LSP Status")
		panel.isReleasedWhenClosed = false
		panel.contentView = contentView(snapshot: snapshot)
		self.panel = panel
		return panel
	}

	private func contentView(snapshot: LSPStatusPanelSnapshot) -> NSView {
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 300))
		let details = NSTextField(labelWithString: Self.detailsText(snapshot))
		details.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		details.textColor = .labelColor
		details.lineBreakMode = .byTruncatingMiddle

		let errorLabel = NSTextField(labelWithString: L10n.string("Last stderr"))
		errorLabel.font = .systemFont(ofSize: 12, weight: .semibold)

		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
		textView.string = snapshot.lastError.isEmpty ? L10n.string("No stderr captured") : snapshot.lastError
		textView.drawsBackground = false

		let scrollView = NSScrollView()
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.borderType = .bezelBorder

		let restartButton = NSButton(title: L10n.string("Restart"), target: self, action: #selector(restart(_:)))
		let stopButton = NSButton(title: L10n.string("Stop"), target: self, action: #selector(stop(_:)))
		let buttonStack = NSStackView(views: [restartButton, stopButton])
		buttonStack.orientation = .horizontal
		buttonStack.alignment = .centerY
		buttonStack.spacing = 8

		for view in [details, errorLabel, scrollView, buttonStack] {
			view.translatesAutoresizingMaskIntoConstraints = false
			contentView.addSubview(view)
		}
		NSLayoutConstraint.activate([
			details.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			details.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			details.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			errorLabel.leadingAnchor.constraint(equalTo: details.leadingAnchor),
			errorLabel.trailingAnchor.constraint(equalTo: details.trailingAnchor),
			errorLabel.topAnchor.constraint(equalTo: details.bottomAnchor, constant: 12),
			scrollView.leadingAnchor.constraint(equalTo: details.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: details.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: errorLabel.bottomAnchor, constant: 6),
			scrollView.bottomAnchor.constraint(equalTo: buttonStack.topAnchor, constant: -12),
			buttonStack.trailingAnchor.constraint(equalTo: details.trailingAnchor),
			buttonStack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
		])
		return contentView
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(560, max(460, hostFrame.width - 120))
		let height = min(340, max(260, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	private static func detailsText(_ snapshot: LSPStatusPanelSnapshot) -> String {
		[
			"Language: \(snapshot.key.languageID)",
			"Workspace: \(snapshot.key.workspaceRoot.path)",
			"Status: \(snapshot.status)",
			"Server: \(snapshot.server)",
			"PID: \(snapshot.pid.map(String.init) ?? "-")",
			"Uptime: \(uptimeText(since: snapshot.startDate))",
		].joined(separator: "\n")
	}

	private static func uptimeText(since startDate: Date?) -> String {
		guard let startDate else {
			return "-"
		}
		let seconds = max(0, Int(Date().timeIntervalSince(startDate)))
		return "\(seconds / 60)m \(seconds % 60)s"
	}

	@objc private func restart(_ sender: NSButton) {
		guard let snapshot else {
			return
		}
		restartRequested?(snapshot.key)
	}

	@objc private func stop(_ sender: NSButton) {
		guard let snapshot else {
			return
		}
		stopRequested?(snapshot.key)
	}
}
