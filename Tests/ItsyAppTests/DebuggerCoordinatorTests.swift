import AppKit
@testable import ItsyApp
import ItsyConfig
import Testing

@Test @MainActor func debuggerCoordinatorEmbedsByDefaultAndDetachesWhenConfigured() throws {
	_ = NSApplication.shared
	let host = NSView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
	var visibility: [Bool] = []
	let embedded = DebuggerCoordinator(
		documentController: ItsyDocumentController(),
		settingsProvider: { ItsySettings.DebuggerSettings() },
		embeddedHostProvider: { host },
		setEmbeddedDebuggerVisible: { visibility.append($0) }
	)
	defer { embedded.terminate() }

	embedded.showCallStack(nil)
	#expect(host.subviews.count == 1)
	#expect(visibility == [true])
	#expect(!NSApp.windows.compactMap { $0 as? NSPanel }.contains { $0.title == "Debugger" && $0.isVisible })

	embedded.showCallStack(nil)
	#expect(visibility == [true, false])

	let detached = DebuggerCoordinator(
		documentController: ItsyDocumentController(),
		settingsProvider: { ItsySettings.DebuggerSettings(presentation: .window) }
	)
	detached.showCallStack(nil)
	let panel = try #require(NSApp.windows.compactMap { $0 as? NSPanel }.first { $0.title == "Debugger" && $0.isVisible })
	defer {
		detached.terminate()
		panel.close()
	}
}
