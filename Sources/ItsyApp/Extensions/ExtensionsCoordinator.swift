import AppKit
import Foundation


@MainActor final class ExtensionsCoordinator: NSObject {
	private var panel: NSPanel?
	private let extensionRootProvider: () -> URL
	private lazy var packagePanel = DeclarativePackagePanel(workspaceRootProvider: { ItsyWorkspaceController.currentRootURL })

	init(extensionRootProvider: @escaping () -> URL = ExtensionsCoordinator.defaultExtensionRoot) {
		self.extensionRootProvider = extensionRootProvider
	}

	func showExtensions(_ sender: Any?) {
		let panel = panel ?? makePanel()
		panel.contentView = makeContentView()
		self.panel = panel
		panel.center()
		panel.makeKeyAndOrderFront(sender)
		NSApp.activate(ignoringOtherApps: true)
	}

	private func makePanel() -> NSPanel {
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 520, height: 320),
			styleMask: [.titled, .closable, .resizable],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Extensions")
		panel.isReleasedWhenClosed = false
		return panel
	}

	private func makeContentView() -> NSView {
		let root = NSView(frame: NSRect(x: 0, y: 0, width: 520, height: 320))
		let title = NSTextField(labelWithString: L10n.string("Installed Extensions"))
		title.font = .systemFont(ofSize: 13, weight: .semibold)
		let textView = NSTextView()
		textView.isEditable = false
		textView.isSelectable = true
		textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
		textView.string = installedExtensionSummary()
		let scrollView = NSScrollView()
		scrollView.hasVerticalScroller = true
		scrollView.borderType = .bezelBorder
		scrollView.documentView = textView
		let refresh = NSButton(title: L10n.string("Refresh"), target: self, action: #selector(refreshPanel(_:)))
		let packages = NSButton(title: L10n.string("Packages…"), target: self, action: #selector(showPackages(_:)))
		let actions = NSStackView(views: [packages, refresh])
		actions.orientation = .horizontal
		actions.spacing = 8
		for view in [title, scrollView, actions] {
			view.translatesAutoresizingMaskIntoConstraints = false
			root.addSubview(view)
		}
		NSLayoutConstraint.activate([
			title.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 16),
			title.trailingAnchor.constraint(equalTo: actions.leadingAnchor, constant: -12),
			title.topAnchor.constraint(equalTo: root.topAnchor, constant: 16),
			actions.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -16),
			actions.centerYAnchor.constraint(equalTo: title.centerYAnchor),
			scrollView.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 16),
			scrollView.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -16),
			scrollView.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 12),
			scrollView.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -16),
		])
		return root
	}

	@objc private func refreshPanel(_ sender: Any?) {
		panel?.contentView = makeContentView()
	}

	@objc private func showPackages(_ sender: Any?) {
		packagePanel.show(relativeTo: panel)
	}

	private func installedExtensionSummary(fileManager: FileManager = .default) -> String {
		let root = extensionRootProvider()
		guard let identifiers = try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
			return L10n.string("No installed extensions.")
		}
		let lines = identifiers
			.filter { isDirectory($0, fileManager: fileManager) }
			.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
			.flatMap { identifierURL -> [String] in
				let versions = ((try? fileManager.contentsOfDirectory(at: identifierURL, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? [])
					.filter { isDirectory($0, fileManager: fileManager) }
					.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
				return versions.map { "\(identifierURL.lastPathComponent)  \($0.lastPathComponent)" }
			}
		return lines.isEmpty ? L10n.string("No installed extensions.") : lines.joined(separator: "\n")
	}

	private func isDirectory(_ url: URL, fileManager: FileManager) -> Bool {
		var isDirectory: ObjCBool = false
		return fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
	}

	private nonisolated static func defaultExtensionRoot() -> URL {
		FileManager.default.homeDirectoryForCurrentUser
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("extensions", isDirectory: true)
	}
}
