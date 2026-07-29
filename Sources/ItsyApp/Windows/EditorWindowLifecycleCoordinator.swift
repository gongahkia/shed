import AppKit

@MainActor protocol EditorWindowLifecycleHandling: AnyObject {
	func editorWindowDidBecomeKey()
	func editorWindowDidBecomeMain()
	func editorWindowDidResize()
	func editorWindowDidEndLiveResize()
	func editorWindowDidEnterFullScreen()
	func editorWindowDidExitFullScreen()
	func editorWindowWillClose()
}

@MainActor final class EditorWindowLifecycleCoordinator: NSObject, NSWindowDelegate {
	private weak var window: NSWindow?
	weak var handler: (any EditorWindowLifecycleHandling)?

	static func makeWindow(contentView: NSView, title: String) -> NSWindow {
		let window = NSWindow(
			contentRect: contentView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = title
		window.isRestorable = true
		window.contentView = contentView
		return window
	}

	init(window: NSWindow) {
		self.window = window
		super.init()
	}

	@discardableResult func install() -> Bool {
		guard let window else { return false }
		window.delegate = self
		return true
	}

	@discardableResult func uninstall() -> Bool {
		guard let window else { return false }
		guard window.delegate === self else { return false }
		window.delegate = nil
		return true
	}

	@discardableResult func centerWindow() -> Bool {
		guard let window else { return false }
		window.center()
		return true
	}

	@discardableResult func bringToFront(_ sender: Any?) -> Bool {
		guard let window else { return false }
		window.makeKeyAndOrderFront(sender)
		window.orderFrontRegardless()
		return true
	}

	func windowDidBecomeKey(_: Notification) {
		handler?.editorWindowDidBecomeKey()
	}

	func windowDidBecomeMain(_: Notification) {
		handler?.editorWindowDidBecomeMain()
	}

	func windowDidResize(_: Notification) {
		handler?.editorWindowDidResize()
	}

	func windowDidEndLiveResize(_: Notification) {
		handler?.editorWindowDidEndLiveResize()
	}

	func windowDidEnterFullScreen(_: Notification) {
		handler?.editorWindowDidEnterFullScreen()
	}

	func windowDidExitFullScreen(_: Notification) {
		handler?.editorWindowDidExitFullScreen()
	}

	func windowWillClose(_: Notification) {
		handler?.editorWindowWillClose()
	}
}
