import AppKit
@testable import ItsyApp
import Testing

@MainActor @Test func editorWindowLifecycleCoordinatorRoutesWindowEventsAndManagesItsDelegate() {
	_ = NSApplication.shared
	let handler = LifecycleHandler()
	let contentView = NSView(frame: .init(x: 0, y: 0, width: 640, height: 480))
	let window = EditorWindowLifecycleCoordinator.makeWindow(contentView: contentView, title: "test")
	let coordinator = EditorWindowLifecycleCoordinator(window: window)
	coordinator.handler = handler
	#expect(window.title == "test")
	#expect(window.contentView === contentView)
	#expect(window.isRestorable)
	#expect(coordinator.install())
	#expect(window.delegate === coordinator)

	coordinator.windowDidBecomeKey(Notification(name: NSWindow.didBecomeKeyNotification))
	coordinator.windowDidBecomeMain(Notification(name: NSWindow.didBecomeMainNotification))
	coordinator.windowDidResize(Notification(name: NSWindow.didResizeNotification))
	coordinator.windowDidEndLiveResize(Notification(name: NSWindow.didEndLiveResizeNotification))
	coordinator.windowDidEnterFullScreen(Notification(name: NSWindow.didEnterFullScreenNotification))
	coordinator.windowDidExitFullScreen(Notification(name: NSWindow.didExitFullScreenNotification))
	coordinator.windowWillClose(Notification(name: NSWindow.willCloseNotification))

	#expect(handler.events == [.becameKey, .becameMain, .resized, .endedLiveResize, .enteredFullScreen, .exitedFullScreen, .willClose])
	#expect(coordinator.uninstall())
	#expect(window.delegate == nil)
}

@MainActor @Test func editorWindowLifecycleCoordinatorDoesNotRemoveAnotherDelegate() {
	_ = NSApplication.shared
	let window = EditorWindowLifecycleCoordinator.makeWindow(contentView: NSView(), title: "test")
	let coordinator = EditorWindowLifecycleCoordinator(window: window)
	let replacement = ReplacementWindowDelegate()
	#expect(coordinator.install())
	window.delegate = replacement

	#expect(!coordinator.uninstall())
	#expect(window.delegate === replacement)
}

@MainActor private final class LifecycleHandler: EditorWindowLifecycleHandling {
	enum Event: Equatable {
		case becameKey
		case becameMain
		case resized
		case endedLiveResize
		case enteredFullScreen
		case exitedFullScreen
		case willClose
	}

	private(set) var events: [Event] = []
	func editorWindowDidBecomeKey() { events.append(.becameKey) }
	func editorWindowDidBecomeMain() { events.append(.becameMain) }
	func editorWindowDidResize() { events.append(.resized) }
	func editorWindowDidEndLiveResize() { events.append(.endedLiveResize) }
	func editorWindowDidEnterFullScreen() { events.append(.enteredFullScreen) }
	func editorWindowDidExitFullScreen() { events.append(.exitedFullScreen) }
	func editorWindowWillClose() { events.append(.willClose) }
}

@MainActor private final class ReplacementWindowDelegate: NSObject, NSWindowDelegate {}
