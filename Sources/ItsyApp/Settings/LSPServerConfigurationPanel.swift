import AppKit
import Foundation
import ItsyEditor

struct LSPServerConfigurationEntry: Equatable {
	var languageID: String
	var command: String
	var arguments: [String]
	var rootPatterns: [String]
	var availability: String
	var remediation: String?

	static func entries(
		registry: LSPServerRegistry,
		environment: [String: String] = ProcessInfo.processInfo.environment
	) -> [LSPServerConfigurationEntry] {
		registry.configs.map { config in
			do {
				let resolution = try registry.executableResolution(forLanguageID: config.languageId, environment: environment)
				let version = resolution.version.map { " · version \($0.major).\($0.minor).\($0.patch)" } ?? ""
				return LSPServerConfigurationEntry(
					languageID: config.languageId,
					command: config.command,
					arguments: config.args,
					rootPatterns: config.rootPatterns,
					availability: "Available · \(resolution.source.rawValue) · \(resolution.executableURL.path)\(version)",
					remediation: nil
				)
			} catch let error as LSPExecutableDetectionError {
				return LSPServerConfigurationEntry(
					languageID: config.languageId,
					command: config.command,
					arguments: config.args,
					rootPatterns: config.rootPatterns,
					availability: availabilityText(for: error),
					remediation: registry.missingBinary(forLanguageID: config.languageId, environment: environment)?.hint
				)
			} catch {
				return LSPServerConfigurationEntry(
					languageID: config.languageId,
					command: config.command,
					arguments: config.args,
					rootPatterns: config.rootPatterns,
					availability: "Unavailable · \(String(describing: error))",
					remediation: registry.missingBinary(forLanguageID: config.languageId, environment: environment)?.hint
				)
			}
		}.sorted { $0.languageID < $1.languageID }
	}

	var detailsText: String {
		var lines = [
			"Language: \(languageID)",
			"Command: \(command)",
			"Arguments: \(arguments.isEmpty ? "(none)" : arguments.joined(separator: " "))",
			"Workspace markers: \(rootPatterns.joined(separator: ", "))",
			"Availability: \(availability)",
		]
		if let remediation {
			lines.append("Fix: \(remediation)")
		}
		return lines.joined(separator: "\n")
	}

	private static func availabilityText(for error: LSPExecutableDetectionError) -> String {
		switch error {
		case let .invalidEnvironmentOverride(variable, value):
			return "Unavailable · invalid \(variable)=\(value)"
		case let .missingExecutable(executable):
			return "Unavailable · \(executable) not found"
		case let .unreadableVersion(executable, path):
			return "Unavailable · cannot read \(executable) version at \(path)"
		case let .unsupportedVersion(executable, found, minimum):
			return "Unavailable · \(executable) \(found.major).\(found.minor).\(found.patch) is below \(minimum.major).\(minimum.minor).\(minimum.patch)"
		}
	}
}

@MainActor final class LSPServerConfigurationPanel: NSObject {
	private let serverPopup = NSPopUpButton(frame: .zero, pullsDown: false)
	private let detailsView = NSTextView()
	private var snapshot: [LSPServerConfigurationEntry] = []
	private var panel: NSPanel?

	func show(relativeTo hostWindow: NSWindow?) {
		reloadSnapshot()
		let panel = makePanelIfNeeded()
		populateServerPopup()
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			return panel
		}
		let contentView = NSView(frame: NSRect(x: 0, y: 0, width: 660, height: 390))
		let panel = NSPanel(
			contentRect: contentView.frame,
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Language Servers")
		panel.isReleasedWhenClosed = false
		panel.contentView = contentView

		let configurationPath = NSTextField(labelWithString: L10n.string("Global config: \(LSPServerRegistryLoader.defaultConfigURL.path)"))
		configurationPath.font = .systemFont(ofSize: 11)
		configurationPath.textColor = .secondaryLabelColor
		configurationPath.lineBreakMode = .byTruncatingMiddle

		serverPopup.target = self
		serverPopup.action = #selector(serverSelectionDidChange(_:))

		detailsView.isEditable = false
		detailsView.isSelectable = true
		detailsView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		detailsView.drawsBackground = false
		let scrollView = NSScrollView()
		scrollView.documentView = detailsView
		scrollView.hasVerticalScroller = true
		scrollView.borderType = .bezelBorder

		let copyButton = NSButton(title: L10n.string("Copy config path"), target: self, action: #selector(copyConfigurationPath(_:)))
		let reloadButton = NSButton(title: L10n.string("Reload LSP configuration"), target: self, action: #selector(reloadConfiguration(_:)))
		let buttonStack = NSStackView(views: [copyButton, reloadButton])
		buttonStack.orientation = .horizontal
		buttonStack.alignment = .centerY
		buttonStack.spacing = 8

		for view in [configurationPath, serverPopup, scrollView, buttonStack] {
			view.translatesAutoresizingMaskIntoConstraints = false
			contentView.addSubview(view)
		}
		NSLayoutConstraint.activate([
			configurationPath.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
			configurationPath.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
			configurationPath.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
			serverPopup.leadingAnchor.constraint(equalTo: configurationPath.leadingAnchor),
			serverPopup.trailingAnchor.constraint(equalTo: configurationPath.trailingAnchor),
			serverPopup.topAnchor.constraint(equalTo: configurationPath.bottomAnchor, constant: 10),
			scrollView.leadingAnchor.constraint(equalTo: configurationPath.leadingAnchor),
			scrollView.trailingAnchor.constraint(equalTo: configurationPath.trailingAnchor),
			scrollView.topAnchor.constraint(equalTo: serverPopup.bottomAnchor, constant: 10),
			scrollView.bottomAnchor.constraint(equalTo: buttonStack.topAnchor, constant: -12),
			buttonStack.trailingAnchor.constraint(equalTo: configurationPath.trailingAnchor),
			buttonStack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
		])
		self.panel = panel
		return panel
	}

	private func reloadSnapshot() {
		snapshot = LSPServerConfigurationEntry.entries(registry: LSPServerRegistryLoader.loadOrBundled())
	}

	private func populateServerPopup() {
		let selectedLanguageID = serverPopup.selectedItem?.representedObject as? String
		serverPopup.removeAllItems()
		for entry in snapshot {
			serverPopup.addItem(withTitle: entry.languageID)
			serverPopup.lastItem?.representedObject = entry.languageID
		}
		if let selectedLanguageID, let item = serverPopup.itemArray.first(where: { $0.representedObject as? String == selectedLanguageID }) {
			serverPopup.select(item)
		} else {
			serverPopup.selectItem(at: 0)
		}
		updateDetails()
	}

	private func updateDetails() {
		guard let languageID = serverPopup.selectedItem?.representedObject as? String,
		      let entry = snapshot.first(where: { $0.languageID == languageID })
		else {
			detailsView.string = L10n.string("No configured language servers")
			return
		}
		detailsView.string = entry.detailsText
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(700, max(520, hostFrame.width - 120))
		let height = min(480, max(340, hostFrame.height - 160))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}

	@objc private func serverSelectionDidChange(_: NSPopUpButton) {
		updateDetails()
	}

	@objc private func copyConfigurationPath(_: NSButton) {
		let pasteboard = NSPasteboard.general
		pasteboard.clearContents()
		pasteboard.setString(LSPServerRegistryLoader.defaultConfigURL.path, forType: .string)
	}

	@objc private func reloadConfiguration(_: NSButton) {
		EditorWindowController.reloadLSPConfiguration()
		reloadSnapshot()
		populateServerPopup()
	}
}
