import AppKit
import Foundation
import ItsyConfig
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
	private let modePopup = NSPopUpButton(frame: .zero, pullsDown: false)
	private let detailsView = NSTextView()
	private let statusLabel = NSTextField(labelWithString: "")
	private let githubMetadataCheckbox = NSButton(checkboxWithTitle: "Use public GitHub repository metadata on refresh", target: nil, action: nil)
	private let catalogAutoCheckCheckbox = NSButton(checkboxWithTitle: "Check signed language-server catalog automatically", target: nil, action: nil)
	private let installManagedButton = NSButton(title: L10n.string("Install in Itsy"), target: nil, action: nil)
	private let removeManagedButton = NSButton(title: L10n.string("Remove managed copy"), target: nil, action: nil)
	private let checkCatalogButton = NSButton(title: L10n.string("Check catalog"), target: nil, action: nil)
	private let applyCatalogButton = NSButton(title: L10n.string("Apply catalog update"), target: nil, action: nil)
	private var panel: NSPanel?
	private var workspaceWatcher: WorkspaceFSEventStream?
	private var watchedRoot: URL?
	private var discovery = WorkspaceSupportSnapshot(root: URL(fileURLWithPath: "/"), languageIDs: [], componentIDs: [])
	private var components: [ManagedSupportComponent] { ManagedSupportCatalogStore.current().components }

	init(workspaceRootProvider: @escaping () -> URL?) {
		self.workspaceRootProvider = workspaceRootProvider
		super.init()
	}

	func show(relativeTo hostWindow: NSWindow?, selecting componentID: String? = nil) {
		let panel = makePanelIfNeeded()
		refreshSettingsControls()
		populateComponents()
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
		modePopup.addItems(withTitles: ItsySettings.LSPMode.allCases.map { $0.rawValue })
		modePopup.target = self
		modePopup.action = #selector(modeChanged(_:))
		let selectors = NSStackView(views: [componentPopup, modePopup])
		selectors.orientation = .horizontal
		selectors.alignment = .centerY
		selectors.spacing = 8
		selectors.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(selectors)

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

		catalogAutoCheckCheckbox.target = self
		catalogAutoCheckCheckbox.action = #selector(catalogAutoCheckChanged(_:))
		catalogAutoCheckCheckbox.translatesAutoresizingMaskIntoConstraints = false
		content.addSubview(catalogAutoCheckCheckbox)

		let refresh = NSButton(title: L10n.string("Refresh workspace"), target: self, action: #selector(refreshRequested(_:)))
		let openSource = NSButton(title: L10n.string("Open official source"), target: self, action: #selector(openSource(_:)))
		let installSystem = NSButton(title: L10n.string("Install system tools"), target: self, action: #selector(installSystemTools(_:)))
		installManagedButton.target = self
		installManagedButton.action = #selector(installManagedSupport(_:))
		removeManagedButton.target = self
		removeManagedButton.action = #selector(removeManagedSupport(_:))
		checkCatalogButton.target = self
		checkCatalogButton.action = #selector(checkCatalog(_:))
		applyCatalogButton.target = self
		applyCatalogButton.action = #selector(applyCatalog(_:))
		let actions = NSStackView(views: [refresh, installManagedButton, removeManagedButton, openSource, installSystem, checkCatalogButton, applyCatalogButton])
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
			selectors.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			selectors.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
			selectors.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 10),
			modePopup.widthAnchor.constraint(equalToConstant: 100),
			scroll.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			scroll.trailingAnchor.constraint(equalTo: selectors.trailingAnchor),
			scroll.topAnchor.constraint(equalTo: selectors.bottomAnchor, constant: 10),
			scroll.bottomAnchor.constraint(equalTo: catalogAutoCheckCheckbox.topAnchor, constant: -12),
			githubMetadataCheckbox.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			githubMetadataCheckbox.bottomAnchor.constraint(equalTo: catalogAutoCheckCheckbox.topAnchor, constant: -6),
			catalogAutoCheckCheckbox.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			catalogAutoCheckCheckbox.bottomAnchor.constraint(equalTo: actions.topAnchor, constant: -10),
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
		let selectedID = selectedComponent?.id
		componentPopup.removeAllItems()
		for component in components {
			componentPopup.addItem(withTitle: "\(component.tier == .core ? "Default" : "On demand") · \(component.displayName)")
			componentPopup.lastItem?.representedObject = component.id
		}
		select(componentID: selectedID)
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
		return ManagedSupportCatalogStore.current().component(id: id)
	}

	private func refreshSettingsControls() {
		let settings = ItsySettingsStore().load().settings
		catalogAutoCheckCheckbox.state = settings.lsp.catalogAutomaticallyCheck ? .on : .off
		let catalogConfigured = ManagedSupportCatalogUpdateConfiguration.bundled() != nil
		catalogAutoCheckCheckbox.isEnabled = catalogConfigured
		checkCatalogButton.isEnabled = catalogConfigured
		if let configuration = ManagedSupportCatalogUpdateConfiguration.bundled() {
			applyCatalogButton.isEnabled = (try? ManagedSupportCatalogUpdateClient.pending(configuration: configuration)) != nil
		} else {
			applyCatalogButton.isEnabled = false
		}
	}

	private func refreshModeControl(for component: ManagedSupportComponent) {
		let settings = ItsySettingsStore().load().settings
		let modes = Set(component.languageIDs.map(settings.lsp.mode(for:)))
		let mode = modes.count == 1 ? modes.first ?? .automatic : .automatic
		modePopup.selectItem(withTitle: mode.rawValue)
		modePopup.toolTip = modes.count == 1 ? nil : "Mixed language modes; selecting a value applies it to every language in this row."
	}

	private func saveSettings(_ mutate: (inout ItsySettings) -> Void) throws -> ItsySettings {
		let store = ItsySettingsStore()
		var settings = store.load().settings
		mutate(&settings)
		try store.save(settings)
		EditorWindowController.reloadLSPConfiguration(settings: settings)
		return settings
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
		refreshModeControl(for: component)
		let installed = ManagedSupportResolver.executableURL(for: component)
		let installedURL = ManagedSupportResolver.installedURL(for: component)
		let artifact = component.artifacts.artifact(for: .current ?? .arm64)
		let nodeSupport = component.nodeSupport
		let discovered = !Set(component.languageIDs).intersection(discovery.languageIDs).isEmpty
		let settings = ItsySettingsStore().load().settings
		let mode = settings.lsp.mode(for: component.languageIDs.first ?? "")
		let registry = LSPServerRegistry()
		let states = component.languageIDs.map { registry.provisioningStatus(forLanguageID: $0, mode: settings.lsp.mode(for: $0)) }
		let state: String
		if states.contains(where: {
			if case .system = $0 { return true }
			return false
		}) {
			state = "Using system installation"
		} else if states.contains(where: {
			if case .managed = $0 { return true }
			return false
		}) {
			state = "Using Itsy-managed installation"
		} else if mode == .disabled {
			state = "Disabled"
		} else if let installed {
			state = "Installed by Itsy: \(installed.path)"
		} else if component.hasVerifiedManagedInstall {
			state = "Available to install in Itsy"
		} else {
			state = "System component required"
		}
		let expectedVersion = artifact?.version ?? nodeSupport?.version
		let installedIsCurrent = installedURL?.lastPathComponent == expectedVersion
		let missingPrivateNode = nodeSupport != nil && ManagedNodeRuntimeInstaller.executableURL() == nil
		installManagedButton.isEnabled = component.hasVerifiedManagedInstall && (!installedIsCurrent || missingPrivateNode)
		removeManagedButton.isEnabled = installedURL != nil
		var details = [
			"Name: \(component.displayName)",
			"Kind: \(component.kind.rawValue)",
			"Tier: \(component.tier.rawValue)",
			"Languages: \(component.languageIDs.joined(separator: ", "))",
			"Workspace need: \(discovered ? "detected" : "not detected")",
			"Mode: \(mode.rawValue)",
			"State: \(state)",
		]
		if let artifact {
			details.append("Verified download: \(artifact.version)")
		} else if let nodeSupport {
			details.append("Verified Node packages: \(nodeSupport.version)")
			details.append("Runtime: Node.js 20 or newer")
		} else if component.installMode == .managed {
			details.append("Verified download: unavailable for this tool")
		}
		details.append("Source: \(component.officialURL.absoluteString)")
		if let hint = component.systemInstallHint {
			details.append("System setup: \(hint)")
		}
		detailsView.string = details.joined(separator: "\n")
		refreshSettingsControls()
	}

	@objc private func componentChanged(_: Any?) {
		refreshDetails()
	}

	@objc private func modeChanged(_: Any?) {
		guard let component = selectedComponent,
		      let title = modePopup.selectedItem?.title,
		      let mode = ItsySettings.LSPMode(rawValue: title)
		else {
			NSSound.beep()
			return
		}
		do {
			_ = try saveSettings { settings in
				for languageID in component.languageIDs {
					if mode == .automatic {
						settings.lsp.modes[languageID] = nil
					} else {
						settings.lsp.modes[languageID] = mode
					}
				}
			}
			statusLabel.stringValue = L10n.string("Updated LSP mode for \(component.displayName).")
			refreshDetails()
		} catch {
			statusLabel.stringValue = L10n.string("Could not save settings: \(String(describing: error))")
			refreshModeControl(for: component)
		}
	}

	@objc private func refreshRequested(_: Any?) {
		refreshWorkspace(allowGitHubMetadata: true)
	}

	@objc private func githubMetadataChanged(_: Any?) {
		UserDefaults.standard.set(githubMetadataCheckbox.state == .on, forKey: Self.githubMetadataKey)
		refreshWorkspace(allowGitHubMetadata: true)
	}

	@objc private func catalogAutoCheckChanged(_: Any?) {
		do {
			_ = try saveSettings { settings in
				settings.lsp.catalogAutomaticallyCheck = catalogAutoCheckCheckbox.state == .on
			}
			statusLabel.stringValue = L10n.string("Signed catalog checking preference saved.")
		} catch {
			statusLabel.stringValue = L10n.string("Could not save settings: \(String(describing: error))")
			refreshSettingsControls()
		}
	}

	@objc private func checkCatalog(_: Any?) {
		guard let configuration = ManagedSupportCatalogUpdateConfiguration.bundled() else {
			statusLabel.stringValue = L10n.string("This build has no signed catalog configuration.")
			return
		}
		checkCatalogButton.isEnabled = false
		statusLabel.stringValue = L10n.string("Checking signed language-server catalog…")
		Task { [weak self] in
			do {
				let catalog = try await ManagedSupportCatalogUpdateClient.check(configuration: configuration)
				guard let self else { return }
				statusLabel.stringValue = L10n.string("Catalog update is ready (\(catalog.components.count) entries).")
				refreshSettingsControls()
			} catch {
				guard let self else { return }
				statusLabel.stringValue = L10n.string("Catalog check failed: \(String(describing: error))")
				refreshSettingsControls()
			}
		}
	}

	@objc private func applyCatalog(_: Any?) {
		guard let configuration = ManagedSupportCatalogUpdateConfiguration.bundled() else { return }
		do {
			let catalog = try ManagedSupportCatalogUpdateClient.applyPending(configuration: configuration)
			EditorWindowController.reloadLSPConfiguration()
			populateComponents()
			statusLabel.stringValue = L10n.string("Applied signed catalog update (\(catalog.components.count) entries).")
		} catch {
			statusLabel.stringValue = L10n.string("Catalog update failed: \(String(describing: error))")
		}
	}

	@objc private func openSource(_: Any?) {
		guard let component = selectedComponent else { return }
		NSWorkspace.shared.open(component.officialURL)
	}

	@objc private func installManagedSupport(_: Any?) {
		guard let component = selectedComponent, component.hasVerifiedManagedInstall else {
			NSSound.beep()
			return
		}
		let artifact = component.artifacts.artifact(for: .current ?? .arm64)
		let nodeSupport = component.nodeSupport
		guard artifact != nil || nodeSupport != nil else {
			NSSound.beep()
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("Install \(component.displayName) in Itsy?")
		let version = artifact?.version ?? nodeSupport!.version
		let runtime = nodeSupport == nil ? "" : " Itsy will also install its private Node.js runtime."
		alert.informativeText = L10n.string("Itsy will download verified version \(version) from the official project and install it only in Application Support.\(runtime)")
		alert.addButton(withTitle: L10n.string("Install"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else { return }
		installManagedButton.isEnabled = false
		statusLabel.stringValue = L10n.string("Downloading \(component.displayName)…")
		Task { [weak self] in
			await IntegrationHealthStore.shared.report(
				service: .package,
				identifier: component.id,
				lifecycle: .starting,
				state: .retrying,
				detailLogReference: "package://\(component.id)/\(version)"
			)
			do {
				if let artifact, ManagedSupportResolver.installedURL(for: component)?.lastPathComponent != artifact.version {
					_ = try await ManagedSupportInstaller.downloadAndInstall(ManagedSupportInstallRequest(component: component, artifact: artifact))
				} else if let nodeSupport {
					if ManagedNodeRuntimeInstaller.executableURL() == nil {
						_ = try await ManagedNodeRuntimeInstaller.downloadAndInstall()
					}
					if ManagedSupportResolver.installedURL(for: component)?.lastPathComponent != nodeSupport.version {
						_ = try await ManagedNodeSupportInstaller.downloadAndInstall(component: component)
					}
				}
				await IntegrationHealthStore.shared.report(
					service: .package,
					identifier: component.id,
					lifecycle: .stopped,
					state: .healthy,
					detailLogReference: "package://\(component.id)/\(version)"
				)
				guard let self else { return }
				ManagedSupportEnablement.setEnabled(true, for: component)
				EditorWindowController.reloadLSPConfiguration()
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
					detailLogReference: "package://\(component.id)/\(version)"
				)
				guard let self else { return }
				statusLabel.stringValue = L10n.string("Installation failed: \(String(describing: error))")
				refreshDetails()
			}
		}
	}

	@objc private func removeManagedSupport(_: Any?) {
		guard let component = selectedComponent,
		      let installedURL = ManagedSupportResolver.installedURL(for: component)
		else {
			NSSound.beep()
			return
		}
		let alert = NSAlert()
		alert.messageText = L10n.string("Remove \(component.displayName) from Itsy?")
		alert.informativeText = L10n.string("This removes \(installedURL.path) and cannot be undone.")
		alert.addButton(withTitle: L10n.string("Remove"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else { return }
		do {
			try FileManager.default.removeItem(at: installedURL)
			ManagedSupportEnablement.setEnabled(false, for: component)
			EditorWindowController.reloadLSPConfiguration()
			statusLabel.stringValue = L10n.string("Removed managed \(component.displayName).")
			refreshDetails()
		} catch {
			statusLabel.stringValue = L10n.string("Removal failed: \(String(describing: error))")
		}
	}

	@objc private func installSystemTools(_: Any?) {
		guard let component = selectedComponent, component.installMode == .system else {
			NSSound.beep()
			return
		}
		let xcodeTool = ["clangd", "lldb-dap", "/usr/bin/xcrun"].contains(component.command)
		let alert = NSAlert()
		alert.messageText = xcodeTool
			? L10n.string("Install Xcode Command Line Tools?")
			: L10n.string("Set up \(component.displayName) on this Mac?")
		alert.informativeText = xcodeTool
			? L10n.string("macOS will handle this installation. Itsy will not install third-party tools globally.")
			: (component.systemInstallHint ?? L10n.string("Itsy will open the official installation instructions; it does not install third-party tools globally."))
		alert.addButton(withTitle: xcodeTool ? L10n.string("Continue") : L10n.string("Open instructions"))
		alert.addButton(withTitle: L10n.string("Cancel"))
		guard alert.runModal() == .alertFirstButtonReturn else { return }
		if xcodeTool {
			let process = Process()
			process.executableURL = URL(fileURLWithPath: "/usr/bin/xcode-select")
			process.arguments = ["--install"]
			try? process.run()
		} else {
			NSWorkspace.shared.open(component.officialURL)
		}
	}

	private static let githubMetadataKey = "itsy.support.githubMetadataEnabled"
	private var githubMetadataEnabled: Bool {
		UserDefaults.standard.bool(forKey: Self.githubMetadataKey)
	}
}
