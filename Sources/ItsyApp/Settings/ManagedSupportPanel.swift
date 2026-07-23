import AppKit
import Foundation
import ItsyEditor

final class ManagedSupportRequest: NSObject {
	let componentID: String?

	init(componentID: String?) {
		self.componentID = componentID
	}
}

@MainActor final class ManagedSupportPanel: NSObject {
	private let workspaceRootProvider: () -> URL?
	private let componentPopup = NSPopUpButton(frame: .zero, pullsDown: false)
	private let detailsView = NSTextView()
	private let statusLabel = NSTextField(labelWithString: "")
	private let githubMetadataCheckbox = NSButton(checkboxWithTitle: "Use public GitHub repository metadata on refresh", target: nil, action: nil)
	private let installManagedButton = NSButton(title: L10n.string("Install in Itsy"), target: nil, action: nil)
	private var panel: NSPanel?
	private var workspaceWatcher: WorkspaceFSEventStream?
	private var watchedRoot: URL?
	private var discovery = WorkspaceSupportSnapshot(root: URL(fileURLWithPath: "/"), languageIDs: [], componentIDs: [])
	private var components: [ManagedSupportComponent] { ManagedSupportCatalog.bundled.components }

	init(workspaceRootProvider: @escaping () -> URL?) {
		self.workspaceRootProvider = workspaceRootProvider
		super.init()
	}

	func show(relativeTo hostWindow: NSWindow?, selecting componentID: String? = nil) {
		let panel = makePanelIfNeeded()
		select(componentID: componentID)
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refreshWorkspace(allowGitHubMetadata: false)
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			return panel
		}
		let content = NSView(frame: NSRect(x: 0, y: 0, width: 720, height: 470))
		let panel = NSPanel(contentRect: content.frame, styleMask: [.titled, .closable, .resizable, .utilityWindow], backing: .buffered, defer: false)
		panel.title = L10n.string("Language & Debugger Support")
		panel.isReleasedWhenClosed = false
		panel.contentView = content

		let title = NSTextField(labelWithString: L10n.string("Install support only when you need it"))
		title.font = .systemFont(ofSize: 13, weight: .semibold)
		title.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(title)

		componentPopup.target = self
		componentPopup.action = #selector(componentChanged(_:))
		componentPopup.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(componentPopup)

		detailsView.isEditable = false
		detailsView.isSelectable = true
		detailsView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		detailsView.drawsBackground = false
		let scroll = NSScrollView()
		scroll.hasVerticalScroller = true
		scroll.borderType = .bezelBorder
		scroll.documentView = detailsView
		scroll.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(scroll)

		githubMetadataCheckbox.state = githubMetadataEnabled ? .on : .off
		githubMetadataCheckbox.target = self
		githubMetadataCheckbox.action = #selector(githubMetadataChanged(_:))
		githubMetadataCheckbox.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(githubMetadataCheckbox)

