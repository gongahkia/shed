import AppKit
import ItsyConfig

@MainActor final class GitPresentationCoordinator {
	private let settingsProvider: () -> ItsySettings.GitSettings
	private let contentViewProvider: () -> NSView
	private let refresh: () -> Void
	private let embeddedHostProvider: () -> NSView?
	private let setEmbeddedVisible: (NSView, Bool) -> Void
	private var panel: NSPanel?
	private var embeddingConstraints: [NSLayoutConstraint] = []
	private var isEmbedded = false
	private weak var presentationHost: NSView?

	init(
		settingsProvider: @escaping () -> ItsySettings.GitSettings,
		contentViewProvider: @escaping () -> NSView,
		refresh: @escaping () -> Void,
		embeddedHostProvider: @escaping () -> NSView?,
		setEmbeddedVisible: @escaping (NSView, Bool) -> Void
	) {
		self.settingsProvider = settingsProvider
		self.contentViewProvider = contentViewProvider
		self.refresh = refresh
		self.embeddedHostProvider = embeddedHostProvider
		self.setEmbeddedVisible = setEmbeddedVisible
	}

	func applyTheme() {
		if let panel {
			AppThemeApplier.apply(AppTheme.palette, to: panel)
		}
	}

	func applySettings(_ settings: ItsySettings.GitSettings) {
		switch settings.presentation {
		case .sidebar:
			if panel?.isVisible == true {
				showEmbedded(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
			}
		case .window:
			if isEmbedded {
				showDetached(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
			}
		}
	}

	func ensureVisible() {
		show(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
	}

	func toggle() {
		capturePresentationHostIfNeeded()
		switch settingsProvider().presentation {
		case .sidebar:
			toggleEmbedded(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
		case .window:
			if panel?.isVisible == true {
				panel?.close()
			} else {
				showDetached(relativeTo: NSApp.keyWindow ?? NSApp.mainWindow)
			}
		}
	}

	func closeEmbedded() {
		guard isEmbedded, let presentationHost else {
			return
		}
		isEmbedded = false
		setEmbeddedVisible(presentationHost, false)
	}

	var auxiliaryPanelHost: NSWindow? {
		panel ?? NSApp.keyWindow ?? NSApp.mainWindow
	}

	private func show(relativeTo hostWindow: NSWindow?) {
		capturePresentationHostIfNeeded()
		switch settingsProvider().presentation {
		case .sidebar:
			showEmbedded(relativeTo: hostWindow)
		case .window:
			showDetached(relativeTo: hostWindow)
		}
	}

	private func showDetached(relativeTo hostWindow: NSWindow?) {
		isEmbedded = false
		if let host = presentationHost ?? embeddedHostProvider() {
			presentationHost = host
			setEmbeddedVisible(host, false)
		}
		let panel = makePanelIfNeeded()
		center(panel, relativeTo: hostWindow)
		panel.makeKeyAndOrderFront(nil)
		refresh()
	}

	private func toggleEmbedded(relativeTo hostWindow: NSWindow?) {
		guard let host = presentationHost ?? embeddedHostProvider() else {
			showDetached(relativeTo: hostWindow)
			return
		}
		presentationHost = host
		if isEmbedded, contentViewProvider().superview === host {
			closeEmbedded()
			return
		}
		showEmbedded(relativeTo: hostWindow)
	}

	private func showEmbedded(relativeTo hostWindow: NSWindow?) {
		guard let host = presentationHost ?? embeddedHostProvider() else {
			showDetached(relativeTo: hostWindow)
			return
		}
		presentationHost = host
		let contentView = contentViewProvider()
		panel?.orderOut(nil)
		setEmbeddedVisible(host, true)
		embed(contentView, in: host)
		isEmbedded = true
		refresh()
	}

	private func capturePresentationHostIfNeeded() {
		guard !isEmbedded, panel?.isVisible != true else {
			return
		}
		presentationHost = embeddedHostProvider()
	}

	private func makePanelIfNeeded() -> NSPanel {
		if let panel {
			let contentView = contentViewProvider()
			detachEmbeddedContent()
			contentView.removeFromSuperview()
			panel.contentView = contentView
			return panel
		}
		let panel = NSPanel(
			contentRect: NSRect(x: 0, y: 0, width: 980, height: 560),
			styleMask: [.titled, .closable, .resizable, .utilityWindow],
			backing: .buffered,
			defer: false
		)
		panel.title = L10n.string("Git Changes")
		panel.isReleasedWhenClosed = false
		let contentView = contentViewProvider()
		detachEmbeddedContent()
		contentView.removeFromSuperview()
		panel.contentView = contentView
		self.panel = panel
		return panel
	}

	private func embed(_ contentView: NSView, in host: NSView) {
		detachEmbeddedContent()
		contentView.removeFromSuperview()
		contentView.translatesAutoresizingMaskIntoConstraints = false
		host.addSubview(contentView)
		embeddingConstraints = [
			contentView.leadingAnchor.constraint(equalTo: host.leadingAnchor),
			contentView.trailingAnchor.constraint(equalTo: host.trailingAnchor),
			contentView.topAnchor.constraint(equalTo: host.topAnchor),
			contentView.bottomAnchor.constraint(equalTo: host.bottomAnchor),
		]
		NSLayoutConstraint.activate(embeddingConstraints)
	}

	private func detachEmbeddedContent() {
		NSLayoutConstraint.deactivate(embeddingConstraints)
		embeddingConstraints = []
	}

	private func center(_ panel: NSPanel, relativeTo hostWindow: NSWindow?) {
		let hostFrame = hostWindow?.frame ?? NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
		let width = min(1100, max(860, hostFrame.width - 100))
		let height = min(660, max(420, hostFrame.height - 120))
		panel.setFrame(NSRect(x: hostFrame.midX - width / 2, y: hostFrame.midY - height / 2, width: width, height: height), display: true)
	}
}
