import AppKit
import Foundation

@MainActor final class WorkbenchRecoveryPanel: NSObject {
	private let openSettings: () -> Void
	private let restoreDefaults: () -> Void
	private let generateDoctor: () -> URL?
	private var panel: NSPanel?
	private var diagnosticLabel: NSTextField?

	init(openSettings: @escaping () -> Void, restoreDefaults: @escaping () -> Void, generateDoctor: @escaping () -> URL?) {
		self.openSettings = openSettings
		self.restoreDefaults = restoreDefaults
		self.generateDoctor = generateDoctor
	}

	func show(diagnostic: String, relativeTo window: NSWindow?) {
		let panel = makePanelIfNeeded()
		diagnosticLabel?.stringValue = diagnostic
		if let window {
			panel.setFrameOrigin(NSPoint(x: window.frame.midX - panel.frame.width / 2, y: window.frame.midY - panel.frame.height / 2))
		}
		panel.makeKeyAndOrderFront(nil)
		NSApp.activate(ignoringOtherApps: true)
	}

	func close() {
		panel?.orderOut(nil)
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel { return panel }
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 520, height: 220),
			styleMask: [.titled, .closable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = "Workbench Layout Recovery"
		panel.isReleasedWhenClosed = false
		let content = NSView(frame: panel.contentRect(forFrameRect: panel.frame))
		let title = NSTextField(labelWithString: "Workbench layout is disabled")
		title.font = .systemFont(ofSize: 16, weight: .semibold)
		let diagnostic = NSTextField(wrappingLabelWithString: "")
		diagnostic.textColor = .secondaryLabelColor
		diagnostic.maximumNumberOfLines = 3
		let open = NSButton(title: "Open TOML", target: self, action: #selector(openSettings(_:)))
		let doctor = NSButton(title: "Generate Doctor", target: self, action: #selector(generateDoctorFile(_:)))
		let restore = NSButton(title: "Restore Defaults", target: self, action: #selector(restoreWorkbenchDefaults(_:)))
		let buttons = NSStackView(views: [open, doctor, restore])
		buttons.orientation = .horizontal
		buttons.spacing = 8
		for view in [title, diagnostic, buttons] {
			view.translatesAutoresizingMaskIntoConstraints = false
			content.addSubview(view)
		}
		NSLayoutConstraint.activate([
			title.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 20),
			title.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20),
			title.topAnchor.constraint(equalTo: content.topAnchor, constant: 20),
			diagnostic.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			diagnostic.trailingAnchor.constraint(equalTo: title.trailingAnchor),
			diagnostic.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 12),
			buttons.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			buttons.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -20),
		])
		panel.contentView = content
		self.panel = panel
		diagnosticLabel = diagnostic
		return panel
	}

	@objc private func openSettings(_: Any?) {
		openSettings()
	}

	@objc private func generateDoctorFile(_: Any?) {
		guard let url = generateDoctor() else { return }
		NSWorkspace.shared.open(url)
	}

	@objc private func restoreWorkbenchDefaults(_: Any?) {
		restoreDefaults()
	}
}