		let refresh = NSButton(title: L10n.string("Refresh workspace"), target: self, action: #selector(refreshRequested(_:)))
		let openSource = NSButton(title: L10n.string("Open official source"), target: self, action: #selector(openSource(_:)))
		let installSystem = NSButton(title: L10n.string("Install system tools"), target: self, action: #selector(installSystemTools(_:)))
		installManagedButton.target = self
		installManagedButton.action = #selector(installManagedSupport(_:))
		let actions = NSStackView(views: [refresh, installManagedButton, openSource, installSystem])
		actions.orientation = .horizontal
		actions.alignment = .centerY
		actions.spacing = 8
		actions.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(actions)

		statusLabel.font = .systemFont(ofSize: 11)
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.lineBreakMode = .byTruncatingMiddle
		statusLabel.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(statusLabel)

		NSLayoutConstraint.activate([
			title.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 14),
			title.topAnchor.constraint(equalTo: content.topAnchor, constant: 14),
			componentPopup.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			componentPopup.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
			componentPopup.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 10),
			scroll.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			scroll.trailingAnchor.constraint(equalTo: componentPopup.trailingAnchor),
			scroll.topAnchor.constraint(equalTo: componentPopup.bottomAnchor, constant: 10),
			scroll.bottomAnchor.constraint(equalTo: githubMetadataCheckbox.topAnchor, constant: -12),
			githubMetadataCheckbox.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			githubMetadataCheckbox.bottomAnchor.constraint(equalTo: actions.topAnchor, constant: -10),
			actions.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			actions.bottomAnchor.constraint(equalTo: statusLabel.topAnchor, constant: -10),
			statusLabel.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: componentPopup.trailingAnchor),
			statusLabel.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -14),
		])
		self.panel = panel
		populateComponents()
		return panel
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		if let hostWindow {
			let frame = hostWindow.frame
			panel.setFrameOrigin(NSPoint(x: frame.midX - panel.frame.width / 2, y: frame.midY - panel.frame.height / 2))
		} else {
			panel.center()
		}
	}

	private func populateComponents() {
		componentPopup.removeAllItems()
		for component in components {
			componentPopup.addItem(withTitle: "\(component.tier == .core ? "Default" : "On demand") · \(component.displayName)")
			componentPopup.lastItem?.representedObject = component.id
		}
		refreshDetails()
	}

	private func select(componentID: String?) {
		guard let componentID, let index = componentPopup.itemArray.firstIndex(where: { $0.representedObject as? String == componentID }) else {
			return
		}
		componentPopup.selectItem(at: index)
		refreshDetails()
	}

	private var selectedComponent: ManagedSupportComponent? {
		guard let id = componentPopup.selectedItem?.representedObject as? String else {
			return nil
		}
		return ManagedSupportCatalog.bundled.component(id: id)
	}

	private func refreshWorkspace(allowGitHubMetadata: Bool) {
		guard let root = workspaceRootProvider() else {
			workspaceWatcher?.stop()
			workspaceWatcher = nil
			watchedRoot = nil
			discovery = WorkspaceSupportSnapshot(root: URL(fileURLWithPath: "/"), languageIDs: [], componentIDs: [])
			statusLabel.stringValue = L10n.string("Open a folder to discover workspace support.")
			refreshDetails()
			return
		}
		startWatchingWorkspace(root: root)
		statusLabel.stringValue = L10n.string("Scanning \(root.path)…")
		DispatchQueue.global(qos: .userInitiated).async { [weak self] in
			let snapshot = WorkspaceSupportScanner.scan(root: root)
			DispatchQueue.main.async {
				guard let self else { return }
				self.discovery = snapshot
				self.statusLabel.stringValue = L10n.string("Found \(snapshot.languageIDs.count) supported languages in \(root.lastPathComponent).")
				self.refreshDetails()
				if allowGitHubMetadata, self.githubMetadataEnabled {
					self.refreshPublicGitHubMetadata(root: root)
				}
			}
		}
	}

	private func startWatchingWorkspace(root: URL) {
		let root = root.standardizedFileURL
		guard watchedRoot != root else { return }
		workspaceWatcher?.stop()
		watchedRoot = root
		let watcher = WorkspaceFSEventStream(root: root) { [weak self] _ in
			DispatchQueue.main.async {
				self?.refreshWorkspace(allowGitHubMetadata: false)
			}
		}
		guard watcher.start() else {
			workspaceWatcher = nil
			return
		}
		workspaceWatcher = watcher
	}

	private func refreshPublicGitHubMetadata(root: URL) {
		guard let remote = gitOrigin(root: root), let repository = GitHubRepositoryLocator.repository(remoteURL: remote) else {
			statusLabel.stringValue = L10n.string("Local scan complete; no GitHub origin was found.")
			return
		}
		Task { [weak self] in
			do {
				let languages = try await GitHubRepositoryLanguageClient.fetchPublicLanguages(for: repository)
				guard let self else { return }
				statusLabel.stringValue = L10n.string("Local scan complete; GitHub reports \(languages.count) languages.")
			} catch {
				guard let self else { return }
				statusLabel.stringValue = L10n.string("Local scan complete; GitHub metadata could not be refreshed.")
			}
		}
	}

	private func gitOrigin(root: URL) -> String? {
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
		process.currentDirectoryURL = root
		process.arguments = ["config", "--get", "remote.origin.url"]
		let output = Pipe()
		process.standardOutput = output
		process.standardError = Pipe()
		guard (try? process.run()) != nil else { return nil }
		process.waitUntilExit()
		guard process.terminationStatus == 0 else { return nil }
		let value = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
		return value.isEmpty ? nil : value
	}

	private func refreshDetails() {
		guard let component = selectedComponent else {
			detailsView.string = ""
			return
		}
		let installed = ManagedSupportResolver.executableURL(for: component)
		let artifact = component.artifacts.artifact(for: .current ?? .arm64)
		let discovered = !Set(component.languageIDs).intersection(discovery.languageIDs).isEmpty
		let enabled = ManagedSupportEnablement.isEnabled(component)
		let state: String
		if !enabled {
			state = "Not enabled"
		} else if let installed {
			state = "Installed by Itsy: \(installed.path)"
		} else if component.installMode == .system {
			state = "System component required"
		} else {
			state = "Not installed"
		}
		installManagedButton.isEnabled = component.installMode == .managed && artifact != nil && installed == nil
		detailsView.string = [
			"Name: \(component.displayName)",
			"Kind: \(component.kind.rawValue)",
			"Tier: \(component.tier.rawValue)",
			"Languages: \(component.languageIDs.joined(separator: ", "))",
			"Workspace need: \(discovered ? "detected" : "not detected")",
			"State: \(state)",
			artifact.map { "Verified download: \($0.version)" } ?? (component.installMode == .managed ? "Verified download: unavailable for this tool" : nil),
			"Source: \(component.officialURL.absoluteString)",
			component.systemInstallHint.map { "System setup: \($0)" },
		].compactMap { $0 }.joined(separator: "\n")
	}

	@objc private func componentChanged(_: Any?) {
		refreshDetails()
	}

	@objc private func refreshRequested(_: Any?) {
		refreshWorkspace(allowGitHubMetadata: true)
	}

	@objc private func githubMetadataChanged(_: Any?) {
		UserDefaults.standard.set(githubMetadataCheckbox.state == .on, forKey: Self.githubMetadataKey)
		refreshWorkspace(allowGitHubMetadata: true)
	}

	@objc private func openSource(_: Any?) {
		guard let component = selectedComponent else { return }
		NSWorkspace.shared.open(component.officialURL)
	}

	@objc private func installManagedSupport(_: Any?) {
		guard let component = selectedComponent, component.installMode == .managed,
			let artifact = component.artifacts.artifact(for: .current ?? .arm64) else {
			NSSound.beep()
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("Install \(component.displayName) in Itsy?")
		alert.informativeText = L10n.string("Itsy will download verified version \(artifact.version) from the official project and install it only in Application Support.")
		alert.addButton(withTitle: L10n.string("Install"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else { return }
		installManagedButton.isEnabled = false
		statusLabel.stringValue = L10n.string("Downloading \(component.displayName)…")
		let request = ManagedSupportInstallRequest(component: component, artifact: artifact)
		Task { [weak self] in
			await IntegrationHealthStore.shared.report(
				service: .package,
				identifier: component.id,
				lifecycle: .starting,
				state: .retrying,
				detailLogReference: "package://\(component.id)/\(artifact.version)"
			)
			do {
				_ = try await ManagedSupportInstaller.downloadAndInstall(request)
				await IntegrationHealthStore.shared.report(
					service: .package,
					identifier: component.id,
					lifecycle: .stopped,
					state: .healthy,
					detailLogReference: "package://\(component.id)/\(artifact.version)"
				)
				guard let self else { return }
				ManagedSupportEnablement.setEnabled(true, for: component)
				statusLabel.stringValue = L10n.string("Installed \(component.displayName) in Itsy.")
				refreshDetails()
			} catch {
				await IntegrationHealthStore.shared.report(
					service: .package,
					identifier: component.id,
					lifecycle: .stopped,
					state: .degraded,
					lastError: String(describing: error),
					remediation: "Review the verified download details and retry.",
					detailLogReference: "package://\(component.id)/\(artifact.version)"
				)
				guard let self else { return }
				statusLabel.stringValue = L10n.string("Installation failed: \(String(describing: error))")
				refreshDetails()
			}
		}
	}

	@objc private func installSystemTools(_: Any?) {
		guard let component = selectedComponent, component.installMode == .system else {
			NSSound.beep()
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("Install Xcode Command Line Tools?")
		alert.informativeText = L10n.string("macOS will handle this installation. Itsy will not install third-party tools globally.")
		alert.addButton(withTitle: L10n.string("Continue"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else { return }
		ManagedSupportEnablement.setEnabled(true, for: component)
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/xcode-select")
		process.arguments = ["--install"]
		try? process.run()
	}

	private static let githubMetadataKey = "itsy.support.githubMetadataEnabled"
	private var githubMetadataEnabled: Bool {
		UserDefaults.standard.bool(forKey: Self.githubMetadataKey)
	}
}
