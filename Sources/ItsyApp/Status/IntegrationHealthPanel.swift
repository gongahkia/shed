import AppKit
import Foundation
import ItsyEditor

struct IntegrationHealthPanelSnapshot {
	var records: [IntegrationHealthRecord]
}

@MainActor final class IntegrationHealthPanel: NSObject {
	private var panel: NSPanel?

	func show(snapshot: IntegrationHealthPanelSnapshot, relativeTo hostWindow: NSWindow?) {
		let panel = makePanel(snapshot: snapshot)
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
	}

	private func makePanel(snapshot: IntegrationHealthPanelSnapshot) -> NSPanel {
		if let panel {
			panel.contentView = contentView(snapshot: snapshot)
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 620, height: 360),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Integration Health")
		panel.isReleasedWhenClosed = false
		panel.contentView = contentView(snapshot: snapshot)
		self.panel = panel
		return panel
	}

	private func contentView(snapshot: IntegrationHealthPanelSnapshot) -> NSView {
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 620, height: 360))
		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textView.string = Self.text(snapshot)
		textView.drawsBackground = false
		let scrollView = NSScrollView()
		scrollView.documentView = textView
		scrollView.hasVerticalScroller = true
		scrollView.borderType = .bezelBorder
		scrollView.translatesAutoresizingMaskIntoConstraints = false
		contentView.addSubview(scrollView)
		NSLayoutConstraint.activate([
			scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			scrollView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
		])
		return contentView
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(680, max(520, hostFrame.width - 120))
		let height = min(460, max(300, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	static func text(_ snapshot: IntegrationHealthPanelSnapshot) -> String {
		guard !snapshot.records.isEmpty else {
			return L10n.string("No integration health has been reported")
		}
		return snapshot.records.map { record in
			[
				"Service: \(record.key.service.rawValue) (\(record.key.identifier))",
				"State: \(record.state.rawValue)",
				"Lifecycle: \(record.lifecycle.rawValue)",
				"Last error: \(record.lastError ?? "-")",
				"Remediation: \(record.remediation ?? "-")",
				"Log: \(record.detailLogReference ?? "-")",
			].joined(separator: "\n")
		}.joined(separator: "\n\n")
	}
}
