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
		setEmbeddedDebuggerVisible: { _, visible in visibility.append(visible) }
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

@Test @MainActor func debuggerCoordinatorRelocatesActivePresentation() throws {
	_ = NSApplication.shared
	let host = NSView(frame: NSRect(x: 0, y: 0, width: 640, height: 720))
	var settings = ItsySettings.DebuggerSettings()
	var visibility: [Bool] = []
	let coordinator = DebuggerCoordinator(
		documentController: ItsyDocumentController(),
		settingsProvider: { settings },
		embeddedHostProvider: { host },
		setEmbeddedDebuggerVisible: { _, visible in visibility.append(visible) }
	)
	defer { coordinator.terminate() }

	coordinator.showCallStack(nil)
	settings.presentation = .window
	coordinator.applyDebuggerSettings(settings)
	let panel = try #require(NSApp.windows.compactMap { $0 as? NSPanel }.first { $0.title == "Debugger" && $0.isVisible })
	defer { panel.close() }
	#expect(host.subviews.isEmpty)
	#expect(visibility == [true, false])

	settings.presentation = .sidebar
	coordinator.applyDebuggerSettings(settings)
	#expect(!panel.isVisible)
	#expect(host.subviews.count == 1)
	#expect(visibility == [true, false, true])
}

@Test @MainActor func debuggerCoordinatorKeepsTheOriginatingHostAcrossPresentationReloads() throws {
	_ = NSApplication.shared
	let firstHost = NSView(frame: NSRect(x: 0, y: 0, width: 640, height: 720))
	let secondHost = NSView(frame: NSRect(x: 0, y: 0, width: 640, height: 720))
	var activeHost = firstHost
	var settings = ItsySettings.DebuggerSettings()
	var visibility: [String] = []
	let coordinator = DebuggerCoordinator(
		documentController: ItsyDocumentController(),
		settingsProvider: { settings },
		embeddedHostProvider: { activeHost },
		setEmbeddedDebuggerVisible: { host, visible in
			visibility.append("\(host === firstHost ? "first" : "second"):\(visible)")
		}
	)
	defer { coordinator.terminate() }

	coordinator.showCallStack(nil)
	activeHost = secondHost
	settings.presentation = .window
	coordinator.applyDebuggerSettings(settings)
	let panel = try #require(NSApp.windows.compactMap { $0 as? NSPanel }.first { $0.title == "Debugger" && $0.isVisible })
	defer { panel.close() }
	settings.presentation = .sidebar
	coordinator.applyDebuggerSettings(settings)

	#expect(visibility == ["first:true", "first:false", "first:true"])
	#expect(firstHost.subviews.count == 1)
	#expect(secondHost.subviews.isEmpty)
}

@Test @MainActor func debuggerCoordinatorConsolidatesEveryDebugSurfaceInOnePresentation() {
	_ = NSApplication.shared
	let host = NSView(frame: NSRect(x: 0, y: 0, width: 640, height: 720))
	let coordinator = DebuggerCoordinator(
		documentController: ItsyDocumentController(),
		settingsProvider: { ItsySettings.DebuggerSettings() },
		embeddedHostProvider: { host },
		setEmbeddedDebuggerVisible: { _, _ in }
	)
	defer { coordinator.terminate() }

	coordinator.showVariables(nil)
	coordinator.showWatches(nil)
	coordinator.showConsole(nil)
	let surfaceControl = debuggerDescendants(in: host).compactMap { $0 as? NSSegmentedControl }.first { $0.segmentCount == 4 }
	#expect(host.subviews.count == 1)
	#expect(surfaceControl?.selectedSegment == 3)
	#expect(!NSApp.windows.compactMap { $0 as? NSPanel }.contains { ["Variables", "Watches", "Debug Console"].contains($0.title) && $0.isVisible })
}

private func debuggerDescendants(in view: NSView) -> [NSView] {
	view.subviews + view.subviews.flatMap(debuggerDescendants)
}
