import AppKit
import ItsyConfig
import ObjectiveC

@MainActor enum ItsyUIConfiguration {
	private static var settings = ItsySettings.UISettings()
	private static var observer: NSObjectProtocol?
	private static var fontSizeKey: UInt8 = 0
	private static var rowHeightKey: UInt8 = 0
	private static var stackSpacingKey: UInt8 = 0

	static func install() {
		guard observer == nil else { return }
		observer = NotificationCenter.default.addObserver(
			forName: NSWindow.didBecomeKeyNotification,
			object: nil,
			queue: .main
		) { notification in
			guard let panel = notification.object as? NSPanel else { return }
			Task { @MainActor in apply(to: panel) }
		}
	}

	static func update(_ value: ItsySettings.UISettings) {
		settings = value
		NSApp.windows.compactMap { $0 as? NSPanel }.forEach(apply(to:))
	}

	static func surface(_ id: String) -> ItsySettings.UISettings.SurfaceSettings {
		settings.surface(id)
	}

	static func size(_ id: String, defaultWidth: CGFloat, defaultHeight: CGFloat) -> NSSize {
		let surface = surface(id)
		return NSSize(width: CGFloat(surface.width ?? Double(defaultWidth)), height: CGFloat(surface.height ?? Double(defaultHeight)))
	}

	static func rowHeight(_ id: String, default value: CGFloat) -> CGFloat {
		let surface = surface(id)
		let density: CGFloat = switch settings.density {
		case .compact: 0.88
		case .regular: 1
		case .comfortable: 1.14
		}
		return CGFloat(surface.rowHeight ?? Double(value * density))
	}

	static func fontSize(_ id: String, default value: CGFloat, input: Bool = false) -> CGFloat {
		let surface = surface(id)
		let override = input ? surface.inputFontSize : surface.itemFontSize
		return CGFloat(override ?? Double(value * CGFloat(settings.fontScale)))
	}

	static func applyPanelStyle(to view: NSView) {
		view.wantsLayer = true
		view.layer?.cornerRadius = CGFloat(settings.cornerRadius)
		view.layer?.borderWidth = CGFloat(settings.borderWidth)
		view.layer?.borderColor = AppTheme.palette.border.cgColor
	}

	static func apply(to panel: NSPanel) {
		guard let contentView = panel.contentView else { return }
		applyPanelStyle(to: contentView)
		let id = surfaceID(for: panel)
		applyMetrics(to: contentView, surfaceID: id)
		guard let id, let width = surface(id).width, let height = surface(id).height else { return }
		let frame = NSRect(x: panel.frame.midX - width / 2, y: panel.frame.midY - height / 2, width: width, height: height)
		panel.setFrame(frame, display: panel.isVisible)
	}

	private static func applyMetrics(to view: NSView, surfaceID: String?) {
		let surface = surfaceID.map(surface)
		if let textField = view as? NSTextField, let font = textField.font {
			let base = (objc_getAssociatedObject(textField, &fontSizeKey) as? NSNumber)?.doubleValue ?? Double(font.pointSize)
			if objc_getAssociatedObject(textField, &fontSizeKey) == nil { objc_setAssociatedObject(textField, &fontSizeKey, NSNumber(value: base), .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
			let override = textField.isEditable ? surface?.inputFontSize : surface?.itemFontSize
			textField.font = font.withSize(CGFloat(override ?? base * Double(settings.fontScale)))
		}
		if let table = view as? NSTableView {
			let base = (objc_getAssociatedObject(table, &rowHeightKey) as? NSNumber)?.doubleValue ?? Double(table.rowHeight)
			if objc_getAssociatedObject(table, &rowHeightKey) == nil { objc_setAssociatedObject(table, &rowHeightKey, NSNumber(value: base), .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
			table.rowHeight = CGFloat(surface?.rowHeight ?? base) * (surface?.rowHeight == nil ? densityScale : 1)
		}
		if let stack = view as? NSStackView {
			let base = (objc_getAssociatedObject(stack, &stackSpacingKey) as? NSNumber)?.doubleValue ?? Double(stack.spacing)
			if objc_getAssociatedObject(stack, &stackSpacingKey) == nil { objc_setAssociatedObject(stack, &stackSpacingKey, NSNumber(value: base), .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
			stack.spacing = CGFloat(base) * densityScale
		}
		view.subviews.forEach { applyMetrics(to: $0, surfaceID: surfaceID) }
	}

	private static var densityScale: CGFloat {
		switch settings.density {
		case .compact: 0.88
		case .regular: 1
		case .comfortable: 1.14
		}
	}

	private static func surfaceID(for panel: NSPanel) -> String? {
		let title = panel.title.lowercased()
		let matches: [(String, String)] = [
			("command palette", "command_palette"), ("find in project", "project_find"), ("terminal", "terminal"), ("outline", "outline"), ("problems", "problems"), ("references", "references"), ("task", "tasks"), ("undo", "undo_tree"), ("git graph", "git_graph"), ("git stash", "git_stash"), ("git", "git"), ("call stack", "debugger"), ("debug console", "debug_console"), ("debug variables", "debug_variables"), ("debug watches", "debug_watches"), ("debug", "debug_launch"), ("language server status", "lsp_status"), ("integration health", "integration_health"), ("integration output", "integration_output"), ("extensions", "extensions"), ("settings catalog", "settings_catalog"), ("language server configuration", "lsp_configuration"), ("language & debugger support", "managed_support"), ("pull request", "github_pull_request"), ("review thread", "github_review_thread"),
		]
		return matches.first { title.contains($0.0) }?.1
	}
}
