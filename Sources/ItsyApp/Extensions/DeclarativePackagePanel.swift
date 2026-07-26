import AppKit
import Foundation
import ItsyEditor

@MainActor final class DeclarativePackagePanel: NSObject {
	private let workspaceRootProvider: () -> URL?
	private let scopePopup = NSPopUpButton(frame: .zero, pullsDown: false)
	private let sourcePopup = NSPopUpButton(frame: .zero, pullsDown: false)
	private let detailsView = NSTextView()
	private let statusLabel = NSTextField(labelWithString: "")
	private var panel: NSPanel?
	private var sources: [DeclarativePackageSource] = []

	init(workspaceRootProvider: @escaping () -> URL?) {
		self.workspaceRootProvider = workspaceRootProvider
		super.init()
	}

	func show(relativeTo host: NSWindow?) {
		let panel = makePanel()
		if let host {
			panel.setFrameOrigin(NSPoint(x: host.frame.midX - panel.frame.width / 2, y: host.frame.midY - panel.frame.height / 2))
		} else {
			panel.center()
		}
		panel.makeKeyAndOrderFront(nil)
		NSApp.activate(ignoringOtherApps: true)
		refresh()
	}

	private func makePanel() -> NSPanel {
		if let panel { return panel }
		let content = NSView(frame: NSRect(x: 0, y: 0, width: 700, height: 430))
		let panel = NSPanel(contentRect: content.frame, styleMask: [.titled, .closable, .resizable, .utilityWindow], backing: .buffered, defer: false)
		panel.title = L10n.string("Declarative Packages")
		panel.isReleasedWhenClosed = false
		panel.contentView = content
		let title = NSTextField(labelWithString: L10n.string("Trusted declarative package sources"))
		title.font = .systemFont(ofSize: 13, weight: .semibold)
		scopePopup.addItems(withTitles: [L10n.string("Global"), L10n.string("Project")])
		scopePopup.target = self
		scopePopup.action = #selector(scopeChanged(_:))
		sourcePopup.target = self
		sourcePopup.action = #selector(sourceChanged(_:))
		detailsView.isEditable = false
		detailsView.isSelectable = true
		detailsView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		let scroll = NSScrollView()
		scroll.hasVerticalScroller = true
		scroll.borderType = .bezelBorder
		scroll.documentView = detailsView
		let refresh = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshRequested(_:)))
		let update = NSButton(title: L10n.string("Update"), target: self, action: #selector(updateSelected(_:)))
		let enabled = NSButton(title: L10n.string("Enable/Disable"), target: self, action: #selector(toggleEnabled(_:)))
		let remove = NSButton(title: L10n.string("Remove"), target: self, action: #selector(removeSelected(_:)))
		let actions = NSStackView(views: [refresh, update, enabled, remove])
		actions.orientation = .horizontal
		actions.spacing = 8
		for view in [title, scopePopup, sourcePopup, scroll, actions, statusLabel] {
			view.translatesAutoresizingMaskIntoConstraints = false
			content.addSubview(view)
		}
		NSLayoutConstraint.activate([
			title.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 14),
			title.topAnchor.constraint(equalTo: content.topAnchor, constant: 14),
			scopePopup.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			scopePopup.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 10),
			sourcePopup.leadingAnchor.constraint(equalTo: scopePopup.trailingAnchor, constant: 10),
			sourcePopup.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -14),
			sourcePopup.centerYAnchor.constraint(equalTo: scopePopup.centerYAnchor),
			scroll.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			scroll.trailingAnchor.constraint(equalTo: sourcePopup.trailingAnchor),
			scroll.topAnchor.constraint(equalTo: scopePopup.bottomAnchor, constant: 10),
			scroll.bottomAnchor.constraint(equalTo: actions.topAnchor, constant: -12),
			actions.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			actions.bottomAnchor.constraint(equalTo: statusLabel.topAnchor, constant: -10),
			statusLabel.leadingAnchor.constraint(equalTo: title.leadingAnchor),
			statusLabel.trailingAnchor.constraint(equalTo: sourcePopup.trailingAnchor),
			statusLabel.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -14),
		])
		self.panel = panel
		return panel
	}

	private var scope: DeclarativePackageScope { scopePopup.indexOfSelectedItem == 1 ? .project : .global }
	private var selectedSource: DeclarativePackageSource? { sourcePopup.selectedItem?.representedObject as? DeclarativePackageSource }

	private func store() -> DeclarativePackageStore {
		DeclarativePackageStore(projectURL: workspaceRootProvider().map(DeclarativePackageStore.defaultProjectURL))
	}

	private func refresh() {
		scopePopup.item(at: 1)?.isEnabled = workspaceRootProvider() != nil
		if scope == .project, workspaceRootProvider() == nil { scopePopup.selectItem(at: 0) }
		do {
			sources = try store().load(scope: scope).sources
			sourcePopup.removeAllItems()
			for source in sources.sorted(by: { $0.id.localizedStandardCompare($1.id) == .orderedAscending }) {
				sourcePopup.addItem(withTitle: source.id)
				sourcePopup.lastItem?.representedObject = source
			}
			statusLabel.textColor = .secondaryLabelColor
			statusLabel.stringValue = sources.isEmpty ? L10n.string("No configured sources.") : L10n.string("Select Update to fetch, verify, and install a source.")
			refreshDetails()
		} catch {
			statusLabel.textColor = .systemRed
			statusLabel.stringValue = String(describing: error)
			sources = []
			sourcePopup.removeAllItems()
			refreshDetails()
		}
	}

	private func refreshDetails() {
		guard let source = selectedSource else {
			detailsView.string = L10n.string("No configured package source.")
			return
		}
		let root = workspaceRootProvider() ?? URL(fileURLWithPath: "/")
		let status = DeclarativePackageInspector.inspect(source: source, scope: scope, repoRoot: root, workspaceRoot: root)
		let trust: String
		switch status.trust {
		case let .allow(record): trust = "Allowed by \(record.signer ?? "unknown source")"
		case let .deny(record): trust = "Denied by \(record.signer ?? "unknown source")"
		case .missing: trust = "Missing"
		}
		detailsView.string = [
			"ID: \(source.packageID)",
			"Scope: \(scope.rawValue)",
			"Source: \(status.sourceDescription)",
			"Version: \(status.version)",
			"Trust: \(trust)",
			"Enabled: \(source.enabled ? "yes" : "no")",
			source.resourceFilters.isEmpty ? nil : "Resource filters: \(source.resourceFilters.map { "\($0.resource)=\($0.enabled ? "allow" : "deny")" }.joined(separator: ", "))",
			status.error.map { "Error: \($0)" },
		].compactMap { $0 }.joined(separator: "\n")
	}

	@objc private func scopeChanged(_: Any?) { refresh() }
	@objc private func sourceChanged(_: Any?) { refreshDetails() }
	@objc private func refreshRequested(_: Any?) { refresh() }

	@objc private func toggleEnabled(_: Any?) {
		guard let source = selectedSource else { return }
		do {
			try store().setEnabled(!source.enabled, sourceID: source.id, scope: scope)
			refresh()
		} catch {
			statusLabel.textColor = .systemRed
			statusLabel.stringValue = String(describing: error)
		}
	}

	@objc private func removeSelected(_: Any?) {
		guard let source = selectedSource else { return }
		do {
			try store().remove(sourceID: source.id, scope: scope)
			refresh()
		} catch {
			statusLabel.textColor = .systemRed
			statusLabel.stringValue = String(describing: error)
		}
	}

	@objc private func updateSelected(_: Any?) {
		guard let source = selectedSource, let workspaceRoot = workspaceRootProvider() else {
			statusLabel.textColor = .systemRed
			statusLabel.stringValue = L10n.string("Open a workspace before installing a package.")
			return
		}
		statusLabel.textColor = .secondaryLabelColor
		statusLabel.stringValue = L10n.string("Validating and installing \(source.id)…")
		Task { [weak self] in
			do {
				let receipt = try await DeclarativePackageManager.install(source: source, scope: self?.scope ?? .global, repoRoot: workspaceRoot, workspaceRoot: workspaceRoot)
				guard let self else { return }
				self.statusLabel.stringValue = L10n.string("Installed \(receipt.identifier) \(receipt.version).")
				self.refreshDetails()
			} catch {
				guard let self else { return }
				self.statusLabel.textColor = .systemRed
				self.statusLabel.stringValue = String(describing: error)
			}
		}
	}
}
